/*
 * fork: správa zabudovaného tunelu (libtailscale). Drží běžící backend,
 * sleduje jeho stav a nabízí ho zbytku aplikace.
 *
 * Předloha pro práci s knihovnou: tailscale-android App.kt / Notifier.kt /
 * localapi Client.kt (BSD-3-Clause). Zde bez kotlinx.serialization —
 * odpovědi se čtou org.json, aby fork nepotřeboval další gradle plugin.
 */
package org.linphone.twentyone.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.MutableLiveData
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.linphone.core.tools.Log

object TsManager {
    private const val TAG = "[Tunnel]"

    private const val NOTIFY_MASK_INITIAL_STATE = 2L
    private const val NOTIFY_MASK_PREFS = 4L
    /** NotifyPeerChanges — bez něj se o změnách protějšků dozvíme až pollem. */
    private const val NOTIFY_MASK_PEER_CHANGES = 4096L
    private const val NOTIFY_MASK_NO_NETMAP = 8192L
    private const val NOTIFY_MASK_INITIAL_STATUS = 16384L

    /** Hystereze hodnocení trasy se počítá z času, ne z počtu událostí:
     *  po zapnutí odběru změn protějšků chodí notifikace ve shlucích. */
    private const val SCOPE_SWITCH_STABLE_MS = 90_000L
    private const val STATUS_POLL_SECONDS = 30L
    /** Slučovací okno pro dotaz na stav po shluku změn protějšků. */
    private const val STATUS_REFRESH_DEBOUNCE_MS = 750L
    /** Nejvýš jedno srovnání předvoleb za tuhle dobu (pojistka proti smyčce). */
    private const val EXIT_NODE_RECONCILE_MS = 10_000L
    /** Odříznutí se hlásí až po takhle dlouho nepřetržitě prázdné netmapě.
     *  Počítá se ČAS, ne počet statusů: po zapnutí odběru změn protějšků
     *  chodí notifikace ve shlucích a dva statusy umí přijít za dvě sekundy. */
    private const val NETWORK_PAUSE_MIN_MS = 45_000L
    /** Neúspěšný zápis předvoleb se opakuje s rostoucím odstupem až po tenhle
     *  strop. Jediný pokus o sundání uzlu při odříznutí se dřív neopakoval
     *  vůbec a v jádře zůstal ležet identifikátor uzlu, který v síti není. */
    private const val EXIT_NODE_RETRY_MAX_MS = 300_000L
    /** Jak dlouho smí v předvolbách jádra ležet výstupní uzel, který v mapě
     *  sítě NENÍ, než se zruší. Přechodný stav uzel neshazuje (úklid ve
     *  správě sítě běží v tiku 60 s), jenže stav bez horní meze přechodný
     *  není: pro nevyřešený výstupní uzel pošle jádro všechen provoz telefonu
     *  do černé díry. */
    private const val EXIT_NODE_STRANDED_MAX_MS = 300_000L
    /** Delší díra mezi dvěma platnými měřeními trasy = nový nepřerušený úsek:
     *  hodiny hystereze se posunou na jeho začátek (NEnulují se). Bez toho
     *  by se po dlouhé pauze rozhodovalo ze zastaralého razítka. */
    private const val ROUTE_SAMPLE_GAP_MS = 60_000L

    enum class State(val value: Int) {
        NO_STATE(0),
        IN_USE_OTHER_USER(1),
        NEEDS_LOGIN(2),
        NEEDS_MACHINE_AUTH(3),
        STOPPED(4),
        STARTING(5),
        RUNNING(6),
        STOPPING(7);

        companion object {
            fun fromInt(value: Int): State = values().firstOrNull { it.value == value } ?: NO_STATE
        }
    }

    private lateinit var appContext: Context
    private var app: libtailscale.Application? = null
    private var tsAppContext: TsAppContext? = null
    private var notificationManager: libtailscale.NotificationManager? = null
    private var preferences: TsPreferences? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val poller: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    /** Od kdy (elapsedRealtime) je trasa k bráně nepřetržitě přímá / přes uzel. */
    private var directSince = 0L
    private var relaySince = 0L

    /** Stupeň, se kterým je rozhraní tunelu OPRAVDU postavené. Mění se až po
     *  skutečné přestavbě (TsVpnService.newBuilder), ne při pouhém dotazu. */
    @Volatile
    private var accessInUse = TsPreferences.TunnelAccess.APP_ONLY

    /** Poslední stav sítě z /localapi/v0/status. Čtenáři na workeru ho berou
     *  odsud — netDiag.value je LiveData plněná přes postValue a na workeru
     *  by vracela starou (nebo žádnou) hodnotu. */
    @Volatile
    private var lastDiag: NetDiag? = null

    @Volatile
    private var currentState: State = State.NO_STATE

    @Volatile
    private var loggedInNow = false

    @Volatile
    private var firstStatusSeen = false

    @Volatile
    private var networkPausedNow = false

    @Volatile
    private var emptyNetmapStatuses = 0

    /** Od kdy (elapsedRealtime) je netmapa nepřetržitě prázdná; 0 = není. */
    @Volatile
    private var emptyNetmapSince = 0L

    @Volatile
    private var lastReconcileAt = 0L

    /** Odstup dalšího pokusu po neúspěšném zápisu předvoleb (0 = poslední
     *  zápis prošel). Roste na dvojnásobek až po EXIT_NODE_RETRY_MAX_MS. */
    @Volatile
    private var exitNodeRetryMs = 0L

    /** Od kdy (elapsedRealtime) chybí nastavený výstupní uzel v mapě sítě;
     *  0 = je v ní, nebo žádný nastavený není. */
    @Volatile
    private var exitNodeStrandedSince = 0L

    /** Kdy naposledy (elapsedRealtime) bylo měření trasy k bráně platné. */
    @Volatile
    private var lastRouteSampleAt = 0L

    /** Zrcadla LiveData pro čtenáře mimo hlavní vlákno: postValue doručí
     *  hodnotu až na hlavním vlákně, takže .value by na workeru a na
     *  notifikačním vlákně vracelo starou hodnotu (stejný důvod jako u
     *  lastDiag). */
    @Volatile
    private var vpnActiveNow = false

    @Volatile
    private var directNow = false

    /** Co podle posledního vědomí appky leží v předvolbách jádra. Plní se
     *  z notifikace Prefs a po každém úspěšném PATCH — srovnání se pak dá
     *  spustit i ze statusu, kdy žádná notifikace Prefs nechodí. */
    @Volatile
    private var storedExitNodeId = ""

    @Volatile
    private var storedExitNodeIp = ""

    private val statusRefreshPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Rozepsané opakování srovnání předvoleb: strop 1×/EXIT_NODE_RECONCILE_MS
     *  pokus dřív zahodil natrvalo, teď se jen odloží. */
    private val reconcileRetryPending = java.util.concurrent.atomic.AtomicBoolean(false)

    val state = MutableLiveData(State.NO_STATE)
    val vpnActive = MutableLiveData(false)
    val browseToUrl = MutableLiveData<String?>(null)
    val loggedIn = MutableLiveData(false)
    val directConnection = MutableLiveData(false)
    val tailnetAddress = MutableLiveData<String?>(null)
    val endpointVerified = MutableLiveData(false)

    /** Síť běží, ale nemá jediný protějšek = odříznutí (chybí kredit). */
    val networkPaused = MutableLiveData(false)

    /** Co appka ví o bráně v síti (tři stavy pro obrazovku i deník). */
    enum class GatewayState {
        /** brána v síti není */
        ABSENT,
        /** brána je, ale provoz ven zatím nepouští */
        NO_EXIT_OPTION,
        /** brána je a provoz ven pustit umí */
        READY,
        /** kandidátů je víc, nedá se rozhodnout */
        AMBIGUOUS
    }

    val gatewayState = MutableLiveData(GatewayState.ABSENT)

    /** Zrcadlo gatewayState pro čtenáře mimo hlavní vlákno (stejný důvod jako
     *  u directNow): porovnání proti LiveData.value vidí na workeru a na
     *  notifikačním vlákně hodnotu, která ještě nebyla doručena — dva statusy
     *  těsně po sobě tím uměly nechat obrazovku uvíznout na stavu, který už
     *  neplatí, a týž přechod zapsat do deníku dvakrát. */
    @Volatile
    private var gatewayStateNow = GatewayState.ABSENT

    /** Brána, o které už je v deníku zapsáno, že se poznala bez adresy
     *  z kódu v telefonu (ať se věta neopakuje při každém dotazu na stav). */
    @Volatile
    private var staleCodeLoggedFor = ""

    /** Poslední věta o výstupním uzlu v deníku — stejná hláška se po sobě
     *  nepíše dvakrát (srovnání teď běží při každém statusu). */
    @Volatile
    private var lastExitNodeLogLine = ""

    /** Poslední zjištěný stav systémového „Blokovat připojení bez VPN"
     *  (čte ho služba tunelu při startu; null = zatím nezjištěno).
     *  Diagnostika stahování podle něj dává jednoznačný verdikt. */
    @Volatile
    var lockdownDetected: Boolean? = null

    @Volatile
    private var endpointMatches = false

    @Volatile
    private var domainMatches: Boolean? = null

    /** Zrcadlo endpointVerified pro čtenáře mimo hlavní vlákno. */
    @Volatile
    private var verifiedNow = false

    val prefs: TsPreferences
        get() = preferences ?: TsPreferences(appContext).also { preferences = it }

    @Synchronized
    fun ensureStarted(context: Context) {
        if (app != null) return
        appContext = context.applicationContext
        preferences = TsPreferences(appContext)

        val ctx = TsAppContext(appContext)
        tsAppContext = ctx
        TsNotifications.createChannel(appContext)

        val filesDir = appContext.filesDir.absolutePath
        Log.i("$TAG Starting tunnel backend, state directory [$filesDir]")
        app = libtailscale.Libtailscale.start(filesDir, filesDir, false, ctx)

        monitorNetworkChanges()
        watchNotifications()

        poller.scheduleWithFixedDelay({
            // currentState, ne state.value: LiveData vrací na jiném než
            // hlavním vlákně hodnotu, která ještě nebyla doručena.
            if (currentState == State.RUNNING) {
                refreshStatus()
            }
        }, STATUS_POLL_SECONDS, STATUS_POLL_SECONDS, TimeUnit.SECONDS)
    }

    fun isStarted(): Boolean = app != null

    /** Uzel je přihlášený a rozhraní tunelu postavené. */
    fun isTunnelUp(): Boolean = currentState == State.RUNNING && vpnActiveNow

    /** Uzel je v síti přihlášený (rozhraní ještě nemusí stát). */
    fun isLoggedIn(): Boolean = currentState == State.RUNNING

    /**
     * Přihlásí uzel proti koordinátorovi. Klíč je jednorázový (preauth) —
     * bez něj se použije interaktivní přihlášení v prohlížeči.
     */
    fun login(controlUrl: String, authKey: String?) {
        worker.execute {
            // Mapa sítě z minulého přihlášení už neplatí. Kdyby zůstala, dal
            // by se z ní po načtení kódu z JINÉHO miniserveru vybrat protějšek
            // původního zákazníka a jeho identifikátor by šel do předvoleb
            // jádra — uzel, který v nové síti není, tam znamená černou díru.
            // Do příchodu prvního statusu z nové sítě se nerozhoduje o ničem.
            invalidateNetworkView()
            val url = prefs.controlUrl
            if (controlUrl.isNotEmpty() && !sameEndpoint(controlUrl, url)) {
                Log.w("$TAG Ignoring requested endpoint [$controlUrl]")
            }
            Log.i("$TAG Logging in against [$url], pre-auth key ${if (authKey.isNullOrEmpty()) "absent" else "present"}")

            // Sekvence PŘESNĚ podle upstreamu (IpnViewModel.login):
            // 1) editPrefs vrátí KOMPLETNÍ prefs — částečný UpdatePrefs by
            //    ostatní pole vynuloval; s klíčem se zároveň ruší LoggedOut
            val masked = JSONObject()
                .put("ControlURL", url)
                .put("ControlURLSet", true)
            if (!authKey.isNullOrEmpty()) {
                masked.put("LoggedOut", false).put("LoggedOutSet", true)
            }
            val edited = callLocalApi("PATCH", "prefs", masked.toString().toByteArray())
            if (edited == null) {
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-E8", "úprava předvoleb tunelu selhala, endpoint=%s".format(url))
                return@execute
            }
            val fullPrefs = try {
                JSONObject(String(edited, Charsets.UTF_8))
            } catch (e: Exception) {
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-E8", "nečitelné předvolby tunelu: %s".format(e.message ?: ""))
                return@execute
            }
            updateEndpointFromPrefs(fullPrefs)
            fullPrefs.put("WantRunning", true)
            // Do startu nesmí jít staré ID výstupního uzlu: po přihlášení
            // k jiné síti by ukazovalo na neexistující uzel a jádro by
            // vynutilo 0.0.0.0/0 do černé díry. Uzel se nasadí až srovnáním
            // předvoleb, až budou známé protějšky.
            fullPrefs.put("ExitNodeID", "")
            fullPrefs.put("ExitNodeIP", "")

            // 2) start s kompletními prefs (resetuje control client) + klíč
            val options = JSONObject().put("UpdatePrefs", fullPrefs)
            if (!authKey.isNullOrEmpty()) {
                options.put("AuthKey", authKey)
            }
            if (callLocalApi("POST", "start", options.toString().toByteArray()) == null) {
                Log.e("$TAG Failed to start tunnel session")
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-E8", "start tunelu selhal, endpoint=%s".format(url))
                return@execute
            }
            // Start prošel s prázdným ExitNodeID i ExitNodeIP, takže od téhle
            // chvíle je to i to, proti čemu srovnání porovnává (notifikace
            // předvoleb nemusí dorazit a staré ID by srovnání odradilo).
            storedExitNodeId = ""
            storedExitNodeIp = ""

            // 3) login-interactive je POVINNÝ i s klíčem (upstream: "required
            //    for both") — s klíčem neotevírá prohlížeč, jen spustí
            //    registraci. Bez něj jádro klíč drží a NIKDY ho nepoužije
            //    (přesně tak zůstal klíč z QR nevyužitý).
            if (callLocalApi("POST", "login-interactive", null) == null) {
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-E8", "spuštění přihlášení selhalo, endpoint=%s".format(url))
                return@execute
            }
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-TUN",
                "přihlášení odesláno (klíč=%s)".format(
                    if (authKey.isNullOrEmpty()) "ne" else "ano"))
            // Registrace u koordinátora běží asynchronně — do deníku se musí
            // dostat i výsledek, jinak ztracený klíč nezanechá žádnou stopu
            // (přesně to se stalo: klíč vydán, nikdy nepoužit, deník mlčel).
            poller.schedule({
                // currentState/loggedInNow, ne LiveData.value: tenhle blok
                // běží na plánovači, kde .value vrací hodnotu, kterou hlavní
                // vlákno ještě nepřevzalo — a falešné E8 do deníku.
                val st = currentState
                // RUNNING = přihlášeno (příznak z notifikací umí přijít
                // později a hlásil falešné E8, i když registrace proběhla)
                if (!loggedInNow && st != State.RUNNING) {
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-E8",
                        ("přihlášení do 30 s neproběhlo (stav=%s) — klíč se " +
                            "nejspíš nedostal ke koordinátorovi").format(st))
                } else {
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN", "přihlášení potvrzeno (stav=%s)".format(st))
                }
            }, 30, TimeUnit.SECONDS)
        }
    }

    fun logout() {
        worker.execute {
            // Po odhlášení už mapa sítě neplatí — viz login().
            invalidateNetworkView()
            callLocalApi("POST", "logout", null)
        }
        stopService()
    }

    /**
     * Zahodí obraz sítě, ze kterého se rozhoduje o výstupním uzlu.
     * Volá se při přihlášení, odhlášení a při každém odchodu backendu ze
     * stavu RUNNING: do příchodu prvního statusu z nové (nebo obnovené) sítě
     * vrací findGateway „brána není“ a srovnání předvoleb se vrací hned.
     */
    private fun invalidateNetworkView() {
        lastDiag = null
        firstStatusSeen = false
        exitNodeStrandedSince = 0L
        staleCodeLoggedFor = ""
    }

    fun setWantRunning(wantRunning: Boolean) {
        worker.execute {
            val masked = JSONObject()
                .put("WantRunning", wantRunning)
                .put("WantRunningSet", true)
            callLocalApi("PATCH", "prefs", masked.toString().toByteArray())
        }
    }

    fun startService() {
        val intent = Intent(appContext, TsVpnService::class.java).apply {
            action = TsVpnService.ACTION_START
        }
        try {
            appContext.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e("$TAG Failed to start tunnel service: $e")
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-E17",
                "službu tunelu nejde nastartovat: %s %s".format(
                    e.javaClass.simpleName, e.message ?: "")
            )
        }
    }

    fun stopService() {
        val intent = Intent(appContext, TsVpnService::class.java).apply {
            action = TsVpnService.ACTION_STOP
        }
        try {
            appContext.startService(intent)
        } catch (e: Exception) {
            Log.e("$TAG Failed to stop tunnel service: $e")
        }
    }

    fun restartService() {
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "přestavba tunelu, přístup=%s".format(prefs.tunnelAccess))
        val intent = Intent(appContext, TsVpnService::class.java).apply {
            action = TsVpnService.ACTION_RESTART
        }
        try {
            appContext.startService(intent)
        } catch (e: Exception) {
            Log.e("$TAG Failed to stop tunnel service for restart: $e")
        }
        // Nová instance až s odstupem, po vzoru upstreamu — restart na téže
        // instanci (close + reset příznaku) končil dvojím odpojením Go
        // strany a tunel po přestavbě zůstal dole.
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ startService() }, 1200)
    }

    /**
     * Stupeň, který se opravdu použije. Snižuje se JEN nejvyšší stupeň
     * (internet přes miniserver) a jen když spojení běží přes přenosový
     * uzel — tam by celý provoz telefonu tekl oklikou. Přístup na
     * miniserver se nesnižuje: do tunelu stejně jdou jen jeho adresy.
     */
    fun effectiveTunnelAccess(): TsPreferences.TunnelAccess {
        val wanted = prefs.tunnelAccess
        // BEZ vedlejšího efektu: dotaz na stupeň nesmí měnit accessInUse,
        // jinak se přestavba tunelu „ztratí" (maybeRebuildTunnel by ji
        // vyhodnotil jako už provedenou).
        // directNow, ne directConnection.value: tahle funkce se volá i ze
        // srovnání předvoleb na workeru, kde LiveData vrací hodnotu, která
        // ještě nebyla doručena — a právě nasazený uzel by se tím zase sundal.
        return if (wanted == TsPreferences.TunnelAccess.INTERNET && !directNow) {
            TsPreferences.TunnelAccess.SERVER
        } else {
            wanted
        }
    }

    /** Volá JEN služba tunelu při stavbě rozhraní: tady stupeň opravdu vzniká. */
    fun tunnelAccessForBuild(): TsPreferences.TunnelAccess {
        val access = effectiveTunnelAccess()
        accessInUse = access
        return access
    }

    fun onVpnStatusChanged(active: Boolean) {
        if (active && ::appContext.isInitialized) {
            // zdravě postavený tunel = konec případné smyčky restartů
            TunnelStartGuard.reset(appContext)
        }
        // SYNCHRONNĚ před postValue: čtenáři mimo hlavní vlákno (přestavba
        // tunelu) se rozhodují podle vpnActiveNow.
        vpnActiveNow = active
        vpnActive.postValue(active)
    }

    fun onServiceClosing() {
        state.postValue(State.STOPPING)
    }

    private fun watchNotifications() {
        val backend = app ?: return
        val mask = NOTIFY_MASK_INITIAL_STATE or NOTIFY_MASK_PREFS or
            NOTIFY_MASK_PEER_CHANGES or NOTIFY_MASK_NO_NETMAP or
            NOTIFY_MASK_INITIAL_STATUS
        notificationManager = backend.watchNotifications(mask) { payload ->
            try {
                onNotify(JSONObject(String(payload, Charsets.UTF_8)))
            } catch (e: Exception) {
                Log.e("$TAG Failed to process backend notification: $e")
            }
        }
    }

    private fun onNotify(notify: JSONObject) {
        if (notify.has("State")) {
            val newState = State.fromInt(notify.getInt("State"))
            Log.i("$TAG Backend state is now [$newState]")
            currentState = newState
            state.postValue(newState)
            if (newState == State.RUNNING || newState == State.STARTING) {
                browseToUrl.postValue(null)
            }
            // Přihlášení se odvozuje i ze stavu backendu: po serverovém
            // smazání uzlu zůstane příznak z Prefs viset na true a hlavní
            // tlačítko by bylo tichý no-op.
            if (newState == State.RUNNING) {
                loggedInNow = true
                loggedIn.postValue(true)
            } else if (newState == State.NEEDS_LOGIN || newState == State.NO_STATE) {
                loggedInNow = false
                loggedIn.postValue(false)
            }
            if (newState != State.RUNNING) {
                // Příznak odříznutí se přepočítává jen ve statusu a statusy
                // mimo RUNNING nechodí — bez tohohle by zůstal viset na true
                // a obrazovka by odpojenému (nebo ze sítě vyhozenému)
                // telefonu dál tvrdila „chybí kredit“, hlavní tlačítko by
                // zůstalo tichý no-op a nové QR by se nenabídlo.
                // Uzel se tu NENASTAVUJE: mimo RUNNING není co nastavovat.
                emptyNetmapStatuses = 0
                emptyNetmapSince = 0L
                // Mimo RUNNING je i poslední mapa sítě jen vzpomínka: kdyby
                // zůstala, dalo by se z ní po přihlášení do jiné sítě vybrat
                // cizí protějšek jako brána.
                invalidateNetworkView()
                if (networkPausedNow) {
                    networkPausedNow = false
                    networkPaused.postValue(false)
                }
            }
        }
        // POZOR: pole bývá v notifikacích přítomné s hodnotou null a org.json
        // ho z getString vrací jako ŘETĚZEC "null" — bez těchhle stráží se
        // "null" tvářil jako adresa k otevření (zdroj pádů před 21p.21)
        if (notify.has("BrowseToURL") && !notify.isNull("BrowseToURL")) {
            val url = notify.getString("BrowseToURL")
            if (url.isNotBlank() && url != "null" && url.startsWith("http")) {
                browseToUrl.postValue(url)
            }
        }
        if (notify.has("Prefs")) {
            val prefsObject = notify.getJSONObject("Prefs")
            if (!prefsObject.optBoolean("LoggedOut", true)) {
                loggedInNow = true
                loggedIn.postValue(true)
            } else if (currentState != State.RUNNING) {
                loggedInNow = false
                loggedIn.postValue(false)
            }
            updateEndpointFromPrefs(prefsObject)
            val storedId = prefsObject.optString("ExitNodeID", "")
            val storedIp = prefsObject.optString("ExitNodeIP", "")
            storedExitNodeId = storedId
            storedExitNodeIp = storedIp
            worker.execute { reconcileExitNode(storedId, storedIp) }
        }
        // Delty protějšků se NEPARSUJÍ (a nesmí se hledět na délku pole —
        // při prázdné netmapě dorazí prázdné pole): rozhoduje se jen podle
        // přítomnosti klíče a jediným zdrojem pravdy zůstává /status.
        if (notify.has("PeersChanged") || notify.has("PeersRemoved") ||
            notify.has("SelfChange")
        ) {
            scheduleStatusRefresh()
        }
        if (notify.has("InitialStatus")) {
            onStatus(notify.getJSONObject("InitialStatus"))
        }
    }

    /** Dotaz na stav po shluku změn protějšků, sloučený do jednoho volání.
     *  Stejná podmínka jako u polleru: jádro posílá změny protějšků i poté,
     *  co uživatel tunel vypnul, a vyhodnocovat z nich stav sítě (a případně
     *  přestavovat rozhraní) nedává smysl. */
    private fun scheduleStatusRefresh() {
        if (currentState != State.RUNNING) return
        if (!statusRefreshPending.compareAndSet(false, true)) return
        poller.schedule({
            statusRefreshPending.set(false)
            if (currentState == State.RUNNING) {
                refreshStatus()
            }
        }, STATUS_REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    /** Řádek diagnostiky o protějšku v privátní síti. */
    data class NetPeer(
        val host: String,
        val online: Boolean,
        /** první adresa protějšku (pro zobrazení a měření) */
        val address: String,
        /** všechny adresy protějšku — brána se hledá mezi nimi, ne jen v [0] */
        val addresses: List<String>,
        /** stabilní identifikátor uzlu (jediné, co smí do předvoleb jádra) */
        val stableId: String,
        /** uzel nabízí, že přes něj smí ven provoz (pole je omitempty) */
        val exitNodeOption: Boolean,
        /** uzel je právě používaný jako výstupní */
        val isExitNode: Boolean,
        val tags: List<String>,
        val relay: String,
        val directAddr: String,
        val lastHandshake: String,
        val rx: Long,
        val tx: Long
    )

    /** Podklady pro obrazovku Diagnostika (co o síti ví jádro tunelu). */
    data class NetDiag(
        val endpoints: List<String>,
        val publicEndpoints: List<String>,
        val relay: String,
        val health: List<String>,
        val peers: List<NetPeer>
    )

    val netDiag = MutableLiveData<NetDiag>()

    /** Adresa z veřejného rozsahu (ne privátní, ne CGNAT, ne link-local). */
    fun isPublicEndpoint(endpoint: String): Boolean {
        val host = endpoint.substringBeforeLast(':', endpoint).trim('[', ']')
        if (host.isEmpty()) return false
        if (host.contains(':')) {
            val h = host.lowercase()
            if (h == "::1" || h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd")) {
                return false
            }
            return true
        }
        val parts = host.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when {
            a == 10 || a == 127 || a == 0 -> false
            a == 192 && b == 168 -> false
            a == 172 && b in 16..31 -> false
            a == 169 && b == 254 -> false
            a == 100 && b in 64..127 -> false
            else -> true
        }
    }

    private fun parseNetDiag(status: JSONObject): NetDiag {
        val endpoints = ArrayList<String>()
        var relay = ""
        status.optJSONObject("Self")?.let { self ->
            val addrs = self.optJSONArray("Addrs")
            if (addrs != null) {
                for (i in 0 until addrs.length()) {
                    val v = addrs.optString(i, "")
                    if (v.isNotEmpty()) endpoints.add(v)
                }
            }
            relay = self.optString("Relay", "")
        }
        val health = ArrayList<String>()
        status.optJSONArray("Health")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotEmpty()) health.add(v)
            }
        }
        val peers = ArrayList<NetPeer>()
        status.optJSONObject("Peer")?.let { map ->
            val keys = map.keys()
            while (keys.hasNext()) {
                val peer = map.optJSONObject(keys.next()) ?: continue
                val ips = peer.optJSONArray("TailscaleIPs")
                val addresses = ArrayList<String>()
                if (ips != null) {
                    for (i in 0 until ips.length()) {
                        val v = ips.optString(i, "")
                        if (v.isNotEmpty()) addresses.add(v)
                    }
                }
                val tags = ArrayList<String>()
                peer.optJSONArray("Tags")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "")
                        if (v.isNotEmpty()) tags.add(v)
                    }
                }
                peers.add(
                    NetPeer(
                        host = peer.optString("HostName", "?"),
                        online = peer.optBoolean("Online", false),
                        address = addresses.firstOrNull().orEmpty(),
                        addresses = addresses,
                        stableId = peer.optString("ID", ""),
                        exitNodeOption = peer.optBoolean("ExitNodeOption", false),
                        isExitNode = peer.optBoolean("ExitNode", false),
                        tags = tags,
                        relay = peer.optString("Relay", ""),
                        directAddr = peer.optString("CurAddr", ""),
                        lastHandshake = peer.optString("LastHandshake", ""),
                        rx = peer.optLong("RxBytes", 0L),
                        tx = peer.optLong("TxBytes", 0L)
                    )
                )
            }
        }
        return NetDiag(
            endpoints = endpoints,
            publicEndpoints = endpoints.filter { isPublicEndpoint(it) },
            relay = relay,
            health = health,
            peers = peers
        )
    }

    /**
     * Změří spojení s protějškem (přímé, nebo přes přenosový uzel).
     * Vrací hotový text pro obrazovku, ať UI nemusí znát formát odpovědi.
     */
    fun pingPeer(address: String, callback: (String) -> Unit) {
        worker.execute {
            val body = callLocalApi(
                "POST",
                "ping?ip=" + java.net.URLEncoder.encode(address, "UTF-8") + "&type=disco",
                ByteArray(0)
            )
            if (body == null) {
                callback("měření se nepodařilo spustit")
                return@execute
            }
            val text = try {
                val json = JSONObject(String(body, Charsets.UTF_8))
                val err = json.optString("Err", "")
                val latency = json.optDouble("LatencySeconds", 0.0)
                val endpoint = json.optString("Endpoint", "")
                val derp = json.optString("DERPRegionCode", "")
                when {
                    err.isNotEmpty() -> "chyba: $err"
                    endpoint.isNotEmpty() ->
                        "přímo, %d ms".format((latency * 1000).toInt())
                    derp.isNotEmpty() ->
                        "přes uzel %s, %d ms".format(derp, (latency * 1000).toInt())
                    else -> "%d ms".format((latency * 1000).toInt())
                }
            } catch (e: Exception) {
                "odpověď se nepodařilo přečíst"
            }
            callback(text)
        }
    }

    private fun onStatus(status: JSONObject) {
        status.optJSONObject("Self")?.let { self ->
            val ips = self.optJSONArray("TailscaleIPs")
            if (ips != null && ips.length() > 0) {
                tailnetAddress.postValue(ips.getString(0))
            }
            val suffix = status.optJSONObject("CurrentTailnet")?.optString("MagicDNSSuffix", "")
            domainMatches = domainVerdict(self.optString("DNSName", ""), suffix.orEmpty())
            publishVerification()
        }
        val diag = parseNetDiag(status)
        // SYNCHRONNĚ před postValue: čtenáři na workeru berou lastDiag
        lastDiag = diag
        firstStatusSeen = true
        netDiag.postValue(diag)
        val (gateway, gatewayStatus) = findGateway(diag)
        publishGatewayState(gatewayStatus)
        logStaleCode(gateway, gatewayStatus)
        updateStrandedExitNode(diag)
        updatePausedState(diag)
        updateConnectionQuality(diag)
        // Srovnání předvoleb nesmí viset JEN na notifikaci Prefs: netmapa se
        // mění bez toho, aby jádro předvolby měnilo (brána se po přeregistraci
        // hlásí novým stabilním identifikátorem, nebo jí cestu ven schválí až
        // za chvíli po návratu protějšků) — a bez téhle cesty by v jádře
        // zůstalo ležet staré nebo prázdné ID, zatímco obrazovka hlásí, že
        // provoz ven jde přes miniserver. Strop EXIT_NODE_RECONCILE_MS hlídá
        // smyčku a při shodě se srovnání vrací hned.
        worker.execute { reconcileExitNode(storedExitNodeId, storedExitNodeIp) }
    }

    /**
     * Odříznutí: síť běží, uzel je přihlášený, ale netmapa je prázdná.
     * Rozhoduje se až po DVOU takových stavech po sobě A po nepřetržitě
     * prázdné netmapě po dobu NETWORK_PAUSE_MIN_MS, aby přechodná prázdná
     * mapa (start, přeposlaná mapa, výpadek řídicího spojení) neshodila
     * uzel ani registraci u ústředny.
     */
    private fun updatePausedState(diag: NetDiag) {
        val running = currentState == State.RUNNING && loggedInNow
        val now = android.os.SystemClock.elapsedRealtime()
        if (running && diag.peers.isEmpty()) {
            emptyNetmapStatuses++
            if (emptyNetmapSince == 0L) emptyNetmapSince = now
        } else {
            emptyNetmapStatuses = 0
            emptyNetmapSince = 0L
        }
        // Rozhoduje ČAS, ne počet statusů: statusy chodí ve shlucích
        // (slučovací okno je 750 ms), takže dvě notifikace sekundu po sobě
        // dřív vyrobily „odříznutí“ za dvě sekundy — a s ním sundaný uzel
        // i pozastavenou registraci u ústředny.
        val paused = running && emptyNetmapStatuses >= 2 &&
            emptyNetmapSince != 0L &&
            now - emptyNetmapSince >= NETWORK_PAUSE_MIN_MS
        if (paused == networkPausedNow) return
        networkPausedNow = paused
        networkPaused.postValue(paused)
        if (paused) {
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-NET",
                "síť je pozastavená (běží, ale nemá jediný protějšek) — " +
                    "výstupní uzel se sundává"
            )
            worker.execute { applyExitNodeNow(false) }
        } else {
            // Zhasnutí příznaku ještě NEZNAMENÁ, že se protějšky vrátily:
            // stejně vyjde i síť, která přestala běžet nebo se odhlásila.
            // Obnovovací větev (deník, nasazení uzlu, přestavba rozhraní)
            // patří jen skutečnému návratu protějšků — jinak by se tunel
            // sám zvedl poté, co ho uživatel vědomě vypnul.
            if (!running || diag.peers.isEmpty()) return
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-NET", "protějšky se vrátily, síť jede dál"
            )
            val desired = effectiveTunnelAccess()
            worker.execute {
                applyExitNodeNow(desired == TsPreferences.TunnelAccess.INTERNET)
            }
            // Rozhraní se přestavuje jen tehdy, když se stupeň opravdu liší
            // — a jen když rozhraní vůbec stojí.
            if (desired != accessInUse && vpnActiveNow) {
                restartService()
            }
        }
    }

    private fun sameEndpoint(a: String, b: String): Boolean {
        return a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
    }

    private fun domainVerdict(dnsName: String, magicDnsSuffix: String): Boolean? {
        val domain = prefs.networkDomain
        val name = dnsName.trim().trimEnd('.')
        if (name.isNotEmpty()) {
            return name.endsWith(".$domain", ignoreCase = true)
        }
        val suffix = magicDnsSuffix.trim().trim('.')
        if (suffix.isNotEmpty()) {
            return suffix.equals(domain, ignoreCase = true)
        }
        return null
    }

    /** Adresa koordinátora z předvoleb jádra (notifikace, odpověď na PATCH,
     *  pravidelné čtení). */
    private fun updateEndpointFromPrefs(prefsObject: JSONObject) {
        endpointMatches = sameEndpoint(prefsObject.optString("ControlURL", ""), prefs.controlUrl)
        publishVerification()
    }

    @Synchronized
    private fun publishVerification() {
        val verified = endpointMatches && domainMatches != false
        if (verified == verifiedNow) return
        verifiedNow = verified
        Log.i("$TAG Endpoint verification is now [$verified]")
        if (verified) {
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-NET", "síť ověřena (endpoint=%s doména=%s)"
                    .format(endpointMatches, domainMatches))
        } else {
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-E9", "síť neprošla ověřením (endpoint=%s doména=%s)"
                    .format(endpointMatches, domainMatches))
        }
        endpointVerified.postValue(verified)
    }

    /**
     * Provoz přes DERP relay má citelnou latenci, plný tunel by z něj udělal
     * úzké hrdlo pro celý telefon. Rozsah se proto drží na přímém spojení
     * a přepíná až po několika stabilních cyklech (hystereze proti blikání).
     */
    private fun updateConnectionQuality(diag: NetDiag) {
        // Hodnotí se trasa k BRÁNĚ, ne ke „kterémukoli protějšku": jiný
        // protějšek o kvalitě cesty přes miniserver nevypovídá nic.
        // Když brána v netmapě není (i při odříznutí), stav zůstává beze změny
        // a hysterezní hodiny se nehýbou.
        val gateway = findGateway(diag).first ?: return
        val direct = gateway.online && gateway.directAddr.isNotEmpty()
        val now = android.os.SystemClock.elapsedRealtime()
        // Po delší díře mezi platnými měřeními (brána nebyla v mapě, statusy
        // nechodily, protože síť neběžela) začíná nový nepřerušený úsek:
        // hodiny se posunou na jeho začátek. Nenulují se — prázdná mapa je
        // podle zadání posouvat ani nulovat nesmí — ale rozhodovat se ze
        // zastaralého razítka by znamenalo přeskočit celou hysterezi a
        // přestavět rozhraní z jediného vzorku hned po návratu brány.
        val gap = lastRouteSampleAt != 0L && now - lastRouteSampleAt > ROUTE_SAMPLE_GAP_MS
        lastRouteSampleAt = now
        if (gap) {
            if (directSince != 0L) directSince = now
            if (relaySince != 0L) relaySince = now
        }
        if (direct) {
            relaySince = 0L
            if (directSince == 0L) directSince = now
        } else {
            directSince = 0L
            if (relaySince == 0L) relaySince = now
        }

        val current = directNow
        if (direct && !current && now - directSince >= SCOPE_SWITCH_STABLE_MS) {
            Log.i("$TAG Direct gateway connection is stable")
            // SYNCHRONNĚ před postValue (viz directNow)
            directNow = true
            directConnection.postValue(true)
            logRoute(diag, true)
            maybeRebuildTunnel(TsPreferences.TunnelAccess.INTERNET)
        } else if (!direct && current && now - relaySince >= SCOPE_SWITCH_STABLE_MS) {
            Log.w("$TAG Gateway connection fell back to a relay")
            directNow = false
            directConnection.postValue(false)
            logRoute(diag, false)
            maybeRebuildTunnel(TsPreferences.TunnelAccess.SERVER)
        }
    }

    /** Do deníku jdou jen počty, žádné adresy. */
    private fun logRoute(diag: NetDiag, direct: Boolean) {
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-NET",
            "trasa k miniserveru: %s (vlastní endpointy %d, z toho veřejné %d; uzel %s)".format(
                if (direct) "přímo" else "přes uzel",
                diag.endpoints.size,
                diag.publicEndpoints.size,
                diag.relay.ifEmpty { "—" }
            )
        )
    }

    /** Přestavba se týká jen nejvyššího stupně (viz effectiveTunnelAccess). */
    private fun maybeRebuildTunnel(desired: TsPreferences.TunnelAccess) {
        // Rozhraní, které uživatel vypnul, se nepřestavuje: jádro posílá
        // změny protějšků i po „Odpojit“ a restartService() by tunel zase
        // zvedl (z toho stavu se uživatel dostával jen odebráním oprávnění).
        if (!vpnActiveNow) return
        if (prefs.tunnelAccess != TsPreferences.TunnelAccess.INTERNET) return
        if (accessInUse == desired) return
        Log.i("$TAG Rebuilding tunnel, access [$accessInUse] -> [$desired]")
        applyExitNode(desired == TsPreferences.TunnelAccess.INTERNET)
        restartService()
    }

    /**
     * Najde bránu mezi protějšky.
     *
     * 1) protějšek, jehož některá adresa se shoduje s adresou z posledního QR;
     * 2) není-li shoda a právě JEDEN protějšek nabízí cestu ven, je to on
     *    (tagovaná brána je jediný uzel, kterému správa sítě cestu ven
     *    schválí, a telefon vidí jen zařízení svého zákazníka) — QR je pak
     *    zastaralé;
     * 3) není-li ani to a je-li v síti právě JEDEN protějšek, je to brána,
     *    která cestu ven (zatím) nenabízí — pozná se jen pro měření a pro
     *    text na obrazovce, výstupní uzel se z ní NENASAZUJE;
     * 4) víc kandidátů nebo žádný → nic; běžící uzel se NESUNDÁVÁ.
     *
     * Adresa slouží jen k identifikaci a měření; do předvoleb jádra jde vždy
     * stabilní identifikátor.
     */
    fun gatewayPeer(diag: NetDiag?): NetPeer? = findGateway(diag).first

    private fun findGateway(diag: NetDiag?): Pair<NetPeer?, GatewayState> {
        val peers = diag?.peers.orEmpty()
        if (peers.isEmpty()) return null to GatewayState.ABSENT
        val wanted = prefs.gatewayAddress
        if (wanted.isNotEmpty()) {
            val matches = peers.filter { peer ->
                peer.addresses.any { it.equals(wanted, ignoreCase = true) }
            }
            if (matches.size == 1) {
                val peer = matches[0]
                // Shoda adresy protějšek jen POZNÁ, nasazení neospravedlňuje:
                // bez nabídnuté cesty ven by jádro vynutilo 0.0.0.0/0 do
                // černé díry.
                return if (peer.exitNodeOption) {
                    peer to GatewayState.READY
                } else {
                    peer to GatewayState.NO_EXIT_OPTION
                }
            }
            if (matches.size > 1) return null to GatewayState.AMBIGUOUS
        }
        val offering = peers.filter { it.exitNodeOption }
        if (offering.size == 1) return offering[0] to GatewayState.READY
        if (offering.size > 1) return null to GatewayState.AMBIGUOUS
        // 3) Poslední, konzervativní záchrana — jen pro IDENTIFIKACI:
        // telefon bez adresy z nového QR a brána, které správa sítě cestu
        // ven zatím neschválila, jsou stav CELÉ dnešní flotily (a povinný
        // mezistupeň vydání: appka jde ven dřív než brána). Když je v síti
        // právě JEDEN protějšek, je to on — měření trasy, hodnocení spojení
        // i věta na obrazovce tím fungují dál. Uzel se přesto nikdy
        // nenasadí: NO_EXIT_OPTION applyExitNodeNow zastaví.
        if (peers.size == 1) return peers[0] to GatewayState.NO_EXIT_OPTION
        return null to GatewayState.ABSENT
    }

    /** Do deníku se píše jen při změně stavu, ať se nezaplní pollem.
     *  Porovnává se proti gatewayStateNow, ne proti LiveData.value: tahle
     *  funkce běží na vlákně, které doručilo status, a .value by tam vracelo
     *  hodnotu, kterou hlavní vlákno ještě nestihlo převzít. */
    private fun publishGatewayState(newState: GatewayState) {
        if (gatewayStateNow == newState) return
        gatewayStateNow = newState
        gatewayState.postValue(newState)
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-NET",
            when (newState) {
                GatewayState.ABSENT -> "brána v síti není"
                GatewayState.NO_EXIT_OPTION ->
                    "brána je, ale provoz ven zatím nepouští"
                GatewayState.READY -> "brána je a provoz ven pustit umí"
                GatewayState.AMBIGUOUS ->
                    "bran je v síti víc — výstupní uzel se nemění"
            }
        )
    }

    /**
     * Brána se poznala podle nabídnuté cesty ven, ne podle adresy z kódu
     * v telefonu — kód je tedy zastaralý (nebo v telefonu žádný takový ještě
     * není). Kontrakt to chce mít v deníku: bez záznamu se z diagnostiky nedá
     * poznat, že telefon jede na zastaralé konfiguraci a že stačí načíst nový
     * kód. Píše se jen při změně identity brány, ne při každém dotazu na stav.
     */
    private fun logStaleCode(peer: NetPeer?, state: GatewayState) {
        if (peer == null || state != GatewayState.READY) return
        val wanted = prefs.gatewayAddress
        if (wanted.isNotEmpty() &&
            peer.addresses.any { it.equals(wanted, ignoreCase = true) }
        ) {
            staleCodeLoggedFor = ""
            return
        }
        val identity = peer.stableId.ifEmpty { peer.address }
        if (staleCodeLoggedFor == identity) return
        staleCodeLoggedFor = identity
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-NET",
            "brána se poznala podle nabídnuté cesty ven — kód v telefonu je " +
                "zastaralý, načti z miniserveru nový"
        )
    }

    /**
     * Horní mez pro uzel, který v mapě sítě NENÍ.
     *
     * Přechodný stav běžící uzel neshazuje (úklid ve správě sítě běží v tiku
     * 60 s, takže krátká nepřítomnost je normální) a uzel, který v mapě je,
     * se nesundává nikdy — ani když se brána zrovna nedá jednoznačně určit.
     * Jenže nastavený uzel, který v mapě chybí, není přechodný stav bez horní
     * meze: jádro pro nevyřešený výstupní uzel pošle všechen provoz telefonu
     * do černé díry. Po EXIT_NODE_STRANDED_MAX_MS se proto takový záznam
     * z předvoleb zruší. Pokrývá i případ, kdy se jediný pokus o sundání
     * uzlu při odříznutí nepodařil.
     */
    private fun updateStrandedExitNode(diag: NetDiag) {
        val stored = storedExitNodeId
        val now = android.os.SystemClock.elapsedRealtime()
        if (stored.isEmpty() || diag.peers.any { it.stableId == stored }) {
            exitNodeStrandedSince = 0L
            return
        }
        if (exitNodeStrandedSince == 0L) {
            exitNodeStrandedSince = now
            return
        }
        if (now - exitNodeStrandedSince < EXIT_NODE_STRANDED_MAX_MS) return
        if (currentState != State.RUNNING) return
        // Razítko se posune, ať se při neúspěšném zápisu zkusí znovu až za
        // další takový úsek (opakování v krátkém sledu řeší applyExitNodeNow).
        exitNodeStrandedSince = now
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-NET",
            ("nastavený výstupní uzel není v síti déle než %d minut — " +
                "ruší se, aby měl provoz telefonu kudy ven")
                .format(EXIT_NODE_STRANDED_MAX_MS / 60_000L)
        )
        worker.execute { applyExitNodeNow(false) }
    }

    /**
     * Zapne/vypne směrování všeho provozu přes miniserver.
     *
     * Vypnutí i zapnutí jde VŽDY jedním PATCH se čtveřicí polí: samotné
     * ExitNodeIP="" identifikátor uzlu nevynuluje a platná ExitNodeIP by
     * právě nastavené ID přebila.
     */
    fun applyExitNode(enable: Boolean) {
        worker.execute { applyExitNodeNow(enable) }
    }

    /** Běží na workeru. */
    private fun applyExitNodeNow(enable: Boolean) {
        var id = ""
        if (enable) {
            val (peer, status) = findGateway(lastDiag)
            when {
                status == GatewayState.READY && peer != null &&
                    peer.stableId.isNotEmpty() -> {
                    id = peer.stableId
                    // Poslední známá dobrá brána. Čte se ZÁMĚRNĚ jen do
                    // deníku: obnovovat z ní ID ve chvíli, kdy brána v síti
                    // není, by znamenalo poslat jádru uzel, který v síti
                    // neexistuje. Přechodné stavy se proto řeší tím, že se
                    // neposílá nic.
                    prefs.gatewayStableId = id
                }
                status == GatewayState.NO_EXIT_OPTION -> {
                    // Brána je v síti, ale cestu ven nenabízí. Nic neposílat:
                    // prázdné ID by sundalo běžící uzel, cizí ID by udělalo
                    // z provozu telefonu černou díru.
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN",
                        "brána je, ale provoz ven zatím nepouští — " +
                            "zapni v jejím ovládání „i dál do sítě“"
                    )
                    return
                }
                else -> {
                    // Přechodný stav (brána chybí nebo jsou kandidáti dva):
                    // poslední známé dobré nastavení se nechává být.
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN",
                        "brána v síti není — výstupní uzel se nemění " +
                            "(poslední známý: %s)".format(
                                if (prefs.gatewayStableId.isEmpty()) "žádný" else "je")
                    )
                    return
                }
            }
        }
        val body = JSONObject()
            .put("ExitNodeID", id)
            .put("ExitNodeIDSet", true)
            .put("ExitNodeIP", "")
            .put("ExitNodeIPSet", true)
            .put("ExitNodeAllowLANAccess", true)
            .put("ExitNodeAllowLANAccessSet", true)
            .toString()
        val result = callLocalApi("PATCH", "prefs", body.toByteArray(Charsets.UTF_8))
        if (result != null) {
            // Co jsme opravdu zapsali, je od téhle chvíle i to, proti čemu
            // srovnání porovnává (notifikace Prefs nemusí dorazit). Při
            // neúspěchu se záznam NECHÁVÁ starý, aby srovnání z dalšího
            // statusu pokus zopakovalo.
            storedExitNodeId = id
            storedExitNodeIp = ""
            // Strop proti smyčce PATCH → Prefs → PATCH se počítá od ÚSPĚŠNÉHO
            // zápisu: neúspěšný pokus nesmí srovnání blokovat dalších 10 s.
            lastReconcileAt = android.os.SystemClock.elapsedRealtime()
            exitNodeRetryMs = 0L
        } else {
            // Jediný pokus nestačí: když se při odříznutí nepodaří uzel
            // sundat, zůstane v předvolbách jádra uzel, který v síti není,
            // a telefon nemá kudy ven. Zkouší se dál s rostoucím odstupem.
            scheduleReconcileRetry(nextExitNodeRetryDelay())
        }
        logExitNodeOutcome(
            if (result == null) {
                // Text podle toho, co se opravdu posílalo — prázdné ID je
                // sundání uzlu, ne „nastavení internetu přes miniserver“.
                if (id.isEmpty()) {
                    "výstupní uzel se nepodařilo sundat"
                } else {
                    "nastavení internetu přes miniserver se nepodařilo"
                }
            } else if (id.isNotEmpty()) {
                "internet telefonu jde přes miniserver"
            } else {
                "internet telefonu jde napřímo"
            }
        )
    }

    /** Tatáž věta se po sobě do deníku nepíše dvakrát: srovnání běží při
     *  každém statusu a opakovaný neúspěch by deník zaplnil. */
    private fun logExitNodeOutcome(line: String) {
        if (lastExitNodeLogLine == line) return
        lastExitNodeLogLine = line
        org.linphone.twentyone.TwentyOneDiag.log("P21-TUN", line)
    }

    /** Odstup dalšího pokusu po neúspěšném zápisu: 10 s, pak dvojnásobky až
     *  po EXIT_NODE_RETRY_MAX_MS. Úspěšný zápis odstup vynuluje. */
    private fun nextExitNodeRetryDelay(): Long {
        val next = if (exitNodeRetryMs <= 0L) {
            EXIT_NODE_RECONCILE_MS
        } else {
            (exitNodeRetryMs * 2).coerceAtMost(EXIT_NODE_RETRY_MAX_MS)
        }
        exitNodeRetryMs = next
        return next
    }

    /** Srovnání se ODKLÁDÁ, nezahazuje. Běží nejvýš jedno odložené. */
    private fun scheduleReconcileRetry(delayMs: Long) {
        if (!reconcileRetryPending.compareAndSet(false, true)) return
        poller.schedule({
            reconcileRetryPending.set(false)
            worker.execute { reconcileExitNode(storedExitNodeId, storedExitNodeIp) }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Co má být uloženo v předvolbách jádra: prázdno = uzel sundat,
     * neprázdné = tenhle identifikátor, null = nevím (nechat být).
     */
    private fun desiredExitNodeId(): String? {
        if (networkPausedNow) return ""
        if (effectiveTunnelAccess() != TsPreferences.TunnelAccess.INTERNET) return ""
        val (peer, status) = findGateway(lastDiag)
        if (status != GatewayState.READY || peer == null) return null
        return peer.stableId.ifEmpty { null }
    }

    /**
     * Srovná uložené předvolby jádra s tím, co má podle volby uživatele
     * platit (po startu i po novém přihlášení).
     *
     * Spouští se ze dvou stran: z notifikace Prefs (změna předvoleb jádra)
     * a z každého statusu (změna netmapy — nové ID brány, brána, která začala
     * nabízet cestu ven). Proti tomu, co v jádře leží, se porovnává poslední
     * známá hodnota z Prefs, případně to, co sama appka naposledy úspěšně
     * zapsala.
     *
     * Dvě pojistky proti smyčce PATCH → Prefs → PATCH: jedná se až po prvním
     * doručeném stavu a nejvýš jednou za EXIT_NODE_RECONCILE_MS. ŽÁDNÁ z nich
     * už pokus nezahazuje: před prvním statusem srovnání spustí sám onStatus,
     * a při zásahu stropu se pokus odloží na zbývající čas. Neúspěšný zápis
     * se navíc opakuje s rostoucím odstupem (viz applyExitNodeNow).
     */
    private fun reconcileExitNode(storedId: String, storedIp: String) {
        // Před prvním statusem se nerozhoduje o ničem — a nic se tím
        // neztrácí: onStatus srovnání sám spustí, jakmile status dorazí.
        if (!firstStatusSeen) return
        val desired = desiredExitNodeId() ?: return
        if (storedId == desired && storedIp.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val wait = EXIT_NODE_RECONCILE_MS - (now - lastReconcileAt)
        if (wait > 0) {
            // Strop proti smyčce pokus jen ODLOŽÍ. Dřív ho zahazoval, takže
            // neshoda, která přišla do 10 s po zápisu, zůstala napořád.
            scheduleReconcileRetry(wait)
            return
        }
        lastReconcileAt = now
        Log.i("$TAG Exit node preference differs from the chosen one, fixing")
        applyExitNodeNow(desired.isNotEmpty())
    }

    fun refreshStatus() {
        worker.execute {
            callLocalApi("GET", "prefs", null)?.let {
                try {
                    updateEndpointFromPrefs(JSONObject(String(it, Charsets.UTF_8)))
                } catch (e: Exception) {
                    Log.e("$TAG Failed to parse tunnel prefs: $e")
                }
            }
            val body = callLocalApi("GET", "status", null) ?: return@execute
            try {
                onStatus(JSONObject(String(body, Charsets.UTF_8)))
            } catch (e: Exception) {
                Log.e("$TAG Failed to parse tunnel status: $e")
            }
        }
    }

    private fun callLocalApi(method: String, path: String, body: ByteArray?): ByteArray? {
        val backend = app ?: run {
            Log.e("$TAG Backend is not running, cannot call [$path]")
            return null
        }
        return try {
            val response = backend.callLocalAPI(
                30000L,
                method,
                "/localapi/v0/$path",
                body?.let { TsInputStream(it) }
            )
            val status = response.statusCode()
            val data = response.bodyBytes() ?: ByteArray(0)
            if (status < 200 || status >= 300) {
                Log.e("$TAG Call [$method $path] failed with status [$status]: ${String(data, Charsets.UTF_8)}")
                return null
            }
            data
        } catch (e: Exception) {
            Log.e("$TAG Call [$method $path] threw: $e")
            null
        }
    }

    private fun monitorNetworkChanges() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // NOT_VPN je klíčové (upstream parita): bez něj se po zvednutí
        // tunelu stane "výchozí sítí" tunel sám a jádro tunelu si do něj
        // sváže vlastní řídicí sokety — spojení se zacyklí do sebe.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    tsAppContext?.defaultNetwork = network
                }

                override fun onLost(network: Network) {
                    if (tsAppContext?.defaultNetwork == network) {
                        tsAppContext?.defaultNetwork = null
                    }
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) {
                    val servers = linkProperties.dnsServers.mapNotNull { it.hostAddress }
                    val domains = linkProperties.domains.orEmpty()
                    val config = "${servers.joinToString(" ")}\n$domains".trim()
                    tsAppContext?.platformDnsConfig = config
                    try {
                        libtailscale.Libtailscale.onDNSConfigChanged(linkProperties.interfaceName)
                    } catch (e: Exception) {
                        Log.w("$TAG Failed to notify backend about DNS change: $e")
                    }
                }
            }
        )
    }
}

private class TsInputStream(data: ByteArray) : libtailscale.InputStream {
    private val stream = ByteArrayInputStream(data)

    override fun read(): ByteArray {
        val buffer = ByteArray(4096)
        val read = stream.read(buffer)
        if (read == -1) return ByteArray(0)
        return buffer.copyOf(read)
    }

    override fun close() {
        stream.close()
    }
}
