# Testing

## Run everything (no device needed)

```bash
./scripts/test-all.sh     # backend tests + Android unit/UI tests + Android lint
```

Requirements: Docker (backend tests start PostgreSQL 16 via Testcontainers), JDK 21, Android SDK 35.

## Backend (`backend/`, `./gradlew test`) — 46 tests

| Suite | What it proves |
|---|---|
| `AuthIntegrationTest` | Parent registration creates a family; Argon2id hashes; case-insensitive unique email; validation errors with field list; generic login failures; lockout after 5 failures; refresh rotation (old access token stops working); **refresh-token reuse revokes the whole token family**; logout revokes immediately |
| `PairingAndAuthorizationTest` | Code format & hashed storage; single use; expiry (410); per-child rate limit (429); role checks (child can't create, parent can't accept); unauthenticated/forged tokens (401); **cross-family isolation** (404 on device, usage, revoke, screen-share); child can't read another child's data; revocation signs the device out and unpairs it; disconnect request alert |
| `UsageIntegrationTest` | Sync → daily/weekly/monthly/ranking computed correctly; child can read own data; snapshot idempotency; validation (future dates, >24 h, bad package names, empty); unpaired/parent sync rejected; limit alert raised once (dedupe) and visible to child |
| `ScreenSharingWebSocketTest` | WS handshake requires a valid token; **full consent flow over WebSocket**: request event → signaling refused before approval → approve with ICE servers → direction rules (only child offers) → offer/answer/ICE relay → ACTIVE → stop → no relay after stop; reject; one open session per device; disabled-by-settings; request & start expiry; **revocation stops the session and closes the device socket**; child disconnect ends session |
| `SecurityAndDatabaseTest` | Flyway created all 13 tables; DB-level unique constraints (email, one open session); security headers; no stack traces in errors; audit log without secrets; retention purge; malformed/oversized bodies |
| `HttpsEnforcementTest`, `RateLimitTest` | Plain HTTP refused when `require-https`; auth rate limiting with `Retry-After` |
| `EndToEndFlowTest` | The 10 required steps (below) over real HTTP + WebSocket |
| `unit/PureLogicTest` | Bedtime window across midnight; pairing-code normalisation; ranking aggregation |

## Android (`android/`, `./gradlew test`) — 142 test executions (debug + release variants of library modules)

| Module | Tests |
|---|---|
| `domain` (19) | `UsageAggregatorTest` (switches, merging, screen-off, window start, open sessions, **midnight split**, ranking), `RoleRouterTest`, `ScreenShareLifecycleTest` (**capture can't start without child approval**, permission denial, server termination, ignore concurrent requests), `FormattingTest` |
| `data` (25×2) | `TokenRefreshTest` (401 → refresh → retry; rejected refresh clears session; **4 concurrent 401s rotate once**; no bearer on auth endpoints), `AuthRepositoryTest` (register with role, login fetches pairing, error mapping, offline logout, **encrypted persistence**), `ErrorMappingTest`, `RoomDaoTest`, `RepositoryParsingTest`, `RealtimeParsingTest`, `UsagePermissionTest` (**nothing collected without usage access**, event-type mapping), `ScreenShareCoordinatorTest` |
| `core` (5×2) | Compose: error retry, empty state action, confirm dialog, password visibility a11y, chart content description in dark theme |
| `feature-parent` (5×2) | `PairDeviceViewModelTest` (realtime pairing completion), `ScreenViewerViewModelTest` (waits for consent; rejection ends without connecting; errors), `AlertRowUiTest` |
| `feature-child` (6×2) | `ChildPairingViewModelTest` (invalid codes never sent; expiry message), `ChildSafetyViewModelTest`, transparency UI tests |
| `app` (15, debug) | `AuthViewModelTest` (validation, role sent, errors), `AuthAndRoutingUiTest` (role selector, error rendering, loading route), `AuthServerUxTest` (setup-server prompt, host display, password check, network error → server settings, sheet error state), `ServerSettingsViewModelTest` (placeholder not prefilled, **saves only after the server answers**, unreachable not saved, invalid input makes no request) |
| `data` additions | `ServerUrlsTest`, `BaseUrlInterceptorTest` (requests really reach the configured server), `NetworkErrorsTest`, `ServerHealthCheckerTest`, `ServerConfigStoreTest`, `SessionEndedListenerTest` (background work stops when the server ends the session) |

Notes:
* Robolectric cannot provide `AndroidKeyStore`; tests use a reversible `FakeCipher`. The real Keystore cipher must be
  verified on a device.
* One Compose test triggers the role selector via its semantics click action: Robolectric did not deliver synthetic
  touch to `SegmentedButton`. The same `onClick` handler runs either way; on-device instrumented tests should cover touch.
* `:app` JVM tests run on the debug variant only (AGP doesn't merge test-only manifests into an application's
  release unit-test manifest).

## End-to-end scenario (required 10 steps)

| # | Step | Automated coverage | Physical device |
|---|---|---|---|
| 1 | Parent registration | `EndToEndFlowTest`, `e2e-smoke.py` | pending |
| 2 | Child registration | same | pending |
| 3 | Device pairing | same (+ realtime `pairing.completed`) | pending |
| 4 | Usage-data sync | same (API); `AndroidUsageCollector` logic via unit tests | **pending — needs UsageStatsManager on a real phone** |
| 5 | Parent dashboard updates | same (`usage.synced` event + REST data) | pending |
| 6 | Screen-sharing request | same | pending |
| 7 | Child approval | same | pending (also Android consent dialog) |
| 8 | Live screen transmission | **signaling only** (offer/answer/ICE relay over WS) | **pending — needs two devices** |
| 9 | Session termination | same | pending |
| 10 | Device revocation | same (+ socket closed, token rejected) | pending |

`scripts/e2e-smoke.py <baseUrl>` runs steps 1–10 (REST) against any running deployment; it passed against the
Docker Compose stack.

## Physical-device test plan

Run with two phones (Android 10+ recommended; include one Android 14+ for foreground-service-type rules) and a
backend reachable over HTTPS (or a debug build + LAN backend).

1. Install the release APK on both (`adb install FamilySafety-1.0.0-release.apk`); confirm launch, light & dark mode.
2. Phone A: register **Parent**. Phone B: register **Child**. Confirm role-specific home screens.
3. A: Devices → Pair → code. B: enter code → confirm dialog lists what is shared → paired. A updates in real time.
4. B: Permissions → grant **Usage access** and notifications. Use a few apps for ~5 minutes. B: Monitoring status →
   "Share activity summary now". A: device details show today's time and top apps; compare roughly with Digital Wellbeing.
5. Leave B idle 30+ minutes (screen off). Verify a background sync happened (A's "last seen"/totals update).
6. A: View screen → B shows notification + consent screen → Approve → Android capture dialog → Start now.
   Verify: A shows live video (< ~1 s latency on same Wi-Fi); B shows ongoing notification "Sharing your screen".
7. B: Stop via notification → A shows "Your child stopped sharing". Repeat and stop from A.
8. Repeat 6 on different networks (Wi-Fi ↔ mobile data). Without TURN this may fail — expected; with coturn it should work.
9. Revoke usage access on B → within ~15 min A receives "Usage access was turned off" alert.
10. B: Safety → "I need help" → A receives a high-priority notification (foreground immediately; background within the
    WorkManager polling window).
11. A: remove B's device while sharing → B's capture stops, B is signed out.
12. Reboot B → periodic sync resumes (WorkManager persists across reboot).
13. Accessibility: TalkBack through sign-in, pairing, consent and viewer screens; 200% font size.

Record results in `docs/reports/TEST_REPORT.md`.

## Reports
* Backend: `backend/build/reports/tests/test/index.html`
* Android: `android/<module>/build/reports/tests/test<Variant>UnitTest/index.html`
* Lint: `android/<module>/build/reports/lint-results-debug.html`
