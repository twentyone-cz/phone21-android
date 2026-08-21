/*
 * fork: nastavení tunelu. Vlastní soubor předvoleb, aby se nesahalo do
 * CorePreferences (menší diff při rebasi).
 */
package org.linphone.twentyone.vpn

import android.content.Context
import org.linphone.BuildConfig

class TsPreferences(context: Context) {
    companion object {
        private const val PREFS_FILE = "twentyone_tunnel_settings"
        private const val KEY_TUNNEL_SCOPE = "tunnel_scope"
        private const val KEY_TUNNEL_ACCESS = "tunnel_access"
        private const val KEY_CONTACTS_SYNC = "contacts_sync_via_tunnel"
        private const val KEY_EXIT_NODE_ID = "exit_node_id"
    }

    /**
     * Co všechno smí do tunelu. Výchozí stupeň (jen Phone21) není volba —
     * bez něj by aplikace nefungovala.
     */
    enum class TunnelAccess {
        /** jen Phone21 (výchozí) */
        APP_ONLY,
        /** všechny aplikace na miniserver — do tunelu jdou jen jeho adresy */
        SERVER,
        /** navíc všechen provoz telefonu ven přes miniserver */
        INTERNET
    }

    @Deprecated("nahrazeno TunnelAccess")
    enum class TunnelScope {
        APP_ONLY,
        FULL
    }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    val controlUrl: String
        get() = BuildConfig.NETWORK_COORDINATOR

    val networkDomain: String
        get() = BuildConfig.NETWORK_DOMAIN

    /** Zvolený stupeň; starý přepínač rozsahu se přebírá (FULL = přístup). */
    var tunnelAccess: TunnelAccess
        get() {
            val stored = prefs.getString(KEY_TUNNEL_ACCESS, null)
            if (stored != null) {
                return runCatching { TunnelAccess.valueOf(stored) }
                    .getOrDefault(TunnelAccess.APP_ONLY)
            }
            return if (prefs.getString(KEY_TUNNEL_SCOPE, "") == "FULL") {
                TunnelAccess.SERVER
            } else {
                TunnelAccess.APP_ONLY
            }
        }
        set(value) = prefs.edit().putString(KEY_TUNNEL_ACCESS, value.name).apply()

    /** Pustit do privátní sítě i synchronizační aplikaci kontaktů. */
    var contactsSyncViaTunnel: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS_SYNC, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTACTS_SYNC, value).apply()

    var exitNodeId: String
        get() = prefs.getString(KEY_EXIT_NODE_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_EXIT_NODE_ID, value).apply()
}
