/*
 * fork: role výchozí SMS aplikace (P21-CAR). Auto si čte zprávy přes
 * Bluetooth ze systémového úložiště SMS — zapisovat do něj smí jen
 * výchozí SMS aplikace, proto o roli žádáme.
 */
package org.linphone.twentyone.car

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import org.linphone.R
import org.linphone.twentyone.TwentyOneDiag

object CarSmsRole {

    /** Jsme výchozí SMS aplikace? */
    fun isHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_SMS)
    }

    /** Text řádku v nastavení podle stavu role. */
    fun titleRes(context: Context): Int =
        if (isHeld(context)) R.string.twentyone_car_sms_title_active
        else R.string.twentyone_car_sms_title

    /** Intent systémového okna „nastavit jako výchozí aplikaci pro SMS";
     *  null = nejde nebo není třeba (uživateli to vysvětlí toast). */
    fun requestIntent(context: Context): android.content.Intent? {
        val rm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
        } else {
            null
        }
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
            TwentyOneDiag.log("P21-CAR", "role SMS není na tomhle zařízení k dispozici")
            Toast.makeText(
                context,
                R.string.twentyone_car_sms_unavailable,
                Toast.LENGTH_SHORT
            ).show()
            return null
        }
        if (rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            Toast.makeText(
                context,
                R.string.twentyone_car_sms_already,
                Toast.LENGTH_SHORT
            ).show()
            return null
        }
        TwentyOneDiag.log("P21-CAR", "žádost o roli výchozí SMS aplikace")
        return rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
    }
}
