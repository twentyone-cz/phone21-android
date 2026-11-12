# Fork linphone-android → Phone21

Fork drží **overlay princip**: maximum nového kódu v nových souborech pod
`app/src/main/java/org/linphone/twentyone/`, minimum dotyků upstream
souborů. Namespace `org.linphone` se NEMĚNÍ (R/databinding), mění se jen
`applicationId`.

## Rebase na upstream release

```
./scripts/rebase-upstream.sh <novy-tag>     # např. 6.2.5
```

Rebasuje se jen na **stable tagy 6.2.x** (master = 6.3-alpha, přechod na
6.3 bude samostatná akce). Naše komity jsou tematická série — konflikty
řešit po tématech, `git rerere` je zapnuté. Po rebasi: build + smoke test,
pak tag `<upstream>-21p.<n>` a push.

## Dotčené upstream soubory (checklist při rebasi)

| Soubor | Změna | Komit |
|---|---|---|
| `gradle/libs.versions.toml` | pin linphone-sdk (5.5.13 místo 5.5.+) | build |
| `app/build.gradle.kts` | ktlintFormat vypnut; versionCode/Name schéma forku; jméno APK; applicationId | build+brand |
| `app/google-services.json` | SMAZÁN (vypíná FCM+Crashlytics) | build |
| `keystore.properties` | dummy v repu, reálný jen na build stroji | build |
| `app/src/main/res/values*/strings.xml` | entita &appName; (19 souborů) | brand |
| `app/src/main/res/values/colors.xml`, `themes.xml` | paleta | brand |
| `app/src/main/res/mipmap*`, splash drawables | ikony | brand |
| `app/src/main/res/navigation/assistant_nav_graph.xml` | startDestination | onboarding |
| `app/src/main/assets/linphonerc_factory` | [ui]/[app] klíče | onboarding |
| `app/src/main/assets/linphonerc_default` | vyprázdněné servery upstreamu | onboarding |
| `app/src/main/AndroidManifest.xml` | SMS role, ConnectionService, VpnService | telecom/sms/vpn |
| `app/src/main/res/navigation/main_nav_graph.xml` | cíl obrazovky tunelu | vpn |
| `app/src/main/java/org/linphone/core/CoreContext.kt` | registrace SystemProvidersSyncManager a TunnelReadinessManager; úklid v `onConfiguringStatus` | sync/vpn/provisioning |
| `app/build.gradle.kts` | závislost `app/libs/libtailscale.aar` (postup je v interních poznámkách) | vpn |
| `app/src/main/res/values/themes.xml` | průhledné téma pro souhlas s VPN | vpn |
| `app/src/main/java/org/linphone/LinphoneApplication.kt` | jazyk aplikace = čeština; zápis pádů do deníku | brand/diagnostika |
| `app/src/main/java/org/linphone/telecom/TelecomManager.kt` | tel: URI identita hovoru (TwentyOneCar) | auto |
| `app/src/main/java/org/linphone/ui/main/MainActivity.kt` | ACTION_SENDTO → konverzace; migrace kontaktů v loadContacts | sms/kontakty |
| `app/src/main/java/org/linphone/utils/PhoneNumberUtils.kt` | labelToType zveřejněn | kontakty |
| `app/src/main/AndroidManifest.xml` | `<queries>` na synchronizační aplikaci, oprávnění pro běh na pozadí | kontakty/baterie |
| `app/src/main/java/org/linphone/ui/main/contacts/viewmodel/ContactNewOrEditViewModel.kt` | uložení kontaktu do systémového adresáře | kontakty |
| `app/src/main/java/org/linphone/ui/main/contacts/fragment/NewContactFragment.kt`, `EditContactFragment.kt` | WRITE_CONTACTS launcher + observery | kontakty |
| `app/src/main/java/org/linphone/ui/main/contacts/viewmodel/ContactViewModel.kt`, `ContactsListViewModel.kt` | mazání nativního kontaktu i ze systému | kontakty |
| `app/build.gradle.kts` | Firebase messaging jen `compileOnly`, když chybí `google-services.json` | bez Google |
| `app/src/main/AndroidManifest.xml` | služba FirebaseMessaging `tools:node="remove"` | bez Google |
| `app/proguard-rules.pro` | `-dontwarn` pro com.google.firebase a com.google.android.gms | bez Google |
| `app/src/main/java/org/linphone/ui/main/help/viewmodel/HelpViewModel.kt` | `catch (Throwable)` u FirebaseApp | bez Google |
| `gradle/gradle-daemon-jvm.properties` | SMAZÁN (build systémovým JDK) | build |
| `app/src/main/res/drawable/assistant_logo.xml`, `welcome_linphone_logo.xml` | vlastní grafika | brand |
| `app/src/main/java/org/linphone/ui/assistant/AssistantActivity.kt` | cesta po oprávněních na náš úvod, zpět na úvodu | onboarding |
| `app/src/main/java/org/linphone/ui/assistant/fragment/PermissionsFragment.kt`, `QrCodeScannerFragment.kt` | žádost o oprávnění Místní síť | onboarding |
| `app/src/main/java/org/linphone/ui/assistant/viewmodel/QrCodeViewModel.kt` | stažení konfigurace vlastním kódem | provisioning |
| `app/src/main/java/org/linphone/ui/assistant/viewmodel/ThirdPartySipAccountLoginViewModel.kt` | ruční účet bez připravené sítě | onboarding |
| `app/src/main/java/org/linphone/ui/call/CallActivity.kt` | senzor přiblížení v `onPause` jen bez hovoru | hovory |
| `app/src/main/java/org/linphone/ui/main/settings/fragment/SettingsFragment.kt`, `res/layout/settings_fragment.xml` | řádky Konexe a Diagnostika | vpn/diagnostika |
| `app/src/main/res/xml/data_extraction_rules.xml` | záloha aplikace vypnuta | bezpečnost |
| `app/src/main/AndroidManifest.xml` | cleartext HTTP, `allowBackup=false` | provisioning/bezpečnost |

Vše ostatní jsou NOVÉ soubory pod `org/linphone/twentyone/` — při rebasi
bez konfliktů.

## Verze

- Tag forku: `<upstream>-21p.<n>` (např. `6.2.4-21p.1`) — git describe
  mechanismus upstreamu pak generuje versionName sám.
- `versionCode` = upstream číslo × 1000 + iterace (602004 → 602004001;
  iterace max 999). POZOR, schéma ×10 přeteklo u 21p.19 (6020059 leželo
  v pásmu upstreamu 6.2.5 — po rebasi by šel jen downgrade); od 21p.20
  (602004020, 2026-08-18) platí ×1000. Pravidlo: nový versionCode musí být
  VŽDY větší než poslední vydaný — versionCode nejde nikdy snížit.

## Licence

GPLv3 (upstream). „Linphone" je ochranná známka Belledonne Communications —
fork nese vlastní jméno a grafiku. Zdroj linphone-sdk binárek:
https://gitlab.linphone.org/BC/public/linphone-sdk

Pozn.: `gradle/gradle-daemon-jvm.properties` je smazané — vynucovalo
stažení JetBrains JDK; fork buildí systémovým JDK 21.

## Provisioning z miniserveru: sekce `[twentyone]` (21p.34)

Jedno QR z ovládání brány nastaví SIP účet i privátní síť. XML generuje brána
(repo `/home/dev/projects/Phone21`, `webui/app.py`: `prov_xml()` a
`network_section()`), appka ho čte v `vpn/TsProvisioning.kt`. Závazný kontrakt
je v repu brány: `docs/ts-protokol.md`, kapitola 6.

Sekce se do XML vypíše, když je známé **cokoli** z trojice — adresa brány se
posílá i tehdy, když se klíč telefonu nepodařilo získat (telefon už v síti být
může a potřebuje jen vědět, kudy ven).

| klíč | co nese | kdo ho píše | `overwrite="true"` |
|---|---|---|---|
| `ts_control_url` | adresa koordinátoru sítě | brána z odpovědi koordinátoru | ANO |
| `ts_authkey` | jednorázový klíč telefonu (platí 1 h) | koordinátor, brána přeposílá | NE |
| `ts_exit_node` | adresa brány v síti (tailnet IPv4) | brána ze souboru `ts/ip` | ANO |

**`ts_control_url`** — adresa koordinátoru, ke kterému se má telefon přihlásit.
Bere se z pole `login_server` odpovědi na `POST /partner/preauthkeys`
(`webui/app.py`, `request_network_key()`); bez uloženého tokenu účtu se klíč
ani adresa koordinátoru nevydají a QR nese jen SIP účet a `ts_exit_node`.
Appka ji čte `TsProvisioning.takeNetworkKey()` a **nemaže** (maže se jen
klíč), takže položka v `linphonerc` zůstává ležet —
proto `overwrite="true"`: bez atributu by xml2lpc existující položku nechal být
a telefon by po přestěhování koordinátoru zůstal u staré adresy. Hodnota je
přitom jen kontrolní: `TsManager.login()` ji porovná s
`BuildConfig.NETWORK_COORDINATOR` a při neshodě ji ignoruje (QR nesmí telefon
přehlásit k cizímu koordinátorovi).

**`ts_authkey`** — jednorázový klíč telefonu do privátní sítě. Vydává ho
koordinátor (`POST /partner/preauthkeys`), brána ho jen přiloží k QR; platí
hodinu a spotřebuje se prvním přihlášením. **Jediná položka bez
`overwrite="true"`**, a to schválně: appka ji hned po přečtení maže zápisem
prázdné hodnoty (`takeNetworkKey()`: `setString(SECTION, "ts_authkey", "")` +
`sync()` — prázdná hodnota položku v liblinphone opravdu odstraní), aby klíč
nikde neležel. Položka tím pádem v konfiguraci neexistuje a příští QR ji zapíše
i bez atributu: přepis zajišťuje mazání v appce, ne `overwrite`.

**`ts_exit_node`** — adresa brány v privátní síti (tailnet IPv4, `100.x`).
Je to tatáž hodnota, kterou QR nese jako SIP doménu: `ProvHandler` si ji vezme
z `ts_ip()` (soubor `ts/ip`, jediný zapisovatel je sidecar brány) a předá ji do
`prov_xml(ip, ts_url, ts_key, ip)`. Appka ji čte `takeExitNodeAddress()` a
ukládá do prefs `gateway_address`; slouží **jen k tomu, aby se brána poznala
mezi protějšky** — do předvoleb jádra tunelu jde vždy `StableID` vybraného
protějšku, nikdy adresa. `overwrite="true"` má, přestože ji appka po přečtení
taky maže: mazání proběhne jen při `ConfiguringState.Successful`
(`TunnelReadinessManager.onConfiguring`), takže po neúspěšné konfiguraci může
v `linphonerc` zůstat stará adresa — a ta se po přeregistrování brány mění.

**Pravidlo `overwrite`:** atribut patří výhradně na `<entry>` (na `<section>`
ho liblinphone 5.5.13 nezná) a porovnává se přesným `strcmp` s `true` — ani
`True`, ani `1` neprojde. Bez něj xml2lpc existující položku nechá být
(„Don't touch"). Od 21p.34 ho proto nesou i všechny `<entry>` v `proxy_0`
a `auth_info_0`, jinak druhé QR nepřepíše ani doménu, ani heslo. Cena: ruční
úprava SIP účtu v telefonu nové QR nepřežije. Souvisí s tím pojistka
v `TwentyOneProvisioning`: před aplikací konfigurace se odeberou přihlašovací
údaje výchozího účtu (kvůli `ha1` z minulé domény) a při NEúspěšné konfiguraci
se z klonu vrátí zpátky (`TunnelReadinessManager.onConfiguring`).

**Klient sítě:** `app/libs/libtailscale.aar` je postavené z `tailscale.com`
v1.102.2 (v `jni/arm64-v8a/libgojni.so` je cesta `tailscale.com@v1.102.2`,
dlouhá verze `1.102.2-t6cac91817`). Koordinátor headscale 0.29.1 vyžaduje
klienta ≥ 1.80 — splněno.

## Kontakty: cíl zápisu (21p.31)

Nové a upravené kontakty jdou do adresáře telefonu. Když je v telefonu
adresář synchronizační aplikace, jde zápis do něj — cíl se vybírá na
obrazovce Konexe (sekce Kontakty).

Prefs `twentyone_contacts`: `migration_done` (jednorázový přenos z aplikační
databáze), `target_mode` (AUTO/LOCAL/BOOK), `target_type`, `target_name`,
`target_lost_hinted`. Detekce adresářů: `ContactsContract.Settings`
sjednocené s účty existujících RAW kontaktů; hlavní účet synchronizační
aplikace se vynechává (kontakty leží v jejích pod-adresářích).

Prefs `twentyone_tunnel_settings`: `tunnel_access` (APP_ONLY/SERVER/INTERNET,
21p.33; přebírá starý `tunnel_scope`), `contacts_sync_via_tunnel` — v režimu
APP_ONLY se do tunelu přidá i synchronizační aplikace (21p.32); přepínač je
vidět jen tehdy, když je aplikace nainstalovaná. Od 21p.34 navíc
`gateway_address` (adresa brány z posledního QR, klíč `ts_exit_node`; jen
k poznání brány mezi protějšky, zapisuje se `commit()`, protože musí ležet dřív,
než se uzel začne nasazovat) a `gateway_stable_id` (`StableID` naposledy
vybrané brány — je to ZÁZNAM do deníku P21-TUN, ne záloha, ze které by se dalo
nasazovat; při změně `gateway_address` se maže, ať se nikdy nepřenese do jiné
sítě). Klíč `tunnel_scope` zůstává jen kvůli migraci, mrtvý enum `TunnelScope`
je od 21p.34 pryč.

Prefs `twentyone_battery`: `asked` — dialog o běhu na pozadí se nabízí
jednou po nastavení účtu, stav je pak vidět na Konexi.
