/*
 * fork: JVM testy extrakce stopy z nativního tombstonu (P21-NATIVE).
 * Čisté funkce nad bajty — jediná automatika, kterou si bez zařízení
 * můžeme dovolit, a zároveň jediné místo, kde by tichá chyba znamenala
 * slepou diagnostiku pádů.
 */
package org.linphone.twentyone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwentyOneExitInfoTest {

    /** Poskládá pseudobinární proud: řetězce prokládané ne-ASCII bajty,
     *  jako v protobuf tombstonu. */
    private fun tombstone(vararg strings: String): ByteArray {
        val out = ArrayList<Byte>()
        for (s in strings) {
            out.add(0x08)
            out.add(0x96.toByte())
            for (b in s.toByteArray(Charsets.US_ASCII)) out.add(b)
            out.add(0x00)
        }
        return out.toByteArray()
    }

    @Test
    fun printableRunsSplitsOnBinaryAndHonoursMinLength() {
        val runs = TwentyOneExitInfo.printableRuns(tombstone("abc", "SIGSEGV"), 4)
        assertFalse(runs.contains("abc"))
        assertTrue(runs.any { it.contains("SIGSEGV") })
    }

    @Test
    fun extractKeepsSignalsLibsAndAbortMessage() {
        val data = tombstone(
            "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)",
            "backtrace:",
            "/data/app/lib/arm64/libgojni.so (offset 0x1234)",
            "nezajimavy retezec bez klicovych slov",
            "Abort message: 'runtime: unexpected fault address'"
        )
        val out = TwentyOneExitInfo.extractInteresting(data)
        assertTrue(out.contains("SIGSEGV"))
        assertTrue(out.contains("SEGV_MAPERR"))
        assertTrue(out.contains("libgojni.so"))
        assertTrue(out.contains("runtime"))
        assertFalse(out.contains("nezajimavy"))
    }

    @Test
    fun extractDeduplicatesAndRespectsLimit() {
        val frames = Array(100) { "/lib/libgojni.so frame $it" }
        val out = TwentyOneExitInfo.extractInteresting(
            tombstone("SIGABRT", *frames, "SIGABRT")
        )
        assertTrue(out.length <= 700)
        assertEquals(1, Regex("SIGABRT").findAll(out).count())
    }

    @Test
    fun extractSurvivesEmptyAndPureBinaryInput() {
        assertEquals("", TwentyOneExitInfo.extractInteresting(ByteArray(0)))
        assertEquals(
            "",
            TwentyOneExitInfo.extractInteresting(ByteArray(64) { 0x01 })
        )
    }

    @Test
    fun signalNamesCoverDiagnosticTriad() {
        assertEquals("SIGSEGV", TwentyOneExitInfo.signalName(11))
        assertEquals("SIGABRT", TwentyOneExitInfo.signalName(6))
        assertEquals("SIGSYS", TwentyOneExitInfo.signalName(31))
        assertEquals("sig42", TwentyOneExitInfo.signalName(42))
    }
}
