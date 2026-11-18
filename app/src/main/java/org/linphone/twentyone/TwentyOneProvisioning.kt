/*
 * fork: konfiguraci z QR stahujeme sami místo SIP knihovny.
 *
 * Dva důvody. Knihovna při chybě neumí říct proč (jen „failed"), takže se
 * nedalo poznat, jestli je vadná síť, adresa, nebo obsah. A hlavně: když
 * běží naše systémová VPN, provoz aplikace jde skrz ni — jenže miniserver
 * se při nastavování stahuje z domácí wifi, kam tunel nevede. Proto se
 * stažení při neúspěchu výchozí cestou zkusí ještě vázané na wifi mimo
 * VPN — ale JEN pod naší systémovou VPN. Drží-li výchozí síť jiná VPN
 * (vnitřní tunel ji nechává běžet), záloha by ji obešla, a to appka
 * nedělá; chyba pak radí, že jiná VPN možná nepouští do domácí sítě.
 *
 * QR zobrazené na dálku míří na miniserver v privátní síti a za # nese
 * jednorázový klíč do ní: telefon se nejdřív připojí a konfiguraci si pak
 * stáhne tunelem — ve vnitřním režimu přes relé uvnitř appky, bez
 * souhlasu s VPN (TsHttp; přímo na adresu v privátní síti nikdy).
 *
 * Odkaz zvenku (linphone-config: z fotoaparátu, prohlížeče, jiné appky) sem
 * přijde až po potvrzení adresy (TwentyOneConfigLinkActivity); skener
 * v appce a ruční adresa v nastavení se neptají. Relé smí na miniserver
 * z odkazu jen po dobu stažení (TsRelay.allowQrHost/clearQrHost).
 *
 * Ruční „vzdálená konfigurace“ v Pokročilém nastavení: webovou adresu
 * stahuje taky tenhle kód. Adresu uloženou ve volací knihovně (config-uri)
 * si knihovna stahuje sama při každém startu jádra výchozí sítí — do
 * privátní sítě tedy jen pod naší systémovou VPN; jinak se neuloží a před
 * startem jádra se smaže ([dropPrivateConfigUri]).
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
import org.linphone.twentyone.vpn.TsHttp
import org.linphone.twentyone.vpn.TsModeController
import org.linphone.twentyone.vpn.TsRelay

object TwentyOneProvisioning {
    private const val TAG = "[Provisioning]"
    private val executor = Executors.newSingleThreadExecutor()
    private val joinKeyRegex = Regex("^[A-Za-z0-9_-]{20,200}$")
    private const val JOIN_TIMEOUT_MS = 120_000L
    private const val TUNNEL_DOWNLOAD_ATTEMPTS = 4
    private const val TUNNEL_RETRY_MS = 3_000L

    /** Jednorázový token v cestě (miniserver: /p/<token>, /prov/<token>.xml). */
    private val pathTokenRegex = Regex("(/p/|/prov/)[^/?#.\\s]+")
    private val joinKeyParamRegex = Regex("([#&]k=)[^&\\s]*")

    /** Odkaz z QR bez klíče do sítě a bez jednorázového tokenu v cestě —
     *  do logů (oba otevírají účet, dokud platí). */
    fun redact(url: String): String =
        url.replace(pathTokenRegex, "$1…").replace(joinKeyParamRegex, "$1…")

    /** Webová adresa (http/https) — tu stahuje appka sama. */
    fun isWebUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** Míří adresa konfigurace do privátní sítě? Číselná adresa (nic se
     *  nepřekládá), i bez schématu; file:// ne. */
    fun pointsIntoPrivateNetwork(uri: String): Boolean {
        val u = uri.trim()
        if (u.isEmpty() || u.startsWith("file:", ignoreCase = true)) return false
        val withScheme = if (u.contains("://")) u else "http://" + u.removePrefix("//")
        val host = try {
            android.net.Uri.parse(withScheme).host
        } catch (_: Exception) {
            null
        } ?: return false
        return TwentyOneCallPrivacy.isPrivateNetworkAddress(host.removePrefix("[").removeSuffix("]"))
    }

    /** Hostitel a port adresy pro deník — nikdy cesta (nese token). */
    private fun hostPort(uri: String): String = try {
        val u = android.net.Uri.parse(if (uri.contains("://")) uri else "http://$uri")
        val h = u.host.orEmpty().ifEmpty { "?" }
        if (u.port > 0) "$h:${u.port}" else h
    } catch (_: Exception) {
        "?"
    }

    /** Smí volací knihovna sama na adresu v privátní síti? Jen když ji
     *  vezme rozhraní naší systémové VPN, které opravdu stojí. */
    private fun libraryReachesPrivateNetwork(): Boolean =
        TsModeController.modeInUse() == TsModeController.Mode.SYSTEM &&
            org.linphone.twentyone.vpn.TsManager.isSystemVpnUp()

    /**
     * Smí se [uri] uložit jako adresa konfigurace volací knihovny? Adresa
     * v privátní síti jen pod naší systémovou VPN; odmítnutí zapíše do
     * deníku (bez cesty). Vlákno jádra.
     */
    @androidx.annotation.WorkerThread
    fun mayStoreConfigUri(uri: String): Boolean {
        if (!pointsIntoPrivateNetwork(uri) || libraryReachesPrivateNetwork()) return true
        Log.w("$TAG Not storing provisioning URI [${redact(uri)}]: private network outside the system VPN")
        TwentyOneDiag.log(
            "P21-CFG",
            "adresa konfigurace v nastavení (%s) míří do privátní sítě mimo systémovou VPN — neuložena, stáhne ji jen tlačítko stažení"
                .format(hostPort(uri))
        )
        return false
    }

    /**
     * Pojistka před každým startem jádra: uložená adresa konfigurace
     * v privátní síti mimo naši systémovou VPN by se stáhla výchozí sítí
     * (jinou VPN nebo k operátorovi) nešifrovaně, i s jednorázovým tokenem.
     * Smazat ji, jeden zápis do deníku bez cesty. Vlákno jádra, před
     * core.start() — konfigurace se stahuje uvnitř startu.
     */
    @androidx.annotation.WorkerThread
    fun dropPrivateConfigUri(core: org.linphone.core.Core) {
        val uri = core.provisioningUri
        if (uri.isNullOrEmpty() || !pointsIntoPrivateNetwork(uri)) return
        if (libraryReachesPrivateNetwork()) return
        core.provisioningUri = null
        Log.w("$TAG Dropped provisioning URI [${redact(uri)}]: private network outside the system VPN")
        TwentyOneDiag.log(
            "P21-CFG",
            "uložená adresa konfigurace (%s) míří do privátní sítě mimo systémovou VPN — smazána před startem jádra"
                .format(hostPort(uri))
        )
    }

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
            // Cíl relé z QR jen po dobu stažení: po chybě hned pryč, po
            // úspěchu až po aplikaci konfigurace (pak už je známá brána).
            val qr = QrHostHold()
            try {
                fetchAndApplyNow(qrUrl, onError, qr)
            } finally {
                if (!qr.handedOver) TsRelay.clearQrHost(qr.gen)
            }
        }
    }

    /** Cíl webového relé z QR povolený pro tohle stažení. */
    private class QrHostHold {
        var gen = 0
        /** úklid převzala aplikace konfigurace na vlákně jádra */
        var handedOver = false
    }

    private fun fetchAndApplyNow(qrUrl: String, onError: (String) -> Unit, qr: QrHostHold) {
        val (url, joinKey) = splitJoinKey(qrUrl)
        val target = try {
            URL(url).let { "%s:%d".format(it.host, if (it.port > 0) it.port else 80) }
        } catch (_: Exception) {
            redact(url)
        }
        val host = try {
            URL(url).host.removePrefix("[").removeSuffix("]")
        } catch (_: Exception) {
            ""
        }
        if (!Compatibility.isAccessLocalNetworkPermissionGranted(
                coreContext.context)) {
            TwentyOneDiag.log("P21-E10", "chybí oprávnění Místní síť, cíl=%s"
                .format(target))
            onError("[P21-E10] Chybí oprávnění pro domácí síť — v " +
                "nastavení aplikace zapni \u201eMístní síť\u201c, a když " +
                "tam není, tak \u201eZařízení v okolí\u201c. Pak naskenuj " +
                "QR znovu.")
            return
        }
        // Adresa v privátní síti jde vždy tunelem (i bez klíče v QR) —
        // přímo by odešla výchozí sítí nešifrovaně.
        val viaTunnel = joinKey.isNotEmpty() || TsHttp.isPrivateNetworkUrl(url)
        if (viaTunnel) {
            // relé smí na miniserver z QR (jen po dobu stažení); bez účtu
            // podle něj i naběhne
            qr.gen = TsRelay.allowQrHost(host)
        }
        if (viaTunnel && !org.linphone.twentyone.vpn.TsManager.isTunnelUp()) {
            if (!joinNetwork(joinKey)) {
                TwentyOneDiag.log("P21-E18", "cíl=%s %s".format(target, networkFacts()))
                if (internalWanted()) {
                    onError("[P21-E18] Telefon se do privátní sítě nepřipojil — " +
                        "tunel uvnitř aplikace nenaběhl. Klíč v QR platí jen " +
                        "jednou, vygeneruj nový QR a zkus to znovu.")
                    return
                }
                // „jiná VPN blokuje QR“ jen tam, kde je potřeba místo pro VPN
                if (org.linphone.twentyone.vpn.TsManager.otherVpnActive(coreContext.context)) {
                    onError(coreContext.context.getString(
                        org.linphone.R.string.twentyone_qr_join_other_vpn))
                    return
                }
                onError("[P21-E18] Telefon se do privátní sítě nepřipojil — " +
                    "potvrdil jsi souhlas s VPN? Klíč v QR platí jen jednou, " +
                    "vygeneruj nový QR a zkus to znovu.")
                return
            }
        }
        try {
            val data = if (viaTunnel) downloadViaTunnel(url) else download(url)
            if (!looksLikeConfig(data)) {
                TwentyOneDiag.log("P21-E1", "cíl=%s cesta=%s délka=%d".format(
                    target, lastRoute, data.size))
                onError("[P21-E1] Server nevrátil konfiguraci (odkaz už asi vypršel).")
                return
            }
            val file = File(coreContext.context.filesDir, "provisioning.xml")
            file.writeBytes(data)
            val qrGen = qr.gen
            qr.handedOver = true
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
                try {
                    core.stop()
                    core.start()
                } finally {
                    TsRelay.clearQrHost(qrGen)
                }
            }
        } catch (e: Exception) {
            Log.e("$TAG Download of [${redact(url)}] via [$lastRoute] failed: $e")
            if (e is TsHttp.RelayException) {
                // zapsáno do deníku (P21-E23/E24) v TsHttp
                onError(
                    if (e is TsHttp.RelayIdentityException) {
                        "[P21-E23] Na místě relé uvnitř aplikace odpověděl někdo " +
                            "jiný — nic se neodeslalo. Zkus QR naskenovat znovu za chvíli."
                    } else if (e.code == "P21-E23") {
                        "[P21-E23] Relé uvnitř aplikace požadavek odmítlo. " +
                            "Zkus QR naskenovat znovu za chvíli."
                    } else {
                        "[P21-E24] Miniserver $target přes privátní síť " +
                            "neodpovídá (HTTP ${e.status}). Je miniserver zapnutý a v síti?"
                    }
                )
                return
            }
            if (viaTunnel && e !is IllegalStateException) {
                TwentyOneDiag.log("P21-E19", "cíl=%s %s výjimka=%s %s".format(
                    target, networkFacts(), e.javaClass.simpleName, e.message ?: ""))
                onError("[P21-E19] Telefon je v privátní síti, ale miniserver " +
                    "$target přes ni neodpovídá. Je miniserver zapnutý a v síti?")
                return
            }
            val code = codeFor(e)
            TwentyOneDiag.log(code, "cíl=%s cesta=%s %s výjimka=%s %s".format(
                target, lastRoute, networkFacts(),
                e.javaClass.simpleName, e.message ?: ""))
            onError(describe(e, target))
        }
    }

    @Volatile
    private var lastRoute = "?"

    /** Stupeň „jen hovory“ bez synchronizace = vnitřní tunel, bez VPN. */
    private fun internalWanted(): Boolean =
        TsModeController.desiredAccessMode(coreContext.context) == TsModeController.Mode.INTERNAL

    /**
     * Přihlásí telefon do privátní sítě klíčem z QR a počká, až tunel běží.
     * Ve vnitřním režimu bez souhlasu s VPN a bez převzetí místa — nic
     * z toho se nepotřebuje a jiná VPN v telefonu běží dál.
     */
    private fun joinNetwork(key: String): Boolean {
        val context = coreContext.context
        val internal = internalWanted()
        TwentyOneDiag.log(
            "P21-TUN",
            if (key.isNotEmpty()) {
                "QR nese klíč do sítě — připojuji telefon před stažením konfigurace (%s)"
            } else {
                "QR míří do privátní sítě — zapínám tunel před stažením konfigurace (%s)"
            }.format(TsModeController.label(if (internal) TsModeController.Mode.INTERNAL else TsModeController.Mode.SYSTEM))
        )
        coreContext.showGreenToastEvent.postValue(
            org.linphone.utils.Event(
                Pair(org.linphone.R.string.twentyone_qr_joining, org.linphone.R.drawable.info)
            )
        )
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val tm = org.linphone.twentyone.vpn.TsManager
        // Stupeň chce systémovou VPN, ale místo drží jiná VPN / chybí souhlas
        // a hovory jedou vnitřním tunelem: ke stažení stačí ten — místo se
        // kvůli QR nebere a jiná VPN běží dál (přepnutí jen přes „Zapnout přístup“).
        val fallback = !internal &&
            TsModeController.modeInUse() == TsModeController.Mode.INTERNAL &&
            TsModeController.systemBlockNow() != TsModeController.SystemBlock.NONE
        if (fallback && tm.isLoggedIn()) {
            TwentyOneDiag.log("P21-TUN", "systémové místo nemáme — konfiguraci stahuji vnitřním tunelem, místo neberu")
            tm.startService(userInitiated = false)
        } else if (!internal && tm.isLoggedIn() && (tm.isSlotLost() || tm.otherVpnActive(context))) {
            // Místo pro VPN drží jiná aplikace (nebo o něj Phone21 přišlo):
            // samotné spuštění služby by rozhraní nepostavilo. Zpátky přes
            // potvrzení a souhlas, stejně jako tlačítko ve výstraze.
            TwentyOneDiag.log("P21-TUN", "telefon už v síti je, ale místo pro VPN nemá — ptám se na převzetí")
            val intent = android.content.Intent(
                context, org.linphone.twentyone.vpn.TsConsentActivity::class.java
            ).apply {
                putExtra(org.linphone.twentyone.vpn.TsConsentActivity.EXTRA_RECLAIM, true)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                context.startActivity(intent)
            }
        } else if (tm.isLoggedIn()) {
            // přihlášený telefon: klíč se nepoužije, jen se spustí rozhraní
            // (vnitřní tunel bez souhlasu s VPN — pokyn ho nepotřebuje)
            TwentyOneDiag.log("P21-TUN", "telefon už v síti je — klíč z QR nepoužit, čekám na rozhraní")
            tm.startService(userInitiated = !internal)
        } else if (key.isEmpty()) {
            TwentyOneDiag.log("P21-TUN", "telefon v síti není a QR nenese klíč do ní")
            return false
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
            // uživatel souhlas / převzetí místa odmítl: nečekat celou lhůtu
            if (org.linphone.twentyone.vpn.TsConsentActivity.lastGaveUpAt >= startedAt) {
                Log.w("$TAG Tunnel consent was not given, giving up on joining")
                return false
            }
            Thread.sleep(1_000)
        }
        Log.w("$TAG Tunnel did not come up within ${JOIN_TIMEOUT_MS / 1000} s")
        return false
    }

    /** Stažení tunelem, několik pokusů; HTTP chyba miniserveru se
     *  neopakuje (odmítnutí relé ano — relé mohlo zrovna převzít nový cíl). */
    private fun downloadViaTunnel(url: String): ByteArray {
        lastRoute = if (TsModeController.modeInUse() == TsModeController.Mode.INTERNAL) {
            "privátní síť (relé v aplikaci)"
        } else {
            "privátní síť"
        }
        var last: Exception? = null
        repeat(TUNNEL_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                return fetch(TsHttp.open(URL(url)))
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
        // Vázání zůstává jako záloha pro případ, že výchozí cestu drží NÁŠ
        // tunel (systémová VPN). Výchozí síť jiné VPN se neobchází.
        val parsed = URL(url)
        val first = try {
            lastRoute = "výchozí síť"
            return fetch(TsHttp.open(parsed))
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e     // HTTP chyba — síť fungovala
            e
        }
        if (!org.linphone.twentyone.vpn.TsManager.isSystemVpnUp()) {
            Log.w("$TAG Default-network fetch failed [$first], no fallback without our system VPN")
            throw first
        }
        Log.w("$TAG Default-network fetch failed [$first], retrying bound to wifi")
        val (network, route) = nonVpnNetwork(coreContext.context)
        lastRoute = route
        if (network == null) {
            throw java.net.NoRouteToHostException("bez wifi")
        }
        return fetch(
            (network.openConnection(parsed) as HttpURLConnection).apply {
                instanceFollowRedirects = false
            }
        )
    }

    private fun fetch(connection: HttpURLConnection): ByteArray {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            val code = TsHttp.responseCode(connection, "konfigurace z QR")
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

    /** Změřené okolnosti pro deník: režim, běží tunel (a relé)? je zapnutá
     *  systémová blokace provozu mimo VPN? Diagnóza místo hádání. */
    private fun networkFacts(): String {
        val system = TsModeController.modeInUse() == TsModeController.Mode.SYSTEM
        // „Blokovat připojení bez VPN“ appka zjistí jen u vlastní VPN
        val lockdown = if (!system) {
            "-"
        } else {
            when (org.linphone.twentyone.vpn.TsManager.lockdownDetected) {
                true -> "zapnutá"
                false -> "vypnutá"
                null -> "?"
            }
        }
        // Vlastní tunel a cizí VPN zvlášť: dřív se jako „tunel=běží“ počítala
        // jakákoli VPN v telefonu, i ta, která Phone21 vzala místo.
        val tm = org.linphone.twentyone.vpn.TsManager
        return "režim=%s tunel=%s relé=%s jiná-vpn=%s blokace-mimo-vpn=%s".format(
            TsModeController.label(TsModeController.modeInUse()).replace(' ', '-'),
            if (tm.isTunnelUp()) "běží" else "ne",
            if (system) "-" else if (TsRelay.isUp()) "běží" else "ne",
            if (tm.otherVpnActive(coreContext.context)) "ano" else "ne",
            lockdown)
    }

    /** Blokace mimo VPN, o které appka ví — jen u vlastní systémové VPN. */
    private fun ownLockdown(): Boolean =
        TsModeController.modeInUse() == TsModeController.Mode.SYSTEM &&
            org.linphone.twentyone.vpn.TsManager.lockdownDetected == true

    /** Výchozí síť appky drží jiná VPN a záloha přes wifi ji neobejde. */
    private fun otherVpnNote(): String =
        if (!org.linphone.twentyone.vpn.TsManager.isSystemVpnUp() &&
            org.linphone.twentyone.vpn.TsManager.otherVpnActive(coreContext.context)
        ) {
            " V telefonu běží jiná VPN — možná nepouští do domácí sítě. " +
                "Povol v ní místní síť (LAN), nebo ji na chvíli vypni."
        } else {
            ""
        }

    private fun codeFor(e: Exception): String = when {
        e is IllegalStateException -> "P21-E2"
        e is java.net.NoRouteToHostException && e.message == "bez wifi" -> "P21-E3"
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "P21-E4"
        e is java.net.SocketTimeoutException ->
            if (ownLockdown()) {
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
                "telefon na stejné wifi jako miniserver?" + otherVpnNote()
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
        return when {
            ownLockdown() ->
                "[P21-E16] Stahování blokuje systémové „Blokovat " +
                "připojení bez VPN“. Vypni ho: Nastavení → VPN " +
                "→ Phone21, pak naskenuj nový QR."
            org.linphone.twentyone.vpn.TsManager.isSystemVpnUp() ->
                "[P21-E5] $target neodpovídá přes $lastRoute a tunel právě " +
                "běží. Zkontroluj systémové „Blokovat připojení bez " +
                "VPN“ u Phone21; když je vypnuté, půjde o izolaci " +
                "klientů na wifi."
            else ->
                "[P21-E5] $target neodpovídá přes $lastRoute (vypršel čas). " +
                "Sedí adresa s tvojí sítí? Nemá wifi izolaci klientů?" + otherVpnNote()
        }
    }
}
