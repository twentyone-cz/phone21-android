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

TAG=$(git -C "$REPO" describe --tags --abbrev=0)
[[ "$TAG" == *-21p.* ]] || { echo "HEAD není na release tagu forku (*-21p.*), je: $TAG" >&2; exit 1; }

cat > "$REPO/keystore.properties" <<EOF
storePassword=$KEYSTORE_PASSWORD
keyPassword=$KEYSTORE_PASSWORD
keyAlias=$KEY_ALIAS
storeFile=$KEYSTORE_DIR/jednadvacet.jks
EOF
trap 'git -C "$REPO" checkout -q keystore.properties' EXIT

( cd "$REPO" && ./gradlew --no-daemon assembleRelease )

APK=$(ls "$REPO"/app/build/outputs/apk/release/jednadvacet-phone-release-*.apk | head -1)
echo "APK: $APK"

if [[ "${NO_PUBLISH:-0}" != "1" ]]; then
  gh release create "$TAG" --repo twentyone-cz/jednadvacet-phone-android \
    --title "jednadvacet phone $TAG" --generate-notes \
    "$APK#jednadvacet-phone-$TAG.apk"
fi
