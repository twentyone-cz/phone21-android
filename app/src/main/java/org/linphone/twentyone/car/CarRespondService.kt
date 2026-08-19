/*
 * fork: „odmítnout hovor zprávou" ze systému (RESPOND_VIA_MESSAGE).
 * Odesílání zpráv jde u nás přes SIP bránu, ne přes SmsManager —
 * systémový požadavek se jen zaznamená a nic se neposílá. (P21-CAR)
 */
package org.linphone.twentyone.car

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.linphone.twentyone.TwentyOneDiag

class CarRespondService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TwentyOneDiag.log("P21-CAR", "RESPOND_VIA_MESSAGE ignorováno (cíl ${intent?.dataString})")
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
