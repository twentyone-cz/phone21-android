# Sestavení libtailscale.aar

Zabudovaný tunel stojí na knihovně `libtailscale` z projektu
[tailscale-android](https://github.com/tailscale/tailscale-android)
(BSD-3-Clause). Knihovna se **nebuildí v tomto repozitáři** — je to Go kód
překládaný přes gomobile, který potřebuje Go toolchain a Android NDK.
Do repa se přidává hotový artefakt `app/libs/libtailscale.aar`.

## Pinovaná verze

    tailscale-android 1.102.2-t6cac91817-g8c99ed49d

Verzi měnit jen vědomě (novější tag = nový build + smoke test tunelu).

## Postup

Potřeba: JDK 21, Android SDK s NDK `23.1.7779620` a `platforms;android-34`
(gomobile ho vyžaduje kvůli `-androidapi 26`), ~2 GB místa na Go moduly.

```sh
git clone --depth 1 --branch <tag> https://github.com/tailscale/tailscale-android
cd tailscale-android
# jen arm64 (release APK je stejně arm64-v8a):
sed -i 's#bind -target android #bind -target android/arm64 #' Makefile
export ANDROID_HOME=~/opt/android-sdk ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=~/opt/jdk-21
make libtailscale
cp android/libs/libtailscale.aar <fork>/app/libs/
```

Go toolchain si stáhne `./tool/go` sám do `~/.cache/tailscale-go`.

Ověřit 16KB zarovnání stránek (Android 15+ na některých zařízeních):

```sh
unzip -p app/libs/libtailscale.aar jni/arm64-v8a/libgojni.so > /tmp/libgojni.so
$ANDROID_HOME/ndk/23.1.7779620/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf \
    -l /tmp/libgojni.so | grep LOAD
```

Poslední sloupec musí být `0x4000`.

## Zádrhely

- Cíl `make libtailscale` v posledním kroku volá `zip`; na stroji bez něj
  poslední krok selže s `zip: not found` a v `android/libs/` zůstane jen
  `libtailscale_unstripped.aar`. Buď doinstalovat `zip`, nebo přebalit
  `temp_aar/` čímkoli jiným (pozor na časy souborů před rokem 1980 —
  Python `zipfile` je odmítne, pomůže `touch`).
- Bez `platforms;android-34` gomobile skončí na
  `failed to find android SDK platform (API level: 26)` — hledá **jakoukoli**
  platformu, ale `platforms;android-37.0` (s tečkou v názvu) mu nestačí.
