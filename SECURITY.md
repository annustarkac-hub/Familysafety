# Security

## Reporting a vulnerability
Please report privately to the maintainers (do not open a public issue). Include reproduction steps and impact.

## Threat model (summary)

| Asset | Threats | Controls |
|---|---|---|
| Accounts | Credential stuffing, brute force | Argon2id; per-IP rate limit on auth (10/min); lockout after 5 failures (15 min); generic error messages; timing equalisation for unknown emails |
| Sessions | Token theft, replay | 15-min JWT; opaque single-use refresh tokens stored hashed (SHA-256); rotation with **reuse detection → whole token family revoked**; absolute session lifetime 90 days; server-side session check on every request |
| Pairing | Guessing codes, hijacking a child | Parent must create the code (auth #1) and child must enter it on their own authenticated device (auth #2); 8 chars / 31-symbol alphabet; 10-min expiry; single use; HMAC-SHA256 with server pepper at rest; 5 attempts/min and 20/hour per child account; max 5 active codes per family |
| Family data | Horizontal privilege escalation | Every query authorised by `AccessPolicy` (role + family membership + ACTIVE pairing); foreign resources return 404; denied attempts are audited |
| Screen sharing | Covert viewing, hijacked signaling | Child in-app approval + Android MediaProjection consent per session; visible ongoing notification; signaling relayed only between the session's requesting parent and the child device; offer only from child, answer only from parent; pairing re-checked on every signal; time limits (90 s / 2 min / 30 min); one open session per device (DB constraint); media E2E-encrypted by WebRTC (DTLS-SRTP); nothing stored |
| Device loss | Continued access | Parent revocation: device + all its sessions revoked, WebSocket closed, open screen sessions stopped |
| Data at rest (phone) | Extraction | Tokens AES-256-GCM with a non-exportable Android Keystore key; `allowBackup=false` and data-extraction rules exclude everything |
| Data in transit | MITM | Release network-security config: cleartext disabled, system CAs only (user CAs not trusted); backend `REQUIRE_HTTPS` rejects non-TLS requests; HSTS |
| Logs | Secret leakage | No tokens/codes/passwords logged; OkHttp logging only in debug with `Authorization` redacted; audit details exclude secrets (tested) |

## Implemented controls (where in code)

| Control | Location |
|---|---|
| Argon2id (19 MiB, t=2, p=1) | `backend/.../config/SecurityConfig.kt` |
| JWT issue/verify (HS256, issuer check) | `security/TokenService.kt` |
| Active-session & device-revocation check per request | `security/SessionAwareJwtConverter.kt` |
| Refresh rotation + reuse detection | `service/AuthService.kt#refresh` |
| Rate limiting (auth, general, pairing) | `security/RateLimiter.kt`, `RateLimitFilter.kt`, `PairingService.kt` |
| HTTPS enforcement + HSTS | `security/RateLimitFilter.kt` (`HttpsEnforcementFilter`) |
| Server-side authorization | `service/AccessPolicy.kt` |
| Audit log (REQUIRES_NEW tx) | `audit/AuditService.kt` |
| Request validation | `web/Dtos.kt` + `GlobalExceptionHandler` |
| Security headers (nosniff, DENY frames) | `SecurityConfig.kt` |
| WebSocket auth (header only, never URL) | `realtime/WebSocketConfig.kt` |
| Android token encryption | `core/.../security/SecretCipher.kt`, `data/.../session/SessionStore.kt` |
| Serialised refresh on Android | `data/.../session/TokenRefresher.kt` |
| Release network security config | `android/app/src/main/res/xml/network_security_config.xml` |
| Signing secrets outside VCS | `android/app/build.gradle.kts` (env vars / untracked `keystore.properties`), `.gitignore` |
| Dependency verification | `android/gradle/verification-metadata.xml`, `backend/gradle/verification-metadata.xml` |

## Secrets management
* No secrets are committed. `.env`, keystores and `keystore.properties` are git-ignored.
* Backend refuses to start without `JWT_SECRET` and `PAIRING_PEPPER` (≥ 32 bytes, base64).
* Rotate `JWT_SECRET` to invalidate all access tokens (refresh tokens remain valid and will mint new ones).
  Rotate `PAIRING_PEPPER` only when no codes are outstanding.

## Security review findings (self-review) and residual risks

| # | Finding | Status |
|---|---|---|
| 1 | Rate limiter is in-memory per instance | **Residual** — use Redis/bucket4j for multi-instance deployments |
| 2 | Email enumeration via `409 EMAIL_ALREADY_REGISTERED` on registration | **Accepted** (UX trade-off), mitigated by rate limiting |
| 3 | No certificate pinning | **Residual** — consider pinning the API's public key once the host is fixed; system-CA-only trust is enforced |
| 4 | WebSocket `setAllowedOriginPatterns("*")` | Accepted: auth is by bearer header, which browsers cannot set cross-origin on WS upgrades |
| 5 | Without TURN, media may relay nowhere (connection fails) — never insecurely | Documented; configure coturn |
| 6 | HS256 shared secret (single service) | Accepted; switch to asymmetric keys if tokens must be verified by other services |
| 7 | Android `onCleared` stopping a session uses app scope (no main-thread blocking) | Fixed during review |
| 8 | `SELECT … FOR UPDATE` inside read-only transaction | Fixed during review (status endpoint) |
| 9 | Expiry transitions rolled back by exceptions | Fixed (`noRollbackFor`) and covered by tests |
| 10 | Device-level verification of MediaProjection/foreground-service flow | **Not yet performed** on hardware |

No critical vulnerabilities are known in the implemented code. This has **not** had an independent third-party
security assessment; one is recommended before production use.
