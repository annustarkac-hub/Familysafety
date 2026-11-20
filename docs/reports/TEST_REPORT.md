# Test Report

Generated 2026-11-20 from the final verification run (`--rerun-tasks --no-build-cache` for Android; clean build for the
backend). Environment: Linux x86_64, OpenJDK 21.0.13, Gradle 8.14.3, Docker 29 / PostgreSQL 16 (Testcontainers),
Robolectric 4.14.1 (SDK 34 runtime). **No emulator or physical device** was available (no KVM, no USB device).

## Summary

| Area | Executed | Passed | Failed |
|---|---|---|---|
| Backend unit + integration + WebSocket + E2E | 46 | 46 | 0 |
| Android JVM / Robolectric / Compose UI (debug + release variants of libraries) | 107 | 107 | 0 |
| Android Lint (5 modules) | — | 0 errors | 0 |
| API smoke test vs Docker Compose stack (`scripts/e2e-smoke.py`) | 10 steps | 10 | 0 |
| TLS enforcement via Caddy (manual curl) | 2 checks | 2 | 0 |
| OpenAPI validation (`openapi-spec-validator`) | 1 | 1 | 0 |
| Physical-device test plan (TESTING.md) | **0** | — | **not run** |

Lint warnings remaining: 72 "newer dependency version available" notices (`GradleDependency`,
`AndroidGradlePluginVersion`) and 1 Compose `ModifierParameter` style warning. No correctness/security warnings.

### Backend (`backend`, JUnit 5, Testcontainers PostgreSQL 16)

| Suite | Tests | Failures | Time (s) |
|---|---|---|---|
| AuthIntegrationTest | 11 | 0 | 3.8 |
| EndToEndFlowTest | 1 | 0 | 1.1 |
| HttpsEnforcementTest | 1 | 0 | 0.1 |
| PairingAndAuthorizationTest | 10 | 0 | 4.0 |
| RateLimitTest | 1 | 0 | 0.4 |
| ScreenSharingWebSocketTest | 7 | 0 | 2.9 |
| SecurityAndDatabaseTest | 7 | 0 | 0.7 |
| UsageIntegrationTest | 5 | 0 | 1.9 |
| PureLogicTest | 3 | 0 | 0.0 |

### Android (`android`, JUnit 4 / Robolectric / Compose UI test)

| Module · task | Suite | Tests | Failures |
|---|---|---|---|
| app · testDebugUnitTest | AuthAndRoutingUiTest | 3 | 0 |
| app · testDebugUnitTest | AuthViewModelTest | 3 | 0 |
| core · testDebugUnitTest | ComponentsUiTest | 5 | 0 |
| core · testReleaseUnitTest | ComponentsUiTest | 5 | 0 |
| data · testDebugUnitTest | AuthRepositoryTest | 6 | 0 |
| data · testDebugUnitTest | ErrorMappingTest | 1 | 0 |
| data · testDebugUnitTest | RealtimeParsingTest | 2 | 0 |
| data · testDebugUnitTest | RepositoryParsingTest | 3 | 0 |
| data · testDebugUnitTest | RoomDaoTest | 2 | 0 |
| data · testDebugUnitTest | ScreenShareCoordinatorTest | 4 | 0 |
| data · testDebugUnitTest | TokenRefreshTest | 4 | 0 |
| data · testDebugUnitTest | UsagePermissionTest | 3 | 0 |
| data · testReleaseUnitTest | AuthRepositoryTest | 6 | 0 |
| data · testReleaseUnitTest | ErrorMappingTest | 1 | 0 |
| data · testReleaseUnitTest | RealtimeParsingTest | 2 | 0 |
| data · testReleaseUnitTest | RepositoryParsingTest | 3 | 0 |
| data · testReleaseUnitTest | RoomDaoTest | 2 | 0 |
| data · testReleaseUnitTest | ScreenShareCoordinatorTest | 4 | 0 |
| data · testReleaseUnitTest | TokenRefreshTest | 4 | 0 |
| data · testReleaseUnitTest | UsagePermissionTest | 3 | 0 |
| domain · test | FormattingTest | 3 | 0 |
| domain · test | RoleRouterTest | 1 | 0 |
| domain · test | ScreenShareLifecycleTest | 6 | 0 |
| domain · test | UsageAggregatorTest | 9 | 0 |
| feature-child · testDebugUnitTest | ChildPairingViewModelTest | 3 | 0 |
| feature-child · testDebugUnitTest | ChildSafetyViewModelTest | 1 | 0 |
| feature-child · testDebugUnitTest | ChildTransparencyUiTest | 2 | 0 |
| feature-child · testReleaseUnitTest | ChildPairingViewModelTest | 3 | 0 |
| feature-child · testReleaseUnitTest | ChildSafetyViewModelTest | 1 | 0 |
| feature-child · testReleaseUnitTest | ChildTransparencyUiTest | 2 | 0 |
| feature-parent · testDebugUnitTest | AlertRowUiTest | 1 | 0 |
| feature-parent · testDebugUnitTest | PairDeviceViewModelTest | 2 | 0 |
| feature-parent · testDebugUnitTest | ScreenViewerViewModelTest | 2 | 0 |
| feature-parent · testReleaseUnitTest | AlertRowUiTest | 1 | 0 |
| feature-parent · testReleaseUnitTest | PairDeviceViewModelTest | 2 | 0 |
| feature-parent · testReleaseUnitTest | ScreenViewerViewModelTest | 2 | 0 |

## E2E smoke output (Docker Compose stack: postgres + backend, then + Caddy)

```
[ 1] parent registration
[ 2] child registration
[ 3] pairing (parent creates code, child accepts)
[ 4] usage-data synchronisation
[ 5] parent dashboard data
[ 6] screen-sharing request
[ 7] child approval
[ 8] live transmission: signaling/media require two devices (see TESTING.md); checking status only
[ 9] session termination
     safety: child check-in reaches parent
[10] device revocation
PASS: all smoke steps succeeded against http://127.0.0.1:8080
```

TLS check with `REQUIRE_HTTPS=true`:
```
POST http://127.0.0.1:8080/api/v1/auth/login  → 403 {"code":"HTTPS_REQUIRED",...}
POST https://localhost/api/v1/auth/login (Caddy) → HTTP/2 401 INVALID_CREDENTIALS, strict-transport-security present
```

## Defects found and fixed during testing
1. Screen-session expiry transitions were rolled back when the request then failed (missing `noRollbackFor`).
2. `GET /screen-sharing/status?sessionId=` used `SELECT … FOR UPDATE` in a read-only transaction (500).
3. Hibernate schema validation: `char(64)` vs `varchar` for hash columns.
4. Lint `MissingPermission`: notification posting not guarded by an explicit `POST_NOTIFICATIONS` check.
5. ViewModel `onCleared` performed a blocking network call on the main thread (moved to application scope).
6. Test-infrastructure issues (Robolectric DataStore singleton, Compose test manifest on release variants, Hilt
   application under Robolectric, Maven mirror) — fixed without weakening assertions.

## Not verified (requires hardware)
UsageStatsManager on a real device; Android Keystore encryption; MediaProjection consent + foreground service on
Android 14/15; WebRTC capture, encoding and playback between two phones; notifications and background WorkManager
timing under Doze; release APK installation and launch; TalkBack walkthrough. See the device plan in TESTING.md.
