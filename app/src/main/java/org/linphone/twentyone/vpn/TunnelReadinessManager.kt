package org.linphone.twentyone.vpn

import android.content.Intent
import androidx.annotation.WorkerThread
import androidx.lifecycle.Observer
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.ConfiguringState
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.tools.Log

class TunnelReadinessManager {
    companion object {
        private const val TAG = "[Tunnel Readiness]"
        private const val RECONCILE_MS = 30_000L
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconcile = object : Runnable {
        override fun run() {
            evaluate(null, reconcile = true)
            mainHandler.postDelayed(this, RECONCILE_MS)
        }
    }

    private var core: Core? = null

    @Volatile
    private var ready = false

    /** Je síť připravená pro registraci účtu? */
    val isReady: Boolean
        get() = ready

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountAdded(core: Core, account: Account) {
            ensureInternationalPrefix(core)
            // ruční účet z asistenta potřebuje totéž co účet z QR
            TsSipTransport.apply(core, TsModeController.modeInUse(), "nový účet")
            applyState(ready)
        }

        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: org.linphone.core.RegistrationState?,
            message: String
        ) {
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-REG",
                "registrace u ústředny: %s %s".format(state, message)
            )
            // Vnitřní režim: registrace bez odpovědi přes běžící relé =
            // šifrované spojení s relé neprošlo (nejčastěji odmítnutý
            // certifikát). Odpověď ústředny (401/403…) sem nepatří.
            if (state == org.linphone.core.RegistrationState.Failed &&
                TsModeController.modeInUse() == TsModeController.Mode.INTERNAL &&
                TsRelay.isUp()
            ) {
                val info = account.errorInfo
                if (info == null || info.protocolCode == 0) {
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-E25",
                        "registrace přes relé bez odpovědi (%s %s) — spojení volací knihovny s relé neprošlo"
                            .format(info?.reason ?: "", message)
                    )
                }
            }
        }

    }

    // Volá se z posluchače CoreContextu, který existuje už při startu jádra.
    // Vlastní posluchač tady se registruje až PO startu — jenže konfigurace
    // se aplikuje BĚHEM startu, takže by událost propadla: soubor s heslem
    // by zůstal ležet a konfigurace by se aplikovala při každém otevření.
    @WorkerThread
    fun onConfiguring(core: Core, status: ConfiguringState?, message: String?) {
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-CFG",
            "konfigurace: %s uri=%s účtů=%d %s".format(
                status,
                if (core.provisioningUri.isNullOrEmpty()) "ne" else "ano",
                core.accountList.size,
                message ?: "")
        )
        // Před aplikací konfigurace jsme odebrali přihlašovací údaje výchozího
        // účtu (kvůli ha1 z minulé domény). Když se konfigurace nepovedla,
        // musí se vrátit — jinak telefon zůstane bez hesla a trvale
        // neregistrovaný, a z hlášky ani z deníku se to nepozná. Platí i pro
        // Skipped: odebrání proběhlo, aplikace ne.
        if (status == ConfiguringState.Successful) {
            org.linphone.twentyone.TwentyOneProvisioning.forgetRemovedAuthInfo()
            // Konfigurace z QR mohla změnit doménu účtu (jiná ústředna pro
            // relé) — účet znovu podle režimu. Během startu jádra to udělá
            // start sám (nastavení před GlobalState.On se neuloží).
            if (core.globalState == org.linphone.core.GlobalState.On) {
                TsSipTransport.apply(core, TsModeController.modeInUse(), "po konfiguraci")
            }
            coreContext.postOnMainThread { evaluate("po konfiguraci") }
        } else {
            org.linphone.twentyone.TwentyOneProvisioning.restoreAuthInfo(core)
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-CFG", "konfigurace neproběhla (%s) — přihlašovací údaje účtu vráceny".format(status)
            )
        }
        if (status == ConfiguringState.Skipped) return
        // stažená konfigurace nese heslo účtu — smazat i po NEúspěchu
        // (soubor s heslem nesmí ležet ve files/ a mrtvá adresa se nesmí
        // zkoušet při každém dalším startu)
        java.io.File(coreContext.context.filesDir, "provisioning.xml").delete()
        if (!core.provisioningUri.isNullOrEmpty()) core.provisioningUri = null
        if (status != ConfiguringState.Successful) return
        // POŘADÍ JE ZÁVAZNÉ: adresa brány se čte a ukládá dřív než klíč.
        // Chybějící klíč (return níž) nesmí adresu zahodit — brána ji posílá
        // i tehdy, když se klíč telefonu nepodařilo získat.
        val gateway = TsProvisioning.takeExitNodeAddress(core)
        if (gateway.isNotEmpty()) {
            val prefs = TsPreferences(coreContext.context)
            if (!gateway.equals(prefs.gatewayAddress, ignoreCase = true)) {
                // jiná brána než minule → poslední známý identifikátor neplatí
                prefs.gatewayStableId = ""
            }
            prefs.gatewayAddress = gateway
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-NET", "konfigurace nese adresu brány v privátní síti"
            )
        }
        val accessToken = TsProvisioning.takeAccessToken(core)
        if (accessToken.isNotEmpty()) {
            TsPreferences(coreContext.context).accessToken = accessToken
        }
        val (url, key) = TsProvisioning.takeNetworkKey(core)
        if (key.isEmpty()) return
        val intent = Intent(coreContext.context, TsConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(TsConsentActivity.EXTRA_URL, url)
            putExtra(TsConsentActivity.EXTRA_KEY, key)
        }
        coreContext.context.startActivity(intent)
    }

    private val stateObserver = Observer<TsManager.State> {
        evaluate()
    }

    private val loginObserver = Observer<Boolean> {
        evaluate()
    }

    private val endpointObserver = Observer<Boolean> {
        evaluate()
    }

    // Cesta k miniserveru podle režimu (systémové rozhraní, nebo vnitřní
    // tunel s relé) — ne vpnActive, to je jen systémové rozhraní.
    private val transportObserver = Observer<Boolean> {
        evaluate()
    }

    private val modeObserver = Observer<TsModeController.Mode> {
        evaluate()
    }

    // Odříznutí (síť běží, ale nemá jediný protějšek) = registrace nemá kam
    // jít. Bez tohoto pozorovatele by appka dokola zkoušela registraci přes
    // síť bez brány a plnila deník P21-REG.
    private val pausedObserver = Observer<Boolean> {
        evaluate()
    }

    // Jednorázově: účet bez mezinárodní předvolby ji dostane podle země
    // SIM karty telefonu (bez SIM podle země mobilní sítě, nouzově podle
    // jazyka telefonu). Bez předvolby se příchozí číslo s předvolbou
    // nespáruje se jménem kontaktu uloženého bez ní.
    @WorkerThread
    private fun ensureInternationalPrefix(core: Core) {
        try {
            val needy = core.accountList.filter { it.params.internationalPrefix.isNullOrEmpty() }
            if (needy.isEmpty()) return
            val tm = coreContext.context.getSystemService(android.content.Context.TELEPHONY_SERVICE)
                as? android.telephony.TelephonyManager
            val iso = tm?.simCountryIso.orEmpty()
                .ifEmpty { tm?.networkCountryIso.orEmpty() }
                .ifEmpty {
                    coreContext.context.resources.configuration.locales
                        .get(0)?.country.orEmpty()
                }
            if (iso.isEmpty()) return
            val dialPlan = org.linphone.utils.PhoneNumberUtils.getDeviceDialPlan(iso) ?: return
            val prefix = dialPlan.countryCallingCode.removePrefix("+")
            if (prefix.isEmpty()) return
            for (account in needy) {
                val copy = account.params.clone()
                copy.internationalPrefix = prefix
                copy.internationalPrefixIsoCountryCode = dialPlan.isoCountryCode
                copy.useInternationalPrefixForCallsAndChats = true
                account.params = copy
            }
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-CFG",
                "mezinárodní předvolba účtu doplněna: +%s (%s)".format(prefix, iso)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Širokopásmový kodek G.722 (16 kHz) zapnout jednou po instalaci.
     * Miniserver ho od 0.9.44 preferuje a modem hraje 16 kHz, jenže SDK ho
     * má ve výchozím stavu vypnutý — hovor pak jel v 8 kHz (PCMA) a zněl
     * „jak ze sudu" (ověřeno 2026-11-17: po ručním zapnutí lepší na obou
     * stranách). Jen jednou: kdo si ho později vědomě vypne, tomu ho appka
     * znovu nezapne. Takt 8000 je správně — G.722 v RTP hlásí 8 kHz, přestože
     * vzorkuje 16 kHz.
     */
    private fun ensureWidebandCodec(core: Core) {
        try {
            val cfg = core.config
            if (cfg.getBool("twentyone", "g722_default_applied", false)) return
            val g722 = core.audioPayloadTypes.firstOrNull { it.mimeType.equals("G722", ignoreCase = true) }
            if (g722 != null && !g722.enabled()) {
                g722.enable(true)
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-CFG", "širokopásmový kodek G.722 zapnut")
            }
            cfg.setBool("twentyone", "g722_default_applied", true)
        } catch (e: Exception) {
            Log.w("$TAG Failed to enable G.722: $e")
        }
    }

    @WorkerThread
    fun onCoreStarted(core: Core) {
        this.core = core
        core.addListener(coreListener)
        ensureInternationalPrefix(core)
        ensureWidebandCodec(core)
        // účet podle uloženého režimu hned (ve vnitřním: trasa na relé =
        // zavřená cesta, i když relé ještě neběží), pak srovnání režimu
        TsModeController.onCoreStarted(core)
        coreContext.postOnMainThread {
            // nový start jádra začíná od nepřipravené sítě
            ready = false
            TsManager.ensureStarted(coreContext.context)
            TsManager.state.observeForever(stateObserver)
            TsManager.loggedIn.observeForever(loginObserver)
            TsManager.endpointVerified.observeForever(endpointObserver)
            TsManager.transportUp.observeForever(transportObserver)
            TsManager.mode.observeForever(modeObserver)
            TsManager.networkPaused.observeForever(pausedObserver)
            evaluate("po startu jádra")
            mainHandler.removeCallbacks(reconcile)
            mainHandler.postDelayed(reconcile, RECONCILE_MS)
        }
        applyState(false)
    }

    @WorkerThread
    fun onCoreStopped() {
        core?.removeListener(coreListener)
        coreContext.postOnMainThread {
            TsManager.state.removeObserver(stateObserver)
            TsManager.loggedIn.removeObserver(loginObserver)
            TsManager.endpointVerified.removeObserver(endpointObserver)
            TsManager.transportUp.removeObserver(transportObserver)
            TsManager.mode.removeObserver(modeObserver)
            TsManager.networkPaused.removeObserver(pausedObserver)
            mainHandler.removeCallbacks(reconcile)
        }
        this.core = null
    }

    private var lastAutoStart = 0L
    private var otherVpnSeenAt = 0L

    // Jádro tunelu běží i bez rozhraní (po vlastních socketech) — po
    // restartu telefonu nebo násilném ukončení službu rozhraní nikdo
    // nespustí a registrace by mlela io error. Když je souhlas s VPN
    // už udělený, služba se spustí sama; bez souhlasu se nic neděje
    // (dialog patří obrazovce Privátní síť / QR cestě).
    //
    // POZOR: VpnService.prepare() NENÍ neškodný dotaz. Aplikaci, která
    // souhlas už má, rovnou přidělí místo pro VPN — a VPN jiné aplikace
    // tím potichu vypne (Android dovolí jen jednu). Proto se sem smí jen
    // tehdy, když uživatel tunel opravdu chce (WantRunning) a místo
    // nepřišlo ztracené: po odebrání (onRevoke) nebo neúspěšném establish
    // se Phone21 vrací VÝHRADNĚ na pokyn uživatele (Připojit, Moje síť,
    // QR, tlačítko v notifikaci).
    //
    // Ani onRevoke ale nemusí přijít: když jiná VPN naběhla, zatímco Phone21
    // nemělo živou službu (restart telefonu, zabitý proces), systém nemá
    // komu ho poslat. Proto se automatický start před prepare() ještě
    // podívá, jestli aktivní síť nedrží VPN jiné aplikace — a když ano,
    // místo nevezme, jen ho ohlásí jako ztracené (bezpečný směr: pokyn
    // uživatele tahle kontrola nikdy neblokuje).
    private fun maybeStartSystem() {
        if (!TsManager.wantsRunning() || TsManager.isSlotLost()) {
            otherVpnSeenAt = 0L
            return
        }
        // Jen když systémová VPN opravdu platí a nic ji neblokuje. Během
        // přepnutí na „jen hovory“ HANDOVER shodí rozhraní dřív, než se
        // uloží nový režim — start tady by postavil systémovou VPN do
        // vnitřního režimu (relé by nenaběhlo, hovory by stály). Požadovaný
        // režim se čte z už uložené volby, takže nezávisí na načasování.
        if (TsModeController.isSwitching() ||
            TsModeController.desired() != TsModeController.Mode.SYSTEM ||
            TsModeController.modeInUse() != TsModeController.Mode.SYSTEM ||
            TsModeController.systemBlockNow() != TsModeController.SystemBlock.NONE
        ) {
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val ctx = coreContext.context
        if (TsManager.otherVpnActive(ctx)) {
            // Před Androidem 11 se vlastní a cizí VPN rozlišuje jen podle
            // našeho rozhraní; dobíhající rozhraní z přestavby tunelu by
            // mohlo chvíli vypadat cize — tam teprve druhé pozorování v témže
            // výpadku rozhraní (5–90 s po prvním; starší se nepočítá, jinak
            // by zbytek z dávné přestavby udělal z další přestavby poplach).
            val age = now - otherVpnSeenAt
            val sure = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R ||
                (otherVpnSeenAt != 0L && age in 5_000L..90_000L)
            if (sure) {
                otherVpnSeenAt = 0L
                TsManager.markSlotLost(ctx, "při automatickém startu drží místo jiná VPN")
            } else if (otherVpnSeenAt == 0L || age > 90_000L) {
                otherVpnSeenAt = now
            }
            return
        }
        otherVpnSeenAt = 0L
        if (now - lastAutoStart < 30_000) return
        if (android.net.VpnService.prepare(coreContext.context) != null) return
        lastAutoStart = now
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN",
            "rozhraní tunelu neběží a souhlas je udělen — startuji službu"
        )
        TsManager.startVpnServiceNow()
    }

    /**
     * Vnitřní tunel neběží, i když jádro tunelu jede: srovnat režim
     * (žádost v knihovně, služba v popředí, relé). Bez souhlasu, bez
     * kontroly jiné VPN a bez logiky místa — nic z toho nepotřebuje.
     */
    private fun maybeStartInternal() {
        if (!TsManager.wantsRunning()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAutoStart < 30_000) return
        lastAutoStart = now
        TsModeController.reconcile("vnitřní tunel neběží")
    }

    /** Přepínání režimu: registrace vypnout a nezapínat, dokud přepnutí
     *  neskončí (TsModeController). Vlákno jádra. */
    @WorkerThread
    fun holdForSwitch(core: Core) {
        ready = false
        if (this.core == null) this.core = core
        applyState(false)
    }

    /** Vynucené vyhodnocení se zápisem do deníku (z libovolného vlákna). */
    fun reevaluate(reason: String) {
        coreContext.postOnMainThread { evaluate(reason) }
    }

    /** [forced] = důvod vynuceného vyhodnocení (zapíše se do deníku i beze
     *  změny), [reconcile] = pravidelná kontrola registrace účtů. */
    private fun evaluate(forced: String? = null, reconcile: Boolean = false) {
        // Skutečný stav jádra, ne zobrazovaný: ten přestavba tunelu přepíše na
        // STOPPING a jádro ho už nevrátí (viz TsManager.backendState).
        val state = TsManager.backendState()
        val loggedIn = TsManager.loggedIn.value == true
        val verified = TsManager.endpointVerified.value == true
        val mode = TsModeController.modeInUse()
        // cesta podle režimu: systémové rozhraní, nebo vnitřní tunel s relé
        val pathUp = TsManager.isTransportUp()
        val paused = TsManager.networkPaused.value == true
        // Stav RUNNING z definice znamená přihlášený uzel — příznak
        // přihlášení z notifikací chodí pozdě/nespolehlivě a držel účet
        // v „disabled", i když tunel dávno běžel (do logu se dál píše).
        if (verified && state == TsManager.State.RUNNING && !pathUp) {
            when (mode) {
                TsModeController.Mode.SYSTEM -> maybeStartSystem()
                TsModeController.Mode.INTERNAL -> maybeStartInternal()
                TsModeController.Mode.OFF -> {}
            }
        }
        // rozhraní stojí: případné první pozorování „jiné VPN“ patřilo
        // dobíhajícímu vlastnímu rozhraní, příští výpadek počítá znovu
        if (mode == TsModeController.Mode.SYSTEM && pathUp) otherVpnSeenAt = 0L
        // Účet ve volací knihovně musí odpovídat režimu: ve vnitřním trasa
        // na port běžícího relé (a šifrování hlasu, jak ho relé chce) — jinak
        // by registrace šla jinudy nebo na mrtvý port. Během přepínání se
        // registrace nezapíná vůbec.
        val sipOk = TsSipTransport.sipConfiguredFor(mode)
        val switching = TsModeController.isSwitching()
        // Bez postavené cesty nemá registrace kudy jít — dřív se
        // zapínala i tak a padala na io error.
        // Pozastavená síť (prázdná mapa protějšků) je pro registraci totéž
        // co spadlé rozhraní: ústředna není odkud dosažitelná. Po návratu
        // protějšků se příznak zhasne a registrace se zapne zpátky sama.
        val available = verified && (state == TsManager.State.RUNNING) && pathUp && !paused &&
            sipOk && !switching
        val facts = ("režim=%s stav=%s přihlášení=%s ověření=%s cesta=%s účet=%s " +
            "pozastaveno=%s přepínání=%s").format(
            TsModeController.label(mode), state, loggedIn, verified, pathUp, sipOk, paused, switching)
        if (reconcile) {
            // znění výstrahy o ztraceném místě pro VPN: jiná VPN se mohla
            // ukázat až po odebrání (jen tiché přepsání, žádné nové pípnutí)
            if (TsManager.isSlotLost() && mode == TsModeController.Mode.SYSTEM) {
                TsManager.refreshSlotLostAlert(coreContext.context)
            }
            ready = available
            coreContext.postOnCoreThread { reconcileAccounts(available, facts) }
            return
        }
        if (forced != null) {
            ready = available
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-NET", "%s: síť %s (%s)".format(
                    forced, if (available) "připravena" else "nepřipravena", facts)
            )
            coreContext.postOnCoreThread { applyState(available) }
            return
        }
        if (available == ready) return
        ready = available
        Log.i("$TAG Network readiness is now [$available]")
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-NET",
            "síť %s (%s) — registrace u ústředny se %s".format(
                if (available) "připravena" else "nepřipravena",
                facts,
                if (available) "zapíná" else "vypíná")
        )
        coreContext.postOnCoreThread {
            applyState(available)
        }
    }

    /** Pravidelná kontrola: registrace účtů musí odpovídat stavu sítě. */
    @WorkerThread
    private fun reconcileAccounts(available: Boolean, facts: String) {
        val core = this.core ?: return
        if (core.accountList.none { it.params.isRegisterEnabled != available }) return
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-NET", "registrace nesouhlasila se stavem sítě — srovnávám na %s (%s)"
                .format(if (available) "zapnuto" else "vypnuto", facts)
        )
        applyState(available)
    }

    @WorkerThread
    private fun applyState(available: Boolean) {
        val core = this.core ?: return
        for (account in core.accountList) {
            val params = account.params
            if (params.isRegisterEnabled == available) continue
            val copy = params.clone()
            copy.isRegisterEnabled = available
            account.params = copy
        }
    }
}
