/*
 * fork: příjem SMS přijaté na SIM v telefonu (SMS_DELIVER chodí jen výchozí
 * SMS aplikaci): uloží se do systémového úložiště a ukáže se upozornění.
 * (P21-CAR)
 */
package org.linphone.twentyone.car

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.linphone.twentyone.TwentyOneDiag

class CarSmsReceiver : BroadcastReceiver() {
    companion object {
        private const val SIM_SMS_CHANNEL = "twentyone_sim_sms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) {
            TwentyOneDiag.log("P21-CAR", "SMS_DELIVER bez obsahu")
            return
        }
        // vícedílná SMS přijde v jednom intentu jako pole dílů
        val number = parts.first()?.displayOriginatingAddress.orEmpty()
        val body = parts.joinToString("") { it?.messageBody.orEmpty() }
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, number)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, parts.first()?.timestampMillis ?: 0L)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            notifySimSms(context, number, body)
            if (uri != null) {
                TwentyOneDiag.log("P21-CAR", "SMS ze sítě uložena (${body.length} zn.)")
            } else {
                saveFallback(context, number, body)
                TwentyOneDiag.log(
                    "P21-CAR",
                    "SMS ze sítě se neuložila (systém zápis odmítl) — záloha v datech aplikace"
                )
            }
        } catch (e: Exception) {
            // zpráva se nesmí ztratit — tělo jde do zálohy v datech aplikace,
            // do deníku NE (deník se kopíruje pro podporu)
            saveFallback(context, number, body)
            notifySimSms(context, number, body)
            TwentyOneDiag.log(
                "P21-CAR",
                "SMS ze sítě se neuložila (${e.javaClass.simpleName}, ${body.length} zn.) — " +
                    "záloha v datech aplikace"
            )
        }
    }

    /** Upozornění na SMS přijatou na SIM v telefonu; klepnutí otevře
     *  systémovou aplikaci pro zprávy. */
    private fun notifySimSms(context: Context, number: String, body: String) {
        try {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            val name = context.getString(org.linphone.R.string.twentyone_sim_sms_channel)
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    SIM_SMS_CHANNEL, name, android.app.NotificationManager.IMPORTANCE_HIGH
                )
            )
            val open = Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = android.app.PendingIntent.getActivity(
                context, 0, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = androidx.core.app.NotificationCompat.Builder(context, SIM_SMS_CHANNEL)
                .setSmallIcon(org.linphone.R.drawable.linphone_notification)
                .setContentTitle(
                    context.getString(org.linphone.R.string.twentyone_sim_sms_title, number)
                )
                .setContentText(body)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
        } catch (e: Exception) {
            TwentyOneDiag.log("P21-CAR", "upozornění na SMS ze SIM selhalo: ${e.javaClass.simpleName}")
        }
    }

    private fun saveFallback(context: Context, number: String, body: String) {
        try {
            java.io.File(context.filesDir, "car-sms-zaloha.txt").appendText(
                "${System.currentTimeMillis()};$number;$body\n"
            )
        } catch (_: Exception) {
        }
    }
}
