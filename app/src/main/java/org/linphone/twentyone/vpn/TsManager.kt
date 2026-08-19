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
    private const val NOTIFY_MASK_NO_NETMAP = 8192L
    private const val NOTIFY_MASK_INITIAL_STATUS = 16384L

    private const val SCOPE_SWITCH_STABLE_CYCLES = 3
    private const val STATUS_POLL_SECONDS = 30L

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

    private var directCycles = 0
    private var relayCycles = 0
    private var scopeInUse = TsPreferences.TunnelScope.APP_ONLY

    val state = MutableLiveData(State.NO_STATE)
    val vpnActive = MutableLiveData(false)
    val browseToUrl = MutableLiveData<String?>(null)
    val loggedIn = MutableLiveData(false)
    val directConnection = MutableLiveData(false)
    val tailnetAddress = MutableLiveData<String?>(null)
    val endpointVerified = MutableLiveData(false)

    /** Poslední zjištěný stav systémového „Blokovat připojení bez VPN"
     *  (čte ho služba tunelu při startu; null = zatím nezjištěno).
     *  Diagnostika stahování podle něj dává jednoznačný verdikt. */
    @Volatile
    var lockdownDetected: Boolean? = null

    @Volatile
    private var endpointMatches = false

    @Volatile
    private var domainMatches: Boolean? = null

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
            if (state.value == State.RUNNING) {
                refreshStatus()
            }
        }, STATUS_POLL_SECONDS, STATUS_POLL_SECONDS, TimeUnit.SECONDS)
    }

    fun isStarted(): Boolean = app != null

    /**
     * Přihlásí uzel proti koordinátorovi. Klíč je jednorázový (preauth) —
     * bez něj se použije interaktivní přihlášení v prohlížeči.
     */
    fun login(controlUrl: String, authKey: String?) {
        worker.execute {
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
            fullPrefs.put("WantRunning", true)

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
                val st = state.value
                // RUNNING = přihlášeno (příznak z notifikací umí přijít
                // později a hlásil falešné E8, i když registrace proběhla)
                if (loggedIn.value != true && st != State.RUNNING) {
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
            callLocalApi("POST", "logout", null)
        }
        stopService()
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
            "P21-TUN", "přestavba tunelu, rozsah=%s".format(prefs.tunnelScope))
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

    fun effectiveTunnelScope(): TsPreferences.TunnelScope {
        scopeInUse = if (prefs.tunnelScope == TsPreferences.TunnelScope.FULL &&
            directConnection.value == true
        ) {
            TsPreferences.TunnelScope.FULL
        } else {
            TsPreferences.TunnelScope.APP_ONLY
        }
        return scopeInUse
    }

    fun onVpnStatusChanged(active: Boolean) {
        if (active && ::appContext.isInitialized) {
            // zdravě postavený tunel = konec případné smyčky restartů
            TunnelStartGuard.reset(appContext)
        }
        vpnActive.postValue(active)
    }

    fun onServiceClosing() {
        state.postValue(State.STOPPING)
    }

    private fun watchNotifications() {
        val backend = app ?: return
        val mask = NOTIFY_MASK_INITIAL_STATE or NOTIFY_MASK_PREFS or
            NOTIFY_MASK_NO_NETMAP or NOTIFY_MASK_INITIAL_STATUS
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
            state.postValue(newState)
            if (newState == State.RUNNING || newState == State.STARTING) {
                browseToUrl.postValue(null)
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
            loggedIn.postValue(!prefsObject.optBoolean("LoggedOut", true))
            endpointMatches = sameEndpoint(prefsObject.optString("ControlURL", ""), prefs.controlUrl)
            publishVerification()
        }
        if (notify.has("InitialStatus")) {
            onStatus(notify.getJSONObject("InitialStatus"))
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
        updateConnectionQuality(status)
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

    private fun publishVerification() {
        val verified = endpointMatches && domainMatches != false
        if (endpointVerified.value != verified) {
            Log.i("$TAG Endpoint verification is now [$verified]")
            if (!verified) {
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-E9", "síť neprošla ověřením (endpoint=%s doména=%s)"
                        .format(endpointMatches, domainMatches))
            }
            endpointVerified.postValue(verified)
        }
    }

    /**
     * Provoz přes DERP relay má citelnou latenci, plný tunel by z něj udělal
     * úzké hrdlo pro celý telefon. Rozsah se proto drží na přímém spojení
     * a přepíná až po několika stabilních cyklech (hystereze proti blikání).
     */
    private fun updateConnectionQuality(status: JSONObject) {
        val peers = status.optJSONObject("Peer") ?: return
        var direct = false
        val keys = peers.keys()
        while (keys.hasNext()) {
            val peer = peers.optJSONObject(keys.next()) ?: continue
            if (!peer.optBoolean("Online", false)) continue
            if (peer.optString("CurAddr", "").isNotEmpty()) {
                direct = true
                break
            }
        }

        if (direct) {
            directCycles++
            relayCycles = 0
        } else {
            relayCycles++
            directCycles = 0
        }

        val current = directConnection.value == true
        if (direct && !current && directCycles >= SCOPE_SWITCH_STABLE_CYCLES) {
            Log.i("$TAG Direct peer connection is stable")
            directConnection.postValue(true)
            maybeRebuildTunnel(TsPreferences.TunnelScope.FULL)
        } else if (!direct && current && relayCycles >= SCOPE_SWITCH_STABLE_CYCLES) {
            Log.w("$TAG Peer connection fell back to a relay")
            directConnection.postValue(false)
            maybeRebuildTunnel(TsPreferences.TunnelScope.APP_ONLY)
        }
    }

    private fun maybeRebuildTunnel(desired: TsPreferences.TunnelScope) {
        if (prefs.tunnelScope != TsPreferences.TunnelScope.FULL) return
        if (scopeInUse == desired) return
        Log.i("$TAG Rebuilding tunnel, scope [$scopeInUse] -> [$desired]")
        restartService()
    }

    fun refreshStatus() {
        worker.execute {
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
