/*
 * fork: režim tunelu.
 *
 * Stupeň „jen hovory“ jede vnitřním tunelem uvnitř appky: systémové místo
 * pro VPN nezabírá, takže Phone21 funguje vedle jakékoli jiné VPN.
 * „Miniserver“ a „síť“ (a „jen hovory“ se synchronizací kontaktů přes
 * tunel — ta je cizí appka) dávají privátní síť i ostatním appkám, a to
 * jde jen systémovou VPN.
 *
 * Přepíná se vždy stejně, sériově na vlákně jádra:
 *  1. vypnout registraci a počkat na odhlášení (nejvýš 3 s) — odejde ještě
 *     starou cestou a ústředna nebude zvonit na mrtvý kontakt,
 *  2. zbořit starou cestu (předání služby, žádné WantRunning=false),
 *  3. postavit novou,
 *  4. nastavit účet ve volací knihovně (TsSipTransport),
 *  5. registraci zapne připravenost, až cesta stojí a účet sedí.
 * Během hovoru se přepnutí odkládá na jeho konec; výjimkou je odebrání
 * systémové VPN (cesta už neexistuje).
 *
 * Systémové místo se nikdy nebere potichu: chybí-li souhlas, drží-li místo
 * jiná VPN, nebo o něj Phone21 přišlo, jedou hovory dál vnitřním tunelem
 * a appka se zeptá (výzva s tlačítkem do TsConsentActivity,
 * [needsSystemConsent] pro obrazovku).
 *
 * Knihovna tunelu má vnitřní režim vyžádaný vždy, když uživatel tunel chce
 * (i pod systémovou VPN, která má přednost): když VPN odebere systém nebo
 * selže, tunel se do vnitřního režimu vrátí sám a knihovna přitom
 * nezapíše WantRunning=false (past „Zakázáno“).
 */
package org.linphone.twentyone.vpn

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.RegistrationState
import org.linphone.core.tools.Log
import org.linphone.twentyone.TwentyOneDiag
import org.linphone.twentyone.contacts.TwentyOneContactsTarget

object TsModeController {
    private const val TAG = "[Tunnel Mode]"

    /** Nejdéle čekat na odhlášení u ústředny před přepnutím. */
    private const val UNREGISTER_WAIT_MS = 3_000L
    private const val UNREGISTER_POLL_MS = 200L

    /** Jak dlouho po pokynu uživatele (souhlas udělen v UI) se smí vzít
     *  systémové místo i přes ztracené místo nebo viditelnou jinou VPN. */
    private const val APPROVAL_VALID_MS = 60_000L

    /** Brzda opakovaného startu služby vnitřního tunelu bez pokynu. */
    private const val SERVICE_START_BRAKE_MS = 30_000L

    enum class Mode {
        /** uživatel tunel nechce */
        OFF,
        /** tunel uvnitř appky, bez systémové VPN */
        INTERNAL,
        /** systémová VPN */
        SYSTEM
    }

    /** Proč požadovaný systémový režim neplatí (hovory jedou vnitřním). */
    enum class SystemBlock {
        NONE,
        /** chybí souhlas s VPN (VpnService.prepare chce dialog) */
        NO_CONSENT,
        /** místo pro VPN drží jiná aplikace */
        OTHER_VPN,
        /** Phone21 o místo přišlo (odebráno systémem, establish selhal) */
        SLOT_LOST
    }

    /** Pro další krok (UI): požadovaný stupeň chce systémovou VPN, ale
     *  hovory jedou vnitřním tunelem — zeptat se uživatele. */
    val needsSystemConsent = MutableLiveData(false)

    /** Důvod k [needsSystemConsent]. */
    val systemBlock = MutableLiveData(SystemBlock.NONE)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var modeInUseNow: Mode? = null

    @Volatile
    private var blockNow = SystemBlock.NONE

    // stav přepínání — jen na vlákně jádra (čtenáři odjinud jen čtou)
    @Volatile
    private var switching = false
    private var pendingReason: String? = null
    private var pendingForced = false
    private var deferredForCall = false

    @Volatile
    private var approvedAt = 0L

    /** Uživatel právě chce tunel zapnout (Připojit, souhlas): systémová
     *  služba se má spustit, i když režim už platí. */
    @Volatile
    private var startRequested = false

    @Volatile
    private var callActiveNow = false

    @Volatile
    private var lastTunnelServiceStart = 0L

    private var listenedCore: Core? = null

    /** Silný odkaz: SharedPreferences drží posluchače jen slabě. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in TsPreferences.MODE_KEYS) {
            reconcile("změna stupně přístupu")
        }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            val active = core.callsNb > 0
            callActiveNow = active
            if (!active && (state == Call.State.Released || state == Call.State.End)) {
                // relé mohlo čekat se změnou cílů na konec hovoru
                TsRelay.poke()
                if (deferredForCall) step(core, "konec hovoru", false)
            }
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val p = TsPreferences(ctx)
        val inUse = parseMode(p.modeInUse)
        modeInUseNow = inUse
        TsManager.mode.postValue(inUse)
        p.raw.registerOnSharedPreferenceChangeListener(prefsListener)
        // Výzva z minulého procesu: jestli pořád platí, rozhodne první
        // srovnání (a ukáže ji znovu potichu); jinak by visela neplatná.
        if (p.systemBlockNotified.isNotEmpty()) TsNotifications.cancelSystemAccessBlocked(ctx)
    }

    private fun context(): Context = appContext ?: coreContext.context

    private fun parseMode(name: String): Mode =
        runCatching { Mode.valueOf(name) }.getOrDefault(Mode.SYSTEM)

    /** Režim, pro který jsou nastavené služby a účet. Čitelné odkudkoli. */
    fun modeInUse(): Mode {
        modeInUseNow?.let { return it }
        val m = parseMode(TsPreferences(context()).modeInUse)
        modeInUseNow = m
        return m
    }

    /** Režim podle stupně přístupu (bez ohledu na to, jestli tunel běží). */
    fun desiredAccessMode(context: Context = context()): Mode {
        val p = TsPreferences(context)
        val sync = p.contactsSyncViaTunnel && TwentyOneContactsTarget.davAppInstalled(context)
        return if (p.tunnelAccess == TsPreferences.TunnelAccess.APP_ONLY && !sync) {
            Mode.INTERNAL
        } else {
            Mode.SYSTEM
        }
    }

    /** Požadovaný režim: vypnuto, vnitřní, nebo systémový. */
    fun desired(): Mode = if (!TsManager.wantsRunning()) Mode.OFF else desiredAccessMode()

    fun isSwitching(): Boolean = switching

    fun isCallActive(): Boolean = callActiveNow

    fun systemBlockNow(): SystemBlock = blockNow

    /**
     * Drží Phone21 hovory i bez systémové VPN? Ano, kdykoli uživatel tunel
     * chce — knihovna má pak vnitřní režim vyžádaný. Odebrání VPN proto
     * nesmí zapsat WantRunning=false.
     */
    fun keepsCallsWithoutSystemVpn(): Boolean = TsManager.wantsRunning()

    /** Uživatel v UI prošel souhlasem s VPN (prepare vrátil OK): systémové
     *  místo se smí vzít, i kdyby ho chvíli vypadalo, že drží jiná VPN. */
    fun approveSystem() {
        approvedAt = android.os.SystemClock.elapsedRealtime()
        startRequested = true
    }

    /** Phone21 přišlo o systémové místo (onRevoke, establish selhal, jiná
     *  VPN při startu): hned, i během hovoru. */
    fun onSlotLost() {
        // Souhlas z UI už neplatí: jinak by si appka místo hned vzala
        // zpátky a s jinou VPN by se o něj přetahovala.
        approvedAt = 0L
        startRequested = false
        reconcile("ztráta místa pro VPN", forced = true)
    }

    /** Nový start jádra: účet podle režimu, posluchač hovorů, srovnání. */
    @WorkerThread
    fun onCoreStarted(core: Core) {
        if (listenedCore !== core) {
            listenedCore?.removeListener(coreListener)
            core.addListener(coreListener)
            listenedCore = core
        }
        callActiveNow = core.callsNb > 0
        TsSipTransport.onCoreStarted(core)
        step(core, "start jádra", false)
    }

    /** Srovnat skutečný režim s požadovaným (z libovolného vlákna). */
    fun reconcile(reason: String, forced: Boolean = false) {
        if (!coreContext.isReady()) {
            // start jádra srovnání udělá sám (onCoreStarted)
            Log.i("$TAG Core not ready, mode reconcile [$reason] postponed")
            return
        }
        coreContext.postOnCoreThread { core -> step(core, reason, forced) }
    }

    @WorkerThread
    private fun step(core: Core, reason: String, forced: Boolean) {
        // Dokud appka neví, jestli uživatel tunel chce (nový proces před
        // první notifikací jádra po aktualizaci), nerozhoduje se nic: účet
        // zůstává nastavený podle uloženého režimu (TsSipTransport při
        // startu jádra) a srovnání přijde se zprávou z jádra.
        if (!TsManager.isStarted() || !TsManager.wantRunningKnown()) {
            Log.i("$TAG Tunnel state not known yet, mode reconcile [$reason] postponed")
            return
        }
        if (switching) {
            pendingReason = reason
            pendingForced = pendingForced || forced
            return
        }
        val ctx = context()
        val from = modeInUse()
        val desired = desired()
        var (target, block) = decide(desired, from, ctx)

        // Vypnutí se neodkládá: službu a žádost už zastavil TsManager.stopService
        // a obnovení staré cesty by tunel zase zapnulo.
        if (target != from && !forced && target != Mode.OFF && core.callsNb > 0) {
            if (!deferredForCall) {
                TwentyOneDiag.log(
                    "P21-TUN",
                    "přepnutí %s → %s odloženo na konec hovoru (%s)".format(
                        label(from), label(target), reason))
            }
            deferredForCall = true
            publishBlock(ctx, block)
            ensurePath(from)
            return
        }
        deferredForCall = false

        // VpnService.prepare() NENÍ dotaz: se souhlasem rovnou přidělí místo
        // (a cizí VPN vypne). Proto až tady — po kontrole jiné VPN,
        // ztraceného místa a hovoru — a jen když se systémová služba opravdu
        // má spustit.
        if (target == Mode.SYSTEM && !TsVpnService.isRunning()) {
            val consent = try {
                VpnService.prepare(ctx)
            } catch (e: Exception) {
                null
            }
            if (consent != null) {
                target = Mode.INTERNAL
                block = SystemBlock.NO_CONSENT
            }
        }
        publishBlock(ctx, block)
        if (target != Mode.SYSTEM) startRequested = false

        // Vnitřní režim místo pro VPN nepotřebuje: ztracené místo se ruší
        // (jinak by obrazovka i výstraha tvrdily, že hovory nechodí).
        if (desired == Mode.INTERNAL && TsManager.isSlotLost()) {
            TsManager.releaseSlotLost(ctx, "vnitřní tunel místo pro VPN nepotřebuje")
        }

        if (target == from) {
            ensurePath(target)
            TsSipTransport.apply(core, target, reason)
            return
        }
        switchMode(core, from, target, reason, forced)
    }

    /** Cíl a případný důvod, proč systémový režim neplatí (bez prepare). */
    private fun decide(desired: Mode, from: Mode, ctx: Context): Pair<Mode, SystemBlock> {
        if (desired != Mode.SYSTEM) return desired to SystemBlock.NONE
        if (from == Mode.SYSTEM && TsVpnService.isRunning() && !TsManager.isSlotLost()) {
            return Mode.SYSTEM to SystemBlock.NONE
        }
        val approved = android.os.SystemClock.elapsedRealtime() - approvedAt < APPROVAL_VALID_MS &&
            approvedAt != 0L
        if (!approved && TsManager.isSlotLost()) return Mode.INTERNAL to SystemBlock.SLOT_LOST
        if (!approved && TsManager.otherVpnActive(ctx)) return Mode.INTERNAL to SystemBlock.OTHER_VPN
        return Mode.SYSTEM to SystemBlock.NONE
    }

    /** Cesta pro režim, který už platí: žádost v knihovně, relé, služby. */
    @WorkerThread
    private fun ensurePath(mode: Mode) {
        when (mode) {
            Mode.INTERNAL -> {
                // Zatoulaná služba VPN (spuštěná uprostřed přepínání nebo
                // opožděným startem): mimo přepínání vedle vnitřního režimu
                // nikdy legitimně neběží a knihovna by jí dala přednost —
                // relé by nenaběhlo a hovory by stály. Předat, nikdy
                // WantRunning=false.
                if (TsVpnService.isRunning()) {
                    TwentyOneDiag.log("P21-TUN", "služba VPN běží ve vnitřním režimu — předávám ji vnitřnímu tunelu")
                    TsManager.handOverVpnService()
                }
                TsRelay.requestInternal()
                TsRelay.setRelayWanted(true)
                startTunnelService(force = false)
            }
            Mode.SYSTEM -> {
                // záloha pod systémovou VPN (viz hlavička)
                TsRelay.requestInternal()
                TsRelay.setRelayWanted(false)
                // Automatický start systémové služby řeší připravenost
                // (maybeStartSystem, s kontrolou jiné VPN); tady jen na
                // pokyn uživatele.
                if (startRequested) {
                    startRequested = false
                    TsManager.startVpnServiceNow()
                }
            }
            Mode.OFF -> {
                TsRelay.stopInternal()
            }
        }
    }

    private fun startTunnelService(force: Boolean) {
        if (TsTunnelService.isRunning()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastTunnelServiceStart != 0L && now - lastTunnelServiceStart < SERVICE_START_BRAKE_MS) {
            return
        }
        lastTunnelServiceStart = now
        TsManager.startTunnelServiceNow()
    }

    @WorkerThread
    private fun switchMode(core: Core, from: Mode, to: Mode, reason: String, forced: Boolean) {
        switching = true
        TwentyOneDiag.log(
            "P21-TUN", "režim: %s → %s (%s)".format(label(from), label(to), reason))
        // Cesta zmizela (odebraná VPN): nejdřív zavřít cestu účtu, ať
        // odhlášení neodejde výchozí sítí nešifrovaně.
        if (forced && from == Mode.SYSTEM && to == Mode.INTERNAL) {
            TsSipTransport.apply(core, Mode.INTERNAL, reason)
        }
        coreContext.tunnelReadinessManager.holdForSwitch(core)
        val deadline = android.os.SystemClock.elapsedRealtime() + UNREGISTER_WAIT_MS
        waitUnregistered(core, deadline) { finishSwitch(core, from, to, reason) }
    }

    @WorkerThread
    private fun waitUnregistered(core: Core, deadline: Long, then: () -> Unit) {
        val busy = core.accountList.any {
            val s = it.state
            s == RegistrationState.Ok || s == RegistrationState.Progress || s == RegistrationState.Refreshing
        }
        if (!busy) {
            then()
            return
        }
        if (android.os.SystemClock.elapsedRealtime() >= deadline) {
            TwentyOneDiag.log("P21-TUN", "odhlášení u ústředny nedoběhlo do 3 s — přepínám i tak")
            then()
            return
        }
        coreContext.postOnCoreThreadDelayed({ waitUnregistered(it, deadline, then) }, UNREGISTER_POLL_MS)
    }

    @WorkerThread
    private fun finishSwitch(core: Core, from: Mode, to: Mode, reason: String) {
        var target = to
        try {
            if (to == Mode.SYSTEM) {
                // Službu VPN napřed: start ze zadu bez výjimky z optimalizace
                // baterie systém odmítne — pak zůstat u vnitřního tunelu
                // (hovory jdou), ne skončit bez cesty. Knihovna dá systémové
                // VPN přednost sama, vnitřní cestu není třeba bořit předem.
                startRequested = false
                if (!TsManager.startVpnServiceNow()) {
                    TwentyOneDiag.log(
                        "P21-TUN", "systémová VPN se nespustila — hovory jedou dál vnitřním tunelem")
                    target = if (from == Mode.OFF) Mode.INTERNAL else from
                }
            }
            // zbořit starou cestu — předáním, nikdy WantRunning=false
            when (from) {
                Mode.SYSTEM -> if (to != Mode.SYSTEM) TsManager.handOverVpnService()
                Mode.INTERNAL -> if (target != Mode.INTERNAL) {
                    TsManager.handOverTunnelService()
                    TsRelay.setRelayWanted(false)
                }
                Mode.OFF -> {}
            }
            TsManager.useNetworkSourceFor(target)
            // postavit novou (systémová služba už startuje — viz výše)
            when (target) {
                Mode.INTERNAL -> {
                    TsRelay.requestInternal()
                    TsRelay.setRelayWanted(true)
                    startTunnelService(force = true)
                }
                // záloha pod systémovou VPN (viz hlavička)
                Mode.SYSTEM -> TsRelay.requestInternal()
                Mode.OFF -> TsRelay.stopInternal()
            }
            TsSipTransport.apply(core, target, reason)
            setModeInUse(target)
        } finally {
            switching = false
        }
        coreContext.tunnelReadinessManager.reevaluate("po přepnutí režimu")
        val next = pendingReason
        if (next != null) {
            val f = pendingForced
            pendingReason = null
            pendingForced = false
            step(core, next, f)
        }
    }

    private fun setModeInUse(mode: Mode) {
        modeInUseNow = mode
        try {
            TsPreferences(context()).modeInUse = mode.name
        } catch (e: Exception) {
            Log.e("$TAG Failed to persist mode: $e")
        }
        TsManager.onModeChanged(mode)
    }

    /** Výzva „zvolený stupeň potřebuje místo pro VPN“ — nikdy potichu. */
    private fun publishBlock(ctx: Context, block: SystemBlock) {
        if (block == blockNow) return
        val old = blockNow
        blockNow = block
        needsSystemConsent.postValue(block != SystemBlock.NONE)
        systemBlock.postValue(block)
        val p = TsPreferences(ctx)
        if (block == SystemBlock.NONE) {
            TsNotifications.cancelSystemAccessBlocked(ctx)
            p.systemBlockNotified = ""
            if (old != SystemBlock.NONE) {
                TwentyOneDiag.log("P21-TUN", "výzva k systémové VPN zrušena")
            }
            return
        }
        TwentyOneDiag.log(
            "P21-TUN",
            "zvolený stupeň chce systémovou VPN, ale %s — hovory jedou vnitřním tunelem, místo se potichu nebere"
                .format(
                    when (block) {
                        SystemBlock.NO_CONSENT -> "chybí souhlas"
                        SystemBlock.OTHER_VPN -> "místo drží jiná VPN"
                        SystemBlock.SLOT_LOST -> "Phone21 o místo přišlo"
                        SystemBlock.NONE -> ""
                    }
                )
        )
        val silent = p.systemBlockNotified == block.name
        p.systemBlockNotified = block.name
        TsNotifications.notifySystemAccessBlocked(ctx, block, silent)
    }

    fun label(mode: Mode): String = when (mode) {
        Mode.OFF -> "vypnuto"
        Mode.INTERNAL -> "vnitřní tunel"
        Mode.SYSTEM -> "systémová VPN"
    }
}
