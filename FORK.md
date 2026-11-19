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
| `app/build.gradle.kts` | ktlintFormat vypnut; versionCode/Name schéma forku; jméno APK; applicationId; jen `arm64-v8a` | build+brand |
| `app/google-services.json` | SMAZÁN (vypíná FCM+Crashlytics) | build |
| `keystore.properties` | SMAZÁN z repa, v `.gitignore` (spolu s `local.properties`); vyrábí ho `scripts/release.sh` | build |
| `README.md`, `.github/ISSUE_TEMPLATE/*`, `metadata/en-US/*` | vlastní texty | brand |
| `CHANGELOG.md` | SMAZÁN | brand |
| `app/src/main/res/values*/strings.xml` | entita `&appName;` (14 souborů); texty úvodu a oprávnění (16 souborů) | brand |
| `app/src/main/res/values/colors.xml`, `themes.xml` | paleta | brand |
| `app/src/main/res/values-v31`, `values-v33`, `values-night-v31`, `values-night-v33` (`themes.xml`) | splash bez obrázku značky; `drawable/linphone_splashscreen_branding.png` SMAZÁN | brand |
| `app/src/main/res/mipmap*`, splash drawables | ikony (PNG upstreamu smazány, `mipmap-anydpi` na `drawable/twentyone_launcher_foreground.xml`) | brand |
| `app/src/main/res/navigation/assistant_nav_graph.xml` | startDestination | onboarding |
| `app/src/main/assets/linphonerc_factory` | [ui]/[app] klíče | onboarding |
| `app/src/main/assets/linphonerc_default` | vyprázdněné servery upstreamu (logy, soubory, kontrola aktualizací) | onboarding |
| `app/src/main/AndroidManifest.xml` | SMS role, ConnectionService, VpnService | telecom/sms/vpn |
| `app/src/main/res/navigation/main_nav_graph.xml` | cíl obrazovky tunelu | vpn |
| `app/src/main/java/org/linphone/core/CoreContext.kt` | registrace TunnelReadinessManager a posluchačů `CarCallLog` (historie hovorů do systému) a `CarSmsMirror` (zprávy do systému); úklid v `onConfiguringStatus`; migrace nedoplňuje server pro nahrání logů a při startu se mažou servery upstreamu (`TwentyOneUpstreamServers`); `TwentyOneProvisioning.dropPrivateConfigUri` před `core.start()` ve `startCore` | sync/vpn/provisioning/soukromí |
| `app/build.gradle.kts` | závislost `app/libs/libtailscale.aar` (sestavení v `tunnel/README.md`) | vpn |
| `app/src/main/res/values/themes.xml` | průhledné téma pro souhlas s VPN | vpn |
| `app/src/main/java/org/linphone/LinphoneApplication.kt` | jazyk aplikace = čeština; zápis pádů do deníku | brand/diagnostika |
| `app/src/main/java/org/linphone/telecom/TelecomManager.kt` | tel: URI identita hovoru (TwentyOneCar) | auto |
| `app/src/main/java/org/linphone/ui/main/MainActivity.kt` | ACTION_SENDTO → konverzace; migrace kontaktů v loadContacts; log záměru i odkazu bez klíče a bez tokenu v cestě; odkaz s konfigurací přes `TwentyOneConfigLinkActivity` (potvrzení) | sms/kontakty/provisioning |
| `app/src/main/java/org/linphone/utils/PhoneNumberUtils.kt` | labelToType zveřejněn | kontakty |
| `app/src/main/AndroidManifest.xml` | `<queries>` na synchronizační aplikaci, oprávnění pro běh na pozadí | kontakty/baterie |
| `app/src/main/java/org/linphone/ui/main/contacts/viewmodel/ContactNewOrEditViewModel.kt` | uložení kontaktu do systémového adresáře | kontakty |
| `app/src/main/java/org/linphone/ui/main/contacts/fragment/NewContactFragment.kt`, `EditContactFragment.kt` | WRITE_CONTACTS launcher + observery | kontakty |
| `app/src/main/java/org/linphone/ui/main/contacts/viewmodel/ContactViewModel.kt`, `ContactsListViewModel.kt` | mazání nativního kontaktu i ze systému | kontakty |
| `app/build.gradle.kts` | Firebase messaging jen s `google-services.json`; SDK přebalené bez push modulu (úloha `linphoneSdkNoPush`) | bez Google |
| `app/src/main/AndroidManifest.xml` | bez služby FirebaseMessaging a bez meta-data Android Auto | bez Google |
| `app/proguard-rules.pro` | `-dontwarn` pro com.google.firebase a com.google.android.gms | bez Google |
| `app/src/main/java/org/linphone/ui/main/help/viewmodel/HelpViewModel.kt`, `res/layout/help_debug_fragment.xml` | bez řádku Firebase project ID; ikona verze `info` | bez Google |
| `app/src/main/res/values*/strings.xml` | odstraněn `help_troubleshooting_firebase_project_title` (13 souborů) | bez Google |
| `app/src/main/res/drawable/firebase.xml`, `google_play.xml`, `res/xml/automotive_app_desc.xml` | SMAZÁNY | bez Google |
| `app/src/main/java/org/linphone/core/CoreContext.kt` | podmínka Crashlytics s `BuildConfig.CRASHLYTICS_ENABLED` | bez Google |
| `app/src/main/java/org/linphone/core/tools/PushNotificationUtils.java` | NOVÝ: náhrada třídy SDK (rozhraní SDK 5.5.13) | bez Google |
| `gradle/gradle-daemon-jvm.properties` | SMAZÁN (build systémovým JDK) | build |
| `app/src/main/res/drawable/assistant_logo.xml`, `welcome_linphone_logo.xml` | vlastní grafika | brand |
| `app/src/main/java/org/linphone/ui/assistant/AssistantActivity.kt` | cesta po oprávněních na náš úvod, zpět na úvodu | onboarding |
| `app/src/main/java/org/linphone/ui/assistant/fragment/PermissionsFragment.kt`, `QrCodeScannerFragment.kt` | žádost o oprávnění Místní síť | onboarding |
| `app/src/main/java/org/linphone/ui/assistant/viewmodel/QrCodeViewModel.kt` | stažení konfigurace vlastním kódem; log odkazu bez klíče | provisioning |
| `app/src/main/java/org/linphone/ui/main/settings/viewmodel/AccountSettingsViewModel.kt` | po uložení `TsSipTransport.reapply` | vpn |
| `app/src/main/java/org/linphone/ui/main/settings/viewmodel/SettingsViewModel.kt` | log adresy vzdálené konfigurace bez tokenu; ruční vzdálená konfigurace: webová adresa přes `TwentyOneProvisioning.fetchAndApply`, adresa v privátní síti se ukládá jen pod systémovou VPN (`mayStoreConfigUri`), `dropPrivateConfigUri` před startem jádra | provisioning |
| `app/src/main/java/org/linphone/ui/assistant/viewmodel/ThirdPartySipAccountLoginViewModel.kt` | ruční účet bez připravené sítě | onboarding |
| `app/src/main/java/org/linphone/ui/call/CallActivity.kt` | senzor přiblížení v `onPause` jen bez hovoru | hovory |
| `app/src/main/java/org/linphone/ui/main/settings/fragment/SettingsFragment.kt`, `res/layout/settings_fragment.xml` | řádky Konexe a Diagnostika | vpn/diagnostika |
| `app/src/main/res/xml/data_extraction_rules.xml` | záloha aplikace vypnuta | bezpečnost |
| `app/src/main/AndroidManifest.xml` | cleartext HTTP, `allowBackup=false` | provisioning/bezpečnost |
| `app/src/main/AndroidManifest.xml` | TsConsentActivity bez přestavby při otočení (`configChanges`), přijímač odmetení upozornění „Hovory nechodí“ | vpn |
| `app/src/main/AndroidManifest.xml` | služba `TsTunnelService` (vnitřní tunel; popředí `specialUse` s podtypem, `exported=false`, `stopWithTask=false`) | vpn |
| `app/src/main/AndroidManifest.xml` | aktivita `TwentyOneConfigLinkActivity` (potvrzení odkazu s konfigurací, `exported=false`, průhledná) | provisioning |
| `app/src/main/java/org/linphone/ui/call/viewmodel/CurrentCallViewModel.kt`, `res/layout/call_media_encryption_info.xml` | štítek „Šifrováno do miniserveru“ u hovoru přes privátní síť i při šifrování hlasu k relé; rozhodnutí a čekání na šifrování v `TwentyOneCallPrivacy` (`showsPrivateNetworkLabel`, `waitsForEncryption`), obecný štítek šifrování se při něm skrývá | hovory |
| `app/src/main/java/org/linphone/ui/main/help/fragment/HelpFragment.kt` | řádek licence v Nápovědě otevře dialog s licencemi z APK (`TwentyOneLicenses`), ne web | licence |
| `app/src/main/res/values/strings.xml` | vlastní texty `twentyone_*` (obrazovky, upozornění a hlášky forku) | vpn/sms/diagnostika/hovory/licence |

Vše ostatní jsou NOVÉ soubory — při rebasi bez konfliktů:
`app/src/main/java/org/linphone/twentyone/` (mj. `TwentyOneLicenses.kt`),
`res/layout/twentyone_*.xml`, `res/drawable/twentyone_launcher_foreground.xml`,
`app/src/main/assets/licenses.txt` (generovaný, viz Licence), `scripts/`
(`rebase-upstream.sh`, `release.sh`, `gen-licenses.py`), `tunnel/`,
`zapstore.yaml`.

## Verze

- `app/build.gradle.kts`: `upstreamCode` (602004) a `forkIteration` →
  versionName `<upstream>-21p.<n>` (např. `6.2.4-21p.1`) a jméno APK
  `phone21-<buildType>-<versionName>.apk`. Tag `<upstream>-21p.<n>`
  vzniká až po sestavení; versionName z tagu neplyne.
- `versionCode` = upstream číslo × 1000 + iterace (602004 → 602004001;
  iterace max 999). Schéma ×1000 platí od 21p.20 (602004020, 2026-08-18),
  do 21p.19 bylo ×10. Pravidlo: nový versionCode musí být VŽDY větší než
  poslední vydaný — versionCode nejde nikdy snížit.

## Licence

GPLv3 (upstream). „Linphone" je ochranná známka Belledonne Communications —
fork nese vlastní jméno a grafiku. Zdroj linphone-sdk binárek:
https://gitlab.linphone.org/BC/public/linphone-sdk

Licence aplikace a všech knihoven v APK (volací knihovna, knihovna
privátní sítě a její Go moduly, knihovny Androidu) jsou v
`app/src/main/assets/licenses.txt`; aplikace je ukazuje v Nápovědě.
Soubor generuje `scripts/gen-licenses.py` z výstupu `go version -m` nad
`libgojni.so` z `app/libs/libtailscale.aar` a ze seznamu závislostí
`releaseRuntimeClasspath` (použití v hlavičce skriptu).

Pozn.: `gradle/gradle-daemon-jvm.properties` je smazané; fork buildí
systémovým JDK 21.

## Provisioning z miniserveru: sekce `[twentyone]` (21p.34)

Jedno QR z ovládání miniserveru nastaví SIP účet i privátní síť. XML
generuje miniserver ([twentyone-cz/phone21](https://github.com/twentyone-cz/phone21),
`webui/app.py`: `prov_xml()` a `network_section()`), appka ho čte
v `vpn/TsProvisioning.kt`.

Sekce se do XML vypíše, když je známé **cokoli** z trojice.

| klíč | co nese | kdo ho píše | `overwrite="true"` |
|---|---|---|---|
| `ts_control_url` | adresa koordinátoru sítě | miniserver z odpovědi koordinátoru | ANO |
| `ts_authkey` | jednorázový klíč telefonu (platí 1 h) | koordinátor, miniserver přeposílá | NE |
| `ts_exit_node` | adresa miniserveru v síti (IPv4 `100.x`) | miniserver ze souboru `ts/ip` | ANO |

- **`ts_control_url`** — appka ji čte `TsProvisioning.takeNetworkKey()`
  a nemaže. `TsManager.login()` ji porovná s
  `BuildConfig.NETWORK_COORDINATOR`; při neshodě ji ignoruje.
- **`ts_authkey`** — appka ho hned po přečtení maže zápisem prázdné
  hodnoty (`takeNetworkKey()`: `setString(SECTION, "ts_authkey", "")` +
  `sync()`), proto je jako jediný bez `overwrite`.
- **`ts_exit_node`** — stejná hodnota jako SIP doména v QR. Appka ji čte
  `takeExitNodeAddress()`, ukládá do prefs `gateway_address` a maže ji
  při `ConfiguringState.Successful` (`TunnelReadinessManager.onConfiguring`).
  Slouží jen k poznání miniserveru mezi protějšky; do předvoleb jádra
  tunelu jde vždy `StableID` vybraného protějšku, nikdy adresa.

**Pravidlo `overwrite`:** atribut patří výhradně na `<entry>` (ne na
`<section>`) a platí jen přesná hodnota `true`. Od 21p.34 ho nesou i
všechny `<entry>` v `proxy_0` a `auth_info_0` — nové QR přepíše ruční
úpravy SIP účtu. `TwentyOneProvisioning` před aplikací konfigurace odebere
přihlašovací údaje výchozího účtu a při NEúspěšné konfiguraci je z klonu
vrátí (`TunnelReadinessManager.onConfiguring`).

**Klient sítě:** `app/libs/libtailscale.aar` je postavené z `tailscale.com`
v1.104.7 (v `jni/arm64-v8a/libgojni.so` je cesta `tailscale.com@v1.104.7`,
dlouhá verze `1.104.7-tfb3b9b296-ga3655be16`; do 21p.45 včetně
`-g226f029a3`). Nese vlastní záplaty (vnitřní režim bez systémové VPN
a relé na 127.0.0.1) — jsou v `tunnel/patches/` nad commitem `226f029a3`,
sestavení v `tunnel/README.md`; appka volá `requestInternal`, `stopInternal`,
`internalStatus`, `startRelay`, `relayStats`, `generateRelayIdentity`
(`vpn/TsRelay.kt`). HTTP na adresy privátní sítě jde jen přes
`vpn/TsHttp.kt` (ve vnitřním režimu přes webové relé
`https://127.0.0.1:<port>/<cíl>:<port><cesta>` s připnutým certifikátem
relé a hlavičkou `X-Phone21-Relay`; vždy bez proxy a bez přesměrování).
Koordinátor headscale 0.29.7 vyžaduje klienta ≥ 1.80, jeho peer relay
klienta ≥ 1.86 — splněno.

### QR na dálku: klíč do sítě v odkazu (21p.37)

Ovládání otevřené přes privátní síť dává do QR odkaz na konfiguraci
v privátní síti a za něj `#k=<klíč>`, jednorázový klíč telefonu do sítě
(stejný druh klíče jako `ts_authkey`).

    linphone-config:http://<adresa v síti>:8091/p/<token>#k=<klíč>

Appka (`TwentyOneProvisioning`): klíč oddělí (`splitJoinKey`, tvar
`[A-Za-z0-9_-]{20,200}`), a když tunel neběží, přihlásí telefon a
konfiguraci stáhne až tunelem. Přihlášený telefon klíč nepoužije. Klíč se
nezapisuje do logu (`redact`). Chyby: P21-E18 (do sítě se nepřipojil),
P21-E19 (miniserver přes síť neodpovídá). Konfigurace stažená z takového
odkazu `ts_authkey` nenese.

## Stupeň přístupu a režim tunelu (21p.46)

Stupeň přístupu (prefs `tunnel_access`: APP_ONLY = „Jen hovory“, SERVER =
„Přístup na miniserver“, INTERNET = „Vše přes miniserver“) se volí jen
v appce, skupinou tří voleb na obrazovce Konexe. Appka ho zapisuje na
miniserver (`vpn/TsServerAccess.kt`, port 8091, token `access_token`
z QR) a při náběhu cesty nebo otevření obrazovky ověří, že ho miniserver
má; rozdíl přepíše stupněm z telefonu. Nezapsaná volba čeká
v `access_push_pending`. Ovládání miniserveru (od 0.9.47) stupeň jen
ukazuje.

Režim tunelu (`vpn/TsModeController.kt`):

| stupeň | režim |
|---|---|
| APP_ONLY | INTERNAL — vnitřní tunel v appce (`TsTunnelService`), bez systémové VPN |
| APP_ONLY + `contacts_sync_via_tunnel` (se synchronizační aplikací) | SYSTEM |
| SERVER, INTERNET | SYSTEM — systémová VPN (`TsVpnService`) |

Když SYSTEM nejde (chybí souhlas, místo drží jiná VPN, místo ztraceno),
jedou hovory dál v režimu INTERNAL a appka nabídne „Zapnout přístup“
(`TsConsentActivity`). Přepnutí režimu během hovoru se odloží na jeho
konec; výjimkou je odebrání systémové VPN, při kterém se hovor do
privátní sítě ukončí.

## Kontakty: cíl zápisu (21p.31)

Nové a upravené kontakty jdou do adresáře telefonu. Když je v telefonu
adresář synchronizační aplikace, jde zápis do něj — cíl se vybírá na
obrazovce Konexe (sekce Kontakty).

Prefs `twentyone_contacts`: `migration_done` (jednorázový přenos z aplikační
databáze), `target_mode` (AUTO/LOCAL/BOOK), `target_type`, `target_name`,
`target_lost_hinted`. Detekce adresářů: `ContactsContract.Settings`
sjednocené s účty existujících RAW kontaktů; hlavní účet synchronizační
aplikace se vynechává (kontakty leží v jejích pod-adresářích).

## Předvolby tunelu

Prefs `twentyone_tunnel_settings`:

- `tunnel_access` (APP_ONLY/SERVER/INTERNET, 21p.33; přebírá starý
  `tunnel_scope`, který zůstává jen kvůli migraci),
- `contacts_sync_via_tunnel` — synchronizační aplikace v tunelu (21p.32);
  přepínač je vidět jen s nainstalovanou aplikací,
- `gateway_address` (adresa miniserveru z posledního QR, klíč
  `ts_exit_node`; zapisuje se `commit()`) a `gateway_stable_id`
  (`StableID` naposledy vybraného miniserveru, jen záznam do deníku
  P21-TUN; při změně `gateway_address` se maže) — 21p.34,
- `access_token`, `access_push_pending` — zápis stupně na miniserver,
- `slot_lost`, `slot_lost_reason`, `slot_lost_at`,
  `slot_lost_dismissed_boot` — Phone21 přišlo o místo pro VPN (21p.45),
- `other_vpn_hold` — místo pro VPN drží jiná aplikace, appka si ho bez
  pokynu nevezme,
- `mode_in_use` (OFF/INTERNAL/SYSTEM — pro co jsou nastavené služby
  a účet; bez záznamu SYSTEM), `want_running` (zrcadlo WantRunning jádra
  tunelu), `relay_sip_port`, `relay_http_port`, `relay_private_sip_port`,
  `relay_client_srtp` (porty a šifrování hlasu posledního relé; token se
  neukládá), `system_block_notified` (poslední důvod výzvy „zapnout
  přístup“).

Šifrované prefs `twentyone_relay`: `p21relay-identity` (certifikáty
a klíče relé a volací knihovny). V `linphonerc` sekce `[twentyone]` klíč
`internal_media` (šifrování hlasu zapnul vnitřní režim) a ve vnitřním
režimu `[rtp] bind_address=127.0.0.1`, transporty UDP 0, TCP 0, TLS -2,
šifrování hlasu SRTP povinné (`[sip] srtp_crypto_suites` jen
`AES_CM_128_HMAC_SHA1_80`), adresa serveru i trasa účtu na `127.0.0.1`
(TLS) a vlastní politika NAT účtu — systémový režim je vrací.

Prefs `twentyone_battery`: `asked` — dialog o běhu na pozadí se nabízí
jednou po nastavení účtu, stav je pak vidět na Konexi.
