# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com/),
versioning: [SemVer](https://semver.org/).

## [Unreleased]

## [1.1.0] — 2026-11-20

### Added
- **In-app server setting** (sign-in screen → Server): enter the backend address, test the connection
  (`/actuator/health`) and save it; persisted per install. Release builds accept HTTPS only. A *Connect to your server
  first* card appears when no real server is configured (previously the app silently used a placeholder host and failed
  with a generic "Unable to reach the server").
- Specific network error messages (unknown host, timeout, refused, untrusted certificate) with a shortcut to Server settings.
- Per-ABI release APKs (arm64-v8a ≈ 13.6 MB instead of the 45 MB universal APK; universal still produced).
- `docs/SERVER_QUICKSTART.md`.

### Changed
- Redesigned sign-in/registration: brand header, Sign in / Create account tabs, role selector with icons, leading
  field icons, keyboard Next/Done actions (Done submits), live password-length check, animated error banner, privacy note.
- Motion: animated role/screen transitions, NavHost enter/exit transitions, animated usage bars and status changes.
- Debug builds allow plain HTTP to any host (for LAN development servers); release remains HTTPS-only.

### Fixed
- When the server ended a session (rejected/reused refresh token), background sync and the realtime socket kept running;
  they now stop and local data is cleared on any sign-out.
- Child dashboard re-opened the screen-share consent screen every time it was shown while a request was pending.
- Parent dashboard refreshed once per realtime event; bursts are now debounced and overlapping refreshes skipped.

## [1.0.0] — 2026-11-20 — first functional prototype (not production-ready)

### Added
- Android app (single APK, Parent and Child roles): Compose/Material 3 UI with light/dark themes, Hilt, Room,
  DataStore + Android Keystore session encryption, Retrofit/OkHttp with serialised refresh-token rotation,
  WorkManager usage sync and alert polling, WebSocket realtime client, WebRTC screen sharing
  (MediaProjection foreground service on the child, receive-only viewer on the parent).
- Parent screens: dashboard, device list, pairing, device details, screen-time reports (week/month), app activity
  ranking, live screen viewer, safety alerts, per-device safety settings, account settings.
- Child screens: pairing (with "what is shared" disclosure), dashboard, permissions, screen-sharing consent,
  monitoring status, activity summary, safety (check-in / report / help), privacy settings & disconnect request.
- Backend (Spring Boot 3.5, Kotlin, PostgreSQL, Flyway): auth with Argon2id and JWT + rotating refresh tokens with
  reuse detection, pairing codes, device revocation, usage sync & reports, screen-sharing state machine and
  WebSocket signaling relay, safety alerts & settings, audit log, rate limiting, HTTPS enforcement, retention purge.
- OpenAPI 3.0 specification, Docker Compose (PostgreSQL, backend, Caddy TLS proxy, optional coturn), scripts,
  dependency verification metadata, full documentation set.
- Tests: 46 backend (incl. WebSocket and E2E), 107 Android test executions (unit, Robolectric, Compose UI).

### Known limitations
- Not yet verified on physical devices; no FCM push; usage data is periodic, not real-time; limits alert but do not block.
