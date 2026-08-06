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
| `app/build.gradle.kts` | závislost `app/libs/libtailscale.aar` (viz docs/tunnel-aar.md) | vpn |
| `app/src/main/res/values/themes.xml` | průhledné téma pro souhlas s VPN | vpn |
| `app/src/main/java/org/linphone/LinphoneApplication.kt` | 1 řádka: jazyk aplikace = čeština | brand |

Vše ostatní jsou NOVÉ soubory pod `org/linphone/twentyone/` — při rebasi
bez konfliktů.

## Verze

- Tag forku: `<upstream>-21p.<n>` (např. `6.2.4-21p.1`) — git describe
  mechanismus upstreamu pak generuje versionName sám.
- `versionCode` = upstream číslo × 10 + iterace (602004 → 6020041).

## Licence

GPLv3 (upstream). „Linphone" je ochranná známka Belledonne Communications —
fork nese vlastní jméno a grafiku. Zdroj linphone-sdk binárek:
https://gitlab.linphone.org/BC/public/linphone-sdk

Pozn.: `gradle/gradle-daemon-jvm.properties` je smazané — vynucovalo
stažení JetBrains JDK; fork buildí systémovým JDK 21.
