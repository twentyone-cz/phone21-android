/*
 * fork: nastavení tunelu. Vlastní soubor předvoleb, aby se nesahalo do
 * CorePreferences (menší diff při rebasi).
 */
package org.linphone.twentyone.vpn

import android.content.Context

class TsPreferences(context: Context) {
    companion object {
        private const val PREFS_FILE = "twentyone_tunnel_settings"
        private const val KEY_CONTROL_URL = "control_url"
        private const val KEY_TUNNEL_SCOPE = "tunnel_scope"
        private const val KEY_EXIT_NODE_ID = "exit_node_id"

        const val DEFAULT_CONTROL_URL = "https://cockscale.twentyone.cz"
    }

    enum class TunnelScope {
        APP_ONLY,
        FULL
    }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var controlUrl: String
        get() = prefs.getString(KEY_CONTROL_URL, DEFAULT_CONTROL_URL).orEmpty()
        set(value) = prefs.edit().putString(KEY_CONTROL_URL, value).apply()

    var tunnelScope: TunnelScope
        get() = when (prefs.getString(KEY_TUNNEL_SCOPE, TunnelScope.APP_ONLY.name)) {
            TunnelScope.FULL.name -> TunnelScope.FULL
            else -> TunnelScope.APP_ONLY
        }
        set(value) = prefs.edit().putString(KEY_TUNNEL_SCOPE, value.name).apply()

    var exitNodeId: String
        get() = prefs.getString(KEY_EXIT_NODE_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_EXIT_NODE_ID, value).apply()
}
