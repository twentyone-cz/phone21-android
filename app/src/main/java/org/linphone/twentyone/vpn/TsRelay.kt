/*
 * fork: relé vnitřního tunelu („jen hovory“ bez systémové VPN).
 *
 * Obálka nad knihovnou tunelu: žádost o vnitřní režim, jeho stav, start
 * relé na 127.0.0.1 a identity obou konců šifrovaného spojení mezi volací
 * knihovnou a relé. Volací knihovna mluví jen s relé (TsSipTransport),
 * relé nese signalizaci, hlas a web k miniserveru rozhraním tunelu uvnitř
 * procesu — nic z toho nejde mimo jinou VPN, kterou uživatel v telefonu má.
 *
 * Stav se nečte sekundovým časovačem: kontrolu spouští TsManager při
 * změnách stavu jádra tunelu a ve svém 30s cyklu, sem jen dokud vnitřní
 * režim nenaběhne (rostoucí odstup, strop 30 s). Plánovač nebudí telefon
 * ze spánku.
 *
 * Tajné věci: soukromé klíče identit leží jen v šifrovaných předvolbách
 * (EncryptedSharedPreferences, klíč v Keystore), token webového relé jen
 * v paměti. Do deníku ani logu nesmí nic z toho.
 */
package org.linphone.twentyone.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log
import org.linphone.twentyone.TwentyOneCallPrivacy
import org.linphone.twentyone.TwentyOneDiag

object TsRelay {
    private const val TAG = "[Tunnel Relay]"
    private const val SECRETS_FILE = "twentyone_relay"
    private const val KEY_IDENTITY = "p21relay-identity"

    /** Ústředna poslouchá UDP i TCP na 5060 (relé k ní mluví z adresy
     *  telefonu v privátní síti). */
    private const val UPSTREAM_SIP_PORT = 5060

    private const val RETRY_MIN_MS = 1_000L
    private const val RETRY_MAX_MS = 30_000L

    /** Jak dlouho smí vnitřní režim hlásit chybu, než se požádá znovu
     *  (StopInternal + RequestInternal — tak to knihovna chce). */
    private const val REREQUEST_AFTER_MS = 20_000L

    /** Rozsah, ze kterého se jednou vylosuje port pro signalizaci na
     *  127.0.0.1 (trasa účtu ho potřebuje dřív, než relé poprvé naběhne). */
    private const val SIP_PORT_MIN = 40_000
    private const val SIP_PORT_SPAN = 20_000

    /** Co relé hlásí po startu (bez tokenu — ten je jen v [httpEndpoint]). */
    data class Info(
        val sipPort: Int,
        val httpPort: Int,
        val privateSipPort: Int,
        /** relé chce od volací knihovny šifrovaný hlas (SRTP s klíči v SDP) */
        val clientSrtp: Boolean,
        /** instance rozhraní uvnitř procesu, na které relé běží */
        val generation: Int
    )

    /** InternalStatus knihovny. */
    data class Status(
        val requested: Boolean,
        val up: Boolean,
        val generation: Int,
        val relay: Boolean,
        val error: String,
        val vpnService: Boolean,
        val leftoverSockets: Int
    )

    /** Identity obou konců spojení na 127.0.0.1 (PEM). Nikdy do logu. */
    class Identity(
        val serverCertPem: String,
        val serverKeyPem: String,
        val clientCertPem: String,
        val clientKeyPem: String
    )

    /** Vnitřní režim stojí a relé na něm běží (porty jsou známé). */
    val internalUp = MutableLiveData(false)

    /** Porty běžícího relé, null = neběží. */
    val relayInfo = MutableLiveData<Info?>(null)

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val checkQueued = AtomicBoolean(false)
    private var pending: ScheduledFuture<*>? = null
    private var retryMs = 0L

    @Volatile
    private var appContext: Context? = null

    /** Má relé běžet (vnitřní režim je ten používaný)? */
    @Volatile
    private var relayWanted = false

    @Volatile
    private var info: Info? = null

    /** Token webového relé — JEN v paměti, nikdy na disk ani do deníku. */
    @Volatile
    private var token: String? = null

    @Volatile
    private var upNow = false

    /** Co naposled odešlo do [relayInfo] (LiveData.value mimo hlavní
     *  vlákno vrací hodnotu, která ještě nebyla doručena). */
    @Volatile
    private var publishedInfo: Info? = null

    @Volatile
    private var identity: Identity? = null

    /** Adresa ústředny (doména účtu v privátní síti), plní TsSipTransport. */
    @Volatile
    private var sipHost: String? = null

    /** Další povolené cíle webového relé (adresa z QR — další krok). */
    private val extraHttpHosts = CopyOnWriteArraySet<String>()

    /** Cíle, se kterými běží současné relé (při změně se restartuje). */
    @Volatile
    private var runningTargets: String? = null

    // jen na vlákně [exec]
    private var lastError = ""
    private var errorSince = 0L
    private var lastRejected = 0L
    private var lastForbidden = 0L
    private var waitingForTargetsLogged = false

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun context(): Context = appContext ?: coreContext.context

    // ---- vnitřní režim v knihovně tunelu ----

    /** Požádat knihovnu o vnitřní režim. Idempotentní, nečeká. Systémová
     *  VPN má vždy přednost; žádost pak čeká jako záloha (po odebrání nebo
     *  selhání VPN se tunel vrátí do vnitřního režimu sám). */
    fun requestInternal() {
        try {
            libtailscale.Libtailscale.requestInternal()
        } catch (e: Throwable) {
            Log.e("$TAG requestInternal failed: $e")
        }
    }

    /** Žádost stáhnout (tunel vypnutý). Zastaví i hlídání relé. */
    fun stopInternal() {
        setRelayWanted(false)
        try {
            libtailscale.Libtailscale.stopInternal()
        } catch (e: Throwable) {
            Log.e("$TAG stopInternal failed: $e")
        }
    }

    /** Hlídat a spouštět relé (vnitřní režim je ten používaný). */
    fun setRelayWanted(wanted: Boolean) {
        if (relayWanted == wanted) {
            if (wanted) poke()
            return
        }
        relayWanted = wanted
        Log.i("$TAG Relay wanted: $wanted")
        exec.execute {
            pending?.cancel(false)
            pending = null
            retryMs = 0L
            if (wanted) {
                check()
            } else {
                // Relé zastavuje knihovna sama, když rozhraní předá systémové
                // VPN nebo zavře; tady jen zapomenout, co o něm víme.
                setDown()
            }
        }
    }

    /** Podnět ke kontrole (změna stavu jádra tunelu, 30s cyklus). Levné:
     *  bez chtěného relé hned končí, víc podnětů najednou se sloučí. */
    fun poke() {
        if (!relayWanted) return
        if (!checkQueued.compareAndSet(false, true)) return
        exec.execute {
            checkQueued.set(false)
            check()
        }
    }

    // ---- rozhraní pro zbytek appky ----

    /** Relé běží na současném rozhraní a porty jsou známé. */
    fun isUp(): Boolean = upNow

    fun currentInfo(): Info? = info

    /**
     * Webové relé pro HTTP k miniserveru: (port na 127.0.0.1, token pro
     * hlavičku X-Phone21-Relay), null = relé neběží. Token nikam neukládat
     * ani nelogovat.
     */
    fun httpEndpoint(): Pair<Int, String>? {
        if (!upNow) return null
        val i = info ?: return null
        val t = token ?: return null
        return i.httpPort to t
    }

    /** Povolit webovému relé další cíl v privátní síti (adresa z QR).
     *  Relé se kvůli tomu restartuje (mimo hovor). */
    fun addHttpHost(host: String) {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (!TwentyOneCallPrivacy.isPrivateNetworkAddress(h)) return
        if (extraHttpHosts.add(h)) poke()
    }

    /** Statistika relé (jen počty, bez osobních údajů); "{}" = neběží. */
    fun statsJson(): String = try {
        libtailscale.Libtailscale.relayStats()
    } catch (e: Throwable) {
        "{}"
    }

    fun status(): Status? = try {
        val o = JSONObject(libtailscale.Libtailscale.internalStatus())
        Status(
            requested = o.optBoolean("requested", false),
            up = o.optBoolean("up", false),
            generation = o.optInt("generation", 0),
            relay = o.optBoolean("relay", false),
            error = o.optString("error", ""),
            vpnService = o.optBoolean("vpnService", false),
            leftoverSockets = o.optInt("leftoverSockets", 0)
        )
    } catch (e: Throwable) {
        Log.e("$TAG internalStatus failed: $e")
        null
    }

    /** Port signalizace pro trasu účtu: běžícího relé, jinak přání
     *  z předvoleb (napoprvé vylosované a uložené). */
    fun routePort(): Int {
        info?.let { return it.sipPort }
        val p = TsPreferences(context())
        var port = p.relaySipPort
        if (port !in 1..65535) {
            port = SIP_PORT_MIN + java.security.SecureRandom().nextInt(SIP_PORT_SPAN)
            p.relaySipPort = port
        }
        return port
    }

    /** Má volací knihovna posílat šifrovaný hlas? Podle běžícího relé,
     *  jinak podle posledního (do odpovědi nového relé je registrace
     *  stejně vypnutá). */
    fun clientSrtpExpected(): Boolean = info?.clientSrtp ?: TsPreferences(context()).relayClientSrtp

    /**
     * Adresy pro relé, volá TsSipTransport na vlákně jádra: ústředna
     * (doména účtu, IPv4 v privátní síti) a cíle webového relé.
     */
    fun setSipHost(host: String?) {
        val h = host?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.takeIf { isIpv4(it) && TwentyOneCallPrivacy.isPrivateNetworkAddress(it) }
        if (h == sipHost) return
        sipHost = h
        poke()
    }

    // ---- identity ----

    private fun secrets(ctx: Context): SharedPreferences? = try {
        TsAppContext.openEncrypted(ctx, SECRETS_FILE)
    } catch (e: Exception) {
        Log.e("$TAG Encrypted storage unavailable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Identity relé a volací knihovny. Vyrábí je knihovna tunelu (ECDSA
     * P-256, klíč v PEM, jak ho volací knihovna chce) jednou, pak se čtou
     * ze šifrovaných předvoleb. null = úložiště nejde otevřít.
     */
    @Synchronized
    fun identity(): Identity? {
        identity?.let { return it }
        val ctx = context()
        val store = secrets(ctx) ?: run {
            TwentyOneDiag.log("P21-E22", "úložiště klíčů relé nejde otevřít")
            return null
        }
        store.getString(KEY_IDENTITY, null)?.let { saved ->
            try {
                val o = JSONObject(saved)
                val s = o.getJSONObject("server")
                val c = o.getJSONObject("client")
                val id = Identity(
                    s.getString("certPem"), s.getString("keyPem"),
                    c.getString("certPem"), c.getString("keyPem")
                )
                identity = id
                return id
            } catch (e: Exception) {
                Log.e("$TAG Stored relay identity unreadable, generating a new one")
            }
        }
        return try {
            val s = JSONObject(libtailscale.Libtailscale.generateRelayIdentity())
            val c = JSONObject(libtailscale.Libtailscale.generateRelayIdentity())
            val id = Identity(
                s.getString("certPem"), s.getString("keyPem"),
                c.getString("certPem"), c.getString("keyPem")
            )
            store.edit().putString(
                KEY_IDENTITY,
                JSONObject().put("server", s).put("client", c).toString()
            ).commit()
            identity = id
            TwentyOneDiag.log("P21-TUN", "identity relé a volací knihovny vytvořeny")
            id
        } catch (e: Throwable) {
            // zpráva knihovny klíč neobsahuje
            TwentyOneDiag.log(
                "P21-E22", "identity relé nejde vytvořit: %s".format(e.message ?: e.javaClass.simpleName))
            null
        }
    }

    // ---- kontrola stavu (vlákno exec) ----

    private fun check() {
        if (!relayWanted) return
        val st = status()
        if (st == null || !st.up) {
            setDown()
            if (st != null) noteError(st)
            scheduleRetry()
            return
        }
        lastError = ""
        errorSince = 0L

        val targets = targetsKey()
        val cur = info
        val current = cur != null && st.relay && cur.generation == st.generation
        // Jiná ústředna nebo cíle webu: restart relé, ale ne uprostřed hovoru
        // (zbořil by ho); po hovoru to dožene podnět z TsModeController.
        val retarget = current && targets != null && targets != runningTargets &&
            !TsModeController.isCallActive()
        if (current && !retarget) {
            retryMs = 0L
            publish(true)
            checkStats()
            return
        }
        if (targets == null) {
            if (!waitingForTargetsLogged) {
                waitingForTargetsLogged = true
                TwentyOneDiag.log("P21-TUN", "relé čeká na adresu ústředny (účet ani adresa brány nejsou známé)")
            }
            setDown()
            scheduleRetry()
            return
        }
        waitingForTargetsLogged = false
        start(targets)
    }

    private fun start(targets: String) {
        val id = identity() ?: run {
            setDown()
            scheduleRetry()
            return
        }
        val upstream = upstreamHost() ?: return
        val p = TsPreferences(context())
        val cfg = JSONObject()
            .put("upstreamSip", "$upstream:$UPSTREAM_SIP_PORT")
            .put("httpHosts", JSONArray(httpHosts().toList()))
            .put("serverCertPem", id.serverCertPem)
            .put("serverKeyPem", id.serverKeyPem)
            .put("clientCertPem", id.clientCertPem)
            .put("sipPort", routePort())
            .put("httpPort", p.relayHttpPort.coerceIn(0, 65535))
            .put("privateSipPort", p.relayPrivateSipPort.coerceIn(0, 65535))
        val result = try {
            libtailscale.Libtailscale.startRelay(cfg.toString())
        } catch (e: Throwable) {
            // chyby z knihovny nikdy necitují certifikáty ani klíče
            TwentyOneDiag.log(
                "P21-E22", "relé nenaběhlo: %s".format(e.message ?: e.javaClass.simpleName))
            setDown()
            scheduleRetry()
            return
        }
        val st = status()
        if (st == null || !st.up || !st.relay) {
            // rozhraní se mezitím vyměnilo — zkusit znovu na novém
            setDown()
            scheduleRetry()
            return
        }
        val o = try {
            JSONObject(result)
        } catch (e: Exception) {
            TwentyOneDiag.log("P21-E22", "relé vrátilo nečitelnou odpověď")
            setDown()
            scheduleRetry()
            return
        }
        val newInfo = Info(
            sipPort = o.optInt("sipPort", 0),
            httpPort = o.optInt("httpPort", 0),
            privateSipPort = o.optInt("privateSipPort", 0),
            clientSrtp = o.optBoolean("clientSrtp", false),
            generation = st.generation
        )
        token = o.optString("httpToken", "").ifEmpty { null }
        info = newInfo
        runningTargets = targets
        retryMs = 0L
        lastRejected = 0L
        lastForbidden = 0L
        p.relaySipPort = newInfo.sipPort
        p.relayHttpPort = newInfo.httpPort
        p.relayPrivateSipPort = newInfo.privateSipPort
        p.relayClientSrtp = newInfo.clientSrtp
        TwentyOneDiag.log(
            "P21-TUN",
            "relé běží: signalizace 127.0.0.1:%d, web %d, hlas %s, rozhraní č. %d".format(
                newInfo.sipPort, newInfo.httpPort,
                if (newInfo.clientSrtp) "šifrovaný" else "NEšifrovaný (starší knihovna)",
                newInfo.generation)
        )
        publish(true)
        // Nové relé spojení s volací knihovnou nezná, dozví se ho z REGISTER
        // (bez něj nedoručí příchozí hovor). Změnu portu nebo šifrování
        // musí nejdřív převzít účet (TsSipTransport).
        coreContext.postOnCoreThread { core -> TsSipTransport.onRelayChanged(core) }
    }

    private fun publish(up: Boolean) {
        val i = if (up) info else null
        if (upNow == up && publishedInfo == i) return
        upNow = up
        publishedInfo = i
        internalUp.postValue(up)
        relayInfo.postValue(i)
        TsManager.onInternalStatus(up)
    }

    private fun setDown() {
        if (info != null || token != null || upNow) {
            info = null
            token = null
            runningTargets = null
            publish(false)
        }
    }

    private fun scheduleRetry() {
        if (!relayWanted) return
        retryMs = if (retryMs <= 0L) RETRY_MIN_MS else (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
        pending?.cancel(false)
        pending = exec.schedule({ check() }, retryMs, TimeUnit.MILLISECONDS)
    }

    /** Chyba vnitřního režimu do deníku (jen změna); dlouho trvající chyba
     *  = požádat znovu, jak to knihovna chce. */
    private fun noteError(st: Status) {
        if (st.error.isEmpty()) {
            lastError = ""
            errorSince = 0L
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (st.error != lastError) {
            lastError = st.error
            errorSince = now
            TwentyOneDiag.log("P21-E22", "vnitřní tunel nenaběhl: %s".format(st.error))
            return
        }
        if (st.requested && !st.vpnService && now - errorSince >= REREQUEST_AFTER_MS) {
            errorSince = now
            TwentyOneDiag.log("P21-TUN", "vnitřní tunel pořád nestojí — žádám o něj znovu")
            try {
                libtailscale.Libtailscale.stopInternal()
                libtailscale.Libtailscale.requestInternal()
            } catch (e: Throwable) {
                Log.e("$TAG Re-request of the internal mode failed: $e")
            }
        }
    }

    /** Cizí klient na 127.0.0.1 (bez platného certifikátu) nebo webový
     *  požadavek bez tokenu → P21-E23 s počty. */
    private fun checkStats() {
        val counters = try {
            JSONObject(statsJson()).optJSONObject("counters") ?: return
        } catch (e: Exception) {
            return
        }
        val rejected = counters.optLong("client.rejected", 0L)
        val forbidden = counters.optLong("http.forbidden_token", 0L)
        if (rejected > lastRejected || forbidden > lastForbidden) {
            TwentyOneDiag.log(
                "P21-E23",
                "na 127.0.0.1 se hlásil cizí klient: bez platného certifikátu %d×, bez tokenu %d×"
                    .format(rejected - lastRejected, forbidden - lastForbidden)
            )
        }
        lastRejected = rejected
        lastForbidden = forbidden
    }

    // ---- cíle relé ----

    /** Ústředna: doména účtu, jinak adresa brány z QR, jinak cíl z QR. */
    private fun upstreamHost(): String? {
        sipHost?.let { return it }
        val gw = TsPreferences(context()).gatewayAddress.trim()
        if (isIpv4(gw) && TwentyOneCallPrivacy.isPrivateNetworkAddress(gw)) return gw
        return extraHttpHosts.firstOrNull { isIpv4(it) }
    }

    /** Cíle webového relé: jen adresy v privátní síti (relé jinam nesmí). */
    private fun httpHosts(): Set<String> {
        val out = LinkedHashSet<String>()
        val gw = TsPreferences(context()).gatewayAddress.trim().removePrefix("[").removeSuffix("]")
        if (gw.isNotEmpty() && TwentyOneCallPrivacy.isPrivateNetworkAddress(gw)) out.add(gw)
        sipHost?.let { out.add(it) }
        out.addAll(extraHttpHosts)
        return out
    }

    private fun targetsKey(): String? {
        val up = upstreamHost() ?: return null
        return up + "|" + httpHosts().sorted().joinToString(",")
    }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255 }
    }
}
