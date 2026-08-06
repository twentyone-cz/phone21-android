/*
 * fork: VpnService, kterou si drží zabudovaný tunel. Předloha:
 * tailscale-android IPNService.kt + VPNServiceBuilder.kt (BSD-3-Clause).
 *
 * Rozsah tunelu se řídí TsPreferences.tunnelScope:
 *  - APP_ONLY: do tunelu jde jen provoz této aplikace (addAllowedApplication),
 *  - FULL: celý telefon, s exit node na krabičce.
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
    }

    private val randomId: String = UUID.randomUUID().toString()
    private var closed = false

    override fun id(): String = randomId

    override fun onCreate() {
        super.onCreate()
        TsManager.ensureStarted(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                TsManager.setWantRunning(false)
                close()
                START_NOT_STICKY
            }
            ACTION_RESTART -> {
                close()
                closed = false
                startForegroundNotification()
                libtailscale.Libtailscale.requestVPN(this)
                START_STICKY
            }
            else -> {
                startForegroundNotification()
                TsManager.setWantRunning(true)
                libtailscale.Libtailscale.requestVPN(this)
                START_STICKY
            }
        }
    }

    private fun startForegroundNotification() {
        try {
            startForeground(
                TsNotifications.NOTIFICATION_ID,
                TsNotifications.buildStatusNotification(this)
            )
        } catch (e: Exception) {
            Log.e("$TAG Failed to start foreground service: $e")
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
        TsManager.onVpnStatusChanged(status)
    }

    override fun onDestroy() {
        close()
        updateVpnStatus(false)
        super.onDestroy()
    }

    override fun onRevoke() {
        TsManager.setWantRunning(false)
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

        val scope = TsManager.effectiveTunnelScope()
        if (scope == TsPreferences.TunnelScope.APP_ONLY) {
            try {
                builder.addAllowedApplication(packageName)
                Log.i("$TAG Tunnel restricted to [$packageName]")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e("$TAG Failed to restrict tunnel to own package: $e")
            }
        } else {
            Log.i("$TAG Tunnel covers the whole device")
        }

        return TsVpnBuilder(builder)
    }
}

private class TsVpnBuilder(private val builder: VpnService.Builder) :
    libtailscale.VPNServiceBuilder {
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
        return builder.establish()?.let { TsParcelFileDescriptor(it) }
    }
}

private class TsParcelFileDescriptor(private val fd: android.os.ParcelFileDescriptor) :
    libtailscale.ParcelFileDescriptor {
    override fun detach(): Int = fd.detachFd()
}
