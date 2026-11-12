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

    private val vpnObserver = Observer<Boolean> {
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

    @WorkerThread
    fun onCoreStarted(core: Core) {
        this.core = core
        core.addListener(coreListener)
        ensureInternationalPrefix(core)
        coreContext.postOnMainThread {
            // nový start jádra začíná od nepřipravené sítě
            ready = false
            TsManager.ensureStarted(coreContext.context)
            TsManager.state.observeForever(stateObserver)
            TsManager.loggedIn.observeForever(loginObserver)
            TsManager.endpointVerified.observeForever(endpointObserver)
            TsManager.vpnActive.observeForever(vpnObserver)
            TsManager.networkPaused.observeForever(pausedObserver)
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
            TsManager.vpnActive.removeObserver(vpnObserver)
            TsManager.networkPaused.removeObserver(pausedObserver)
        }
        this.core = null
    }

    private var lastAutoStart = 0L

    // Jádro tunelu běží i bez rozhraní (po vlastních socketech) — po
    // restartu telefonu nebo násilném ukončení službu rozhraní nikdo
    // nespustí a registrace by mlela io error. Když je souhlas s VPN
    // už udělený, služba se spustí sama; bez souhlasu se nic neděje
    // (dialog patří obrazovce Privátní síť / QR cestě).
    private fun maybeStartService() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAutoStart < 30_000) return
        if (android.net.VpnService.prepare(coreContext.context) != null) return
        lastAutoStart = now
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN",
            "rozhraní tunelu neběží a souhlas je udělen — startuji službu"
        )
        TsManager.startService()
    }

    private fun evaluate() {
        val state = TsManager.state.value
        val loggedIn = TsManager.loggedIn.value == true
        val verified = TsManager.endpointVerified.value == true
        val vpnUp = TsManager.vpnActive.value == true
        val paused = TsManager.networkPaused.value == true
        // Stav RUNNING z definice znamená přihlášený uzel — příznak
        // přihlášení z notifikací chodí pozdě/nespolehlivě a držel účet
        // v „disabled", i když tunel dávno běžel (do logu se dál píše).
        if (verified && state == TsManager.State.RUNNING && !vpnUp) {
            maybeStartService()
        }
        // Bez postaveného rozhraní nemá registrace kudy jít — dřív se
        // zapínala i tak a padala na io error.
        // Pozastavená síť (prázdná mapa protějšků) je pro registraci totéž
        // co spadlé rozhraní: ústředna není odkud dosažitelná. Po návratu
        // protějšků se příznak zhasne a registrace se zapne zpátky sama.
        val available = verified && (state == TsManager.State.RUNNING) && vpnUp && !paused
        if (available == ready) return
        ready = available
        Log.i("$TAG Network readiness is now [$available]")
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-NET",
            ("síť %s (stav=%s přihlášení=%s ověření=%s rozhraní=%s pozastaveno=%s) — " +
                "registrace u ústředny se %s").format(
                if (available) "připravena" else "nepřipravena",
                state, loggedIn, verified, vpnUp, paused,
                if (available) "zapíná" else "vypíná")
        )
        coreContext.postOnCoreThread {
            applyState(available)
        }
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
