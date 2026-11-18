/*
 * fork: trvalá notifikace služby tunelu (Android ji u VpnService vyžaduje)
 * a výstrahy: blokace mimo VPN, ztracené místo pro VPN.
 */
package org.linphone.twentyone.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.linphone.R
import org.linphone.ui.main.MainActivity

object TsNotifications {
    const val NOTIFICATION_ID = 42
    const val LOCKDOWN_NOTIFICATION_ID = 43
    const val SLOT_LOST_NOTIFICATION_ID = 44
    private const val CHANNEL_ID = "twentyone_tunnel"

    /** Výstrahy (ztracené místo pro VPN). Stavový kanál výš zůstává tichý
     *  (LOW); tahle zpráva ale znamená, že nechodí hovory, a musí být vidět. */
    private const val ALERT_CHANNEL_ID = "twentyone_tunnel_alert"

    fun createChannel(context: Context) {
        val name = context.getString(R.string.twentyone_tunnel_notification_channel)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
        channel.description = name
        channel.setShowBadge(false)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun buildStatusNotification(context: Context): Notification {
        // kanál se mohl mezitím smazat (první start jádra po instalaci maže všechny)
        createChannel(context)
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
        createChannel(context)
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

    private fun createAlertChannel(context: Context) {
        val name = context.getString(R.string.twentyone_tunnel_alert_channel)
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = name
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /** Titulek a text podle toho, jestli je vidět VPN jiné aplikace. */
    private fun slotLostTexts(context: Context): Pair<String, String> {
        return if (TsManager.otherVpnActive(context)) {
            context.getString(R.string.twentyone_slot_lost_other_vpn_title) to
                context.getString(R.string.twentyone_slot_lost_other_vpn_message)
        } else {
            context.getString(R.string.twentyone_slot_lost_title) to
                context.getString(R.string.twentyone_slot_lost_message)
        }
    }

    /**
     * Phone21 přišlo o místo pro VPN. Posílá se JEDNOU, při přechodu do
     * stavu „místo ztraceno“ ([silent] = jen tiché přepsání textu).
     * Tlačítko „Zapnout Phone21“ vede přes TsConsentActivity, která se před
     * převzetím místa od jiné VPN ještě zeptá.
     */
    fun notifySlotLost(context: Context, silent: Boolean = false) {
        createAlertChannel(context)
        val (title, message) = slotLostTexts(context)
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reclaim = PendingIntent.getActivity(
            context,
            3,
            Intent(context, TsConsentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(TsConsentActivity.EXTRA_RECLAIM, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.linphone_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setContentIntent(open)
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    4,
                    Intent(context, TsSlotLostDismissReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(0, context.getString(R.string.twentyone_slot_lost_action), reclaim)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(SLOT_LOST_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    /**
     * Pravidelná kontrola: znovu NEPOSÍLÁ. Jen když zpráva pořád visí a jiná
     * VPN se mezitím ukázala (nebo zmizela), tiše přepíše text. Zprávu, kterou
     * uživatel odsunul, nevrací.
     */
    fun refreshSlotLostWording(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            val shown = manager.activeNotifications
                .firstOrNull { it.id == SLOT_LOST_NOTIFICATION_ID } ?: return
            val shownTitle = shown.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (shownTitle != slotLostTexts(context).first) {
                notifySlotLost(context, silent = true)
            }
        } catch (_: Exception) {
        }
    }

    fun isSlotLostShown(context: Context): Boolean {
        return try {
            context.getSystemService(NotificationManager::class.java)
                .activeNotifications.any { it.id == SLOT_LOST_NOTIFICATION_ID }
        } catch (_: Exception) {
            false
        }
    }

    fun cancelSlotLost(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)
                .cancel(SLOT_LOST_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}

/**
 * Uživatel výstrahu o ztraceném místě pro VPN odsunul (deleteIntent). Systém
 * tenhle záměr NEposílá, když notifikaci maže sám (restart telefonu,
 * aktualizace appky, násilné zastavení) — takovou výstrahu appka při startu
 * vrátí (TsManager.remindSlotLost), odsunutou do restartu telefonu ne.
 */
class TsSlotLostDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            TsPreferences(context).slotLostDismissedBoot = TsManager.bootId(context)
        } catch (_: Exception) {
        }
    }
}
