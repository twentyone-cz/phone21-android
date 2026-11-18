package org.linphone.twentyone

import androidx.annotation.WorkerThread
import org.linphone.core.Call
import org.linphone.twentyone.vpn.TsManager

/**
 * Štítek šifrování na obrazovce hovoru.
 *
 * Phone21 nešifruje hlas ve volací knihovně, šifruje ho tunel privátní sítě
 * mezi telefonem a miniserverem. Knihovna o tunelu neví a hlásila by
 * „Hovor není šifrován“. Hovor jde přes privátní síť, když účet míří na
 * adresu miniserveru v privátní síti a tunel běží. Jinak (např. účet ručně
 * nastavený na adresu v LAN) zůstane poctivé „není šifrován“.
 */
object TwentyOneCallPrivacy {
    @WorkerThread
    fun viaPrivateNetwork(call: Call): Boolean {
        val account = call.params.account ?: call.core.defaultAccount ?: return false
        val host = account.params.serverAddress?.domain ?: return false
        return isPrivateNetworkAddress(host) && TsManager.isTunnelUp()
    }

    /**
     * Adresa z rozsahu privátní sítě: 100.64.0.0/10 nebo fd7a:115c:a1e0::/48.
     * Bere jen číselnou adresu, jméno nikdy nepřekládá (žádné DNS na vlákně
     * jádra).
     */
    fun isPrivateNetworkAddress(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val v4 = h.split(".")
        if (v4.size == 4) {
            val octets = v4.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 100 && octets[1] in 64..127
        }
        if (!h.contains(":")) return false
        val groups = expandIpv6(h.substringBefore("%")) ?: return false
        return groups[0] == 0xfd7a && groups[1] == 0x115c && groups[2] == 0xa1e0
    }

    private fun expandIpv6(addr: String): List<Int>? {
        val parts = addr.split("::")
        if (parts.size > 2) return null
        fun parse(s: String): List<Int>? =
            if (s.isEmpty()) {
                emptyList()
            } else {
                s.split(":").map { g ->
                    if (g.isEmpty() || g.length > 4) return null
                    g.toIntOrNull(16) ?: return null
                }
            }
        val head = parse(parts[0]) ?: return null
        if (parts.size == 1) return if (head.size == 8) head else null
        val tail = parse(parts[1]) ?: return null
        val missing = 8 - head.size - tail.size
        if (missing < 1) return null
        return head + List(missing) { 0 } + tail
    }
}
