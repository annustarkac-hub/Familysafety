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

## Release artifact

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
