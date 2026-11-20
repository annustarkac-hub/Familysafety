# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com/),
versioning: [SemVer](https://semver.org/).

## [Unreleased]

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
