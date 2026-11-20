# Build Report

Generated 2026-11-20.

## Environment
| Item | Value |
|---|---|
| OS | Linux 6.18 x86_64 (cloud container, 4 vCPU, 15 GB RAM) |
| JDK | OpenJDK 21.0.13 |
| Gradle | 8.14.3 (wrapper) |
| Android SDK | cmdline-tools 13114758, platform android-35, build-tools 35.0.0, platform-tools 37.0.3 |
| AGP / Kotlin | 8.7.3 / 2.0.21 |
| Docker | Engine 29.10.0 |

## Commands and results

| Command | Result |
|---|---|
| `backend: ./gradlew clean test` | BUILD SUCCESSFUL — 46 tests, 0 failures |
| `backend: ./gradlew bootJar` | `family-safety-backend.jar` built; runs in Docker image `eclipse-temurin:21-jre-alpine` |
| `android: ./gradlew test lint --rerun-tasks --no-build-cache` | BUILD SUCCESSFUL — 107 test executions, 0 failures; lint 0 errors |
| `android: ./gradlew assembleRelease` (via `scripts/build-apk.sh`) | BUILD SUCCESSFUL — R8 minify + resource shrinking |
| `apksigner verify --verbose --print-certs` | **Verifies** — v2: true, v3: true, 1 signer |
| Dependency verification (`gradle/verification-metadata.xml`, SHA-256) | Active in both builds; all builds above passed verification |
| `docker compose up` + `scripts/e2e-smoke.py` | PASS |

## Release artifacts — 1.1.1 (current)

Same signing certificate; versionCode 3. Verified with apksigner. Built after regenerating dependency verification
from an empty Gradle cache; 142/142 Android tests and lint (0 correctness warnings) passed in that fresh-cache run.

| File | Size (bytes) | SHA-256 |
|---|---|---|
| FamilySafety-1.1.1-arm64-v8a-release.apk | 13,631,233 | `22b47799eb6e26168122fa8740a351718fbe6ef271c84a625f9c11e240a31886` |
| FamilySafety-1.1.1-armeabi-v7a-release.apk | 8,664,215 | `2f28410e3df884ae9b4de68fd80d79fde707f47a419fe4de1dcb2955f3a46b5a` |
| FamilySafety-1.1.1-universal-release.apk | 45,499,800 | `77fbc649af6f2715544e0a8ae20c8ce100b9ebdf725c1089af0ace9b2cde53de` |
| FamilySafety-1.1.1-x86-release.apk | 14,427,247 | `6fd1a4e2ebf27e34a84a90cf1ba6896661732e8c6deab50ba2c7cf2e2a5f3052` |
| FamilySafety-1.1.1-x86_64-release.apk | 14,910,080 | `2cf387505bbd9449a1ab6211678a98bbd0cbea3ccf9bdd8b75c256385821ca7b` |

Server image (`backend/Dockerfile`): built from an empty cache, run with the Render blueprint's settings under a
512 MB limit (~296 MB used); `e2e-smoke.py --forwarded-https` 10/10 PASS; plain HTTP → 403. Backend tests 46/46.

## Release artifacts — 1.1.0 (superseded)

Built with `scripts/build-apk.sh`, every APK verified with `apksigner` (v2+v3), same signing certificate as 1.0.0
(SHA-256 `8a881ecc…db59ac`), so 1.1.0 installs over 1.0.0 as an update. versionCode 2, versionName 1.1.0.

| File | Size (bytes) | SHA-256 |
|---|---|---|
| FamilySafety-1.1.0-arm64-v8a-release.apk | 13,631,237 | `a32a2943255134e691ccbc981fc9435fbaf9463752cda972ce2dd67bc966d730` |
| FamilySafety-1.1.0-armeabi-v7a-release.apk | 8,664,219 | `01db98e1ce5b46ff0aef10668b2a9266f43b49a6d9c04a77aac57d8c1dab1026` |
| FamilySafety-1.1.0-universal-release.apk | 45,499,804 | `227b1fa980c84084877a88c30342199fbcbd9c97f32ecc80d72cc71d008aeaed` |
| FamilySafety-1.1.0-x86-release.apk | 14,427,251 | `de2f80b3e123c79d749e2f2efa0da5df11a4684b9dcfdf2404a488893569a655` |
| FamilySafety-1.1.0-x86_64-release.apk | 14,910,084 | `5e69d46ce524b0931f3204cdb6f0c5e1bf01e0b7d6e4428831ed43d2956cbb29` |

The default server address is still a placeholder; users set theirs in the app (sign-in → Server).

## Release artifact (1.0.0, superseded)

| Property | Value |
|---|---|
| File | `dist/FamilySafety-1.0.0-release.apk` (not committed; delivered separately) |
| Size | 45,405,072 bytes |
| **SHA-256** | `3df6cd35d453b9eaed6e803d1c3af287e85c76a3b08776342838b5fcf47f57a5` |
| Package | `com.familysafety.app`, versionCode 1, versionName 1.0.0 |
| SDK | minSdk 26, targetSdk 35, compileSdk 35 |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 (WebRTC native libraries ≈ 43 MB uncompressed) |
| Signing schemes | v2 + v3 |
| Signing certificate | `CN=Family Safety Local Release, O=Local Build, C=US`, SHA-256 `8a881eccca3fcf035fa68fdfb0967be8c3490de10a47ac1577770b7b23db59ac` |
| API endpoint compiled in | `https://api.familysafety.example/` (**placeholder**) |

### Important
* This APK was signed with a **throwaway key generated inside the build container** (kept outside the repository).
  It is suitable for evaluation only. Production builds must use your own long-lived key (or Play App Signing); an app
  signed with this key cannot be updated by a differently signed build.
* Because the compiled API URL is a placeholder, the APK will show network errors until rebuilt with
  `-Pfamilysafety.apiBaseUrl=https://<your-api>/` (see BUILD_GUIDE.md). For local testing use a debug build.
* **Not verified:** installation and launch on a physical device (no device or emulator was available).

## R8 / shrinking checks
Dex inspection of the release APK confirmed retention of: kotlinx.serialization `$$serializer` classes (37),
`TokenResponseDto` serializer, `UsageSyncWorker`, `ScreenCaptureService`, `org.webrtc.PeerConnectionFactory`,
`NativePeerConnectionFactory`, `ScreenCapturerAndroid`.
