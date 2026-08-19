/*
 * fork: vytáčení z auta. Klasická telecom služba jen jako „výhybka":
 * z požadavku vezme tel: číslo, hovor spustí přes Core (stejně jako
 * tel: odkaz v MainActivity.handleCallIntent) a systémový pokus hned
 * zruší (CANCELED — bez chybové hlášky). Skutečný hovor se do telecomu
 * přihlásí sám stávající cestou (TelecomManager.onCallCreated), takže
 * nevzniknou dvě reprezentace téhož hovoru.
 */
package org.linphone.twentyone.car

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.twentyone.TwentyOneDiag
import org.linphone.utils.LinphoneUtils

class CarConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val number = request?.address?.schemeSpecificPart
        if (number.isNullOrEmpty()) {
            TwentyOneDiag.log("P21-CAR", "požadavek na hovor bez čísla")
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.ERROR)
            )
        }

        // číslo do deníku nepatří celé (deník se kopíruje pro podporu)
        TwentyOneDiag.log(
            "P21-CAR",
            "vytáčení z auta (${number.length} zn., …${number.takeLast(3)})"
        )
        coreContext.postOnCoreThread { core ->
            val address = core.interpretUrl(
                number,
                LinphoneUtils.applyInternationalPrefix()
            )
            if (address != null) {
                // test dosažitelnosti sítě přeskočit — proces mohl
                // právě startovat (stejně jako u tel: odkazu)
                coreContext.startAudioCall(
                    address,
                    skipNetworkReachabilityTest = true
                )
            } else {
                TwentyOneDiag.log(
                    "P21-CAR",
                    "číslo nejde přeložit na adresu (${number.length} zn.)"
                )
            }
        }

        // systémový pokus zrušit — navázaný hovor převezme stávající
        // vrstva, auto ho uvidí stejně jako při ručním vytočení
        return Connection.createCanceledConnection()
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        TwentyOneDiag.log("P21-CAR", "systém odchozí hovor odmítl")
    }

    // příchozí tudy nechodí (addNewIncomingCall nikde nevoláme)
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        return Connection.createFailedConnection(
            DisconnectCause(DisconnectCause.ERROR)
        )
    }
}
