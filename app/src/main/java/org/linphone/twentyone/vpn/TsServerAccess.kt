/*
 * fork: stupeň přístupu přes privátní síť je jedna volba sdílená
 * s miniserverem — telefon ji tam zapíše a při otevření obrazovky přečte.
 *
 * Miniserver je v privátní síti: spojení jen přes TsHttp (ve vnitřním
 * režimu přes relé, nikdy přímo — token by jinak odešel výchozí sítí).
 */
package org.linphone.twentyone.vpn

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject
import org.linphone.core.tools.Log

object TsServerAccess {
    private const val TAG = "[Tunnel Access Sync]"
    private const val PORT = 8091
    private const val TIMEOUT_MS = 5000

    /** Nejdéle čekat na relé vnitřního tunelu (brána je jeho cílem vždy). */
    private const val RELAY_WAIT_MS = 5_000L

    enum class Result { OK, NO_TOKEN, NO_GATEWAY, UNAUTHORIZED, FAILED }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun toServer(access: TsPreferences.TunnelAccess): String = when (access) {
        TsPreferences.TunnelAccess.APP_ONLY -> "phone"
        TsPreferences.TunnelAccess.SERVER -> "endpoint"
        TsPreferences.TunnelAccess.INTERNET -> "router"
    }

    private fun fromServer(level: String): TsPreferences.TunnelAccess? = when (level) {
        "phone" -> TsPreferences.TunnelAccess.APP_ONLY
        "endpoint" -> TsPreferences.TunnelAccess.SERVER
        "router" -> TsPreferences.TunnelAccess.INTERNET
        else -> null
    }

    /** Zapíše stupeň na miniserver; výsledek přijde na hlavním vlákně. */
    fun push(
        prefs: TsPreferences,
        access: TsPreferences.TunnelAccess,
        done: (Result) -> Unit
    ) {
        call(prefs, "POST", "level=" + toServer(access)) { result, _ -> done(result) }
    }

    /** Přečte stupeň z miniserveru; výsledek přijde na hlavním vlákně. */
    fun pull(prefs: TsPreferences, done: (Result, TsPreferences.TunnelAccess?) -> Unit) {
        call(prefs, "GET", null, done)
    }

    private fun call(
        prefs: TsPreferences,
        method: String,
        body: String?,
        done: (Result, TsPreferences.TunnelAccess?) -> Unit
    ) {
        val token = prefs.accessToken
        val gateway = prefs.gatewayAddress
        if (token.isEmpty()) {
            main.post { done(Result.NO_TOKEN, null) }
            return
        }
        if (gateway.isEmpty()) {
            main.post { done(Result.NO_GATEWAY, null) }
            return
        }
        worker.execute {
            var result = Result.FAILED
            var level: TsPreferences.TunnelAccess? = null
            var conn: HttpURLConnection? = null
            try {
                val host = if (gateway.contains(':')) "[$gateway]" else gateway
                conn = TsHttp.open(URL("http://$host:$PORT/pristup"), RELAY_WAIT_MS)
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.requestMethod = method
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                when (TsHttp.responseCode(conn, "stupeň přístupu")) {
                    200 -> {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        level = fromServer(JSONObject(text).optString("level"))
                        if (level != null) result = Result.OK
                    }
                    401 -> result = Result.UNAUTHORIZED
                }
            } catch (e: TsHttp.RelayException) {
                // zapsáno do deníku (P21-E23/E24) v TsHttp
                Log.w("$TAG $method refused by the relay [${e.status}]")
            } catch (e: Exception) {
                Log.w("$TAG $method failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-NET", "stupeň přístupu na miniserveru: %s %s".format(method, result)
            )
            main.post { done(result, level) }
        }
    }
}
