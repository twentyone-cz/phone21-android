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
| `app/src/main/AndroidManifest.xml` | SMS role, ConnectionService, VpnService | telecom/sms/vpn |
| `app/src/main/res/navigation/main_nav_graph.xml` | cíl obrazovky tunelu | vpn |
| `app/src/main/java/org/linphone/core/CoreContext.kt` | registrace SystemProvidersSyncManager a TunnelReadinessManager | sync/vpn |
| `app/build.gradle.kts` | závislost `app/libs/libtailscale.aar` (postup je v interních poznámkách) | vpn |
| `app/src/main/res/values/themes.xml` | průhledné téma pro souhlas s VPN | vpn |
| `app/src/main/java/org/linphone/LinphoneApplication.kt` | 1 řádka: jazyk aplikace = čeština | brand |
| `app/src/main/java/org/linphone/telecom/TelecomManager.kt` | tel: URI identita hovoru (TwentyOneCar) | auto |
| `app/src/main/java/org/linphone/ui/main/MainActivity.kt` | ACTION_SENDTO → konverzace; migrace kontaktů v loadContacts | sms/kontakty |
| `app/src/main/java/org/linphone/utils/PhoneNumberUtils.kt` | labelToType zveřejněn | kontakty |
| `app/src/main/AndroidManifest.xml` | `<queries>` na synchronizační aplikaci, oprávnění pro běh na pozadí | kontakty/baterie |
| `app/src/main/java/org/linphone/ui/main/contacts/viewmodel/ContactNewOrEditViewModel.kt` | uložení kontaktu do systémového adresáře | kontakty |
| `app/src/main/java/org/linphone/ui/main/contacts/fragment/NewContactFragment.kt`, `EditContactFragment.kt` | WRITE_CONTACTS launcher + observery | kontakty |
| `app/src/main/java/org/linphone/ui/main/contacts/viewmodel/ContactViewModel.kt`, `ContactsListViewModel.kt` | mazání nativního kontaktu i ze systému | kontakty |

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

## Kontakty: cíl zápisu (21p.31)

Nové a upravené kontakty jdou do adresáře telefonu. Když je v telefonu
adresář synchronizační aplikace, jde zápis do něj — cíl se vybírá na
obrazovce Konexe (sekce Kontakty).

Prefs `twentyone_contacts`: `migration_done` (jednorázový přenos z aplikační
databáze), `target_mode` (AUTO/LOCAL/BOOK), `target_type`, `target_name`,
`target_lost_hinted`. Detekce adresářů: `ContactsContract.Settings`
sjednocené s účty existujících RAW kontaktů; hlavní účet synchronizační
aplikace se vynechává (kontakty leží v jejích pod-adresářích).

Prefs `twentyone_tunnel_settings`: `contacts_sync_via_tunnel` — v režimu
APP_ONLY se do tunelu přidá i synchronizační aplikace (21p.32); přepínač je
vidět jen tehdy, když je aplikace nainstalovaná.

Prefs `twentyone_battery`: `asked` — dialog o běhu na pozadí se nabízí
jednou po nastavení účtu, stav je pak vidět na Konexi.
