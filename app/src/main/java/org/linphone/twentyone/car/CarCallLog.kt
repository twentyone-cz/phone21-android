/*
 * fork: zrcadlení ukončených hovorů do systémové historie (CallLog.Calls).
 * Aplikace vede hovory jen ve vlastní databázi — autorádio (Bluetooth PBAP)
 * ale čte systémovou historii telefonu, bez zrcadla je seznam volání
 * v autě prázdný. Vlastní soubor předvoleb po vzoru TsPreferences.
 */
package org.linphone.twentyone.car

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog.Calls
import org.json.JSONArray
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.twentyone.TwentyOneDiag

object CarCallLog {
    private const val PREFS_FILE = "twentyone_car_calllog"
    private const val KEY_ENABLED = "mirror_enabled"
    private const val KEY_MIRRORED_IDS = "mirrored_ids"
    private const val MAX_REMEMBERED_IDS = 20

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** Zapnuto přepínačem a zároveň uděleno oprávnění. */
    fun isActive(context: Context): Boolean =
        isEnabled(context) && hasPermission(context)

    /** Zrcadlí jeden ukončený hovor; volá se z onCallLogUpdated na vlákně Core. */
    fun mirror(callLog: CallLog, context: Context) {
        try {
            if (!isActive(context)) return
            if (callLog.wasConference()) return

            val id = callLog.callId ?: callLog.refKey ?: return
            if (alreadyMirrored(context, id)) return

            val type = when {
                callLog.dir == Call.Dir.Outgoing -> Calls.OUTGOING_TYPE
                callLog.status == Call.Status.Success -> Calls.INCOMING_TYPE
                callLog.status == Call.Status.Declined ||
                    callLog.status == Call.Status.DeclinedElsewhere -> Calls.REJECTED_TYPE
                callLog.status == Call.Status.AcceptedElsewhere ->
                    Calls.ANSWERED_EXTERNALLY_TYPE
                // Missed, Aborted, EarlyAborted — nezvednuté příchozí
                else -> Calls.MISSED_TYPE
            }
            val missed = type == Calls.MISSED_TYPE

            val values = ContentValues().apply {
                put(
                    Calls.NUMBER,
                    callLog.remoteAddress.username
                        ?: callLog.remoteAddress.asStringUriOnly()
                )
                put(Calls.TYPE, type)
                // startDate je v sekundách, sloupec DATE chce milisekundy
                put(Calls.DATE, callLog.startDate * 1000)
                // duration i sloupec DURATION jsou v sekundách
                put(Calls.DURATION, callLog.duration.toLong())
                put(Calls.NEW, if (missed) 1 else 0)
                put(Calls.IS_READ, if (missed) 0 else 1)
            }

            val uri = context.contentResolver.insert(Calls.CONTENT_URI, values)
            if (uri == null) {
                TwentyOneDiag.log("P21-CAR", "systém záznam hovoru nepřijal")
                return
            }
            rememberMirrored(context, id)
        } catch (e: Exception) {
            // zrcadlo nesmí nikdy shodit hovorové vlákno
            TwentyOneDiag.log(
                "P21-CAR",
                "zápis do systémové historie selhal: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun alreadyMirrored(context: Context, id: String): Boolean {
        val arr = storedIds(context)
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == id) return true
        }
        return false
    }

    private fun rememberMirrored(context: Context, id: String) {
        val old = storedIds(context)
        val trimmed = JSONArray()
        val from = maxOf(0, old.length() - (MAX_REMEMBERED_IDS - 1))
        for (i in from until old.length()) {
            trimmed.put(old.optString(i))
        }
        trimmed.put(id)
        prefs(context).edit().putString(KEY_MIRRORED_IDS, trimmed.toString()).apply()
    }

    private fun storedIds(context: Context): JSONArray {
        val stored = prefs(context).getString(KEY_MIRRORED_IDS, "[]").orEmpty()
        return try {
            JSONArray(stored)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
