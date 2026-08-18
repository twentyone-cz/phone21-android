/*
 * fork: pojistka proti smyčce restartů služby tunelu. Po nativním pádu
 * (START_STICKY) systém službu křísí — když knihovna tunelu padá hned po
 * startu, telefon by kroužil pád→vzkříšení→pád, žral baterii a přepisoval
 * deník. Počítadlo startů v krátkém okně to utne a zapíše P21-E13.
 */
package org.linphone.twentyone.vpn

import android.content.Context
import org.linphone.twentyone.TwentyOneDiag

object TunnelStartGuard {
    private const val PREFS = "twentyone_tunnel_guard"
    private const val KEY_WINDOW = "window_start"
    private const val KEY_COUNT = "start_count"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_STARTS = 4

    /**
     * Započítá vzkříšení služby systémem. Vrací false, když se to v okně
     * děje pořád dokola — služba se pak má nechat ležet (START_NOT_STICKY),
     * dokud ji uživatel nespustí ručně (ruční start jde přes [reset]).
     */
    fun registerSystemStart(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var windowStart = p.getLong(KEY_WINDOW, 0L)
        var count = p.getInt(KEY_COUNT, 0)
        if (now - windowStart > WINDOW_MS) {
            windowStart = now
            count = 0
        }
        count++
        // commit synchronně — zápis musí přežít i okamžitý nativní pád
        p.edit().putLong(KEY_WINDOW, windowStart).putInt(KEY_COUNT, count).commit()
        if (count > MAX_STARTS) {
            TwentyOneDiag.log(
                "P21-E13",
                ("tunel opakovaně shodil aplikaci (%d startů za %d min) — " +
                    "služba se nechá ležet do ručního zapnutí").format(
                    count, WINDOW_MS / 60000)
            )
            return false
        }
        return true
    }

    /** Ruční akce uživatele nebo zdravě běžící tunel počítadlo nuluje. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_WINDOW, 0L).putInt(KEY_COUNT, 0).apply()
    }
}
