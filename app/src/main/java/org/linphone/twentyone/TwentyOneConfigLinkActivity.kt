/*
 * fork: potvrzení odkazu s konfigurací (linphone-config:), který přišel
 * zvenku — z fotoaparátu, prohlížeče nebo jiné aplikace.
 *
 * Konfigurace přepíše účet a heslo, token pro miniserver i adresu brány
 * a klíč v ní telefon přihlásí do sítě. Odkaz ale může poslat kdokoli
 * (webová stránka, appka v telefonu), takže se nic nestáhne a relé nedostane
 * nový cíl, dokud uživatel nepotvrdí adresu, odkud se nastavení vezme.
 * Skener QR přímo v aplikaci se neptá — ten uživatel spustil vědomě.
 *
 * Průhledná aktivita nad tím, co je zrovna vidět: MainActivity při prvním
 * spuštění nebo bez účtu otevírá úvod nebo průvodce a dialog v ní by
 * zůstal schovaný pod nimi. Proto se spouští stejnou frontou jako ony
 * (vlákno jádra → hlavní vlákno), tedy až po nich.
 */
package org.linphone.twentyone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import java.net.IDN
import java.net.URL
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log
import org.linphone.utils.Event

class TwentyOneConfigLinkActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "[Config Link]"
        private const val EXTRA_URL = "config_url"

        /**
         * Zeptat se, jestli stáhnout konfiguraci z [url] (odkaz zvenku).
         * Jen http/https — nic jiného stažení konfigurace neumí.
         */
        @UiThread
        fun ask(activity: Activity, url: String) {
            if (!TwentyOneProvisioning.isWebUrl(url)) {
                Log.w("$TAG Config link does not point to a web address, ignoring it")
                TwentyOneDiag.log("P21-CFG", "odkaz s konfigurací zvenku nemíří na web — nepoužit")
                return
            }
            val appContext = activity.applicationContext
            coreContext.postOnCoreThread {
                coreContext.postOnMainThread {
                    val intent = Intent(appContext, TwentyOneConfigLinkActivity::class.java)
                        .putExtra(EXTRA_URL, url)
                    try {
                        if (activity.isFinishing || activity.isDestroyed) {
                            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            activity.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        Log.e("$TAG Cannot ask about the config link: $e")
                    }
                }
            }
        }

        /** Adresa pro dialog a deník: hostitel (cizí písma jako xn--…)
         *  a port, nikdy cesta (nese jednorázový token). */
        private fun hostLabel(url: String): String = try {
            val u = URL(url)
            val raw = u.host.orEmpty()
            val host = try {
                IDN.toASCII(raw)
            } catch (_: Exception) {
                raw
            }
            when {
                host.isEmpty() -> "?"
                u.port > 0 -> "$host:${u.port}"
                else -> host
            }
        } catch (_: Exception) {
            "?"
        }
    }

    private var url: String? = null
    private var decided = false

    private val localNetPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i("$TAG ACCESS_LOCAL_NETWORK granted [$granted]")
        if (granted) {
            fetch()
        } else {
            TwentyOneDiag.log("P21-E11", "žádost o Místní síť zamítnuta bez dialogu (odkaz)")
            TwentyOneDiag.openAppSettings(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.getStringExtra(EXTRA_URL)
        // Obnova po zániku procesu: potvrzení se ztratilo, nic nestahovat.
        if (link == null || savedInstanceState != null) {
            finish()
            return
        }
        url = link
        val where = hostLabel(link)
        AlertDialog.Builder(this)
            .setTitle(R.string.twentyone_config_link_title)
            .setMessage(getString(R.string.twentyone_config_link_message, where))
            .setPositiveButton(R.string.twentyone_config_link_confirm) { _, _ ->
                decided = true
                TwentyOneDiag.log("P21-CFG", "odkaz s konfigurací zvenku (%s) potvrzen".format(where))
                proceed()
            }
            .setNegativeButton(R.string.twentyone_config_link_cancel, null)
            .setOnDismissListener {
                if (!decided) {
                    decided = true
                    Log.i("$TAG Config link was not confirmed")
                    TwentyOneDiag.log(
                        "P21-CFG", "odkaz s konfigurací zvenku (%s) nepotvrzen — nic nestaženo".format(where))
                    finish()
                }
            }
            .show()
    }

    private fun proceed() {
        if (!Compatibility.isAccessLocalNetworkPermissionGranted(this)) {
            // bez „Místní síť“ Android spojení na miniserver tiše zahodí
            localNetPermission.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
            return
        }
        fetch()
        finish()
    }

    private fun fetch() {
        val link = url ?: return
        url = null
        TwentyOneProvisioning.fetchAndApply(link) { reason ->
            coreContext.showFormattedRedToastEvent.postValue(
                Event(Pair(reason, R.drawable.warning_circle))
            )
        }
    }
}
