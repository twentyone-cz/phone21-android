#!/usr/bin/env bash
# Podepsaný release APK + GitHub Release. Spouštět na stroji s keystore.
#
#   KEYSTORE_DIR=... ./scripts/release.sh            # build + release
#   KEYSTORE_DIR=... NO_PUBLISH=1 ./scripts/release.sh   # jen build
#
# Klíč NIKDY nedávat do repa ani do CI secrets — podepisuje se lokálně.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_DIR="${KEYSTORE_DIR:?nastav KEYSTORE_DIR (adresář s jednadvacet.jks + keystore.env)}"
# shellcheck disable=SC1091
source "$KEYSTORE_DIR/keystore.env"

# --exact-match: HEAD musí být PŘESNĚ na tagu (--abbrev=0 by vzal i tag
# o pár commitů zpět a vydal by APK z jiného kódu, než tag popisuje)
TAG=$(git -C "$REPO" describe --tags --exact-match HEAD 2>/dev/null) \
  || { echo "HEAD není přesně na tagu — nejdřív: git tag <upstream>-21p.<n>" >&2; exit 1; }
[[ "$TAG" == *-21p.* ]] || { echo "HEAD není na release tagu forku (*-21p.*), je: $TAG" >&2; exit 1; }
# tag musí být ANOTOVANÝ (git tag -a): upstream gitVersion volá `git
# describe` bez --tags a lightweight tag nevidí — v aplikaci i ve jménu
# APK by pak byla předchozí verze s příponou +hash
[[ "$(git -C "$REPO" cat-file -t "refs/tags/$TAG" 2>/dev/null)" == "tag" ]] \
  || { echo "tag $TAG není anotovaný — vytvoř ho: git tag -fa $TAG -m 'Phone21 $TAG'" >&2; exit 1; }
[[ -z "$(git -C "$REPO" status --porcelain)" ]] \
  || { echo "pracovní strom není čistý — necommitnuté změny by se zapekly do release APK" >&2; exit 1; }
# tag ↔ verze v gradle: versionName se skládá z prefixu a forkIteration
ITER="${TAG##*-21p.}"
BASE="${TAG%-21p.*}"
grep -Eq "val forkIteration = ${ITER}\b" "$REPO/app/build.gradle.kts" \
  || { echo "forkIteration v app/build.gradle.kts nesedí s tagem $TAG" >&2; exit 1; }
grep -Fq "versionName = \"${BASE}-21p.\$forkIteration\"" "$REPO/app/build.gradle.kts" \
  || { echo "versionName v app/build.gradle.kts nesedí s tagem $TAG" >&2; exit 1; }

cat > "$REPO/keystore.properties" <<EOF
storePassword=$KEYSTORE_PASSWORD
keyPassword=$KEYSTORE_PASSWORD
keyAlias=$KEY_ALIAS
storeFile=$KEYSTORE_DIR/jednadvacet.jks
EOF
# keystore.properties je gitignorovaný, takže ho nelze „vrátit" gitem —
# po buildu musí zmizet, jinak zůstanou hesla ležet v pracovním adresáři
trap 'rm -f "$REPO/keystore.properties"' EXIT

( cd "$REPO" && ./gradlew --no-daemon assembleRelease )

APK=$(ls "$REPO"/app/build/outputs/apk/release/phone21-release-*.apk | head -1)
echo "APK: $APK"

if [[ "${NO_PUBLISH:-0}" != "1" ]]; then
  gh release create "$TAG" --repo twentyone-cz/phone21-android \
    --title "Phone21 $TAG" --generate-notes \
    "$APK#phone21-$TAG.apk"
fi
