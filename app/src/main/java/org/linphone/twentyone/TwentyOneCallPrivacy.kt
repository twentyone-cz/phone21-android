package org.linphone.twentyone

import androidx.annotation.WorkerThread
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.MediaEncryption
import org.linphone.twentyone.vpn.TsManager

/**
 * Štítek šifrování na obrazovce hovoru.
 *
 * Phone21 nešifruje hlas až k protistraně, šifruje ho tunel privátní sítě
 * mezi telefonem a miniserverem. Knihovna o tunelu neví a hlásila by
 * „Hovor není šifrován“. Hovor jde přes privátní síť, když účet míří na
 * adresu miniserveru v privátní síti a tunel běží. Jinak (např. účet ručně
 * nastavený na adresu v LAN) zůstane poctivé „není šifrován“.
 *
 * Vnitřní tunel („jen hovory“ bez VPN): volací knihovna šifruje hlas
 * k relé uvnitř telefonu a hlásí šifrování hlasu — to končí v relé, dál ho
 * nese tunel. I pak platí „Šifrováno do miniserveru“, ne obecný štítek
 * šifrování, a obrazovka nemá čekat na zprávu o šifrování.
 *
 * Rozhodnutí je celé tady; obrazovka hovoru (upstream) jen volá
 * [showsPrivateNetworkLabel] a [waitsForEncryption].
 */
object TwentyOneCallPrivacy {
    private const val RELAY_HOST = "127.0.0.1"

    @WorkerThread
    fun viaPrivateNetwork(call: Call): Boolean {
        val account = accountOf(call) ?: return false
        // ve vnitřním režimu míří adresa serveru na relé — miniserver je
        // v účtu uložený zvlášť
        org.linphone.twentyone.vpn.TsSipTransport.miniserverHost(account) ?: return false
        return TsManager.isTunnelUp()
    }

    /**
     * Ukázat „Šifrováno do miniserveru“ místo štítku volací knihovny?
     * Hovor přes privátní síť bez šifrování hlasu (systémová VPN), nebo se
     * šifrováním, které končí v relé (účet míří na relé na 127.0.0.1).
     */
    @WorkerThread
    fun showsPrivateNetworkLabel(call: Call): Boolean {
        if (!viaPrivateNetwork(call)) return false
        return when (call.currentParams.mediaEncryption) {
            MediaEncryption.None -> true
            MediaEncryption.SRTP -> viaRelay(call)
            else -> false
        }
    }

    /**
     * Má obrazovka hovoru čekat na zprávu o šifrování? Upstream čeká, dokud
     * knihovna hlásí „bez šifrování“; hovor přes privátní síť (i vnitřním
     * tunelem, kde šifrování k relé naběhne až s hlasem) nečeká.
     */
    @WorkerThread
    fun waitsForEncryption(call: Call): Boolean =
        call.currentParams.mediaEncryption == MediaEncryption.None && !viaPrivateNetwork(call)

    /** Jde signalizace (a s ní hlas) účtu hovoru přes relé uvnitř telefonu? */
    @WorkerThread
    private fun viaRelay(call: Call): Boolean {
        val account = accountOf(call) ?: return false
        return account.params.routesAddresses.any { it.domain == RELAY_HOST }
    }

    private fun accountOf(call: Call): Account? = call.params.account ?: call.core.defaultAccount

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
