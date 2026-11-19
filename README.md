# Phone21

Telefonní aplikace k miniserveru **Phone21**. Miniserver drží SIM kartu
doma; aplikace v telefonu z něj bere hovory a zprávy, ať jsi kdekoli —
přes privátní šifrovanou síť, bez veřejné IP a bez port forwardu.

**Funguje vedle jiné VPN.** Ve výchozím nastavení běží privátní síť
uvnitř aplikace a místo pro VPN v Androidu nechává volné. VPN jiné
aplikace (komerční, firemní…) tak může běžet současně a nic se
nenastavuje. Podrobnosti v oddílu [Souběh s jinou VPN](#souběh-s-jinou-vpn).

Co aplikace umí:

- hovory a zprávy z čísla, které má v sobě miniserver,
- běh současně s VPN jiné aplikace,
- nastavení jediným QR kódem z ovládání miniserveru (účet i síť),
- handsfree v autě přes Bluetooth: jméno volajícího, historie hovorů,
  čtení zpráv a vytáčení z displeje,
- kontakty ukládá do adresáře telefonu, takže je vidí i auto,
- diagnostiku spojení a deník bez telefonních čísel.

Aplikace neobsahuje služby Google a SMS uložené v telefonu nečte.

## Souběh s jinou VPN

Android dovolí jen jednu VPN najednou. Přístup do privátní sítě má
v Phone21 tři stupně a jen dva z nich to místo potřebují. Stupeň se volí
v aplikaci (Nastavení → Konexe → Přístup přes privátní síť); ovládání
miniserveru zvolený stupeň jen ukazuje.

- **Jen hovory** (výchozí) — do privátní sítě smí jen Phone21. Síť běží
  uvnitř aplikace, místo pro VPN zůstává volné a VPN jiné aplikace může
  běžet vedle. Hovor je šifrovaný až do miniserveru; obrazovka hovoru to
  ukazuje štítkem „Šifrováno do miniserveru“.
- **Přístup na miniserver** — na služby miniserveru dosáhnou i ostatní
  aplikace v telefonu. Potřebuje místo pro VPN.
- **Vše přes miniserver** — všechen provoz telefonu jde ven přes
  miniserver, tedy z tvého domácího připojení. Potřebuje místo pro VPN.
  Komerční VPN tím nenahradíš.

Výjimka u „Jen hovory“: volba „Synchronizovat kontakty i přes privátní
síť“ místo pro VPN potřebuje.

Když zvolený stupeň místo pro VPN potřebuje a drží ho jiná aplikace,
Phone21 se zeptá, jestli ji vypnout. Když odmítneš, hovory jdou dál
uvnitř aplikace — jen ostatní aplikace se na miniserver nedostanou.

## Instalace

Aplikace je v obchodě [Zapstore](https://zapstore.dev/apps/cz.twentyone.phone21)
pod názvem Phone21, Zapstore hlídá i aktualizace. APK ke stažení je také na stránce
[Releases](https://github.com/twentyone-cz/phone21-android/releases).

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
(viz soubor `LICENSE.txt`). Součástí je knihovna tunelu odvozená
z tailscale-android (BSD-3-Clause) s vlastními záplatami
(`tunnel/patches/`, závislosti pion/srtp a související pod licencí MIT),
viz `FORK.md` a `tunnel/README.md`. Licence všech součástí najdeš i přímo
v aplikaci v Nápovědě (řádek s licencí GNU GPL v3).
