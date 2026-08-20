# Phone21

Telefonní aplikace k miniserveru **Phone21**. Miniserver drží SIM kartu
doma; aplikace v telefonu z něj bere hovory a zprávy, ať jsi kdekoli —
přes privátní šifrovanou síť, bez veřejné IP a bez port forwardu.

Co aplikace umí:

- hovory a zprávy z čísla, které má v sobě miniserver,
- nastavení jediným QR kódem z ovládání miniserveru (účet i síť),
- handsfree v autě přes Bluetooth: jméno volajícího, historie hovorů,
  čtení zpráv a vytáčení z displeje,
- kontakty ukládá do adresáře telefonu, takže je vidí i auto,
- diagnostiku spojení a deník bez telefonních čísel.

## Instalace

APK je na stránce [Releases](https://github.com/twentyone-cz/phone21-android/releases).
Pohodlné je nechat si aktualizace hlídat aplikací Obtainium (F-Droid) —
zdroj nastav na tenhle repozitář.

Aplikaci nastaví QR kód z ovládání miniserveru (záložka *Telefon*).
Návod pro uživatele: [phone.twentyone.cz](https://phone.twentyone.cz).

## Sestavení ze zdrojáků

```
./gradlew assembleDebug
```

APK vznikne v `app/build/outputs/apk/debug/`. Podepsané vydání staví
`scripts/release.sh` (potřebuje keystore mimo repozitář).

Poznámky k forku, rebasu na upstream a dotčeným souborům jsou ve
[FORK.md](FORK.md).

## Licence

Aplikace je odvozená z **linphone-android**, © Belledonne Communications,
a šíří se pod licencí [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.en.html)
(viz soubor `LICENSE`). Součástí je knihovna tunelu odvozená
z tailscale-android (BSD-3-Clause), viz `FORK.md`.
