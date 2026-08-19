/*
 * fork: identita hovoru pro systémový Telecom (Android Auto, hodinky, HFP).
 * Hovory přes bránu nesou v user-části SIP adresy telefonní číslo — když ho
 * poznáme, předáme systému „tel:" URI, aby číslo spároval s kontakty telefonu
 * a ukázal hovor jako běžný. Ostatní adresy zůstávají „sip:" jako dosud.
 */
package org.linphone.twentyone

import android.net.Uri
import androidx.core.net.toUri
import org.linphone.R
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.Conference
import org.linphone.core.Core
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils

object TwentyOneCar {
    /** Vizuální oddělovače čísel (RFC 3966: - . ( )) + mezery. */
    private val separators = Regex("""[\s\-.()]""")

    /** Telefonní číslo: volitelné +, pak 3–15 číslic. */
    private val phoneNumber = Regex("""^\+?[0-9]{3,15}$""")

    /**
     * URI hovoru pro CallAttributesCompat: „tel:<číslo>" pro číselné
     * user-části (po odstranění oddělovačů), jinak sip: adresa beze změny.
     */
    fun callAddressUri(address: Address): Uri {
        val number = address.username.orEmpty().replace(separators, "")
        return if (phoneNumber.matches(number)) {
            "tel:$number".toUri()
        } else {
            address.asStringUriOnly().toUri()
        }
    }

    /**
     * Najde/založí 1:1 konverzaci pro číslo (sms: odkaz, role SMS) a vrátí
     * její id pro navigaci; null při chybě. Volat na vlákně Core.
     * Zjednodušená větev createOneToOneChatRoomWith — konverzace u brány
     * jsou prosté (Basic), šifrovaná větev tu nemá co dělat.
     */
    fun conversationIdForNumber(core: Core, number: String): String? {
        val remote = core.interpretUrl(
            number,
            LinphoneUtils.applyInternationalPrefix()
        ) ?: return null
        val account = core.defaultAccount ?: return null
        val params = core.createConferenceParams(null)
        params.isChatEnabled = true
        params.isGroupEnabled = false
        params.subject = AppUtils.getString(R.string.conversation_one_to_one_hidden_subject)
        params.account = account
        val chatParams = params.chatParams ?: return null
        chatParams.ephemeralLifetime = 0
        chatParams.backend = ChatRoom.Backend.Basic
        params.securityLevel = Conference.SecurityLevel.None
        val participants = arrayOf(remote)
        val localAddress = account.params.identityAddress
        val chatRoom = core.searchChatRoom(params, localAddress, null, participants)
            ?: core.createChatRoom(params, participants)
            ?: return null
        return LinphoneUtils.getConversationId(chatRoom)
    }
}
