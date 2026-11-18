/*
 * fork: podrobný log uložený z Diagnostiky bez tajných hodnot.
 *
 * Log píše volací knihovna (a do něj i knihovna tunelu a náš kód přes
 * společný logger). Náš kód tajné věci nelogguje, ale co zapíše knihovna,
 * appka neovládá — hlavně celé zprávy signalizace: ve vnitřním režimu nesou
 * klíče šifrování hlasu k relé (SDP „a=crypto … inline:“). Proto se log při
 * ukládání do souboru, který odchází z telefonu, ještě jednou projde:
 *  - bloky soukromých klíčů PEM (-----BEGIN … PRIVATE KEY----- … END),
 *  - „Bearer <token>“ a hlavička tokenu webového relé,
 *  - klíče šifrování hlasu za „inline:“,
 *  - tajné hodnoty, které appka zná (token relé v paměti, token pro
 *    miniserver), kdekoli v textu.
 * Čte a píše po řádcích jako ISO-8859-1 (bajt = znak), takže zbytek logu
 * zůstane bajtově beze změny bez ohledu na kódování.
 */
package org.linphone.twentyone

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object TwentyOneLogScrub {
    // Náhrady jako bajty UTF-8 přečtené po znacích ISO-8859-1 (viz hlavička):
    // ve výstupu pak leží správné UTF-8.
    private val REDACTED = latin1("…")
    private val KEY_REDACTED = latin1("[soukromý klíč odstraněn]")

    private fun latin1(text: String): String =
        String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    private val beginKey = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""")
    private val endKey = Regex("""-----END [A-Z0-9 ]*PRIVATE KEY-----""")
    private val bearer = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]+""")
    private val relayHeader = Regex("""(?i)(X-Phone21-Relay\s*:\s*)\S+""")
    private val srtpInline = Regex("""(inline:)[A-Za-z0-9+/=]+(\|[0-9^:]+)*""")

    /** Kolik čeho se nahradilo (do deníku — bez hodnot). */
    class Result(var keys: Int = 0, var tokens: Int = 0, var srtp: Int = 0)

    /**
     * Zkopíruje log [source] do [out] bez tajných hodnot. [gzip] = zdroj
     * i cíl jsou komprimované (.gz z volací knihovny). [secrets] = známé
     * tajné řetězce, které se nahradí kdekoli (prázdné se ignorují).
     */
    fun copy(source: File, out: OutputStream, gzip: Boolean, secrets: List<String>): Result {
        val result = Result()
        val known = secrets.filter { it.length >= 8 }
        val input: InputStream = if (gzip) GZIPInputStream(source.inputStream()) else source.inputStream()
        val output: OutputStream = if (gzip) GZIPOutputStream(out) else out
        BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1)).use { reader ->
            OutputStreamWriter(output, Charsets.ISO_8859_1).use { writer ->
                var inKey = false
                while (true) {
                    val raw = reader.readLine() ?: break
                    var line = raw
                    if (inKey) {
                        val end = endKey.find(line) ?: continue
                        inKey = false
                        line = line.substring(end.range.last + 1)
                    }
                    line = scrubKeys(line, result) { inKey = true }
                    line = scrubLine(line, known, result)
                    writer.write(line)
                    writer.write("\n")
                }
                writer.flush()
            }
        }
        return result
    }

    /** Bloky soukromých klíčů v řádku; nekončí-li blok na řádku, [open]. */
    private fun scrubKeys(line: String, result: Result, open: () -> Unit): String {
        var rest = line
        val sb = StringBuilder()
        while (true) {
            val begin = beginKey.find(rest) ?: break
            result.keys++
            sb.append(rest, 0, begin.range.first).append(KEY_REDACTED)
            val after = rest.substring(begin.range.last + 1)
            val end = endKey.find(after)
            if (end == null) {
                open()
                return sb.toString()
            }
            rest = after.substring(end.range.last + 1)
        }
        return sb.append(rest).toString()
    }

    private fun scrubLine(line: String, known: List<String>, result: Result): String {
        var l = line
        for (secret in known) {
            if (l.contains(secret)) {
                result.tokens++
                l = l.replace(secret, REDACTED)
            }
        }
        if (bearer.containsMatchIn(l)) {
            result.tokens++
            l = bearer.replace(l, "$1$REDACTED")
        }
        if (relayHeader.containsMatchIn(l)) {
            result.tokens++
            l = relayHeader.replace(l, "$1$REDACTED")
        }
        if (srtpInline.containsMatchIn(l)) {
            result.srtp++
            l = srtpInline.replace(l, "$1$REDACTED")
        }
        return l
    }
}
