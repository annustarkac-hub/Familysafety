# Architecture

## Overview

```
┌──────────────── Android (one APK, com.familysafety.app) ────────────────┐
│ app            MainActivity · RoleRouter-driven AppRoot · Auth UI · Hilt │
│ feature-parent  Compose screens + ViewModels (MVVM)                      │
│ feature-child   Compose screens + ViewModels + ScreenCaptureService      │
│ data           Repositories · Retrofit/OkHttp · Room · DataStore ·       │
│                WorkManager · WebSocket client · WebRTC peers             │
│ core           Material 3 theme/components · Keystore cipher · LoadState  │
│ domain (JVM)   Models · repository interfaces · pure business logic      │
└───────────────┬──────────────────────────────┬──────────────────────────┘
       HTTPS REST│ (JWT bearer)        WSS /ws   │ (JWT on upgrade)
                 ▼                               ▼
┌────────────────────── Backend (Spring Boot 3.5, Kotlin) ─────────────────┐
│ web (controllers, DTO validation) → service (authz via AccessPolicy,     │
│ state machines, audit) → repository (Spring Data JPA) → PostgreSQL 16    │
│ realtime: WebSocket handler, ConnectionRegistry, signaling relay          │
│ security: JWT (HS256) + session check, Argon2id, rate limiting, HTTPS     │
│ scheduled: screen-session expiry sweep (10 s), retention purge (daily)    │
└──────────────────────────────────────────────────────────────────────────┘
        WebRTC media: child ⇄ parent peer-to-peer (STUN) or via TURN (coturn)
```

## Android

### Module dependency graph

```
app ──► feature-parent ──► data ──► core ──► domain
  │ ──► feature-child  ──► data
  └───► data, core
```

* **domain** is a plain Kotlin/JVM module with no Android dependencies, so business rules are fast to unit test:
  `UsageAggregator` (event stream → sessions → per-day totals), `RoleRouter`, `ScreenShareLifecycle` (pure
  reducer for the child-side consent state machine), `Formatting` (durations, pairing codes, credentials).
* **data** implements the repository interfaces. It owns all I/O and exposes `Flow`/`StateFlow`.
* **feature-*** modules contain only UI and ViewModels and depend on interfaces from domain.
* **app** wires Hilt, WorkManager (`Configuration.Provider` with `HiltWorkerFactory`), and the top-level role routing.

### Patterns
* **MVVM**: `@HiltViewModel`s expose `StateFlow` UI state (`LoadState<T>` for loading/error/ready); Compose
  collects with `collectAsStateWithLifecycle`.
* **Repository pattern** with `AppResult<T>` / `AppError` so UI never sees transport exceptions.
* **Single source of truth**: `SessionStore` (encrypted DataStore) drives `AuthRepository.authState`; the device list is
  cached in Room and refreshed from the API; local usage aggregation is cached in Room.
* **Coroutines/Flow** everywhere; an application-scoped `CoroutineScope` for work that must outlive a screen.

### Authentication on the client
* `AuthHeaderInterceptor` attaches the bearer token (never to `/auth/login|register|refresh`).
* `TokenAuthenticator` handles `401` by calling `TokenRefresher`, which **serialises** refreshes: the backend treats
  refresh tokens as single-use and revokes the token family on reuse, so two parallel refreshes would sign the user
  out. If the server rejects the refresh, local credentials are cleared and the UI returns to sign-in.
* Tokens are encrypted with AES-256-GCM using a non-exportable Android Keystore key (`KeystoreSecretCipher`).

### Realtime
`RealtimeClient` (OkHttp WebSocket) authenticates with the `Authorization` header and reconnects with exponential
backoff (1 s → 30 s). `RealtimeLifecycle` keeps it connected **only while the app is in the foreground** or while a
component holds a keep-alive (the screen-capture foreground service). There is no hidden persistent background socket.

### Activity monitoring (and its limits)
* `AndroidUsageCollector` reads `UsageStatsManager.queryEvents` for the last 7 days, maps `ACTIVITY_RESUMED/PAUSED`,
  `SCREEN_NON_INTERACTIVE`, `DEVICE_SHUTDOWN` to the domain model, excludes the launcher and System UI, and aggregates
  with `UsageAggregator` (implicit app switches, activity-transition merging, midnight splitting, open-session capping).
* **Limitations (by design of Android):**
  - Requires the user-granted *Usage access* special permission (`PACKAGE_USAGE_STATS`). Without it, nothing is collected.
  - It is **not real-time**: the event log is read on a periodic WorkManager job (minimum interval 15 min) and when the
    child opens the app. Doze, App Standby buckets and OEM battery managers can defer jobs for hours.
  - The OS retains detailed events only for a limited period (typically about a week), so only recent days are collected.
  - Only foreground time per app is observable. In-app content, URLs, messages, etc. are not — and must not be — observed.
  - Values can differ slightly from the system "Digital Wellbeing" figures, which use private aggregation rules.

### Screen sharing (child side)
1. `ScreenShareCoordinator` (singleton) receives `screen_share.requested` (WebSocket) or finds a pending request by
   polling `/screen-sharing/status` on resume, and shows a high-priority notification.
2. The consent screen shows who is asking and what will happen. **Approve** → `POST /approve` (returns ICE servers).
3. The app launches `MediaProjectionManager.createScreenCaptureIntent()` — Android's own consent dialog.
4. On `RESULT_OK`, `ScreenCaptureService` starts as a **foreground service of type `mediaProjection`** with an ongoing
   notification and a Stop action, then `ScreenSharePublisher` captures with WebRTC's `ScreenCapturerAndroid`
   (≤1280 px long edge, 15 fps, no audio) and sends a **send-only** offer via the signaling channel.
5. The parent's `ScreenShareViewer` answers (receive-only) and renders into a `SurfaceViewRenderer`.
6. Any end condition (child stop, parent stop, server expiry/revocation, MediaProjection stopped by the system,
   peer failure) tears down capture and the service. The service refuses to start without a matching approved session.

## Backend

* **Layers**: controllers (validation with Jakarta Bean Validation) → services (business rules, authorization through
  `AccessPolicy`, audit) → Spring Data repositories → PostgreSQL (Flyway-managed schema, `ddl-auto=validate`).
* **Security**: stateless; `SessionAwareJwtConverter` verifies the JWT signature *and* that the referenced
  `authentication_sessions` row is still ACTIVE and the device not revoked — so logout/revocation are immediate.
* **Realtime**: one raw WebSocket endpoint `/ws`. `ConnectionRegistry` indexes live connections by user, device and auth
  session. Events are published **after commit** (`afterCommit {}`) so clients never observe rolled-back state.
* **Screen-sharing state machine** in `ScreenSharingService` with pessimistic row locks; a partial unique index
  guarantees at most one open session per device.
* **Scheduling**: expiry sweep every 10 s; retention purge daily at 03:17 UTC.

See [docs/SYSTEM_DESIGN.md](docs/SYSTEM_DESIGN.md) for sequence diagrams and [docs/DATABASE_SCHEMA.md](docs/DATABASE_SCHEMA.md).

## Technology choices

| Concern | Choice | Why |
|---|---|---|
| UI | Jetpack Compose + Material 3 | Required; modern, accessible defaults |
| DI | Hilt | Required; first-class ViewModel/Worker support |
| Persistence | Room (cache), DataStore (session) | Required; DataStore + Keystore instead of deprecated EncryptedSharedPreferences |
| Network | Retrofit + OkHttp + kotlinx.serialization | Required; no reflection-based JSON, R8-friendly |
| WebRTC | `io.getstream:stream-webrtc-android` 1.3.8 | Maintained prebuilt of upstream libwebrtc (Google no longer publishes Android artifacts) |
| Backend | Spring Boot 3.5, Spring Security resource server (Nimbus JWT), Spring Data JPA, Flyway | Required stack |
| Password hashing | Argon2id (Bouncy Castle via Spring Security) | OWASP-recommended |
| Tests | JUnit, MockK, Turbine, Robolectric, Compose UI test, MockWebServer, Testcontainers | |
