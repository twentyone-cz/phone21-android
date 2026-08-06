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
