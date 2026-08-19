/*
 * fork: nastavení vytáčení z auta. Vlastní soubor předvoleb po vzoru
 * TsPreferences — do CorePreferences se nesahá (menší diff při rebasi).
 */
package org.linphone.twentyone.car

import android.content.Context

class CarPreferences(context: Context) {
    companion object {
        private const val PREFS_FILE = "twentyone_car_settings"
        private const val KEY_CAR_DIALING = "car_dialing_enabled"
    }

    private val prefs =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // výchozí VYPNUTO — účet do systému přibude jen na výslovné přání
    var carDialingEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAR_DIALING, false)
        set(value) = prefs.edit().putBoolean(KEY_CAR_DIALING, value).apply()
}
