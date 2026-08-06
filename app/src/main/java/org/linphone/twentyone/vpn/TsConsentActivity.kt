/*
 * fork: neviditelná mezizastávka po naskenování QR. Systémový souhlas s VPN
 * musí přijít z aktivity, takže tahle se otevře, zeptá se, přihlásí telefon
 * do privátní sítě přiloženým klíčem a zase zmizí.
 */
package org.linphone.twentyone.vpn

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.linphone.core.tools.Log

class TsConsentActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Tunnel Consent]"
        const val EXTRA_URL = "ts_url"
        const val EXTRA_KEY = "ts_key"
    }

    private var authKey: String = ""
    private var controlUrl: String = ""

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            connect()
        } else {
            Log.w("$TAG VPN permission was refused, tunnel stays down")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controlUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        authKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        if (authKey.isEmpty()) {
            finish()
            return
        }
        TsManager.ensureStarted(applicationContext)
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnPermission.launch(consent)
        } else {
            connect()
            finish()
        }
    }

    private fun connect() {
        TsManager.startService()
        TsManager.login(controlUrl, authKey)
    }
}
