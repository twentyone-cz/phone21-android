/*
 * fork: stupeň přístupu přes privátní síť se volí JEN v telefonu
 * a miniserver ho jen ukazuje (rozhodnutí uživatele 2026-11-18; ovládání
 * miniserveru od 0.9.47 jen zobrazuje). Telefon je jediný zdroj pravdy:
 * stupeň na miniserver zapíše a při náběhu cesty nebo otevření obrazovky
 * ověří, že tam pořád je — rozdíl (reinstalace miniserveru, starší
 * ovládání s tlačítky) přepíše stupněm z telefonu, nikdy obráceně.
 *
 * Miniserver je v privátní síti: spojení jen přes TsHttp (ve vnitřním
 * režimu přes relé, nikdy přímo — token by jinak odešel výchozí sítí).
 *
 * Zápis se nesmí ztratit: změna stupně obvykle přepíná i cestu k
 * miniserveru (vnitřní tunel ↔ systémová VPN) a zápis odeslaný hned po
 * přepnutí padal do přestavby (test2 21p.45, 18. 11. 2026: telefon na
 * „miniserver“, miniserver zůstal na „jen hovory“ a filtr telefon na web
 * nepustil). Proto: volba se napřed uloží jako čekající
 * (TsPreferences.accessPushPending), zápis počká, až cesta stojí, při
 * neúspěchu se opakuje a čekající volbu zkouší znovu i běžící tunel
 * (TsManager), dokud neprojde. Průběh ukazuje obrazovka ([syncState]).
 */
package org.linphone.twentyone.vpn

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.linphone.core.tools.Log
import org.linphone.twentyone.TwentyOneDiag

object TsServerAccess {
    private const val TAG = "[Tunnel Access Sync]"
    private const val PORT = 8091
    private const val TIMEOUT_MS = 5000

    /** Nejdéle čekat na relé vnitřního tunelu (brána je jeho cílem vždy). */
    private const val RELAY_WAIT_MS = 5_000L

    /** První pokus až po rozběhu přepnutí režimu (posluchač předvoleb ho
     *  pošle na vlákno jádra) — jinak by šel starou cestou, která se boří. */
    private const val FIRST_DELAY_MS = 1_000L

    /** Po změně stupně zkoušet tak dlouho (obrazovka mezitím ukazuje
     *  zápis); pak volba čeká a zapíše ji [reconcile]. */
    private const val PUSH_WINDOW_MS = 30_000L

    /** Jak často se dívat, jestli už cesta stojí. */
    private const val PATH_POLL_MS = 1_000L

    /** Pauzy mezi neúspěšnými pokusy uvnitř okna (poslední se opakuje). */
    private val RETRY_MS = longArrayOf(2_000L, 4_000L, 8_000L)

    /** Čekající volbu na pozadí zkoušet nejvýš jednou za tuhle dobu. */
    private const val BACKGROUND_RETRY_MS = 60_000L

    /** Ověření stupně na miniserveru nejvýš jednou za tuhle dobu. */
    private const val VERIFY_MS = 60_000L

    enum class Result { OK, NO_TOKEN, NO_GATEWAY, UNAUTHORIZED, FAILED }

    /** Co obrazovka ukazuje u volby přístupu. */
    enum class SyncState {
        /** Miniserver má stupeň z telefonu (nebo se to nedá zjistit). */
        SYNCED,

        /** Zapisuje se po změně (kolečko, volby zašedlé). */
        WRITING,

        /** Zápis zatím neprošel, zkusí se znovu sám. */
        PENDING
    }

    /** Změna [syncState] — obrazovka si stav přečte znovu. */
    val syncChanged = MutableLiveData<SyncState>()

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Každá změna stupně v telefonu = nová generace; starší pokusy končí. */
    private val generation = AtomicInteger()

    /** Generace, jejíž zápis po změně právě běží (0 = žádný). */
    @Volatile
    private var writingGen = 0

    @Volatile
    private var backgroundTriedAt = 0L

    @Volatile
    private var verifiedAt = 0L

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

    fun syncState(prefs: TsPreferences): SyncState = when {
        writingGen != 0 && writingGen == generation.get() -> SyncState.WRITING
        hasPending(prefs) -> SyncState.PENDING
        else -> SyncState.SYNCED
    }

    private fun notifyState(prefs: TsPreferences) {
        syncChanged.postValue(syncState(prefs))
    }

    /** Čeká v telefonu volba, která na miniserver ještě nedošla? */
    fun hasPending(prefs: TsPreferences): Boolean = pending(prefs) != null

    private fun pending(prefs: TsPreferences): TsPreferences.TunnelAccess? {
        val name = prefs.accessPushPending
        if (name.isEmpty()) return null
        return runCatching { TsPreferences.TunnelAccess.valueOf(name) }.getOrNull()
    }

    /** Volba došla (nebo zápis nemá smysl opakovat): už nečeká — jen když
     *  ji mezitím nepřepsala novější. */
    private fun clearPending(prefs: TsPreferences, access: TsPreferences.TunnelAccess) {
        synchronized(this) {
            if (prefs.accessPushPending == access.name) prefs.accessPushPending = ""
        }
    }

    /** Cesta k miniserveru stojí a režim se zrovna nepřepíná. */
    private fun pathReady(): Boolean =
        !TsModeController.isSwitching() && TsManager.isTunnelUp()

    /**
     * Zapíše stupeň na miniserver: uloží ho jako čekající, počká na cestu
     * a při neúspěchu to zkouší znovu. Mezitím [SyncState.WRITING].
     * [done] přijde na hlavním vlákně jednou — po zápisu, nebo když se do
     * [PUSH_WINDOW_MS] nepovedl (pak volba čeká dál a zapíše ji
     * [reconcile]). Novější změna stupně starší pokusy ukončí bez [done].
     */
    fun push(
        prefs: TsPreferences,
        access: TsPreferences.TunnelAccess,
        done: (Result) -> Unit
    ) {
        val gen = synchronized(this) {
            prefs.accessPushPending = access.name
            generation.incrementAndGet()
        }
        writingGen = gen
        notifyState(prefs)
        val started = SystemClock.elapsedRealtime()
        worker.schedule(
            Runnable { attempt(prefs, gen, started, 0, done) },
            FIRST_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun attempt(
        prefs: TsPreferences,
        gen: Int,
        started: Long,
        failures: Int,
        done: (Result) -> Unit
    ) {
        if (gen != generation.get()) return
        val access = pending(prefs) ?: return deliver(prefs, gen, Result.OK, done)
        val elapsed = SystemClock.elapsedRealtime() - started
        if (!pathReady()) {
            // vypnutý tunel nenaběhne sám — kolečko by se jen točilo
            if (elapsed < PUSH_WINDOW_MS && TsManager.wantsRunning()) {
                worker.schedule(
                    Runnable { attempt(prefs, gen, started, failures, done) },
                    PATH_POLL_MS,
                    TimeUnit.MILLISECONDS
                )
                return
            }
            TwentyOneDiag.log(
                "P21-NET",
                "stupeň přístupu %s: cesta k miniserveru nestojí, zápis čeká na spojení".format(access)
            )
            return deliver(prefs, gen, Result.FAILED, done)
        }
        val (result, _) = request(prefs, "POST", "level=" + toServer(access))
        when (result) {
            Result.OK -> {
                clearPending(prefs, access)
                verifiedAt = SystemClock.elapsedRealtime()
                if (failures > 0) {
                    TwentyOneDiag.log(
                        "P21-NET",
                        "stupeň přístupu %s zapsán na miniserver (%d. pokus)".format(access, failures + 1)
                    )
                }
                deliver(prefs, gen, result, done)
            }
            // bez tokenu / s neplatným tokenem opakování nepomůže (a další
            // pokusy by jen plnily strop na miniserveru) — po novém QR
            // uživatel stupeň přepne znovu
            Result.NO_TOKEN, Result.NO_GATEWAY, Result.UNAUTHORIZED -> {
                clearPending(prefs, access)
                deliver(prefs, gen, result, done)
            }
            Result.FAILED -> {
                val pause = RETRY_MS[minOf(failures, RETRY_MS.size - 1)]
                if (elapsed + pause < PUSH_WINDOW_MS) {
                    worker.schedule(
                        Runnable { attempt(prefs, gen, started, failures + 1, done) },
                        pause,
                        TimeUnit.MILLISECONDS
                    )
                } else {
                    TwentyOneDiag.log(
                        "P21-NET",
                        "stupeň přístupu %s se na miniserver nezapsal, zkusí se znovu".format(access)
                    )
                    deliver(prefs, gen, result, done)
                }
            }
        }
    }

    private fun deliver(prefs: TsPreferences, gen: Int, result: Result, done: (Result) -> Unit) {
        if (gen != generation.get()) return
        if (writingGen == gen) writingGen = 0
        notifyState(prefs)
        main.post { done(result) }
    }

    /**
     * Srovnat miniserver s telefonem (z libovolného vlákna):
     * - čekající volba: zkusit ji zapsat (nejvýš jednou za
     *   [BACKGROUND_RETRY_MS]),
     * - jinak s [verify] přečíst stupeň z miniserveru (nejvýš jednou za
     *   [VERIFY_MS]) a když se liší, zapsat ten z telefonu.
     * Nic nedělá, když cesta nestojí nebo běží zápis po změně stupně.
     */
    fun reconcile(prefs: TsPreferences, verify: Boolean) {
        if (writingGen != 0 && writingGen == generation.get()) return
        if (!pathReady()) return
        val now = SystemClock.elapsedRealtime()
        if (hasPending(prefs)) {
            if (backgroundTriedAt != 0L && now - backgroundTriedAt < BACKGROUND_RETRY_MS) return
            backgroundTriedAt = now
            val gen = generation.get()
            worker.execute { retryOnce(prefs, gen) }
            return
        }
        if (!verify) return
        if (verifiedAt != 0L && now - verifiedAt < VERIFY_MS) return
        verifiedAt = now
        val gen = generation.get()
        worker.execute {
            val (result, level) = request(prefs, "GET", null)
            if (result != Result.OK || level == null) return@execute
            // mezitím změna v telefonu: ta se zapisuje sama
            if (gen != generation.get() || hasPending(prefs)) return@execute
            val mine = prefs.tunnelAccess
            if (level == mine) return@execute
            TwentyOneDiag.log(
                "P21-NET",
                "miniserver má stupeň přístupu %s, telefon %s — zapisuji stupeň z telefonu".format(level, mine)
            )
            synchronized(this) {
                if (gen != generation.get() || hasPending(prefs)) return@execute
                prefs.accessPushPending = mine.name
            }
            notifyState(prefs)
            retryOnce(prefs, gen)
        }
    }

    /** Jeden pokus o zápis čekající volby (pracovní vlákno). */
    private fun retryOnce(prefs: TsPreferences, gen: Int) {
        if (gen != generation.get()) return
        val access = pending(prefs) ?: return
        val (result, _) = request(prefs, "POST", "level=" + toServer(access))
        when (result) {
            Result.OK -> {
                clearPending(prefs, access)
                verifiedAt = SystemClock.elapsedRealtime()
                TwentyOneDiag.log(
                    "P21-NET", "čekající stupeň přístupu %s zapsán na miniserver".format(access)
                )
            }
            Result.NO_TOKEN, Result.NO_GATEWAY, Result.UNAUTHORIZED ->
                clearPending(prefs, access)
            Result.FAILED -> {}
        }
        notifyState(prefs)
    }

    /** Jeden požadavek na miniserver (z pracovního vlákna). */
    private fun request(
        prefs: TsPreferences,
        method: String,
        body: String?
    ): Pair<Result, TsPreferences.TunnelAccess?> {
        val token = prefs.accessToken
        val gateway = prefs.gatewayAddress
        if (token.isEmpty()) return Result.NO_TOKEN to null
        if (gateway.isEmpty()) return Result.NO_GATEWAY to null
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
        TwentyOneDiag.log(
            "P21-NET", "stupeň přístupu na miniserveru: %s %s".format(method, result)
        )
        return result to level
    }
}
