/*
 * fork: běh na pozadí. Bez výjimky z optimalizace baterie umí systém
 * aplikaci ve spánku telefonu uspat a hovor nedozvoní (push notifikace
 * upstreamu nepoužíváme). Systémový dialog se nabídne jednou po nastavení
 * účtu; stav se dá kdykoli zkontrolovat na obrazovce Konexe.
 */
package org.linphone.twentyone

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object TwentyOneBattery {

    private const val PREFS = "twentyone_battery"
    private const val KEY_ASKED = "asked"

    fun isUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Systémový dialog „Povolit běh na pozadí?" — vyžaduje aktivitu. */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:" + context.packageName)
        }

    /** Nabídne dialog jednou po nastavení účtu; podruhé už jen na Konexi. */
    fun offerOnce(context: Context) {
        if (isUnrestricted(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        try {
            val intent = requestIntent(context)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            TwentyOneDiag.log("P21-CFG", "nabídnut běh na pozadí bez omezení")
        } catch (e: Exception) {
            TwentyOneDiag.log("P21-CFG", "dialog pro běh na pozadí systém nenabízí")
        }
    }
}
