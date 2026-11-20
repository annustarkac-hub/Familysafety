# Development Roadmap & Progress Log

Legend: ✅ done and verified by automated tests/builds · 🟡 implemented, needs physical-device verification · ⬜ not started

## Phases

| Phase | Scope | Status |
|---|---|---|
| 1 Repository & architecture | Monorepo, module boundaries, Gradle KTS + version catalog, wrappers, .gitignore | ✅ |
| 2 Database & backend foundation | Flyway schema (13 tables), entities, repositories, error model, security config | ✅ |
| 3 Android foundation & auth | Hilt, Compose theme, Keystore-encrypted session, Retrofit, token refresh, auth UI | ✅ (Keystore on device 🟡) |
| 4 Parent & Child interfaces | All screens in UI/UX spec, role routing, light/dark, empty/error/loading states | ✅ compiled + UI tests · 🟡 visual QA on device |
| 5 Device pairing | Codes, accept, realtime completion, revocation, disconnect request | ✅ |
| 6 Activity monitoring | UsageStatsManager collector, aggregator, WorkManager sync, reports | ✅ logic/API · 🟡 on-device collection |
| 7 Live screen sharing | Consent state machine, MediaProjection FGS, WebRTC publisher/viewer, signaling relay, expiry | ✅ server + state machines + signaling · 🟡 capture & video between two devices |
| 8 Communication safety | Check-ins, incident reports, help requests, check-in requests, limit/bedtime alerts, notifications | ✅ API/realtime · 🟡 device notifications |
| 9 Security hardening | Argon2id, rotation + reuse detection, rate limits, HTTPS enforcement, audit, NSC, backup rules | ✅ |
| 10 Automated testing & debugging | 46 backend + 107 Android test executions, E2E test, smoke script | ✅ |
| 11 Release configuration & APK | R8, signing via env, versioning, dependency verification, release APK + SHA-256 | ✅ built & signature-verified · 🟡 install on device |
| 12 Documentation & delivery | All docs, OpenAPI, reports | ✅ |

## Next steps (proposed)

1. **Physical-device validation** of the test plan in [TESTING.md](TESTING.md#physical-device-test-plan); fix findings.
2. **Push delivery** (Firebase Cloud Messaging high-priority data messages) for screen-share requests and urgent alerts
   when the app is in the background. Requires a Firebase project and `google-services.json` (not committed).
3. **TURN in production** (coturn with TLS on 5349) and TURN-only fallback testing on carrier networks.
4. Shared rate-limit store (Redis) for horizontal scaling; move the expiry sweep to a leader-elected job.
5. Co-parent invitations (multiple parents per family) and family management screens.
6. Instrumented UI tests on Firebase Test Lab / device farm; Macrobenchmark + Baseline Profiles.
7. Certificate/public-key pinning once the API hostname is fixed.
8. Data export & account deletion endpoints (GDPR/CCPA self-service).
9. Upgrade toolchain (AGP 8.x → latest, Kotlin 2.1+), keeping dependency verification in sync.

## Progress log

| Date (UTC) | Entry |
|---|---|
| 2026-11-20 | Inspected environment: JDK 21, Gradle 8.14.3, Docker 29, PostgreSQL 16; no Android SDK → installed cmdline-tools, platform 35, build-tools 35. No KVM → no emulator; no physical device attached. |
| 2026-11-20 | Backend: schema, entities, JWT/Argon2id security, AccessPolicy, auth/pairing/device/usage/screen-sharing/safety services, WebSocket signaling, retention. First test run: 3 failures → fixed real bugs (expiry rollback via `noRollbackFor`; `SELECT FOR UPDATE` in read-only tx) and one test-ordering assumption. 46/46 passing. Commit `5f534c0`. |
| 2026-11-20 | Android: domain (aggregator, router, lifecycle) 19/19 tests; core, data (network, session, Room, WorkManager, realtime, WebRTC), feature modules and app compiled; debug APK built. Commit `c1bd727`. |
| 2026-11-20 | Maven Central redirected Robolectric's large artifact to a blocked mirror → added opt-in `familysafety.mavenMirror` property. Android tests: fixed test-infra issues (DataStore singleton per process, Compose test manifest on release variants, Hilt Application needing Keystore under Robolectric). 107 executions passing. |
| 2026-11-20 | Lint: fixed `MissingPermission` (POST_NOTIFICATIONS guard) and `ObsoleteSdkInt`; 0 errors. Release: R8 build signed with a locally generated key outside the repo; apksigner verified v2+v3; R8 keep rules confirmed by dex inspection. Commit `26b7763`. |
| 2026-11-20 | Docker Compose stack (postgres + backend + Caddy TLS) started; `scripts/e2e-smoke.py` passed 10/10; TLS enforcement verified (HTTP → 403, HTTPS via proxy → API). Dependency verification metadata generated for both builds. OpenAPI spec validated. Documentation written. |
| 2026-11-20 | Final verification: backend clean test 46/46; Android `test lint --rerun-tasks --no-build-cache` 107/107, lint 0 errors; release APK rebuilt via `scripts/build-apk.sh`, apksigner verified, SHA-256 `3df6cd35…f57a5`. Reports in `docs/reports/`. Physical-device verification remains outstanding. |
