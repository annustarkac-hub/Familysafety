# System Design

## Components

| Component | Responsibility | Scaling notes |
|---|---|---|
| Android app | UI, on-device usage aggregation, screen capture/playback, secure session storage | — |
| Backend API | AuthN/AuthZ, persistence, state machines, signaling relay, scheduled jobs | Stateless REST; WebSocket connections are per-instance (see below) |
| PostgreSQL 16 | System of record | Single primary; retention keeps tables bounded |
| Caddy | TLS termination, HSTS, HTTP/2 | Any TLS-terminating proxy that forwards `X-Forwarded-Proto` works |
| coturn (optional) | TURN relay for WebRTC behind restrictive NAT | Bandwidth-heavy: ~0.5–1.5 Mbit/s per relayed session |
| STUN | Public address discovery | Default `stun.l.google.com:19302`; configurable |

## Key flows

### Registration & token lifecycle
```
App ── POST /auth/register {role, device} ─► API: Argon2id hash, user (+family if PARENT), device upsert,
                                               auth_session(refresh hash) ─► {access JWT 15m, refresh 30d}
App ── any request (Bearer) ─────────────────► API: verify JWT sig/issuer/exp + session ACTIVE + device ACTIVE
App ◄─ 401 ── (expired) ; App ── POST /auth/refresh {r1} ─► API: r1 ACTIVE → mark ROTATED, issue r2 (same family)
Attacker ── POST /auth/refresh {r1} (replay) ─► API: r1 ROTATED → revoke ALL ACTIVE sessions in family → 401 REUSED
```

### Pairing
```
Parent ── POST /pairing/create ─► code K7QM-2XRP (HMAC stored, 10 min, single use)
Parent ··· tells child the code (in person) ···
Child  ── POST /pairing/accept {code} ─► token locked FOR UPDATE → consumed; family_members(CHILD);
                                         device_pairings(ACTIVE); safety_settings(default); alert DEVICE_PAIRED
API ── WS pairing.completed ─► Parent
```

### Usage synchronisation
```
WorkManager (15 min, network) ─► UsageSyncWorker
  └ UsageStatsManager.queryEvents(last 7 days) → UsageAggregator → Room (local cache)
  └ POST /usage/sync {timezone, days[...]}  (snapshot per day; idempotent)
        API: replace app_usage_records(day), upsert daily_usage_summaries,
             evaluate limit/bedtime → safety_alerts (dedupe per device+day) → WS safety.alert
        API ── WS usage.synced ─► Parents (dashboard refreshes)
```

### Screen sharing (consent + signaling)
```
Parent ─ POST /screen-sharing/request ─► API: REQUESTED (90 s)  ── WS screen_share.requested ─► Child
                                                                    (+ notification; or found by polling /status)
Child UI: "Pat wants to see your screen" [Approve] [Decline]
Child ─ POST /approve ─► API: APPROVED (start within 2 min), returns ICE servers ── WS updated ─► Parent
Child: MediaProjection consent dialog ── granted ─► ScreenCaptureService (FGS mediaProjection, ongoing notification)
Child ─ WS signal{offer} ─► API: check participant/direction/pairing; APPROVED→ACTIVE (30 min max) ─► Parent
Parent ─ WS signal{answer}, {ice}… ─► API ─► Child          (media: DTLS-SRTP P2P or via TURN; never via API)
Either ─ POST /stop  |  expiry sweep  |  device revoked  |  participant WS lost ─► STOPPED/EXPIRED/FAILED ─► both
```

### Device revocation
```
Parent ─ DELETE /devices/{id} ─► pairing REVOKED, device REVOKED, auth sessions REVOKED, open screen sessions STOPPED,
                                  membership removed, alert DEVICE_REVOKED
       after commit ─► WS device.revoked (child + parents) ─► close child sockets
Child's next request ─► 401 (session revoked) ─► app clears local data, shows sign-in
```

## Consistency & concurrency
* Realtime events are emitted **after commit** to avoid phantom notifications.
* Pairing tokens and screen sessions use `PESSIMISTIC_WRITE` locks for state transitions.
* DB invariants: one ACTIVE pairing per device, one open screen session per device, one CHILD membership per user,
  unique `(device, date, package)` usage rows, unique alert `dedupe_key`.
* Client refresh-token use is serialised (`TokenRefresher`) because the server treats reuse as theft.

## Failure handling
| Failure | Behaviour |
|---|---|
| Child offline when request sent | Request expires after 90 s; parent sees "expired". Child sees pending requests on resume (polling). |
| WebSocket drops mid-session | Server ends APPROVED/ACTIVE sessions of a participant with no remaining connection (`PARTICIPANT_DISCONNECTED`). Clients reconnect with backoff. |
| ICE fails (NAT) | Peer state FAILED → child stops with `CONNECTION_FAILED`; parent shows guidance. Configure TURN. |
| Sync fails (network/5xx) | WorkManager exponential backoff; local cache retained; snapshots make retries safe. |
| Usage access revoked | Nothing collected; child notified locally; parents alerted once per day. |
| Backend restart | Sessions/tokens persist in DB; WS clients reconnect; expiry sweep resumes. |

## Horizontal scaling (future)
WebSocket delivery is in-process. For >1 instance: sticky sessions are **not** sufficient (parent and child may hit
different instances). Add a pub/sub fan-out (PostgreSQL `LISTEN/NOTIFY` or Redis) in `ConnectionRegistry.send*`, a
shared rate-limit store, and run the expiry sweep on one instance (ShedLock).

## Capacity estimates (per 10k families, 1.5 children avg)
* Usage rows: ~15k devices × ~40 apps/day × 90 days ≈ 54M rows → partition `app_usage_records` by month beyond this.
* Sync traffic: 15k devices × 96 syncs/day × ~10 KB ≈ 14 GB/day ingress (gzip reduces substantially).
* Screen sharing is bursty and P2P; TURN bandwidth is the main cost driver.
