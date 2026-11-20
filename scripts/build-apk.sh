#!/usr/bin/env bash
# Builds, verifies and checksums the release APK.
#
# Required environment (never commit these):
#   FS_KEYSTORE_PATH  FS_KEYSTORE_PASSWORD  FS_KEY_ALIAS  FS_KEY_PASSWORD
# Required Gradle property (or set in ~/.gradle/gradle.properties):
#   -Pfamilysafety.apiBaseUrl=https://your-api.example.com/
#
# Output: dist/FamilySafety-<version>-release.apk and its .sha256
set -euo pipefail
cd "$(dirname "$0")/../android"

: "${FS_KEYSTORE_PATH:?set FS_KEYSTORE_PATH}"
: "${FS_KEYSTORE_PASSWORD:?set FS_KEYSTORE_PASSWORD}"
: "${FS_KEY_ALIAS:?set FS_KEY_ALIAS}"
: "${FS_KEY_PASSWORD:?set FS_KEY_PASSWORD}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)"

./gradlew --no-daemon assembleRelease "$@"

APK=app/build/outputs/apk/release/app-release.apk
VERSION=$(grep -oE '"versionName"[[:space:]]*:[[:space:]]*"[^"]+"' app/build/outputs/apk/release/output-metadata.json | head -1 | sed -E 's/.*"([^"]+)"$/\1/')
"$APKSIGNER" verify --verbose --print-certs "$APK"

mkdir -p ../dist
OUT="../dist/FamilySafety-${VERSION:-unknown}-release.apk"
cp "$APK" "$OUT"
(cd ../dist && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
cat "$OUT.sha256"
