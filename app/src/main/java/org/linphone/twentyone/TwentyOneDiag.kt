/*
 * fork: deník chyb. Hláška na obrazovce zmizí dřív, než ji jde opsat —
 * tady zůstane kód chyby i s detaily (čas, cíl, cesta), takže „byla tam E5"
 * stačí a zbytek se dá dohledat zpětně.
 */
package org.linphone.twentyone

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext

object TwentyOneDiag {
    private const val MAX_LINES = 100
    private val lock = Any()

    private fun file(): File = File(coreContext.context.filesDir, "diag.log")

    /** Zapíše událost; [code] je P21-Exx, [detail] proměnné části. */
    fun log(code: String, detail: String) {
        val stamp = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.ROOT).format(Date())
        synchronized(lock) {
            try {
                val f = file()
                val lines = (if (f.exists()) f.readLines() else emptyList())
                    .takeLast(MAX_LINES - 1)
                f.writeText((lines + "$stamp  [$code]  $detail")
                    .joinToString("\n") + "\n")
            } catch (_: Exception) {
                // deník nesmí nikdy shodit aplikaci
            }
        }
    }

    fun read(): String {
        synchronized(lock) {
            return try {
                val f = file()
                if (f.exists()) f.readLines().reversed().joinToString("\n")
                else ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            file().delete()
        }
    }
}
