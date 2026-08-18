/*
 * fork: čtení záznamů o ukončení procesu (ApplicationExitInfo). Nativní pád
 * (knihovna tunelu, Go/JNI) javový crash handler nevidí — proces umře pod
 * ním a v deníku nezbyde nic. Systém si ale důvod ukončení pamatuje a u
 * nativních pádů drží i tombstone; tady se při dalším startu přepíše do
 * deníku Diagnostiky jako P21-NATIVE. Uživatel tak pošle signál, chybový
 * kód i jméno knihovny bez adb.
 */
package org.linphone.twentyone

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TwentyOneExitInfo {
    private const val PREFS = "twentyone_diag"
    private const val KEY_LAST_TS = "last_exit_ts"
    private const val TRACE_READ_LIMIT = 256 * 1024
    private const val TRACE_LOG_LIMIT = 700

    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val thread = Thread {
            try {
                doCollect(context)
            } catch (_: Throwable) {
                // diagnostika nesmí nikdy shodit start aplikace
            }
        }
        thread.name = "p21-exitinfo"
        thread.isDaemon = true
        thread.start()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doCollect(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_TS, 0L)
        val records = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val fresh = records
            .filter { it.timestamp > lastSeen && wantReason(it.reason) }
            .sortedBy { it.timestamp }
            .takeLast(5)
        if (fresh.isEmpty()) return
        for (info in fresh) {
            logHeader(info)
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                info.reason == ApplicationExitInfo.REASON_ANR
            ) {
                logTrace(info)
            }
        }
        // dedup až po zápisu do deníku — kdyby zápis spadl, záznam se
        // zkusí znovu při příštím startu
        prefs.edit().putLong(KEY_LAST_TS, fresh.last().timestamp).commit()
    }

    /** Vědomá ukončení (uživatel, systémové úklidy) jsou šum — zajímají
     *  nás jen konce, které si aplikace nevybrala sama. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun wantReason(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_OTHER -> true
        else -> false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason$reason"
    }

    /** Jméno signálu rozhoduje o diagnóze: SIGABRT = abort běhového
     *  prostředí/alokátoru, SIGSEGV = paměť (MTESERR/MAPERR v stopě),
     *  SIGSYS = seccomp filtr systému. */
    fun signalName(status: Int): String = when (status) {
        4 -> "SIGILL"
        5 -> "SIGTRAP"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        31 -> "SIGSYS"
        else -> "sig$status"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun logHeader(info: ApplicationExitInfo) {
        val stamp = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.ROOT)
            .format(Date(info.timestamp))
        val sig = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            info.reason == ApplicationExitInfo.REASON_SIGNALED
        ) {
            " signál=%d/%s".format(info.status, signalName(info.status))
        } else {
            ""
        }
        TwentyOneDiag.log(
            "P21-NATIVE",
            "proces skončil %s důvod=%s%s %s".format(
                stamp, reasonName(info.reason), sig,
                info.description?.take(200) ?: ""
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun logTrace(info: ApplicationExitInfo) {
        val trace = try {
            info.traceInputStream?.use { extractInteresting(readLimited(it)) }
        } catch (_: Exception) {
            null
        }
        if (!trace.isNullOrBlank()) {
            TwentyOneDiag.log("P21-NATIVE", "stopa: $trace")
        }
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val buffer = ByteArray(TRACE_READ_LIMIT)
        var total = 0
        while (total < buffer.size) {
            val read = stream.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    /**
     * Tombstone je na API 31+ binární protobuf — místo strukturovaného
     * parseru se z proudu vytáhnou doslovně uložené řetězce a nechají se
     * jen ty vypovídající (signály, si_code, knihovny, abort message).
     * Čistá funkce nad bajty — pokrytá JVM testem.
     */
    fun extractInteresting(data: ByteArray): String {
        val keep = Regex(
            "SIG[A-Z]{2,}|SEGV_|BUS_[A-Z]+|ILL_[A-Z]+|SYS_[A-Z]+|SI_[A-Z]+|" +
                "\\.so\\b|abort|fatal|runtime|hardened|MTE|malloc|Cause|backtrace",
            RegexOption.IGNORE_CASE
        )
        val seen = LinkedHashSet<String>()
        var used = 0
        for (run in printableRuns(data, 4)) {
            val line = run.trim()
            if (line.isEmpty() || !keep.containsMatchIn(line)) continue
            if (!seen.add(line)) continue
            used += line.length + 3
            if (used > TRACE_LOG_LIMIT) break
        }
        return seen.joinToString(" | ").take(TRACE_LOG_LIMIT)
    }

    /** Běhy tisknutelných ASCII znaků délky aspoň [minLen]. */
    fun printableRuns(data: ByteArray, minLen: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                sb.append(c.toChar())
            } else {
                if (sb.length >= minLen) out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.length >= minLen) out.add(sb.toString())
        return out
    }
}
