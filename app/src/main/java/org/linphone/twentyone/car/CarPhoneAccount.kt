/*
 * fork: účet pro volání (managed PhoneAccount, CALL_PROVIDER, tel:).
 * Do systému se přidá jen při zapnuté volbě; povolit ho pak musí
 * uživatel ručně v Telefonu → Účty pro volání. Registrace vlastního
 * CALL_PROVIDER účtu žádné oprávnění nevyžaduje.
 */
package org.linphone.twentyone.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import org.linphone.R
import org.linphone.twentyone.TwentyOneDiag

object CarPhoneAccount {
    private const val ACCOUNT_ID = "twentyone_car"

    private fun handle(context: Context) = PhoneAccountHandle(
        ComponentName(context, CarConnectionService::class.java),
        ACCOUNT_ID
    )

    private fun telecom(context: Context) =
        context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    /** Přidá účet do systému (vypnutý, povoluje uživatel). Vrací úspěch. */
    fun register(context: Context): Boolean {
        return try {
            val account = PhoneAccount.builder(
                handle(context),
                context.getString(R.string.twentyone_car_account_label)
            )
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()
            telecom(context).registerPhoneAccount(account)
            TwentyOneDiag.log("P21-CAR", "účet pro volání zaregistrován")
            true
        } catch (e: Exception) {
            TwentyOneDiag.log("P21-CAR", "registrace účtu selhala: $e")
            false
        }
    }

    fun unregister(context: Context) {
        try {
            telecom(context).unregisterPhoneAccount(handle(context))
            TwentyOneDiag.log("P21-CAR", "účet pro volání odebrán")
        } catch (e: Exception) {
            TwentyOneDiag.log("P21-CAR", "odebrání účtu selhalo: $e")
        }
    }

    /** Otevře systémovou obrazovku Účty pro volání. */
    fun openCallingAccounts(context: Context) {
        try {
            context.startActivity(
                Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // obrazovka nemusí existovat (zařízení bez telecom funkce)
            TwentyOneDiag.log("P21-CAR", "účty pro volání nejde otevřít: $e")
        }
    }
}
