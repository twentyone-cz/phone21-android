/*
 * fork: zrcadlení chatu do systémového úložiště SMS (P21-CAR). Auto si přes
 * Bluetooth čte zprávy ze systémového úložiště — SIP zprávy z brány do něj
 * kopírujeme. Zápis systém dovolí jen výchozí SMS aplikaci, jinak se nedělá
 * nic. Nezrcadlí se prázdné a mizející zprávy a potvrzenky brány o odeslání
 * (auto by je hlásilo jako novou zprávu od člověka, který je nenapsal).
 */
package org.linphone.twentyone.car

import android.content.ContentValues
import android.net.Uri
import android.provider.Telephony
import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.ChatRoom
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.twentyone.TwentyOneDiag

object CarSmsMirror {

    // texty drží krok s potvrzenkami ústředny (exten report)
    private val receiptPrefixes = listOf(
        "✓ SMS odeslána do sítě",
        "✗ SMS se nepodařilo odeslat"
    )

    val coreListener = object : CoreListenerStub() {

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage?>
        ) {
            if (!CarSmsRole.isHeld(coreContext.context)) return
            for (message in messages) {
                if (message == null || message.isOutgoing || message.isEphemeral) continue
                val body = textOf(message) ?: continue
                if (receiptPrefixes.any { body.startsWith(it) }) continue
                val number = message.fromAddress.username ?: continue
                insert(Telephony.Sms.Inbox.CONTENT_URI, number, body, message.time, incoming = true)
            }
        }

        @WorkerThread
        override fun onMessageSent(core: Core, chatRoom: ChatRoom, message: ChatMessage) {
            if (!CarSmsRole.isHeld(coreContext.context)) return
            if (message.isEphemeral) return
            val body = textOf(message) ?: return
            val number = chatRoom.peerAddress.username ?: return
            // zapsat až po potvrzení doručení bráně — jinak by auto ukazovalo
            // jako odeslanou i zprávu, která nikdy neodešla
            message.addListener(object : ChatMessageListenerStub() {
                @WorkerThread
                override fun onMsgStateChanged(message: ChatMessage, state: ChatMessage.State?) {
                    when (state) {
                        ChatMessage.State.Delivered,
                        ChatMessage.State.DeliveredToUser,
                        ChatMessage.State.Displayed -> {
                            message.removeListener(this)
                            if (CarSmsRole.isHeld(coreContext.context)) {
                                insert(
                                    Telephony.Sms.Sent.CONTENT_URI,
                                    number,
                                    body,
                                    message.time,
                                    incoming = false
                                )
                            }
                        }
                        ChatMessage.State.NotDelivered -> message.removeListener(this)
                        else -> {}
                    }
                }
            })
        }
    }

    /** Textový obsah zprávy; null = nezrcadlit (prázdná / jen příloha). */
    private fun textOf(message: ChatMessage): String? {
        val text = message.contents.find { it.isText }?.utf8Text.orEmpty()
        return text.ifEmpty { null }
    }

    private fun insert(uri: Uri, number: String, body: String, timeSec: Long, incoming: Boolean) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, number)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timeSec * 1000) // Linphone čas je v sekundách
                put(Telephony.Sms.READ, if (incoming) 0 else 1)
                put(Telephony.Sms.SEEN, if (incoming) 0 else 1)
            }
            coreContext.context.contentResolver.insert(uri, values)
            // úspěch se neloguje: deník by za pár dní provozu přišel
            // o záznamy pádů a nesl by čísla protistran
        } catch (e: Exception) {
            TwentyOneDiag.log(
                "P21-CAR",
                "zápis do úložiště SMS selhal: ${e.javaClass.simpleName}"
            )
        }
    }
}
