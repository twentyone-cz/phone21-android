/*
 * fork: jediná cesta HTTP k adresám privátní sítě (100.64.0.0/10,
 * fd7a:115c:a1e0::/48) — web miniserveru (stupeň přístupu, konfigurace
 * z QR na dálku).
 *
 * Vnitřní režim: jen přes webové relé na 127.0.0.1 (cíl v cestě, token
 * v hlavičce), NIKDY přímo. Bez systémové VPN nemá telefon do privátní
 * sítě trasu — adresa 100.64.x by odešla výchozí sítí (jinou VPN nebo
 * k operátorovi) nešifrovaně, i s tokenem pro miniserver. Když relé
 * neběží, je to chyba, ne důvod jít jinudy.
 * Systémový režim: přímo, rozhraní tunelu adresu vezme — ale jen když
 * opravdu stojí (jinak by šla stejně výchozí sítí).
 * Vypnutý tunel: chyba.
 *
 * Vždy bez systémové proxy (proxy nastavená na wifi nebo PAC by viděla
 * token relé i token pro miniserver) a bez automatického přesměrování
 * (přesměrování na privátní adresu by se otevřelo napřímo, mimo tuhle
 * kontrolu).
 *
 * Cíle mimo privátní síť (QR v domácí síti) jdou jako dosud výchozí sítí;
 * zálohu přes wifi mimo VPN řeší TwentyOneProvisioning (jen pod naší
 * systémovou VPN).
 *
 * Do deníku ani logu nikdy cesta požadavku (konfigurace z QR má v cestě
 * jednorázový token) ani token relé.
 */
package org.linphone.twentyone.vpn

import androidx.annotation.WorkerThread
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import org.linphone.core.tools.Log
import org.linphone.twentyone.TwentyOneCallPrivacy
import org.linphone.twentyone.TwentyOneDiag

object TsHttp {
    private const val TAG = "[Tunnel HTTP]"

    /** Hlavička s tokenem webového relé (stejné jméno jako v knihovně). */
    private const val RELAY_HEADER = "X-Phone21-Relay"

    private const val RELAY_HOST = "127.0.0.1"

    /** Jak dlouho počkat, než relé naběhne nebo převezme nový cíl. */
    const val DEFAULT_RELAY_WAIT_MS = 10_000L

    /**
     * Relé požadavek odmítlo nebo ho nedoručilo — odpověď nepřišla od
     * miniserveru. [code] je kód do deníku (P21-E23 token, P21-E24 jinak).
     */
    class RelayException(val status: Int, val code: String) :
        IOException("relé odpovědělo $status")

    /** Míří adresa do privátní sítě? Jen číselná adresa, nic se nepřekládá. */
    fun isPrivateNetworkUrl(url: URL): Boolean =
        TwentyOneCallPrivacy.isPrivateNetworkAddress(bare(url.host))

    fun isPrivateNetworkUrl(url: String): Boolean = try {
        isPrivateNetworkUrl(URL(url))
    } catch (_: Exception) {
        false
    }

    /**
     * Otevře spojení (ještě nepřipojené — volající nastaví metodu, časy
     * a hlavičky a pak čte [responseCode]). Z pracovního vlákna: ve
     * vnitřním režimu umí až [relayWaitMs] čekat, než relé naběhne nebo
     * převezme nový cíl.
     *
     * @throws IOException když cesta do privátní sítě teď nevede (relé
     *   neběží, systémová VPN nestojí, tunel je vypnutý) — nikdy nejde
     *   jinudy.
     */
    @WorkerThread
    fun open(url: URL, relayWaitMs: Long = DEFAULT_RELAY_WAIT_MS): HttpURLConnection {
        val host = bare(url.host)
        if (!TwentyOneCallPrivacy.isPrivateNetworkAddress(host)) {
            // QR v domácí síti: výchozí sítí jako dosud (bez přesměrování —
            // to by mohlo vést na privátní adresu napřímo)
            return (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
            }
        }
        return when (TsModeController.modeInUse()) {
            TsModeController.Mode.INTERNAL -> viaRelay(url, host, relayWaitMs)
            TsModeController.Mode.SYSTEM -> {
                // Rozhraní nestojí (přepíná se, místo pro VPN drží jiná
                // appka): adresa by odešla výchozí sítí — radši chyba.
                if (!TsManager.isTransportUp()) throw IOException("systémová VPN neběží")
                (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                }
            }
            TsModeController.Mode.OFF -> throw IOException("tunel je vypnutý")
        }
    }

    private fun viaRelay(url: URL, host: String, waitMs: Long): HttpURLConnection {
        // relé nese jen http (šifruje tunel); https by znamenalo jiný cíl
        if (!url.protocol.equals("http", ignoreCase = true)) {
            throw IOException("relé nese jen http")
        }
        val (port, token) = TsRelay.httpEndpointFor(host, waitMs)
            ?: throw IOException("relé uvnitř appky neběží nebo na cíl zatím nesmí")
        val targetPort = if (url.port > 0) url.port else url.defaultPort
        // IPv6 v hranatých závorkách, zakódovaných (relé cestu dekóduje)
        val target = if (host.contains(':')) "%5B$host%5D" else host
        val path = url.path.ifEmpty { "/" }
        val query = url.query?.let { "?$it" }.orEmpty()
        val relayUrl = URL("http", RELAY_HOST, port, "/$target:$targetPort$path$query")
        return (relayUrl.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            setRequestProperty(RELAY_HEADER, token)
        }
    }

    /** Šlo spojení přes relé? (Privátní cíl nikdy není 127.0.0.1.) */
    fun viaRelay(conn: HttpURLConnection): Boolean = conn.url.host == RELAY_HOST

    /**
     * Stavový kód odpovědi. Odpověď relé místo miniserveru (odmítnutý
     * token, nepovolený cíl, miniserver tunelem nedostupný) zapíše do
     * deníku a vyhodí [RelayException]. [what] = co se stahovalo (do
     * deníku; nikdy adresu s cestou).
     *
     * Miniserver sám 403, 407 ani 502 nevrací a 400 posílá jako JSON —
     * relé chyby píše jako prostý text.
     */
    @WorkerThread
    fun responseCode(conn: HttpURLConnection, what: String): Int {
        val status = conn.responseCode
        if (!viaRelay(conn)) return status
        val code = when (status) {
            407 -> "P21-E23"
            // relé: odmítnutý token „forbidden“, nepovolený cíl „Forbidden“
            403 -> if (errorText(conn) == "forbidden") "P21-E23" else "P21-E24"
            502, 504 -> "P21-E24"
            400 -> if (isPlainText(conn)) "P21-E24" else null
            else -> null
        } ?: return status
        TwentyOneDiag.log(
            code,
            when {
                code == "P21-E23" -> "HTTP přes relé odmítnuto (%s): neplatný token, HTTP %d"
                status == 502 || status == 504 -> "HTTP přes relé (%s): miniserver tunelem neodpovídá, HTTP %d"
                status == 403 -> "HTTP přes relé (%s): cíl relé nepovolen, HTTP %d"
                else -> "HTTP přes relé (%s): relé požadavek nepřijalo, HTTP %d"
            }.format(what, status)
        )
        Log.w("$TAG Relay answered [$status] for [$what]")
        throw RelayException(status, code)
    }

    private fun isPlainText(conn: HttpURLConnection): Boolean =
        conn.contentType?.startsWith("text/plain") == true

    private fun errorText(conn: HttpURLConnection): String = try {
        conn.errorStream?.use { s ->
            val buf = ByteArray(64)
            val n = s.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.US_ASCII).trim()
        }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun bare(host: String): String = host.trim().removePrefix("[").removeSuffix("]")
}
