# Product Requirements Document — Family Safety

## 1. Problem

Parents want reassurance about how their children use their phones and a quick way to help when something
goes wrong. Many "parental monitoring" products achieve this with covert surveillance (reading messages,
keylogging, hidden recording), which harms children's trust and privacy, is often illegal, and violates
Google Play policy. Family Safety provides **transparent, consent-based** oversight instead.

## 2. Principles (non-negotiable)

1. **Transparency** — the child can always see what is shared, with whom, and when screen sharing is active.
2. **Consent per session** — every live screen view is approved by the child *and* by Android's own capture dialog.
3. **Minimisation** — only app foreground time and safety events are collected; never content.
4. **No covert features** — no hidden SMS/notification interception, keylogging, call recording, or stored recordings.
5. **Server-side authority** — the backend authorises every action independently of the client.

## 3. Users and roles

| Role | Description |
|---|---|
| Parent | Owns a family; pairs and manages child devices; views summaries; requests screen views; configures settings. |
| Child | Uses their own phone; pairs it into a family with a parent-issued code; controls permissions; approves sessions. |

A single APK serves both roles. The role is chosen at registration and bound to the account and to every
access token. A child account belongs to at most one family.

## 4. Functional requirements

### 4.1 Accounts & sessions
- FR-1 Register with email, password (12–128 chars), display name and role.
- FR-2 Sign in; account lockout after 5 failures for 15 minutes; rate limiting per IP.
- FR-3 Short-lived access tokens (15 min) with refresh-token rotation and reuse detection.
- FR-4 Sign out revokes the session server-side immediately.

### 4.2 Pairing
- FR-5 Parent creates a single-use code (8 chars, 10-minute expiry, max 5 active per family).
- FR-6 Child enters the code on their own device after reviewing what will be shared; device becomes paired.
- FR-7 Parents are notified in real time when pairing completes.
- FR-8 Parent can revoke a device: unpairs it, signs it out, ends any screen session.
- FR-9 Child can request disconnection (parents are notified and complete it).

### 4.3 Activity monitoring
- FR-10 With the child's "Usage access" permission, the app computes per-app foreground time, launch counts,
  last-used time and first/last activity per day from UsageStatsManager.
- FR-11 Data syncs about every 15 minutes (WorkManager) and on demand; sync is idempotent.
- FR-12 Parent views: today, last 7 days, calendar month, application ranking (today / 7 / 30 days).
- FR-13 Child views the same summaries about themselves.
- FR-14 If usage access is turned off, parents are told (once per day); nothing is collected.

### 4.4 Live screen sharing
- FR-15 Parent requests a session for a paired device (if enabled in settings).
- FR-16 Child sees the request (in-app, realtime, and notification) and approves or declines.
- FR-17 After approval, Android's MediaProjection consent dialog is shown; capture starts only if granted.
- FR-18 Video is streamed with WebRTC; signaling is relayed by the backend only between the two participants.
- FR-19 A persistent notification with a Stop action is shown for the whole session; in-app banner too.
- FR-20 Either side can stop. Sessions end automatically on request timeout (90 s), start timeout (2 min),
  max duration (30 min), device revocation, pairing loss, or participant disconnect.
- FR-21 Nothing is recorded or stored; no audio is captured.

### 4.5 Communication safety
- FR-22 Child: check in ("I'm OK"), report a concern (category + optional text), ask for help (urgent).
- FR-23 Parent: request a check-in.
- FR-24 Configurable risk alerts: daily screen-time limit exceeded; device used during bedtime.
- FR-25 System alerts: device paired/revoked, usage access revoked, screen-share declined, disconnect requested.
- FR-26 Alerts can be acknowledged; parents see unacknowledged counts.

### 4.6 Settings
- FR-27 Per-device: daily limit (off / 1–4 h), bedtime 21:00–07:00, limit & bedtime alerts, screen-sharing
  allowed, usage retention (30/90/180/365 days).

## 5. Non-functional requirements
- NFR-1 Android 8.0+ (minSdk 26), target SDK 35.
- NFR-2 TLS-only in release; no user-installed CAs trusted.
- NFR-3 Tokens encrypted at rest with an Android Keystore key; no app-data backup.
- NFR-4 Argon2id password hashing.
- NFR-5 Audit log of security-relevant actions (no secrets in logs).
- NFR-6 Accessibility: content descriptions, live regions for status changes, 48dp targets (Material defaults),
  light/dark themes, dynamic colour, layouts constrained for tablets.
- NFR-7 Retention purge runs daily.

## 6. Out of scope (deliberately)
App blocking/enforcement (requires device-owner or accessibility-service abuse), location tracking, message or
call monitoring, web filtering, recordings, iOS client.

## 7. Success criteria (Definition of Done mapping)

| DoD item | Evidence |
|---|---|
| Both roles in one APK | Single `applicationId`, role routing (`RoleRouter`) — unit + UI tests |
| Device pairing works | Backend integration/E2E tests; smoke test against Docker stack |
| Actual authorised usage data | `AndroidUsageCollector` + aggregator unit tests; **device test pending** |
| Screen sharing with explicit permission | State machine tests (server + client); signaling relay tests; **two-device test pending** |
| Parent dashboard shows real backend data | Repositories + ViewModels backed by API; E2E test |
| Safety notifications | Realtime + polling worker; backend tests; **device notification test pending** |
| Backend authorisation enforced | `PairingAndAuthorizationTest`, `ScreenSharingWebSocketTest` |
| Automated tests pass | 46 backend + 107 Android test executions, 0 failures |
| Release APK built and verified | `apksigner verify` (v2/v3) + SHA-256 in BUILD_REPORT; **install on device pending** |
| All deliverables documented | This repository's docs |
