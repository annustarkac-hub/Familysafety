# Build Guide

## Toolchain

| Tool | Version used | Notes |
|---|---|---|
| JDK | 21 (OpenJDK 21.0.13) | Backend targets 21; Android modules compile to 17 bytecode |
| Gradle | 8.14.3 (wrapper) | Both `android/` and `backend/` have wrappers |
| Android Gradle Plugin | 8.7.3 | |
| Kotlin | 2.0.21 (+ Compose compiler plugin) | KSP 2.0.21-1.0.28 |
| Android SDK | platform 35, build-tools 35.0.0 | `compileSdk 35`, `targetSdk 35`, `minSdk 26` |
| Docker | 24+ (tested on 29) | Backend tests (Testcontainers) and the dev stack |

Set the SDK location via `ANDROID_HOME` or `android/local.properties` (`sdk.dir=/path/to/sdk`, not committed).

## Backend

```bash
cd backend
./gradlew test          # needs Docker
./gradlew bootJar       # build/libs/family-safety-backend.jar
```

## Android — debug

```bash
cd android
./gradlew assembleDebug                                   # API: http://10.0.2.2:8080/ (emulator → host)
./gradlew assembleDebug -Pfamilysafety.debugApiBaseUrl=http://192.168.1.20:8080/
```
Debug builds use `applicationId com.familysafety.app.debug`, so they install side-by-side with release.
Cleartext HTTP is allowed in debug **only** for the hosts listed in `app/src/debug/res/xml/network_security_config.xml`.

## Android — release

1. Create a signing key (once; keep it safe and **out of the repository**):
   ```bash
   keytool -genkeypair -storetype PKCS12 -keystore ~/keys/familysafety-release.p12 -alias familysafety \
           -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Provide signing values through environment variables (CI secrets) **or** an untracked `android/keystore.properties`:
   ```bash
   export FS_KEYSTORE_PATH=~/keys/familysafety-release.p12
   export FS_KEYSTORE_PASSWORD=...  FS_KEY_ALIAS=familysafety  FS_KEY_PASSWORD=...
   ```
   ```properties
   # android/keystore.properties  (git-ignored)
   storeFile=/home/me/keys/familysafety-release.p12
   storePassword=...
   keyAlias=familysafety
   keyPassword=...
   ```
3. Set your production API (must be `https://`; the build fails otherwise):
   ```bash
   ./scripts/build-apk.sh -Pfamilysafety.apiBaseUrl=https://api.your-domain.example/ \
                          -Pfamilysafety.versionCode=2 -Pfamilysafety.versionName=1.0.1
   ```
   The script runs `assembleRelease`, verifies the signature with `apksigner`, and writes
   `dist/FamilySafety-<version>-<abi>-release.apk` (per ABI + universal) plus `dist/SHA256SUMS`.

Release configuration: R8 minification + resource shrinking (`proguard-rules.pro` keeps kotlinx.serialization
serializers, Retrofit interfaces and `org.webrtc` JNI classes), v2+v3 APK signing, `allowBackup=false`, HTTPS-only
network security config. Without signing values `assembleRelease` produces an unsigned APK.

### APK size
ABI splits are enabled: `assembleRelease` produces `app-arm64-v8a-release.apk` (≈13.6 MB, most phones),
`armeabi-v7a` (≈8.7 MB, older 32-bit phones), `x86`/`x86_64` (emulators) and `app-universal-release.apk` (≈45 MB, any
device). `scripts/build-apk.sh` copies all of them to `dist/` with a `SHA256SUMS` file. For Play, build an App Bundle
(`./gradlew bundleRelease`).

## Quality gates

```bash
cd android && ./gradlew test lint        # unit/UI tests + lint (abortOnError=true)
cd backend && ./gradlew test
```

## Dependency verification

Both builds contain `gradle/verification-metadata.xml` (SHA-256). Gradle fails the build if any downloaded artifact
does not match. After intentionally changing dependencies, regenerate:

```bash
./gradlew --write-verification-metadata sha256 assembleDebug assembleRelease test lint   # android/
./gradlew --write-verification-metadata sha256 build                                      # backend/
```
Review the diff before committing. The Android file also lists AAPT2 checksums for macOS and Windows.

## Restricted networks

If Maven Central redirects to a blocked mirror host, set an alternative mirror in `~/.gradle/gradle.properties`:
```properties
familysafety.mavenMirror=https://maven-central.storage-download.googleapis.com/maven2
```
It is used for Gradle dependency resolution and for Robolectric's runtime downloads.
