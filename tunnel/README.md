# Záplaty knihovny tunelu

`app/libs/libtailscale.aar` je postavené z projektu
[tailscale-android](https://github.com/tailscale/tailscale-android)
(BSD-3-Clause) ve verzi `1.104.7-tfb3b9b296-g226f029a3` (commit `226f029a3`)
a záplat v adresáři `patches/`: tunel uvnitř aplikace bez systémové VPN
a místní relé na 127.0.0.1 pro hovory a přístup k miniserveru.

Sestavení:

```sh
git clone https://github.com/tailscale/tailscale-android
cd tailscale-android
git checkout 226f029a3
git am <fork>/tunnel/patches/*.patch
# jen arm64 (release APK je arm64-v8a):
sed -i 's#bind -target android #bind -target android/arm64 #' Makefile
export ANDROID_HOME=~/opt/android-sdk ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=~/opt/jdk-21
make libtailscale
cp android/libs/libtailscale.aar <fork>/app/libs/
```

Potřeba: JDK 21, Android SDK s NDK `23.1.7779620` a `platforms;android-34`,
přístup k Go proxy (gomobile si při sestavení spouští `go mod tidy`).
Dlouhá verze výsledku je `1.104.7-tfb3b9b296-g<commit po záplatách>`.

Záplaty přidávají závislosti `github.com/pion/srtp/v3`, `pion/rtp`,
`pion/rtcp`, `pion/transport/v5`, `pion/logging` a `pion/randutil`
(licence MIT).
