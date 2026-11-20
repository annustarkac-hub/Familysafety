# Troubleshooting

## Build

| Symptom | Fix |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME` or create `android/local.properties` with `sdk.dir=/path/to/Android/sdk` |
| `Received status code 429 ... Too Many Requests` from Maven Central | Transient throttling; re-run. Behind a shared proxy, configure a mirror (below). |
| `403 ... gcp.mirrors.maven.org` or large artifacts fail to download | Maven Central redirects big files to a CDN host your network blocks. Add to `~/.gradle/gradle.properties`: `familysafety.mavenMirror=https://maven-central.storage-download.googleapis.com/maven2` |
| `Dependency verification failed` | An artifact changed or a new one was added. If intentional, regenerate metadata (BUILD_GUIDE.md) and review the diff. On a new OS, AAPT2 needs that OS's checksum (Linux/macOS/Windows are listed). |
| `familysafety.apiBaseUrl must use https://` | Release builds refuse non-TLS endpoints by design. |
| Release APK is unsigned | `FS_KEYSTORE_PATH` etc. not set and no `android/keystore.properties`. |
| `Cannot find a Java installation ... languageVersion=17` | Use JDK 17+; the build compiles with `jvmTarget` rather than toolchain auto-provisioning. |
| Backend tests: `Could not find a valid Docker environment` | Start Docker; on Docker Engine ≥ 29 Testcontainers ≥ 1.21.4 is required (configured). |
| Robolectric tests: `Unable to resolve activity for Intent ... ComponentActivity` | The Compose `ui-test-manifest` must be on the unit-test classpath (configured as `testImplementation`). |

## Backend runtime

| Symptom | Fix |
|---|---|
| Startup: `FAMILYSAFETY_SECURITY_JWT_SECRET must be configured` | Set `JWT_SECRET` and `PAIRING_PEPPER` (base64, ≥ 32 bytes). `./scripts/dev-up.sh` generates them. |
| Every API call returns `403 HTTPS_REQUIRED` | Requests are reaching the backend over plain HTTP. Go through the TLS proxy, ensure it sends `X-Forwarded-Proto: https`, or for emulator-only development run `./scripts/dev-up.sh --emulator`. |
| `Schema-validation: wrong column type` | Entities and migrations disagree; add a Flyway migration instead of editing applied ones. |
| `401` right after a refresh on another thread | Expected: rotated sessions invalidate the old access token. Clients must use the new token (the Android client does). |
| `REFRESH_TOKEN_REUSED` and all devices signed out | A refresh token was used twice (replay or a client bug). This is the intended protection; sign in again. |

## Android runtime

| Symptom | Fix |
|---|---|
| Emulator can't reach backend | Debug build uses `http://10.0.2.2:8080/`; backend must run with `REQUIRE_HTTPS=false` (`dev-up.sh --emulator`). |
| Physical phone can't reach a LAN backend | Build with `-Pfamilysafety.debugApiBaseUrl=http://<LAN-IP>:8080/` **and** add that IP to `app/src/debug/res/xml/network_security_config.xml`, or use HTTPS. |
| Child shows no screen time | Grant *Usage access* (Permissions screen). Totals appear after a sync (open Monitoring status → "Share activity summary now"). |
| Usage updates arrive late | Expected: WorkManager runs at most every 15 min and may be deferred by Doze/battery optimisation. Ask the child to exclude the app from battery restrictions if appropriate. |
| Screen-share request never appears on the child | No push provider: the child app must be open or opened within the 90-second window. Check notifications are allowed. |
| Viewer stuck at "Connecting…" | Usually NAT traversal. Configure TURN (`TURN_URLS`/`TURN_SECRET`). Check both devices are online. |
| Sharing stops by itself | Session limit (30 min), the other side disconnected, Android stopped the projection (e.g. screen lock on some OEMs), or the device was revoked. The end reason is shown. |
| Signed out unexpectedly | Device revoked by a parent, refresh token expired (30 days idle / 90 days absolute) or reuse detected. |
| App data lost after reinstall/restore | By design (`allowBackup=false`, Keystore-bound tokens). Sign in again. A reinstall gets a new installation ID, so a child device must be paired again with a new code (the parent can remove the old entry). |
