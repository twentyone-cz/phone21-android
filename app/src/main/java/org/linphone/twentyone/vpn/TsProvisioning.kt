/*
 * fork: jeden QR nastaví účet i síť. Provisioning z miniserveru může nést
 * sekci [twentyone] s adresou koordinátora a jednorázovým klíčem — ten se
 * použije hned a z konfigurace se smaže, ať nikde neleží.
 */
package org.linphone.twentyone.vpn

import androidx.annotation.WorkerThread
import org.linphone.core.Core
import org.linphone.core.tools.Log

object TsProvisioning {
    private const val TAG = "[Tunnel Provisioning]"
    private const val SECTION = "twentyone"
    private const val KEY_URL = "ts_control_url"
    private const val KEY_AUTH = "ts_authkey"
    private const val KEY_EXIT = "ts_exit_node"
    private const val KEY_ACCESS_TOKEN = "pristup_token"

    /**
     * Adresa brány v privátní síti ze zrovna aplikovaného provisioningu.
     *
     * Čte se NEZÁVISLE na klíči: brána adresu posílá i tehdy, když se klíč
     * telefonu nepodařilo získat. Po přečtení se položka hned maže —
     * zápis prázdné hodnoty ji v liblinphone opravdu odstraní, takže
     * příští QR ji přepíše i bez atributu overwrite.
     */
    @WorkerThread
    fun takeExitNodeAddress(core: Core): String {
        val config = core.config ?: return ""
        val address = config.getString(SECTION, KEY_EXIT, "").orEmpty().trim()
        if (address.isEmpty()) return ""
        config.setString(SECTION, KEY_EXIT, "")
        config.sync()
        Log.i("$TAG Gateway address found in provisioning")
        return address
    }

    /** Token pro stupeň přístupu na miniserveru; z konfigurace se hned maže. */
    @WorkerThread
    fun takeAccessToken(core: Core): String {
        val config = core.config ?: return ""
        val token = config.getString(SECTION, KEY_ACCESS_TOKEN, "").orEmpty().trim()
        if (token.isEmpty()) return ""
        config.setString(SECTION, KEY_ACCESS_TOKEN, "")
        config.sync()
        return token
    }

    /** Klíč ze zrovna aplikovaného provisioningu, nebo prázdno. */
    @WorkerThread
    fun takeNetworkKey(core: Core): Pair<String, String> {
        val config = core.config ?: return "" to ""
        val url = config.getString(SECTION, KEY_URL, "").orEmpty()
        val key = config.getString(SECTION, KEY_AUTH, "").orEmpty()
        if (key.isEmpty()) return "" to ""
        config.setString(SECTION, KEY_AUTH, "")
        config.sync()
        Log.i("$TAG Network key found in provisioning, endpoint [$url]")
        return url to key
    }
}
