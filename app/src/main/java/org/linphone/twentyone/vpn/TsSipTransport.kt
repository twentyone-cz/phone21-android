/*
 * fork: účet ve volací knihovně podle režimu tunelu.
 *
 * Vnitřní režim („jen hovory“ bez systémové VPN): účty s doménou
 * v privátní síti mluví jen s relé na 127.0.0.1 — šifrovaná signalizace
 * se vzájemným ověřením certifikátů, hlas jen na 127.0.0.1, šifrovaný,
 * když ho relé chce. Trasa na relé platí i tehdy, když relé zrovna neběží:
 * spojení na 127.0.0.1 se odmítne a nic neodejde přímo (přes jinou VPN
 * nebo k operátorovi) nešifrovaně.
 *
 * Systémový režim: všechno zpátky, jak to bylo před vnitřním tunelem
 * (provoz k miniserveru jde systémovou VPN).
 *
 * Vlákno jádra, idempotentní: volá se při startu jádra, přidání účtu, po
 * konfiguraci z QR, při přepnutí režimu, když relé změní port, po uložení
 * nastavení účtu a v pravidelné kontrole připravenosti (trasu účtu může
 * přepsat i obrazovka nastavení — prázdná obchozí proxy trasu na relé
 * smaže a uložený stav [applied] by to neviděl).
 *
 * Stojí na chování volací knihovny 5.5.13 (rozbor v interní dokumentaci):
 * REGISTER jde VŽDY na adresu serveru účtu a trasu účtu ignoruje
 * (SalRegisterOp::sendRegister přepíše trasu adresou serveru) — ve
 * vnitřním režimu proto míří na relé i adresa serveru, ne jen trasa;
 * původní adresa miniserveru se uloží do účtu ([SERVER_PARAM]) a vrací se
 * v systémovém režimu. Bez toho REGISTER mířil na UDP miniserveru, které
 * je ve vnitřním režimu vypnuté, a knihovna hlásila „Registration failed“,
 * aniž co odeslala (test 21p.45-test1, 2026-11-18).
 * Klientský certifikát a připnutý certifikát relé (setTlsCert/setTlsKey,
 * setRootCaData) se neukládají — po každém startu jádra znovu; připnutí
 * nahrazuje kořenové certifikáty pro VŠECHNA šifrovaná spojení knihovny;
 * samotná změna trasy registraci nezopakuje; TLS -2 = jen klient bez
 * naslouchání; [rtp] bind_address platí od dalšího hovoru.
 */
package org.linphone.twentyone.vpn

import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.NatPolicy
import org.linphone.core.tools.Log
import org.linphone.twentyone.TwentyOneCallPrivacy
import org.linphone.twentyone.TwentyOneDiag

object TsSipTransport {
    private const val TAG = "[Tunnel SIP]"
    private const val RELAY_HOST = "127.0.0.1"

    /** Jediná sada šifrování hlasu, kterou knihovna k relé nabídne. Klíč
     *  v konfiguraci je [sip] srtp_crypto_suites (ověřeno v 5.5.13). */
    private const val SRTP_SUITE = "AES_CM_128_HMAC_SHA1_80"

    private const val MARK_SECTION = "twentyone"

    /** Vlastní parametr účtu: původní adresa serveru (miniserver v privátní
     *  síti), dokud adresa serveru míří na relé. Ukládá se s účtem. */
    private const val SERVER_PARAM = "twentyone_server"

    /** Značka „šifrování hlasu zapnul vnitřní režim“ — vrací se jen to, co
     *  jsme sami nastavili. */
    private const val MARK_MEDIA = "internal_media"

    /** Transporty ve vnitřním režimu: UDP a TCP vypnuté (nikdo
     *  z místní sítě ani jiná appka nepodstrčí žádost), TLS jen klient. */
    private const val PORT_OFF = 0
    private const val PORT_RANDOM = -1
    private const val PORT_DONTBIND = -2

    /** Co je ve volací knihovně opravdu nastavené (za tohoto startu jádra). */
    private data class Applied(
        val mode: TsModeController.Mode,
        val port: Int,
        val srtp: Boolean
    )

    @Volatile
    private var applied: Applied? = null

    /** Certifikát relé je připnutý (setRootCaData nemá čtecí metodu). */
    @Volatile
    private var rootCaPinned = false

    private var foreignWarned = false
    private var plainMediaWarned = false

    /**
     * Je účet nastavený pro [mode] tak, že registrace smí ven?
     * Vnitřní: trasa míří na port běžícího relé a šifrování hlasu odpovídá
     * tomu, co relé chce. Systémový: vše vrácené.
     */
    fun sipConfiguredFor(mode: TsModeController.Mode): Boolean {
        val a = applied ?: return false
        return when (mode) {
            TsModeController.Mode.INTERNAL -> {
                val i = TsRelay.currentInfo() ?: return false
                a.mode == TsModeController.Mode.INTERNAL && a.port == i.sipPort && a.srtp == i.clientSrtp
            }
            TsModeController.Mode.SYSTEM -> a.mode == TsModeController.Mode.SYSTEM
            TsModeController.Mode.OFF -> false
        }
    }

    /** Nový start jádra: data certifikátů v knihovně nejsou, vše znovu. */
    @WorkerThread
    fun onCoreStarted(core: Core) {
        applied = null
        rootCaPinned = false
        apply(core, TsModeController.modeInUse(), "start jádra")
    }

    /** Relé (re)startovalo: převzít port a šifrování a nové spojení ohlásit
     *  registrací (vždy — výpadek a návrat relé se v pozorovatelích může
     *  slít do jedné změny a připravenost by registraci nezopakovala). */
    @WorkerThread
    fun onRelayChanged(core: Core) {
        if (TsModeController.modeInUse() != TsModeController.Mode.INTERNAL) return
        if (TsModeController.isSwitching()) return
        val reRegistering = apply(core, TsModeController.Mode.INTERNAL, "relé (znovu) běží")
        // Změnu trasy přeregistruje připravenost (vypnout → zapnout); jinak
        // stačí obnova — relé se z ní dozví šifrované spojení knihovny.
        if (!reRegistering) core.refreshRegisters()
        coreContext.tunnelReadinessManager.reevaluate("relé běží")
    }

    /**
     * Účet se mohl změnit mimo nás (uložené nastavení účtu, pravidelná
     * kontrola): znovu podle používaného režimu. Během přepínání nic —
     * přepnutí nastaví cílový režim samo a starý by ho tu přebil. Vrací
     * totéž co [apply].
     */
    @WorkerThread
    fun reapply(core: Core, reason: String): Boolean {
        if (TsModeController.isSwitching()) return false
        return apply(core, TsModeController.modeInUse(), reason)
    }

    /** Vrací true, když kvůli změně trasy vypnulo registraci (zapne ji
     *  znovu připravenost). */
    @WorkerThread
    fun apply(core: Core, mode: TsModeController.Mode, reason: String): Boolean {
        return try {
            when (mode) {
                TsModeController.Mode.INTERNAL -> applyInternal(core, reason)
                TsModeController.Mode.SYSTEM -> applySystem(core, reason)
                TsModeController.Mode.OFF -> {
                    rememberTargets(core)
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("$TAG Failed to configure accounts for [$mode]: $e")
            TwentyOneDiag.log(
                "P21-TUN", "nastavení účtu pro režim %s selhalo: %s".format(mode, e.javaClass.simpleName))
            false
        }
    }

    /**
     * Miniserver účtu v privátní síti: doména adresy serveru, nebo — když
     * adresa serveru míří na relé — doména uložené původní adresy. null =
     * účet mimo privátní síť.
     */
    fun miniserverHost(account: Account): String? {
        val params = account.params
        val host = params.serverAddress?.domain ?: return null
        if (TwentyOneCallPrivacy.isPrivateNetworkAddress(host)) return host
        if (host != RELAY_HOST) return null
        return savedServer(params)?.domain
            ?.takeIf { TwentyOneCallPrivacy.isPrivateNetworkAddress(it) }
    }

    private fun savedServer(params: org.linphone.core.AccountParams): org.linphone.core.Address? {
        val saved = params.getCustomParam(SERVER_PARAM)
        if (saved.isNullOrEmpty()) return null
        return Factory.instance().createAddress(saved)
    }

    private fun isPrivate(account: Account): Boolean = miniserverHost(account) != null

    /** Ústředna pro relé: miniserver výchozího účtu, jinak prvního v privátní síti. */
    private fun rememberTargets(core: Core) {
        val def = core.defaultAccount?.takeIf { isPrivate(it) }
        val acc = def ?: core.accountList.firstOrNull { isPrivate(it) }
        TsRelay.setSipHost(acc?.let { miniserverHost(it) })
    }

    private fun isOurNatPolicy(np: NatPolicy?): Boolean =
        np != null && !np.isStunEnabled && !np.isIceEnabled && !np.isTurnEnabled &&
            !np.isUpnpEnabled && np.stunServer.isNullOrEmpty()

    @WorkerThread
    private fun applyInternal(core: Core, reason: String): Boolean {
        rememberTargets(core)
        val identity = TsRelay.identity()
        val port = TsRelay.routePort()
        val srtp = TsRelay.clientSrtpExpected()
        val accounts = core.accountList
        val private = accounts.filter { isPrivate(it) }
        val foreign = accounts.size - private.size
        var changed = false
        var reRegister = false

        // 1) adresa serveru i trasa na relé (REGISTER jde na adresu serveru,
        //    ostatní žádosti trasou), vlastní politika NAT bez STUN/ICE/TURN
        //    (kandidáti by do SDP přinesli adresy wifi, mobilních dat i cizí
        //    VPN a STUN by šel na cizí server). Relé adresu 127.0.0.1
        //    v cíli žádosti přepíše na miniserver.
        val server = Factory.instance().createAddress("sip:$RELAY_HOST:$port;transport=tls")
        val route = Factory.instance().createAddress("sip:$RELAY_HOST:$port;transport=tls;lr")
        if (server == null || route == null) {
            Log.e("$TAG Cannot build relay route")
            return false
        }
        for (account in private) {
            val params = account.params
            val current = params.serverAddress
            val sameServer = current?.asStringUriOnly() == server.asStringUriOnly()
            val routes = params.routesAddresses
            val sameRoute = routes.size == 1 && routes[0].asStringUriOnly() == route.asStringUriOnly()
            val natOk = isOurNatPolicy(params.natPolicy)
            if (sameServer && sameRoute && natOk) continue
            val copy = params.clone()
            // původní adresu uložit, dokud ještě míří na miniserver (po
            // nové konfiguraci z QR ji znovu přepíše aktuální)
            if (current != null && current.domain != RELAY_HOST) {
                copy.addCustomParam(SERVER_PARAM, current.asStringUriOnly())
            }
            // nejdřív server (při zapnuté obchozí proxy přepíše i trasu), pak trasa
            if (!sameServer) copy.serverAddress = server
            if (!sameRoute || !sameServer) copy.setRoutesAddresses(arrayOf(route))
            if (!natOk) {
                copy.natPolicy = core.createNatPolicy().apply {
                    isStunEnabled = false
                    isIceEnabled = false
                    isTurnEnabled = false
                    isUpnpEnabled = false
                    stunServer = ""
                }
            }
            // Samotná změna trasy registraci nezopakuje a obnova by šla
            // původní cestou — vypnout a nechat připravenost zapnout znovu.
            if ((!sameRoute || !sameServer) && copy.isRegisterEnabled) {
                copy.isRegisterEnabled = false
                reRegister = true
            }
            account.params = copy
            changed = true
        }

        // 2) transporty: UDP a TCP pryč, TLS jen klient (bez naslouchání)
        val wantPlain = if (foreign > 0) PORT_RANDOM else PORT_OFF
        if (foreign > 0 && !foreignWarned) {
            foreignWarned = true
            TwentyOneDiag.log(
                "P21-TUN",
                "účet mimo privátní síť (%d): UDP a TCP zůstávají zapnuté; jeho hlas ve vnitřním režimu nepůjde".format(foreign))
        }
        val t = core.transports
        if (t.udpPort != wantPlain || t.tcpPort != wantPlain || t.tlsPort != PORT_DONTBIND) {
            t.udpPort = wantPlain
            t.tcpPort = wantPlain
            t.tlsPort = PORT_DONTBIND
            core.setTransports(t)
            changed = true
        }

        // 3) šifrované spojení s relé: klientský certifikát knihovny
        //    a připnutý certifikát relé (nic jiného na 127.0.0.1 neprojde)
        if (identity != null) {
            if (core.tlsCert != identity.clientCertPem) core.tlsCert = identity.clientCertPem
            if (core.tlsKey != identity.clientKeyPem) core.tlsKey = identity.clientKeyPem
            if (!rootCaPinned) {
                core.setRootCaData(identity.serverCertPem)
                rootCaPinned = true
            }
            if (!core.isVerifyServerCertificates) core.verifyServerCertificates(true)
            if (!core.isVerifyServerCn) core.verifyServerCn(true)
        }

        // 4) hlas jen na 127.0.0.1, porty vždy náhodné (pevný port nebo
        //    rozsah zapne sdílení portu a cizí appka by hlas odposlechla)
        val cfg = core.config
        if (cfg.getString("rtp", "bind_address", null) != RELAY_HOST) {
            cfg.setString("rtp", "bind_address", RELAY_HOST)
            changed = true
        }
        val range = core.audioPortsRange
        if (core.audioPort != PORT_RANDOM || range.min != range.max) {
            core.audioPort = PORT_RANDOM
            changed = true
        }

        // 5) šifrování hlasu k relé, když ho relé chce (ústředna ho nemá,
        //    relé ho ukončuje); jen jedna sada
        if (srtp) {
            if (core.mediaEncryption != MediaEncryption.SRTP) core.setMediaEncryption(MediaEncryption.SRTP)
            if (!core.isMediaEncryptionMandatory) core.isMediaEncryptionMandatory = true
            if (core.srtpCryptoSuites != SRTP_SUITE) core.srtpCryptoSuites = SRTP_SUITE
            cfg.setBool(MARK_SECTION, MARK_MEDIA, true)
            plainMediaWarned = false
        } else {
            revertMedia(core)
            if (!plainMediaWarned) {
                plainMediaWarned = true
                TwentyOneDiag.log(
                    "P21-TUN", "relé nežádá šifrovaný hlas (starší knihovna) — hlas na 127.0.0.1 nešifrovaný")
            }
        }

        val now = Applied(TsModeController.Mode.INTERNAL, port, srtp)
        if (identity != null) {
            if (applied != now || changed) {
                TwentyOneDiag.log(
                    "P21-TUN",
                    "účet míří na relé 127.0.0.1:%d, hlas %s (%s)".format(
                        port, if (srtp) "šifrovaný" else "nešifrovaný", reason))
            }
            applied = now
        } else {
            // bez identity se s relé nespojí nic — cesta zůstává zavřená
            applied = null
        }
        if (reRegister) coreContext.tunnelReadinessManager.reevaluate("trasa účtu změněna")
        return reRegister
    }

    @WorkerThread
    private fun applySystem(core: Core, reason: String): Boolean {
        rememberTargets(core)
        var changed = false
        var reRegister = false
        for (account in core.accountList.filter { isPrivate(it) }) {
            val params = account.params
            val relayServer = params.serverAddress?.domain == RELAY_HOST
            val relayRoute = params.routesAddresses.any { it.domain == RELAY_HOST }
            val ours = isOurNatPolicy(params.natPolicy)
            if (!relayServer && !relayRoute && !ours) continue
            val copy = params.clone()
            // původní adresa miniserveru zpátky (isPrivate zaručuje, že je
            // uložená), pak trasa pryč — server se zapnutou obchozí proxy
            // by trasu nastavil znovu
            if (relayServer) savedServer(params)?.let { copy.serverAddress = it }
            if (relayServer || relayRoute) {
                copy.setRoutesAddresses(arrayOf<org.linphone.core.Address>())
                if (copy.isRegisterEnabled) {
                    copy.isRegisterEnabled = false
                    reRegister = true
                }
            }
            if (ours) copy.natPolicy = null
            account.params = copy
            changed = true
        }

        val t = core.transports
        if (t.udpPort == PORT_OFF || t.tcpPort == PORT_OFF || t.tlsPort == PORT_DONTBIND) {
            if (t.udpPort == PORT_OFF) t.udpPort = PORT_RANDOM
            if (t.tcpPort == PORT_OFF) t.tcpPort = PORT_RANDOM
            if (t.tlsPort == PORT_DONTBIND) t.tlsPort = PORT_RANDOM
            core.setTransports(t)
            changed = true
        }

        if (core.tlsCert != null) core.tlsCert = null
        if (core.tlsKey != null) core.tlsKey = null
        if (rootCaPinned) {
            // návrat k přibaleným kořenovým certifikátům knihovny
            val path = core.rootCa
            core.setRootCaData(null)
            core.rootCa = path
            rootCaPinned = false
            changed = true
        }

        val cfg = core.config
        if (cfg.hasEntry("rtp", "bind_address") != 0) {
            cfg.cleanEntry("rtp", "bind_address")
            changed = true
        }
        if (revertMedia(core)) changed = true

        if (applied?.mode != TsModeController.Mode.SYSTEM && changed) {
            TwentyOneDiag.log("P21-TUN", "účet zpátky na systémovou VPN (%s)".format(reason))
        }
        applied = Applied(TsModeController.Mode.SYSTEM, 0, false)
        if (reRegister) coreContext.tunnelReadinessManager.reevaluate("trasa účtu změněna")
        return reRegister
    }

    /** Šifrování hlasu vrátit, jen když ho zapnul vnitřní režim. */
    private fun revertMedia(core: Core): Boolean {
        val cfg = core.config
        if (!cfg.getBool(MARK_SECTION, MARK_MEDIA, false)) return false
        core.setMediaEncryption(MediaEncryption.None)
        core.isMediaEncryptionMandatory = false
        cfg.cleanEntry("sip", "srtp_crypto_suites")
        cfg.setBool(MARK_SECTION, MARK_MEDIA, false)
        return true
    }
}
