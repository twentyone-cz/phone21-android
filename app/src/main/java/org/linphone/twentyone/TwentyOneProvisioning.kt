/*
 * fork: konfiguraci z QR stahujeme sami místo SIP knihovny.
 *
 * Dva důvody. Knihovna při chybě neumí říct proč (jen „failed"), takže se
 * nedalo poznat, jestli je vadná síť, adresa, nebo obsah. A hlavně: když
 * běží náš tunel, provoz aplikace jde skrz něj — jenže miniserver se při
 * nastavování stahuje z domácí wifi, kam tunel nevede. Proto se stažení
 * váže výslovně na síť bez VPN a knihovně se předá hotový soubor.
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

    /** Stáhne konfiguraci a aplikuje ji. Chyby hlásí voláním [onError]
     *  s krátkým lidským popisem — ten se zobrazí v hlášce. */
    fun fetchAndApply(url: String, onError: (String) -> Unit) {
        executor.execute {
            val target = try {
                URL(url).let { "%s:%d".format(it.host, if (it.port > 0) it.port else 80) }
            } catch (_: Exception) {
                url
            }
            if (!Compatibility.isAccessLocalNetworkPermissionGranted(
                    coreContext.context)) {
                TwentyOneDiag.log("P21-E10", "chybí oprávnění Místní síť, cíl=%s"
                    .format(target))
                onError("[P21-E10] Chybí oprávnění \u201eMístní síť\u201c — " +
                    "bez něj Android nepustí aplikaci k miniserveru. Povol ho " +
                    "v systémovém nastavení aplikace a naskenuj QR znovu.")
                return@execute
            }
            try {
                val data = download(url)
                if (!looksLikeConfig(data)) {
                    TwentyOneDiag.log("P21-E1", "cíl=%s cesta=%s délka=%d".format(
                        target, lastRoute, data.size))
                    onError("[P21-E1] Server nevrátil konfiguraci (odkaz už asi vypršel).")
                    return@execute
                }
                val file = File(coreContext.context.filesDir, "provisioning.xml")
                file.writeBytes(data)
                coreContext.postOnCoreThread { core ->
                    core.provisioningUri = "file://" + file.absolutePath
                    Log.i("$TAG Config downloaded (${data.size} B), applying from file")
                    core.stop()
                    core.start()
                }
            } catch (e: Exception) {
                Log.e("$TAG Download of [$url] via [$lastRoute] failed: $e")
                val code = codeFor(e)
                TwentyOneDiag.log(code, "cíl=%s cesta=%s výjimka=%s %s".format(
                    target, lastRoute, e.javaClass.simpleName, e.message ?: ""))
                onError(describe(e, target))
            }
        }
    }

    @Volatile
    private var lastRoute = "?"

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

    private fun codeFor(e: Exception): String = when {
        e is IllegalStateException -> "P21-E2"
        e is java.net.NoRouteToHostException && e.message == "bez wifi" -> "P21-E3"
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "P21-E4"
        e is java.net.SocketTimeoutException -> "P21-E5"
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
            e is java.net.SocketTimeoutException ->
                "[P21-E5] $target neodpovídá přes $lastRoute (vypršel čas). " +
                "Sedí adresa s tvojí sítí? Nemá wifi izolaci klientů?"
            e is java.net.UnknownHostException ->
                "[P21-E6] Adresu $target se nepodařilo najít."
            else -> "[P21-E7] Stažení z $target selhalo: " +
                "${e.message ?: e.javaClass.simpleName}"
        }
    }
}
