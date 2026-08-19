/*
 * fork: trvalá notifikace služby tunelu (Android ji u VpnService vyžaduje).
 */
package org.linphone.twentyone.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.linphone.R
import org.linphone.ui.main.MainActivity

object TsNotifications {
    const val NOTIFICATION_ID = 42
    const val LOCKDOWN_NOTIFICATION_ID = 43
    private const val CHANNEL_ID = "twentyone_tunnel"

    fun createChannel(context: Context) {
        val name = context.getString(R.string.twentyone_tunnel_notification_channel)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
        channel.description = name
        channel.setShowBadge(false)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun buildStatusNotification(context: Context): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.linphone_notification)
            .setContentTitle(context.getString(R.string.twentyone_tunnel_notification_title))
            .setContentText(context.getString(R.string.twentyone_tunnel_notification_message))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    /** Systémové „Blokovat připojení bez VPN" odřízne při našem tunelu
     *  (vede jen telefonování) zbytek telefonu od internetu. Aplikace to
     *  vypnout nemůže — aspoň na to nahlas upozorní a odvede uživatele
     *  přímo do nastavení VPN. */
    fun notifyLockdownWarning(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_VPN_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.linphone_notification)
            .setContentTitle(context.getString(R.string.twentyone_tunnel_lockdown_title))
            .setContentText(context.getString(R.string.twentyone_tunnel_lockdown_message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.twentyone_tunnel_lockdown_message)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(LOCKDOWN_NOTIFICATION_ID, notification)
    }
}
