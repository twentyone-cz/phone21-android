/*
 * fork: neviditelná mezizastávka po naskenování QR. Systémový souhlas s VPN
 * musí přijít z aktivity, takže tahle se otevře, zeptá se, přihlásí telefon
 * do privátní sítě přiloženým klíčem a zase zmizí.
 *
 * Druhá cesta (EXTRA_RECLAIM, tlačítko „Zapnout Phone21“ ve výstraze):
 * vzít si zpátky místo pro VPN, o které Phone21 přišlo. Běží-li VPN jiné
 * aplikace, nejdřív se zeptá — VpnService.prepare() ji vypne.
 */
package org.linphone.twentyone.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.linphone.R
import org.linphone.core.tools.Log

class TsConsentActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Tunnel Consent]"
        const val EXTRA_URL = "ts_url"
        const val EXTRA_KEY = "ts_key"

        /** Návrat po ztraceném místě pro VPN (tlačítko ve výstraze). */
        const val EXTRA_RECLAIM = "ts_reclaim"

        /** Jak dlouho čekat na první stav jádra v čerstvém procesu. */
        private const val STATE_WAIT_MS = 3_000L

        /** Kdy (elapsedRealtime) aktivita naposled skončila bez zapnutí
         *  tunelu — připojování z QR na to nemusí čekat celou lhůtu. */
        @Volatile
        var lastGaveUpAt = 0L
            private set

        /**
         * Potvrzení před VpnService.prepare(), které vezme místo pro VPN:
         * běží-li VPN jiné aplikace, nebo Phone21 o místo přišlo (VPN, která
         * Phone21 nepokrývá — vyjmutí z tunelu, VPN jen pro vybrané
         * aplikace — se z aktivní sítě nepozná). null = není se na co ptát.
         * Jen dotaz, nikdy zábrana.
         */
        fun takeoverConfirmTexts(context: Context): Pair<Int, Int>? = when {
            TsManager.otherVpnActive(context) ->
                R.string.twentyone_other_vpn_confirm_title to
                    R.string.twentyone_other_vpn_confirm_message
            TsManager.isSlotLost() ->
                R.string.twentyone_slot_lost_confirm_title to
                    R.string.twentyone_slot_lost_confirm_message
            else -> null
        }

        /**
         * Souhlas neprošel. Běží-li přitom VPN jiné aplikace, mohl ho zrušit
         * uživatel, nebo ho systém zamítl sám, protože je ta VPN trvale
         * zapnutá (to se odsud rozlišit nedá) — zapsat do deníku a říct to
         * uživateli (jinak by „Zapnout Phone21“ nedělalo nic a nikdo by
         * nevěděl proč). Vrací false, když jiná VPN neběží.
         */
        fun explainRefusal(context: Context, onDone: () -> Unit): Boolean {
            if (!TsManager.otherVpnActive(context)) return false
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-E21",
                "běží jiná VPN a souhlas neprošel — zrušen, nebo je ta VPN " +
                    "v systému trvale zapnutá a Phone21 ji nepřebije"
            )
            AlertDialog.Builder(context)
                .setTitle(R.string.twentyone_other_vpn_blocked_title)
                .setMessage(R.string.twentyone_other_vpn_blocked_message)
                .setPositiveButton(R.string.twentyone_other_vpn_blocked_settings) { _, _ ->
                    try {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
                .setNegativeButton(R.string.twentyone_tunnel_login_url_close, null)
                .setOnDismissListener { onDone() }
                .show()
            return true
        }
    }

    private var authKey: String = ""
    private var controlUrl: String = ""
    private var reclaim = false

    /** Tunel se z téhle aktivity spustil (jinak její konec = vzdání). */
    private var started = false
    private var createdAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (reclaim) startTunnel() else connect()
            finish()
            return@registerForActivityResult
        }
        Log.w("$TAG VPN permission was refused, tunnel stays down")
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "souhlas s VPN odmítnut — tunel zůstává vypnutý")
        giveUp()
    }

    /** Konec bez zapnutí tunelu; běží-li jiná VPN, nejdřív vysvětlit proč. */
    private fun giveUp() {
        lastGaveUpAt = android.os.SystemClock.elapsedRealtime()
        if (!explainRefusal(this) { finish() }) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAt = android.os.SystemClock.elapsedRealtime()
        reclaim = intent.getBooleanExtra(EXTRA_RECLAIM, false)
        if (reclaim) {
            // proces může být čerstvý (klepnutí na výstrahu po jeho zabití)
            // a TsManager.startService potřebuje nastartovaný TsManager
            TsManager.ensureStarted(applicationContext)
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-TUN", "uživatel chce Phone21 zpátky (ztracené místo pro VPN)")
            reclaimWhenStateKnown()
            return
        }
        controlUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        authKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        if (authKey.isEmpty()) {
            finish()
            return
        }
        TsManager.ensureStarted(applicationContext)
        confirmThenPrepare()
    }

    /**
     * Čerstvý proces zná stav jádra až z první notifikace (do té doby
     * NO_STATE) — chvíli počkat, jinak by stráž níž nezabrala.
     */
    private fun reclaimWhenStateKnown(waitedMs: Long = 0L) {
        if (isFinishing || isDestroyed) return
        val state = TsManager.backendState()
        if (state == TsManager.State.NO_STATE && waitedMs < STATE_WAIT_MS) {
            handler.postDelayed({ reclaimWhenStateKnown(waitedMs + 200L) }, 200L)
            return
        }
        if (state == TsManager.State.NEEDS_LOGIN ||
            state == TsManager.State.NEEDS_MACHINE_AUTH
        ) {
            // Telefon do sítě nepatří: místo pro VPN by se vzalo (a jiná
            // VPN vypnula) zbytečně, rozhraní by stejně nevzniklo.
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-TUN", "telefon do sítě nepatří — místo pro VPN neberu, otevírám čtení nového kódu")
            lastGaveUpAt = android.os.SystemClock.elapsedRealtime()
            startActivity(Intent(this, org.linphone.ui.assistant.AssistantActivity::class.java))
            finish()
            return
        }
        confirmThenPrepare()
    }

    /**
     * Odchod tlačítkem Domů / přepnutím aplikací: aktivita bez historie
     * skončí, dialog zmizí bez onDismiss. I to je vzdání — QR připojování
     * nemá čekat celou lhůtu.
     */
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isFinishing && !started && lastGaveUpAt < createdAt) {
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-TUN", "uživatel odešel bez zapnutí tunelu")
            lastGaveUpAt = android.os.SystemClock.elapsedRealtime()
        }
        super.onDestroy()
    }

    /**
     * Zeptat se dřív, než prepare() místo pro VPN sebere (viz
     * [takeoverConfirmTexts]). Aktivita je průhledná a bez historie: zavřený
     * dialog bez pokračování ji musí ukončit, jinak by tu visela neviditelná.
     */
    private fun confirmThenPrepare() {
        val texts = takeoverConfirmTexts(applicationContext)
        if (texts == null) {
            prepareAndGo()
            return
        }
        var proceed = false
        AlertDialog.Builder(this)
            .setTitle(texts.first)
            .setMessage(texts.second)
            .setPositiveButton(R.string.twentyone_slot_lost_action) { _, _ ->
                proceed = true
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-TUN", "uživatel potvrdil převzetí místa pro VPN")
            }
            .setNegativeButton(R.string.twentyone_other_vpn_confirm_cancel, null)
            .setOnDismissListener {
                if (proceed) {
                    prepareAndGo()
                } else {
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN", "místo pro VPN nechávám — uživatel Phone21 nezapnul")
                    lastGaveUpAt = android.os.SystemClock.elapsedRealtime()
                    finish()
                }
            }
            .show()
    }

    private fun prepareAndGo() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnPermission.launch(consent)
        } else {
            if (reclaim) startTunnel() else connect()
            finish()
        }
    }

    /** Stejně jako „Připojit“: služba v onStartCommand nastaví WantRunning=true. */
    private fun startTunnel() {
        started = true
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "souhlas s VPN je — zapínám Phone21 zpátky")
        TsManager.startService()
    }

    private fun connect() {
        started = true
        org.linphone.twentyone.TwentyOneDiag.log(
            "P21-TUN", "souhlas s VPN udělen — přihlašuji klíčem z QR")
        TsManager.startService()
        TsManager.login(controlUrl, authKey)
        // účet i síť jsou nastavené — teď má smysl říct si o běh na pozadí
        org.linphone.twentyone.TwentyOneBattery.offerOnce(this)
    }
}
