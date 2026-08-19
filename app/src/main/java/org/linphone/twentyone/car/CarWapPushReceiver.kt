/*
 * fork: WAP push / MMS (WAP_PUSH_DELIVER chodí jen výchozí SMS aplikaci).
 * MMS neumíme stáhnout (telefon nemá SIM ani datové APN) — jen záznam do
 * deníku, nic se neukládá. (P21-CAR)
 */
package org.linphone.twentyone.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.linphone.twentyone.TwentyOneDiag

class CarWapPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val size = intent.getByteArrayExtra("data")?.size ?: 0
        TwentyOneDiag.log("P21-CAR", "WAP push (MMS) přijat, nezpracováváme ($size B, ${intent.type})")
    }
}
