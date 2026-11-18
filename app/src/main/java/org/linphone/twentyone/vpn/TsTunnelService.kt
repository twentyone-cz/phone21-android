/*
 * fork: služba v popředí pro vnitřní tunel („jen hovory“ bez systémové
 * VPN). Drží proces naživu, aby příchozí hovor dorazil i ve spánku;
 * samotný tunel i relé běží v knihovně tunelu uvnitř procesu.
 *
 * Akce:
 *  - START: běž (vnitřní režim řídí TsModeController),
 *  - STOP: uživatel tunel vypnul (WantRunning=false, žádost stažena),
 *  - HANDOVER: přepnutí na systémovou VPN — jen zhasnout, NIC nevypínat
 *    (žádost o vnitřní režim zůstává jako záloha pod systémovou VPN),
 *  - bez záměru (systém službu vzkřísil po zabití procesu): pokračovat,
 *    jen když je pořád vnitřní režim (i záložní při miniserveru/síti)
 *    a uživatel tunel chce.
 *
 * Počítadlo instancí se mění až v onCreate/onDestroy na hlavním vlákně,
 * vlákno jádra ale jedná hned po startu: proto [isStartPending] (start
 * odeslán, instance ještě nevznikla) a stopSelf(startId) u HANDOVER/STOP
 * (novější START se nespolkne).
 */
package org.linphone.twentyone.vpn

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.atomic.AtomicInteger
import org.linphone.core.tools.Log
import org.linphone.twentyone.TwentyOneDiag

class TsTunnelService : Service() {
    companion object {
        private const val TAG = "[Tunnel Internal Service]"

        const val ACTION_START = "cz.twentyone.phone.INTERNAL_TUNNEL_START"
        const val ACTION_STOP = "cz.twentyone.phone.INTERNAL_TUNNEL_STOP"
        const val ACTION_HANDOVER = "cz.twentyone.phone.INTERNAL_TUNNEL_HANDOVER"

        private val instances = AtomicInteger(0)

        @Volatile
        private var startPending = false

        /** Žije instance služby? (Posílat STOP/HANDOVER jen živé službě —
         *  jinak by ji záměr teprve vytvořil.) */
        fun isRunning(): Boolean = instances.get() > 0

        /** START odešel a instance ještě nevznikla: STOP/HANDOVER poslat
         *  i tak — systém ho doručí po STARTu téže instanci. */
        fun isStartPending(): Boolean = startPending

        fun markStartPending() {
            startPending = true
        }
    }

    private var guardOk = true
    private var startFailed = false

    /** Tahle instance končí předáním (HANDOVER) — viz onDestroy. */
    private var handedOver = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instances.incrementAndGet()
        startPending = false
        // stejná pojistka proti smyčce pád→vzkříšení→pád jako u služby VPN
        guardOk = TunnelStartGuard.registerSystemStart(applicationContext)
        if (guardOk) startBackend()
    }

    private fun startBackend() {
        try {
            TsManager.ensureStarted(applicationContext)
        } catch (e: Throwable) {
            Log.e("$TAG Failed to start tunnel backend: $e")
            TwentyOneDiag.log(
                "P21-E13",
                "start jádra tunelu selhal: %s %s".format(e.javaClass.simpleName, e.message ?: "")
            )
            startFailed = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            TunnelStartGuard.reset(applicationContext)
            if (!guardOk && !startFailed) {
                guardOk = true
                startBackend()
            }
        }
        if ((!guardOk && intent == null) || startFailed) {
            // povinnost po startForegroundService splnit a hned zhasnout
            startForegroundNotification()
            quit()
            return START_NOT_STICKY
        }
        return when (intent?.action) {
            ACTION_STOP -> {
                Log.i("$TAG Stopped by the user")
                TsManager.setWantRunning(false)
                TsRelay.stopInternal()
                quit(startId)
                START_NOT_STICKY
            }
            ACTION_HANDOVER -> {
                Log.i("$TAG Handing over to the system VPN")
                handedOver = true
                quit(startId)
                START_NOT_STICKY
            }
            null -> {
                startForegroundNotification()
                // Zrcadlo WantRunning i uložený režim přežívají zabití
                // procesu (TsPreferences). Pokračovat i v záložním vnitřním
                // režimu u miniserveru/sítě (jiná VPN, ztracené místo, bez
                // souhlasu) — tam je požadovaný režim systémový, ale hovory
                // jedou vnitřním tunelem. Požadovaný vnitřní pokrývá
                // aktualizaci z verze bez uloženého režimu.
                val want = TsManager.wantsRunning()
                if (want && (
                        TsModeController.modeInUse() == TsModeController.Mode.INTERNAL ||
                            TsModeController.desiredAccessMode(applicationContext) ==
                            TsModeController.Mode.INTERNAL
                        )
                ) {
                    TwentyOneDiag.log("P21-TUN", "služba vnitřního tunelu vzkříšena systémem po zabití procesu")
                    TsModeController.reconcile("vzkříšení služby vnitřního tunelu")
                    START_STICKY
                } else {
                    TwentyOneDiag.log(
                        "P21-TUN", "služba vnitřního tunelu vzkříšena, ale vnitřní tunel teď nemá běžet — končím")
                    TsModeController.reconcile("vzkříšení služby vnitřního tunelu")
                    quit()
                    START_NOT_STICKY
                }
            }
            else -> {
                // START po HANDOVER téže instanci (stopSelf(startId) ji nechal
                // žít): už nekončí předáním
                handedOver = false
                startForegroundNotification()
                // Start, který předběhl vypnutí (Odpojit/odhlášení ve stejném
                // kroku vlákna jádra), nesmí nechat běžet službu bez tunelu.
                if (!TsManager.wantsRunning()) {
                    Log.i("$TAG Started, but the tunnel is not wanted any more")
                    quit(startId)
                    return START_NOT_STICKY
                }
                Log.i("$TAG Running")
                START_STICKY
            }
        }
    }

    private fun startForegroundNotification() {
        try {
            startForeground(
                TsNotifications.INTERNAL_NOTIFICATION_ID,
                TsNotifications.buildStatusNotification(this, TsModeController.Mode.INTERNAL)
            )
        } catch (e: Exception) {
            Log.e("$TAG Failed to start foreground service: $e")
            TwentyOneDiag.log(
                "P21-E12",
                "služba vnitřního tunelu nenaběhla na popředí: %s %s".format(
                    e.javaClass.simpleName, e.message ?: "")
            )
        }
    }

    /** [startId] = skončit jen tehdy, když po tomhle pokynu nepřišel
     *  novější START (ten pak službu drží dál). */
    private fun quit(startId: Int? = null) {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    override fun onDestroy() {
        instances.decrementAndGet()
        // Vnitřní režim se tu NEVYPÍNÁ: STOP ho stáhl sám, předání ho
        // nechává jako zálohu pod systémovou VPN. Stav cesty hlásí relé.
        // Po předání se mohl režim mezitím vrátit na vnitřní a start nové
        // instance se přeskočil (tahle se ještě počítala).
        if (handedOver) TsModeController.onTunnelServiceHandedOver()
        super.onDestroy()
    }
}
