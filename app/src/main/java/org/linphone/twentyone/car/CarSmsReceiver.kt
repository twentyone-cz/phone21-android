/*
 * fork: příjem skutečné SMS ze sítě (SMS_DELIVER chodí jen výchozí SMS
 * aplikaci). Telefon SIM nemá, ale role nás dělá zodpovědnými: kdyby SMS
 * přišla, uloží se do systémového úložiště, odkud ji uvidí auto i každá
 * budoucí SMS aplikace. (P21-CAR)
 */
package org.linphone.twentyone.car

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.linphone.twentyone.TwentyOneDiag

class CarSmsReceiver : BroadcastReceiver() {

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
            TwentyOneDiag.log(
                "P21-CAR",
                "SMS ze sítě se neuložila (${e.javaClass.simpleName}, ${body.length} zn.) — " +
                    "záloha v datech aplikace"
            )
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
