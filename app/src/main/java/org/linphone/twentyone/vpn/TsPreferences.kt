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
        private const val KEY_ACCESS_PUSH_PENDING = "access_push_pending"
        private const val KEY_SLOT_LOST = "slot_lost"
        private const val KEY_SLOT_LOST_REASON = "slot_lost_reason"
        private const val KEY_SLOT_LOST_AT = "slot_lost_at"
        private const val KEY_SLOT_LOST_DISMISSED_BOOT = "slot_lost_dismissed_boot"
        const val KEY_MODE_IN_USE = "mode_in_use"
        private const val KEY_WANT_RUNNING = "want_running"
        private const val KEY_RELAY_SIP_PORT = "relay_sip_port"
        private const val KEY_RELAY_HTTP_PORT = "relay_http_port"
        private const val KEY_RELAY_PRIVATE_SIP_PORT = "relay_private_sip_port"
        private const val KEY_RELAY_CLIENT_SRTP = "relay_client_srtp"
        private const val KEY_SYSTEM_BLOCK_NOTIFIED = "system_block_notified"
        private const val KEY_OTHER_VPN_HOLD = "other_vpn_hold"

        /** Klíče, jejichž změna mění požadovaný režim tunelu (TsModeController). */
        val MODE_KEYS = setOf(KEY_TUNNEL_ACCESS, KEY_CONTACTS_SYNC)
    }

    /** Soubor předvoleb pro posluchače změn (TsModeController). */
    val raw: android.content.SharedPreferences
        get() = prefs

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

    /**
     * Stupeň změněný v telefonu, který se ještě nepodařilo zapsat na
     * miniserver (jméno [TunnelAccess], "" = nic nečeká). Zápis se zkouší
     * znovu, jakmile cesta k miniserveru stojí (TsServerAccess). Zdrojem
     * pravdy je telefon: stupeň z miniserveru nepřebírá, rozdíl přepíše
     * svým. Synchronně: musí přežít zabití procesu hned po změně.
     */
    var accessPushPending: String
        get() = prefs.getString(KEY_ACCESS_PUSH_PENDING, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ACCESS_PUSH_PENDING, value).commit()
        }

    /**
     * Phone21 přišlo o místo pro VPN (Android dovolí jen jednu): systém
     * zrušil naše rozhraní, nebo se rozhraní nepodařilo postavit. Dokud
     * příznak platí, appka si místo sama NEBERE — zpátky jen na pokyn
     * uživatele. Zapisuje se synchronně: musí přežít zabití procesu hned
     * po odebrání.
     */
    val slotLost: Boolean
        get() = prefs.getBoolean(KEY_SLOT_LOST, false)

    /** Proč (do deníku a Diagnostiky), a kdy (System.currentTimeMillis). */
    val slotLostReason: String
        get() = prefs.getString(KEY_SLOT_LOST_REASON, "").orEmpty()

    val slotLostAt: Long
        get() = prefs.getLong(KEY_SLOT_LOST_AT, 0L)

    fun setSlotLost(reason: String) {
        prefs.edit()
            .putBoolean(KEY_SLOT_LOST, true)
            .putString(KEY_SLOT_LOST_REASON, reason)
            .putLong(KEY_SLOT_LOST_AT, System.currentTimeMillis())
            .commit()
    }

    /** Ve kterém startu telefonu (TsManager.bootId) uživatel výstrahu o
     *  ztraceném místě odsunul. Do restartu telefonu se pak sama nevrací;
     *  výstraha, kterou smazal systém (aktualizace, zastavení), ano. */
    var slotLostDismissedBoot: Long
        get() = prefs.getLong(KEY_SLOT_LOST_DISMISSED_BOOT, Long.MIN_VALUE)
        set(value) {
            prefs.edit().putLong(KEY_SLOT_LOST_DISMISSED_BOOT, value).apply()
        }

    fun clearSlotLost() {
        prefs.edit()
            .putBoolean(KEY_SLOT_LOST, false)
            .remove(KEY_SLOT_LOST_REASON)
            .remove(KEY_SLOT_LOST_AT)
            .remove(KEY_SLOT_LOST_DISMISSED_BOOT)
            .commit()
    }

    /**
     * Režim, pro který jsou OPRAVDU nastavené služby a účet ve volací
     * knihovně (TsModeController.Mode jménem). Bez záznamu (instalace
     * z verze před vnitřním tunelem) platí SYSTEM: tehdy jiný nebyl.
     * Zapisuje se synchronně — nový proces podle něj hned po startu ví,
     * co má uklidit.
     */
    var modeInUse: String
        get() = prefs.getString(KEY_MODE_IN_USE, null) ?: "SYSTEM"
        set(value) {
            prefs.edit().putString(KEY_MODE_IN_USE, value).commit()
        }

    /**
     * Zrcadlo WantRunning z jádra tunelu. Nový proces se musí rozhodnout
     * hned (vnitřní tunel startuje ještě před první notifikací jádra);
     * null = verze před vnitřním tunelem, zrcadlo ještě nikdo nezapsal.
     */
    var wantRunning: Boolean?
        get() = if (prefs.contains(KEY_WANT_RUNNING)) prefs.getBoolean(KEY_WANT_RUNNING, false) else null
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_WANT_RUNNING).commit()
            } else {
                prefs.edit().putBoolean(KEY_WANT_RUNNING, value).commit()
            }
        }

    /**
     * Port relé pro šifrovanou signalizaci na 127.0.0.1 (0 = zatím žádný).
     * Předává se relé jako přání při každém startu a stejný port je v trase
     * účtu — trasa tak platí i před startem relé (zavřená cesta).
     */
    var relaySipPort: Int
        get() = prefs.getInt(KEY_RELAY_SIP_PORT, 0)
        set(value) {
            prefs.edit().putInt(KEY_RELAY_SIP_PORT, value).apply()
        }

    /** Port webového relé na 127.0.0.1 (0 = libovolný). Token se NEUKLÁDÁ. */
    var relayHttpPort: Int
        get() = prefs.getInt(KEY_RELAY_HTTP_PORT, 0)
        set(value) {
            prefs.edit().putInt(KEY_RELAY_HTTP_PORT, value).apply()
        }

    /** Port relé na adrese telefonu v privátní síti (registrace u ústředny
     *  na něm zůstane platná i po restartu relé). 0 = libovolný. */
    var relayPrivateSipPort: Int
        get() = prefs.getInt(KEY_RELAY_PRIVATE_SIP_PORT, 0)
        set(value) {
            prefs.edit().putInt(KEY_RELAY_PRIVATE_SIP_PORT, value).apply()
        }

    /** Chtělo poslední relé šifrovaný hlas (clientSrtp)? Do příchodu
     *  odpovědi nového relé se podle toho nastavuje volací knihovna. */
    var relayClientSrtp: Boolean
        get() = prefs.getBoolean(KEY_RELAY_CLIENT_SRTP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RELAY_CLIENT_SRTP, value).apply()
        }

    /** Důvod, kvůli kterému naposled vyšla výzva „zapnout přístup“
     *  (TsModeController.SystemBlock jménem); stejný důvod se po restartu
     *  procesu ohlásí potichu. */
    var systemBlockNotified: String
        get() = prefs.getString(KEY_SYSTEM_BLOCK_NOTIFIED, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SYSTEM_BLOCK_NOTIFIED, value).apply()
        }

    /**
     * Zvolený stupeň chce systémovou VPN, ale appka viděla, že místo drží
     * jiná VPN (TsModeController). Platí, dokud uživatel nedá pokyn
     * (souhlas, „Zapnout přístup“) nebo nezmění stupeň — i když jiná VPN
     * mezitím na chvíli zmizí (přepojení) a i po zabití procesu: jinak by
     * si ho appka sama vzala a tu VPN vypnula. Zapisuje se synchronně.
     */
    var otherVpnHold: Boolean
        get() = prefs.getBoolean(KEY_OTHER_VPN_HOLD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OTHER_VPN_HOLD, value).commit()
        }
}
