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
        private const val KEY_GATEWAY_ADDRESS = "gateway_address"
        private const val KEY_GATEWAY_STABLE_ID = "gateway_stable_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
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

    /**
     * Adresa brány v privátní síti z posledního QR (klíč ts_exit_node).
     * Slouží JEN k tomu, aby se brána poznala mezi protějšky — do předvoleb
     * jádra tunelu jde vždy stabilní identifikátor, nikdy adresa.
     * Zapisuje se synchronně (commit): hodnota přijde při aplikaci
     * konfigurace a musí být uložená dřív, než se uzel začne nasazovat.
     */
    var gatewayAddress: String
        get() = prefs.getString(KEY_GATEWAY_ADDRESS, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_GATEWAY_ADDRESS, value).commit()
        }

    /**
     * Stabilní identifikátor brány, kterou appka naposledy vybrala pro
     * nasazení výstupního uzlu. Je to ZÁZNAM, ne záloha, ze které by se dalo
     * nasazovat.
     *
     * Rozhodnuto vědomě (a schválně napsáno sem, ať to není mrtvý stav bez
     * vysvětlení): v přechodných stavech, kdy brána v mapě sítě není nebo je
     * kandidátů víc, se do předvoleb jádra NEPOSÍLÁ NIC — ani prázdné ID
     * (sundalo by běžící uzel), ani tahle uložená hodnota. Identifikátor uzlu,
     * který v mapě sítě není, jádro nedokáže přeložit a nainstaluje místo něj
     * výchozí trasu do černé díry, takže by telefon zůstal bez internetu;
     * právě proti tomu je celá tahle oprava. Uzel se proto nasazuje výhradně
     * z protějšku, který je v mapě sítě právě teď a cestu ven opravdu nabízí.
     *
     * K čemu hodnota je: do deníku P21-TUN („poslední známý: žádný / je“),
     * tedy k dohledání, jestli telefon někdy bránu vůbec viděl. Maže se při
     * změně adresy brány z nového QR, aby se nikdy nepřenesla do jiné sítě.
     */
    var gatewayStableId: String
        get() = prefs.getString(KEY_GATEWAY_STABLE_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_GATEWAY_STABLE_ID, value).commit()
        }

    /** Token z QR, kterým telefon čte a nastavuje stupeň přístupu na miniserveru. */
    var accessToken: String
        get() = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ACCESS_TOKEN, value).commit()
        }
}
