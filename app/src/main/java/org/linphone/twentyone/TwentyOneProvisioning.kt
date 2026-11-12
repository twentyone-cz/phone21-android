/*
 * fork: konfiguraci z QR stahujeme sami místo SIP knihovny.
 *
 * Dva důvody. Knihovna při chybě neumí říct proč (jen „failed"), takže se
 * nedalo poznat, jestli je vadná síť, adresa, nebo obsah. A hlavně: když
 * běží náš tunel, provoz aplikace jde skrz něj — jenže miniserver se při
 * nastavování stahuje z domácí wifi, kam tunel nevede. Proto se stažení
 * váže výslovně na síť bez VPN a knihovně se předá hotový soubor.
 *
 * QR zobrazené na dálku míří na miniserver v privátní síti a za # nese
 * jednorázový klíč do ní: telefon se nejdřív připojí a konfiguraci si pak
 * stáhne tunelem.
 */
package org.linphone.twentyone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log

object TwentyOneProvisioning {
    private const val TAG = "[Provisioning]"
    private val executor = Executors.newSingleThreadExecutor()
    private val joinKeyRegex = Regex("^[A-Za-z0-9_-]{20,200}$")
    private const val JOIN_TIMEOUT_MS = 120_000L
    private const val TUNNEL_DOWNLOAD_ATTEMPTS = 4
    private const val TUNNEL_RETRY_MS = 3_000L

    /** Odkaz z QR bez klíče do sítě — do logů. */
    fun redact(url: String): String =
        url.replace(Regex("([#&]k=)[^&]*"), "$1…")

    /** Odkaz bez části za # a klíč do sítě z ní (prázdný, když tam není). */
    internal fun splitJoinKey(url: String): Pair<String, String> {
        val hash = url.indexOf('#')
        if (hash < 0) return url to ""
        val key = url.substring(hash + 1).split('&')
            .firstOrNull { it.startsWith("k=") }
            ?.removePrefix("k=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            .orEmpty()
        return url.substring(0, hash) to (if (joinKeyRegex.matches(key)) key else "")
    }

    /** Kopie přihlašovacích údajů výchozího účtu, které jsme odebrali před
     *  aplikací konfigurace. Když aplikace selže (vadné nebo vypršelé QR),
     *  musí se vrátit — jinak by telefon zůstal bez hesla a trvale
     *  neregistrovaný, aniž by to bylo z čeho poznat. Čte a maže ji
     *  [org.linphone.twentyone.vpn.TunnelReadinessManager.onConfiguring]. */
    @Volatile
    var removedAuthInfo: List<org.linphone.core.AuthInfo> = emptyList()
        private set

    /** Vrátí odebrané údaje zpět. Idempotentní. */
    fun restoreAuthInfo(core: org.linphone.core.Core) {
        val saved = removedAuthInfo
        if (saved.isEmpty()) return
        removedAuthInfo = emptyList()
        // Znovu jen tehdy, když mezitím nic nového nepřibylo — po úspěšné
        // konfiguraci má účet vlastní, čerstvé údaje a ty nesmíme přebít.
        val existing = core.defaultAccount?.findAuthInfo()
        if (existing != null) {
            Log.i("$TAG Not restoring credentials, account already has its own")
            return
        }
        Log.w("$TAG Restoring ${saved.size} credential(s) after failed config")
        saved.forEach { core.addAuthInfo(it) }
    }

    /** Zahodí zapamatované údaje (po úspěšné konfiguraci). */
    fun forgetRemovedAuthInfo() {
        removedAuthInfo = emptyList()
    }

    /** Uživatelské jméno účtu z konfigurace (sekce auth_info_0), nebo null. */
    private fun provisionedUsername(xml: String): String? =
        Regex("""<section name="auth_info_0">[\s\S]*?<entry name="username"[^>]*>([^<]+)</entry>""")
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Stáhne konfiguraci a aplikuje ji. Chyby hlásí voláním [onError]
     *  s krátkým lidským popisem — ten se zobrazí v hlášce. */
    fun fetchAndApply(qrUrl: String, onError: (String) -> Unit) {
        executor.execute {
            val (url, joinKey) = splitJoinKey(qrUrl)
            val target = try {
                URL(url).let { "%s:%d".format(it.host, if (it.port > 0) it.port else 80) }
            } catch (_: Exception) {
                url
            }
            if (!Compatibility.isAccessLocalNetworkPermissionGranted(
                    coreContext.context)) {
                TwentyOneDiag.log("P21-E10", "chybí oprávnění Místní síť, cíl=%s"
                    .format(target))
                onError("[P21-E10] Chybí oprávnění pro domácí síť — v " +
                    "nastavení aplikace zapni \u201eMístní síť\u201c, a když " +
                    "tam není, tak \u201eZařízení v okolí\u201c. Pak naskenuj " +
                    "QR znovu.")
                return@execute
            }
            val viaTunnel = joinKey.isNotEmpty()
            if (viaTunnel && !org.linphone.twentyone.vpn.TsManager.isTunnelUp()) {
                if (!joinNetwork(joinKey)) {
                    TwentyOneDiag.log("P21-E18", "cíl=%s %s".format(target, networkFacts()))
                    onError("[P21-E18] Telefon se do privátní sítě nepřipojil — " +
                        "potvrdil jsi souhlas s VPN? Klíč v QR platí jen jednou, " +
                        "vygeneruj nový QR a zkus to znovu.")
                    return@execute
                }
            }
            try {
                val data = if (viaTunnel) downloadViaTunnel(url) else download(url)
                if (!looksLikeConfig(data)) {
                    TwentyOneDiag.log("P21-E1", "cíl=%s cesta=%s délka=%d".format(
                        target, lastRoute, data.size))
                    onError("[P21-E1] Server nevrátil konfiguraci (odkaz už asi vypršel).")
                    return@execute
                }
                val file = File(coreContext.context.filesDir, "provisioning.xml")
                file.writeBytes(data)
                coreContext.postOnCoreThread { core ->
                    // Uložené přihlašovací údaje výchozího účtu musí pryč JEŠTĚ
                    // před aplikací konfigurace: jádro si heslo pamatuje i jako
                    // ha1 spočítané pro minulou doménu, a to by přebilo heslo
                    // i doménu, které nová konfigurace přepisuje. Cíleně jen
                    // výchozí účet — hromadné smazání by vzalo i údaje
                    // synchronizace kontaktů, které s ústřednou nesouvisejí.
                    // Totéž pro všechny uložené údaje se jménem z konfigurace:
                    // po smazání účtu zůstávají a přihlášení je najde dřív.
                    val user = provisionedUsername(String(data, Charsets.UTF_8))
                    val stale = (
                        listOfNotNull(core.defaultAccount?.findAuthInfo()) +
                            core.authInfoList.filter { user != null && it.username == user }
                        ).distinctBy { "${it.username}|${it.realm}|${it.domain}" }
                    if (stale.isNotEmpty()) {
                        Log.i("$TAG Removing ${stale.size} stored credential(s) before config")
                        // kopie si necháme: když aplikace konfigurace selže,
                        // vrátíme je, ať telefon nezůstane bez hesla
                        removedAuthInfo = stale.map { it.clone() }
                        stale.forEach { core.removeAuthInfo(it) }
                    }
                    core.provisioningUri = "file://" + file.absolutePath
                    Log.i("$TAG Config downloaded (${data.size} B), applying from file")
                    core.stop()
                    core.start()
                }
            } catch (e: Exception) {
                Log.e("$TAG Download of [$url] via [$lastRoute] failed: $e")
                if (viaTunnel && e !is IllegalStateException) {
                    TwentyOneDiag.log("P21-E19", "cíl=%s %s výjimka=%s %s".format(
                        target, networkFacts(), e.javaClass.simpleName, e.message ?: ""))
                    onError("[P21-E19] Telefon je v privátní síti, ale miniserver " +
                        "$target přes ni neodpovídá. Je miniserver zapnutý a v síti?")
                    return@execute
                }
                val code = codeFor(e)
                TwentyOneDiag.log(code, "cíl=%s cesta=%s %s výjimka=%s %s".format(
                    target, lastRoute, networkFacts(),
                    e.javaClass.simpleName, e.message ?: ""))
                onError(describe(e, target))
            }
        }
    }

    @Volatile
    private var lastRoute = "?"

    /** Přihlásí telefon do privátní sítě klíčem z QR a počká, až tunel běží. */
    private fun joinNetwork(key: String): Boolean {
        val context = coreContext.context
        TwentyOneDiag.log("P21-TUN", "QR nese klíč do sítě — připojuji telefon před stažením konfigurace")
        coreContext.showGreenToastEvent.postValue(
            org.linphone.utils.Event(
                Pair(org.linphone.R.string.twentyone_qr_joining, org.linphone.R.drawable.info)
            )
        )
        if (org.linphone.twentyone.vpn.TsManager.isLoggedIn()) {
            // přihlášený telefon: klíč se nepoužije, jen se spustí rozhraní
            TwentyOneDiag.log("P21-TUN", "telefon už v síti je — klíč z QR nepoužit, čekám na rozhraní")
            org.linphone.twentyone.vpn.TsManager.startService()
        } else {
            val intent = android.content.Intent(
                context, org.linphone.twentyone.vpn.TsConsentActivity::class.java
            ).apply {
                putExtra(org.linphone.twentyone.vpn.TsConsentActivity.EXTRA_KEY, key)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                context.startActivity(intent)
            }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + JOIN_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (org.linphone.twentyone.vpn.TsManager.isTunnelUp()) {
                TwentyOneDiag.log("P21-TUN", "telefon je v síti — stahuji konfiguraci tunelem")
                return true
            }
            Thread.sleep(1_000)
        }
        Log.w("$TAG Tunnel did not come up within ${JOIN_TIMEOUT_MS / 1000} s")
        return false
    }

    /** Stažení tunelem, několik pokusů; HTTP chyba se neopakuje. */
    private fun downloadViaTunnel(url: String): ByteArray {
        lastRoute = "privátní síť"
        var last: Exception? = null
        repeat(TUNNEL_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                return fetch(URL(url).openConnection() as HttpURLConnection)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                last = e
                Log.w("$TAG Tunnel fetch attempt ${attempt + 1} failed: $e")
                Thread.sleep(TUNNEL_RETRY_MS)
            }
        }
        throw last ?: java.io.IOException("stažení tunelem selhalo")
    }

    private fun download(url: String): ByteArray {
        // Nejdřív stejnou cestou jako prohlížeč (bez vázání na síť) — na
        // reálném telefonu prošla, zatímco socket vázaný na wifi vytimeoutoval.
        // Vázání zůstává jako záloha pro případ, že výchozí cestu drží tunel.
        val parsed = URL(url)
        try {
            lastRoute = "výchozí síť"
            return fetch(parsed.openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e     // HTTP chyba — síť fungovala
            Log.w("$TAG Default-network fetch failed [$e], retrying bound to wifi")
        }
        val (network, route) = nonVpnNetwork(coreContext.context)
        lastRoute = route
        if (network == null) {
            throw java.net.NoRouteToHostException("bez wifi")
        }
        return fetch(network.openConnection(parsed) as HttpURLConnection)
    }

    private fun fetch(connection: HttpURLConnection): ByteArray {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            val code = connection.responseCode
            if (code != 200) {
                throw IllegalStateException("HTTP $code")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** Wifi bez VPN — jediná síť, přes kterou telefon vidí miniserver.
     *  Mobilní data schválně nebereme: na lokální adresu stejně nevedou
     *  a maskovala by pravou příčinu („timeout" místo „nejsi na wifi"). */
    private fun nonVpnNetwork(context: Context): Pair<Network?, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        val seen = mutableListOf<String>()
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val label = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobilní data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "jiná"
            }
            seen += label
            if (label == "vpn") continue
            if (label == "wifi" || label == "ethernet") {
                Log.i("$TAG Using [$label] network, available: $seen")
                return network to label
            }
        }
        Log.w("$TAG No usable wifi network, available: $seen")
        return null to seen.joinToString(",")
    }

    private fun looksLikeConfig(data: ByteArray): Boolean {
        val head = data.take(200).toByteArray().toString(Charsets.UTF_8)
        return "<config" in head
    }

    /** Změřené okolnosti pro deník: běží tunel? je zapnutá systémová
     *  blokace provozu mimo VPN? Diagnóza místo hádání. */
    private fun networkFacts(): String {
        val lockdown = when (org.linphone.twentyone.vpn.TsManager.lockdownDetected) {
            true -> "zapnutá"
            false -> "vypnutá"
            null -> "?"
        }
        return "tunel=%s blokace-mimo-vpn=%s".format(
            if (vpnUp()) "běží" else "ne", lockdown)
    }

    private fun vpnUp(): Boolean {
        val cm = coreContext.context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun codeFor(e: Exception): String = when {
        e is IllegalStateException -> "P21-E2"
        e is java.net.NoRouteToHostException && e.message == "bez wifi" -> "P21-E3"
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "P21-E4"
        e is java.net.SocketTimeoutException ->
            if (org.linphone.twentyone.vpn.TsManager.lockdownDetected == true) {
                "P21-E16"
            } else {
                "P21-E5"
            }
        e is java.net.UnknownHostException -> "P21-E6"
        else -> "P21-E7"
    }

    private fun describe(e: Exception, target: String): String {
        return when {
            e is IllegalStateException -> "[P21-E2] Server $target odpověděl " +
                "${e.message} — vygeneruj nový QR kód, odkaz platí jen chvíli."
            e is java.net.NoRouteToHostException && e.message == "bez wifi" ->
                "[P21-E3] Telefon není na wifi (vidím jen: $lastRoute). Připoj " +
                "ho na stejnou wifi jako miniserver a zkus to znovu."
            e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
                "[P21-E4] Na $target se přes $lastRoute nejde dostat — je " +
                "telefon na stejné wifi jako miniserver?"
            e is java.net.SocketTimeoutException -> describeTimeout(target)
            e is java.net.UnknownHostException ->
                "[P21-E6] Adresu $target se nepodařilo najít."
            else -> "[P21-E7] Stažení z $target selhalo: " +
                "${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Timeout má tři různé příčiny — kód i text se volí podle toho, co
     *  se dá ZMĚŘIT, ne odhadem. */
    private fun describeTimeout(target: String): String {
        val lockdown = org.linphone.twentyone.vpn.TsManager.lockdownDetected
        return when {
            lockdown == true ->
                "[P21-E16] Stahování blokuje systémové „Blokovat " +
                "připojení bez VPN“. Vypni ho: Nastavení → VPN " +
                "→ Phone21, pak naskenuj nový QR."
            vpnUp() ->
                "[P21-E5] $target neodpovídá přes $lastRoute a tunel právě " +
                "běží. Zkontroluj systémové „Blokovat připojení bez " +
                "VPN“ u Phone21; když je vypnuté, půjde o izolaci " +
                "klientů na wifi."
            else ->
                "[P21-E5] $target neodpovídá přes $lastRoute (vypršel čas). " +
                "Sedí adresa s tvojí sítí? Nemá wifi izolaci klientů?"
        }
    }
}
