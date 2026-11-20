# Family Safety

A consent-based parental safety app for Android: **one APK, two roles (Parent and Child)**, backed by a
Kotlin/Spring Boot API.

> **Status: functional prototype, not production-ready.** Core flows are implemented and covered by
> automated tests, and the backend has been exercised end-to-end in Docker. Behaviour that needs real
> phones — usage collection on a device, MediaProjection capture, WebRTC video between two devices,
> notifications and background scheduling — **has not been verified on a physical device yet.**
> See [Known limitations](#known-limitations).

## What it does

| Parent | Child |
|---|---|
| Register / sign in | Register / sign in |
| Pair a child's phone with a one-time code | Enter the code — and see exactly what is shared before confirming |
| Dashboard: devices, today's screen time, open alerts | Dashboard: monitoring status, permissions, today's screen time |
| Weekly / monthly screen-time reports, app ranking | Own activity summary (the same data parents see) |
| Request to **view the screen live** | **Approve or decline** every request; Android asks again; stop anytime |
| Safety alerts: check-ins, help requests, reports, limit & bedtime alerts | Check in, report a concern, ask for help |
| Configure daily limit, bedtime, screen sharing, retention | See the settings parents chose |
| Revoke a device | Ask to disconnect; turn off usage access at any time |

**Never collected:** messages, calls, keystrokes, notification contents, browsing content, photos,
location, recordings. Screen sharing is live-only; nothing is recorded or stored.

## Repository layout

```
android/              Android app (Gradle, Kotlin DSL, version catalog)
  app/                Application, MainActivity, role routing, auth screens, manifest, release config
  core/               Material 3 theme & components, Android Keystore cipher, UI state helpers
  domain/             Pure Kotlin: models, repository contracts, usage aggregation, role router, share lifecycle
  data/               Retrofit/OkHttp, Room, DataStore session, WorkManager, WebSocket, WebRTC, repositories
  feature-parent/     Parent screens + ViewModels (dashboard, devices, analytics, viewer, alerts, settings)
  feature-child/      Child screens + ViewModels, MediaProjection foreground service
backend/              Spring Boot (Kotlin) API, Flyway migrations, tests (Testcontainers)
openapi/openapi.yaml  OpenAPI 3.0 specification (validated)
infrastructure/       Dockerfile, Caddy TLS proxy config, database notes
scripts/              dev-up, build-backend, build-apk, test-all, e2e-smoke
docs/                 Design, API, security, privacy, deployment, testing reports
docker-compose.yml    PostgreSQL, backend, Caddy (TLS), optional coturn
```

## Quick start (local development)

Prerequisites: JDK 21, Docker, Android SDK (platform 35, build-tools 35). See [BUILD_GUIDE.md](BUILD_GUIDE.md).

```bash
./scripts/dev-up.sh --emulator          # creates .env with random secrets, starts postgres + backend
python3 scripts/e2e-smoke.py            # 10-step API smoke test against http://127.0.0.1:8080
cd android && ./gradlew installDebug    # debug build talks to http://10.0.2.2:8080 (emulator → host)
```

On a physical phone, use a debug build pointing at your machine (`-Pfamilysafety.debugApiBaseUrl=http://<LAN-IP>:8080/`
and add that IP to `app/src/debug/res/xml/network_security_config.xml`) or deploy the backend behind TLS.

## Verification status (this build)

| Check | Result |
|---|---|
| Backend tests (`./gradlew test`, Testcontainers PostgreSQL 16) | **46 passed, 0 failed** |
| Android JVM/Robolectric/Compose tests (`./gradlew test`, debug+release variants) | **107 executions passed, 0 failed** |
| Android Lint (`./gradlew lint`) | **0 errors** (warnings: newer-dependency notices) |
| `./gradlew assembleRelease` (R8 + resource shrinking) | **Built and signed (v2+v3), apksigner verified** |
| Docker Compose stack + `scripts/e2e-smoke.py` | **Passed** (all 10 steps over HTTP) |
| TLS enforcement via Caddy | **Verified** (plain HTTP → 403 `HTTPS_REQUIRED`; HTTPS → API) |
| OpenAPI spec validation | **Valid** |
| Install / launch / both roles on a **physical device** | **Not performed** — no device available in the build environment |

Details: [docs/reports/TEST_REPORT.md](docs/reports/TEST_REPORT.md), [docs/reports/BUILD_REPORT.md](docs/reports/BUILD_REPORT.md).

## Known limitations

* **No physical-device verification yet** (see above). The device test plan is in [TESTING.md](TESTING.md#physical-device-test-plan).
* The delivered release APK is built against the placeholder API `https://api.familysafety.example/`. **Rebuild with
  your own HTTPS endpoint** (`-Pfamilysafety.apiBaseUrl=...`) and **your own signing key**.
* **No push provider (FCM) is configured.** Realtime events arrive over a WebSocket held only while the app is in
  the foreground (or while sharing). In the background, alerts are polled by WorkManager (~15 min, subject to Doze).
  A child must open the app (or tap a polled notification) to see a screen-sharing request that arrived while it
  was closed; requests expire after 90 s. See [DEPLOYMENT.md](docs/DEPLOYMENT.md#push-notifications).
* **Usage data is not real-time.** UsageStatsManager is queried on a ~15-minute WorkManager schedule and when the
  child opens the app; Android may defer background work. Only per-app foreground time is available.
* **Screen-time limits alert, they do not block apps.**
* The in-memory rate limiter is per backend instance; multi-instance deployments need a shared store.
* NAT traversal without TURN will fail on some networks (symmetric NAT / carrier-grade NAT); configure coturn.

## Documentation

[PRD](PRD.md) · [Architecture](ARCHITECTURE.md) · [System design](docs/SYSTEM_DESIGN.md) ·
[UI/UX spec](docs/UI_UX_SPECIFICATION.md) · [Database schema](docs/DATABASE_SCHEMA.md) ·
[API](docs/API_DOCUMENTATION.md) · [Security](SECURITY.md) · [Privacy policy](docs/PRIVACY_POLICY.md) ·
[Roadmap & progress log](DEVELOPMENT_ROADMAP.md) · [Testing](TESTING.md) · [Build guide](BUILD_GUIDE.md) ·
[Deployment](docs/DEPLOYMENT.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) · [Contributing](CONTRIBUTING.md) ·
[Changelog](CHANGELOG.md) · [Android manifest & permissions](docs/ANDROID_MANIFEST.md) ·
[Dependency inventory](docs/DEPENDENCIES.md)
