/*
 * fork: deník chyb. Hláška na obrazovce zmizí dřív, než ji jde opsat —
 * tady zůstane kód chyby i s detaily (čas, cíl, cesta), takže „byla tam E5"
 * stačí a zbytek se dá dohledat zpětně.
 *
 * Dva soubory: hlavní deník (chyby, pády, konfigurace) a provozní síťový
 * (P21-REG/P21-NET). Odděleně proto, že flapující registrace umí vyrobit
 * desítky záznamů za minutu a z jednoho kruhového bufferu by vytlačila
 * přesně ty řádky, kvůli kterým deník existuje (P21-CRASH, P21-NATIVE).
 * Opakuje-li se stejný záznam po sobě, jen se u něj zvedá počítadlo (×N).
 */
package org.linphone.twentyone

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext

object TwentyOneDiag {
    private const val MAX_LINES = 150
    private const val MAX_NET_LINES = 60
    private val lock = Any()

    private val stampRe =
        Regex("""^\d{2}\.\d{2}\. \d{2}:\d{2}:\d{2}  (.*?)(?: \(×(\d+)\))?$""")

    private fun file(): File = File(coreContext.context.filesDir, "diag.log")

    private fun netFile(): File = File(coreContext.context.filesDir, "diag-net.log")

    /** Zapíše událost; [code] je P21-Exx, [detail] proměnné části. */
    fun log(code: String, detail: String) {
        val net = code == "P21-REG" || code == "P21-NET"
        synchronized(lock) {
            try {
                append(
                    if (net) netFile() else file(),
                    if (net) MAX_NET_LINES else MAX_LINES,
                    code,
                    detail
                )
            } catch (_: Exception) {
                // deník nesmí nikdy shodit aplikaci
            }
        }
    }

    private fun append(f: File, maxLines: Int, code: String, detail: String) {
        val stamp = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.ROOT).format(Date())
        val payload = "[$code]  $detail"
        val lines = (if (f.exists()) f.readLines() else emptyList())
            .takeLast(maxLines - 1)
            .toMutableList()
        val last = lines.lastOrNull()?.let { stampRe.matchEntire(it) }
        if (last != null && last.groupValues[1] == payload) {
            val n = (last.groupValues[2].toIntOrNull() ?: 1) + 1
            lines[lines.size - 1] = "$stamp  $payload (×$n)"
        } else {
            lines.add("$stamp  $payload")
        }
        f.writeText(lines.joinToString("\n") + "\n")
    }

    fun read(): String {
        synchronized(lock) {
            return try {
                val main = file().let { if (it.exists()) it.readLines() else emptyList() }
                val net = netFile().let { if (it.exists()) it.readLines() else emptyList() }
                buildString {
                    append(main.reversed().joinToString("\n"))
                    if (net.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append("— provoz sítě —\n")
                        append(net.reversed().joinToString("\n"))
                    }
                }
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            file().delete()
            netFile().delete()
        }
    }

    /** Oprávnění „Místní síť" nejde na některých systémech vyžádat oknem —
     *  jediná cesta je ruční přepnutí v nastavení aplikace. */
    fun openAppSettings(context: android.content.Context) {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
