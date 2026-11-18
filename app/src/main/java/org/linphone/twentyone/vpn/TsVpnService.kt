/*
 * fork: VpnService, kterou si drží zabudovaný tunel. Předloha:
 * tailscale-android IPNService.kt + VPNServiceBuilder.kt (BSD-3-Clause).
 *
 * Kdo smí do tunelu, řídí TsPreferences.tunnelAccess:
 *  - APP_ONLY (výchozí): jen tahle aplikace (addAllowedApplication),
 *    volitelně i synchronizace kontaktů,
 *  - SERVER: všechny aplikace; trasy do tunelu jsou pořád jen adresy
 *    privátní sítě, ostatní provoz jde mimo něj,
 *  - INTERNET: navíc výstupní uzel, takže tunelem jde i obecný provoz.
 *
 * APP_ONLY tu jede už jen se synchronizací kontaktů přes tunel; samotné
 * „jen hovory“ běží vnitřním tunelem bez systémové VPN (TsTunnelService,
 * TsModeController). Přepnutí na něj = ACTION_HANDOVER: služba se zavře,
 * ale WantRunning zůstává a knihovna tunelu převezme vnitřní režim.
 */
package org.linphone.twentyone.vpn

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import java.net.InetAddress
import java.util.UUID
import org.linphone.core.tools.Log
import org.linphone.ui.main.MainActivity

class TsVpnService : VpnService(), libtailscale.IPNService {
    companion object {
        private const val TAG = "[Tunnel Service]"

        const val ACTION_START = "cz.twentyone.phone.TUNNEL_START"
        const val ACTION_STOP = "cz.twentyone.phone.TUNNEL_STOP"
        const val ACTION_RESTART = "cz.twentyone.phone.TUNNEL_RESTART"

        /** Předání vnitřnímu tunelu: zavřít bez WantRunning=false. */
        const val ACTION_HANDOVER = "cz.twentyone.phone.TUNNEL_HANDOVER"

        private val instances = java.util.concurrent.atomic.AtomicInteger(0)

        /** Žije instance služby? (Posílat STOP/HANDOVER jen živé službě —
         *  jinak by ji záměr teprve vytvořil.) */
        fun isRunning(): Boolean = instances.get() > 0
    }

    private val randomId: String = UUID.randomUUID().toString()
    private var closed = false
    private var guardOk = true
    private var startFailed = false

    /** Výsledek posledního Builder.establish(): null = zatím žádný, false =
     *  vrátil null (místo pro VPN drží jiná aplikace / souhlas odebrán).
     *  Knihovna tunelu hlásí updateVpnStatus(true) DŘÍV, než zkontroluje, že
     *  establish něco vrátil — bez téhle stráže by appka krátce tvrdila, že
     *  rozhraní stojí. */
    @Volatile
    private var establishOk: Boolean? = null

    /** Systém tuhle instanci odvolal (onRevoke, binder vlákno). Knihovna
     *  tunelu volá establish a updateVpnStatus z vlastního vlákna, takže
     *  úspěch establish, který doběhl až po odvolání, nesmí ztracené místo
     *  zrušit. Nuluje ho jen nový pokyn uživatele (requestInterface). */
    @Volatile
    private var revoked = false

    override fun id(): String = randomId

    override fun onCreate() {
        super.onCreate()
        instances.incrementAndGet()
        // Pojistka proti smyčce pád→vzkříšení→pád: vzkříšení systémem se
        // počítá; ruční start počítadlo hned v onStartCommand vynuluje.
        guardOk = TunnelStartGuard.registerSystemStart(applicationContext)
        if (guardOk) {
            startBackend()
        }
    }

    private fun startBackend() {
        try {
            TsManager.ensureStarted(applicationContext)
        } catch (e: Throwable) {
            Log.e("$TAG Failed to start tunnel backend: $e")
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-E13",
                "start jádra tunelu selhal: %s %s".format(
                    e.javaClass.simpleName, e.message ?: "")
            )
            startFailed = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            // explicitní pokyn (uživatel/aplikace) — smyčková pojistka se
            // týká jen vzkříšení systémem
            TunnelStartGuard.reset(applicationContext)
            if (!guardOk && !startFailed) {
                guardOk = true
                startBackend()
            }
        }
        if ((!guardOk && intent == null) || startFailed) {
            // povinnost po startForegroundService splnit, ale hned zase
            // zhasnout — služba se nechá ležet (žádné další křísení)
            startForegroundNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return when (intent?.action) {
            ACTION_STOP -> {
                if (TsManager.wantsRunning() &&
                    TsModeController.desired() == TsModeController.Mode.INTERNAL
                ) {
                    // Přepnutí na vnitřní tunel nesmí zapsat WantRunning=false
                    // (to by byl stav „Zakázáno“) — jen předat.
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN", "vypnutí služby VPN při vnitřním režimu = předání")
                    close()
                    return START_NOT_STICKY
                }
                TsManager.setWantRunning(false)
                // žádost o vnitřní režim pryč DŘÍV, než se služba odpojí —
                // jinak by knihovna tunel převzala do vnitřního režimu
                TsRelay.stopInternal()
                close()
                START_NOT_STICKY
            }
            ACTION_HANDOVER -> {
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-TUN", "služba VPN předává tunel vnitřnímu režimu")
                close()
                START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // jen ukončit TUHLE instanci; novou startuje TsManager
                // s odstupem. Dřívější reset `closed` na téže instanci vedl
                // k druhému serviceDisconnect nad Go stranou až PO jejím
                // opětovném zaregistrování — tunel po přestavbě zůstal dole.
                close()
                START_NOT_STICKY
            }
            null -> {
                startForegroundNotification()
                TsManager.setWantRunning(true)
                if (TsModeController.desiredAccessMode(applicationContext) !=
                    TsModeController.Mode.SYSTEM
                ) {
                    // Typicky po aktualizaci na verzi s vnitřním tunelem při
                    // „jen hovory“: systémové místo se uvolní, rozhraní se
                    // vůbec nežádá a tunel převezme vnitřní režim.
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN",
                        "služba VPN vzkříšena systémem, ale stupeň jede vnitřním tunelem — systémové místo uvolněno")
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {
                    }
                    stopSelf()
                    TsModeController.reconcile("vzkříšení služby VPN")
                    return START_NOT_STICKY
                }
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-TUN", "služba vzkříšena systémem po zabití procesu")
                libtailscale.Libtailscale.requestVPN(this)
                START_STICKY
            }
            else -> {
                startForegroundNotification()
                if (TsModeController.desiredAccessMode(applicationContext) !=
                    TsModeController.Mode.SYSTEM
                ) {
                    // Opožděný nebo zatoulaný start (automatický start během
                    // přepnutí na „jen hovory“, přestavba s odstupem): stupeň
                    // jede vnitřním tunelem. Rozhraní nežádat — knihovna by
                    // mu dala přednost, relé by nenaběhlo a hovory by stály.
                    // Jako vzkříšení výš: místo uvolnit, WantRunning nechat.
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN",
                        "start služby VPN, ale stupeň jede vnitřním tunelem — rozhraní nežádám, systémové místo uvolněno")
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {
                    }
                    stopSelf()
                    TsModeController.reconcile("start služby VPN mimo systémový stupeň")
                    return START_NOT_STICKY
                }
                warnIfLockdown()
                TsManager.setWantRunning(true)
                // stopa do deníku: bez ní nejde rozlišit „služba vůbec
                // nenastartovala" od „rozhraní se nepostavilo"
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-TUN", "služba běží, žádám o rozhraní")
                requestInterface()
                START_STICKY
            }
        }
    }

    /**
     * Po neúspěšném establish zůstává TAHLE instance v knihovně tunelu
     * zaregistrovaná, poslední konfigurace se nezapsala a nový requestVPN se
     * stejným ID knihovna ignoruje („still the same VPN instance“) — návrat
     * na pokyn uživatele by pak nic nepostavil. Proto se nejdřív odregistruje
     * (CloseTUNs smaže poslední konfiguraci) a pak přihlásí znovu: rozhraní
     * se staví nanovo s uloženou konfigurací.
     */
    private fun requestInterface() {
        revoked = false
        if (establishOk == false && !closed) {
            Log.w("$TAG Last establish failed, re-registering this instance")
            libtailscale.Libtailscale.serviceDisconnect(this)
            establishOk = null
        }
        libtailscale.Libtailscale.requestVPN(this)
    }

    /** Systémové „Blokovat připojení bez VPN" u tunelu, který vede jen
     *  telefonování, odřízne zbytek telefonu od internetu. Aplikace tu
     *  volbu neumí změnit — jen ji poznat a nahlas na ni upozornit. */
    private fun warnIfLockdown() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            TsManager.lockdownDetected = isLockdownEnabled
            if (isLockdownEnabled) {
                org.linphone.twentyone.TwentyOneDiag.log(
                    "P21-E15",
                    "systémové „Blokovat připojení bez VPN“ je zapnuté — " +
                        "mimo telefonování odřízne telefon od internetu"
                )
                TsNotifications.notifyLockdownWarning(this)
            }
        } catch (_: Exception) {
        }
    }

    private fun startForegroundNotification() {
        try {
            startForeground(
                TsNotifications.NOTIFICATION_ID,
                TsNotifications.buildStatusNotification(this, TsModeController.Mode.SYSTEM)
            )
        } catch (e: Exception) {
            Log.e("$TAG Failed to start foreground service: $e")
            org.linphone.twentyone.TwentyOneDiag.log(
                "P21-E12",
                "služba tunelu nenaběhla na popředí: %s %s".format(
                    e.javaClass.simpleName, e.message ?: "")
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        TsManager.onServiceClosing()
        disconnectVPN()
        libtailscale.Libtailscale.serviceDisconnect(this)
    }

    override fun disconnectVPN() {
        stopSelf()
    }

    override fun updateVpnStatus(status: Boolean) {
        if (status && (revoked || establishOk == false)) {
            Log.w("$TAG Ignoring VPN status [up], last establish failed or VPN was revoked")
            return
        }
        TsManager.onVpnStatusChanged(status)
        if (status) {
            // Ztracené místo pro VPN se ruší JEN tady: rozhraní opravdu stojí.
            // Podmínka se ověří znovu pod zámkem — odvolání, které přišlo
            // mezitím, vyhraje.
            TsManager.clearSlotLost(applicationContext) { !revoked && establishOk == true }
            // onRevoke doběhl mezi kontrolou nahoře a ohlášením: nenechat
            // viset „rozhraní stojí“
            if (revoked) TsManager.onVpnStatusChanged(false)
        }
    }

    /** Volá TsVpnBuilder synchronně, ještě než establish() vrátí výsledek. */
    private fun onEstablishResult(ok: Boolean) {
        establishOk = ok && !revoked
        if (!ok) {
            Log.e("$TAG Builder.establish() returned null, VPN slot is not ours")
            TsManager.markSlotLost(
                applicationContext, "rozhraní nejde postavit (establish vrátil nic)")
        }
    }

    override fun onDestroy() {
        close()
        updateVpnStatus(false)
        instances.decrementAndGet()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Opožděné updateVpnStatus(true) z knihovny (establish proběhl těsně
        // před odebráním) nesmí ztracené místo hned zase zrušit.
        revoked = true
        establishOk = false
        // PRVNÍ: od téhle chvíle si appka místo pro VPN sama nevezme
        // (jinak by ho přes VpnService.prepare() potichu sebrala jiné VPN)
        TsManager.markSlotLost(
            applicationContext, "systém odebral rozhraní (jiná VPN nebo vypnutí v nastavení)")
        // Hovory jedou dál vnitřním tunelem (knihovna ho má vyžádaný jako
        // zálohu a po odpojení služby ho převezme sama) — WantRunning=false
        // by z toho udělal stav „Zakázáno“. Jen bez zálohy jako dřív.
        if (!TsModeController.keepsCallsWithoutSystemVpn()) {
            TsManager.setWantRunning(false)
        }
        close()
        updateVpnStatus(false)
        super.onRevoke()
    }

    private fun configIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun newBuilder(): libtailscale.VPNServiceBuilder {
        val builder = Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.setUnderlyingNetworks(null)

        // tunnelAccessForBuild: tady rozhraní opravdu vzniká, takže právě tady
        // se zaznamená stupeň, se kterým je postavené (accessInUse)
        val access = TsManager.tunnelAccessForBuild()
        if (access == TsPreferences.TunnelAccess.APP_ONLY) {
            try {
                builder.addAllowedApplication(packageName)
                Log.i("$TAG Tunnel restricted to [$packageName]")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e("$TAG Failed to restrict tunnel to own package: $e")
            }
            // do tunelu smí i synchronizace kontaktů, když si to uživatel přeje
            if (TsManager.prefs.contactsSyncViaTunnel) {
                val sync = org.linphone.twentyone.contacts.TwentyOneContactsTarget.SYNC_APP
                try {
                    builder.addAllowedApplication(sync)
                    org.linphone.twentyone.TwentyOneDiag.log(
                        "P21-TUN",
                        "tunel staví i pro synchronizaci kontaktů"
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w("$TAG Sync app not installed, tunnel stays app-only")
                }
            }
        } else {
            Log.i("$TAG Tunnel is open to all apps (access=$access)")
        }

        return TsVpnBuilder(builder) { ok -> onEstablishResult(ok) }
    }
}

private class TsVpnBuilder(
    private val builder: VpnService.Builder,
    /** výsledek establish() (true = rozhraní vzniklo), hlášený PŘED návratem */
    private val onEstablished: (Boolean) -> Unit
) : libtailscale.VPNServiceBuilder {
    override fun addAddress(address: String, prefixLength: Int) {
        builder.addAddress(address, prefixLength)
    }

    override fun addDNSServer(server: String) {
        builder.addDnsServer(server)
    }

    override fun addRoute(route: String, prefixLength: Int) {
        builder.addRoute(route, prefixLength)
    }

    override fun excludeRoute(route: String, prefixLength: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.excludeRoute(IpPrefix(InetAddress.getByName(route), prefixLength))
        }
    }

    override fun addSearchDomain(domain: String) {
        builder.addSearchDomain(domain)
    }

    override fun setMTU(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun establish(): libtailscale.ParcelFileDescriptor? {
        val pfd = builder.establish()
        onEstablished(pfd != null)
        return pfd?.let { TsParcelFileDescriptor(it) }
    }
}

private class TsParcelFileDescriptor(private val fd: android.os.ParcelFileDescriptor) :
    libtailscale.ParcelFileDescriptor {
    override fun detach(): Int = fd.detachFd()
}
