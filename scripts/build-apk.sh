#!/usr/bin/env bash
# Builds, verifies and checksums the release APKs (per-ABI + universal).
#
# Required environment (never commit these):
#   FS_KEYSTORE_PATH  FS_KEYSTORE_PASSWORD  FS_KEY_ALIAS  FS_KEY_PASSWORD
# Optional Gradle property: -Pfamilysafety.apiBaseUrl=https://your-api.example.com/
#   (users can also set the server address inside the app on the sign-in screen)
#
# Output: dist/FamilySafety-<version>-<abi>-release.apk and SHA256SUMS
set -euo pipefail
cd "$(dirname "$0")/../android"

: "${FS_KEYSTORE_PATH:?set FS_KEYSTORE_PATH}"
: "${FS_KEYSTORE_PASSWORD:?set FS_KEYSTORE_PASSWORD}"
: "${FS_KEY_ALIAS:?set FS_KEY_ALIAS}"
: "${FS_KEY_PASSWORD:?set FS_KEY_PASSWORD}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)"

./gradlew --no-daemon assembleRelease "$@"

OUTDIR=app/build/outputs/apk/release
VERSION=$(grep -oE '"versionName"[[:space:]]*:[[:space:]]*"[^"]+"' "$OUTDIR/output-metadata.json" | head -1 | sed -E 's/.*"([^"]+)"$/\1/')
mkdir -p ../dist
: > ../dist/SHA256SUMS
for apk in "$OUTDIR"/app-*-release.apk; do
  abi=$(basename "$apk" | sed -E 's/^app-(.*)-release\.apk$/\1/')
  "$APKSIGNER" verify "$apk"
  out="FamilySafety-${VERSION:-unknown}-${abi}-release.apk"
  cp "$apk" "../dist/$out"
  (cd ../dist && sha256sum "$out" >> SHA256SUMS)
done
"$APKSIGNER" verify --print-certs "$OUTDIR/app-universal-release.apk" | grep -E "certificate (DN|SHA-256)"
cat ../dist/SHA256SUMS
