# API Documentation

Machine-readable spec: [`openapi/openapi.yaml`](../openapi/openapi.yaml) (OpenAPI 3.0.3, validated).
This page summarises conventions, payloads and error codes.

## Conventions
* Base path `/api/v1`, JSON (UTF-8). Timestamps ISO-8601 UTC (`2026-11-20T12:00:00Z`), dates `yyyy-MM-dd`.
* Auth: `Authorization: Bearer <accessToken>` except `POST /auth/register|login|refresh`.
* Every response carries `X-Request-Id` (clients may send their own, 8–64 `[A-Za-z0-9-]`).
* Errors:
```json
{ "code": "VALIDATION_FAILED", "message": "Request validation failed", "status": 400,
  "path": "/api/v1/auth/register", "requestId": "…", "timestamp": "…",
  "fieldErrors": [ { "field": "password", "message": "must be between 12 and 128 characters" } ] }
```
* Resources belonging to another family return **404** (not 403). Wrong role returns **403 `FORBIDDEN_ROLE`**.
* `401` responses from the resource server have an empty body (expired/invalid/revoked token).

## Endpoints

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | /auth/register | public | Create account (role) + sign in device → `201 TokenResponse` |
| POST | /auth/login | public | Sign in → `TokenResponse` |
| POST | /auth/refresh | public | Rotate refresh token → `TokenResponse` |
| POST | /auth/logout | any | Revoke current session → `204` |
| GET | /auth/me | any | Profile, deviceId, paired |
| POST | /pairing/create | PARENT | `{childLabel?}` → `201 {pairingTokenId, code, expiresAt}` |
| POST | /pairing/accept | CHILD | `{code}` → `{pairingId, familyId, familyName, deviceId, pairedAt, parents[]}` |
| GET | /devices | any | Parent: family devices; child: own devices |
| GET | /devices/{id} | parent/child-own | Device details |
| DELETE | /devices/{id} | PARENT | Revoke → `204` |
| POST | /devices/{id}/disconnect-request | CHILD | Ask parents to disconnect → `202` |
| POST | /usage/sync | CHILD | Upload day snapshots |
| GET | /usage/daily?deviceId&date | parent/child-own | Day report |
| GET | /usage/weekly?deviceId&endDate | parent/child-own | 7-day report |
| GET | /usage/monthly?deviceId&month | parent/child-own | Month report |
| GET | /usage/applications?deviceId&from&to&limit | parent/child-own | Ranking |
| POST | /screen-sharing/request | PARENT | `{deviceId}` → `201 session` |
| POST | /screen-sharing/approve | CHILD | `{sessionId}` → session + `iceServers` |
| POST | /screen-sharing/reject | CHILD | `{sessionId}` |
| POST | /screen-sharing/stop | participant | `{sessionId, reason?}` |
| GET | /screen-sharing/status?sessionId \| deviceId | participant | `{active, recent[]}` |
| GET | /safety/alerts?deviceId&unacknowledgedOnly&limit&before | any | List |
| POST | /safety/alerts | role-dependent types | Create |
| POST | /safety/alerts/{id}/acknowledge | addressee | Acknowledge |
| GET | /safety/settings?deviceId | parent/child-own | Settings |
| PUT | /safety/settings | PARENT | Replace settings |

## Example payloads

**Register**
```json
POST /api/v1/auth/register
{ "email": "pat@example.com", "password": "correct horse battery staple", "displayName": "Pat", "role": "PARENT",
  "device": { "installationId": "6f1c…-uuid", "deviceName": "Google Pixel 8", "manufacturer": "Google",
              "model": "Pixel 8", "osVersion": "Android 15", "appVersion": "1.0.0" } }

201 { "accessToken": "eyJ…", "accessTokenExpiresAt": "…", "refreshToken": "q0Zr…", "refreshTokenExpiresAt": "…",
      "tokenType": "Bearer", "deviceId": "…",
      "user": { "id": "…", "email": "pat@example.com", "displayName": "Pat", "role": "PARENT",
                "familyId": "…", "familyName": "Pat's family" } }
```

**Usage sync**
```json
POST /api/v1/usage/sync
{ "timezone": "Europe/Berlin",
  "days": [ { "date": "2026-11-20", "firstActivityAt": "2026-11-20T06:55:00Z", "lastActivityAt": "2026-11-20T19:40:00Z",
              "apps": [ { "packageName": "org.example.reader", "appLabel": "Reader", "foregroundMs": 1800000,
                          "launchCount": 4, "lastUsedAt": "2026-11-20T19:40:00Z" } ] } ] }
200 { "acceptedDays": 1, "acceptedRecords": 1, "serverTime": "…" }
```

**Screen-sharing session**
```json
{ "id": "…", "deviceId": "…", "status": "APPROVED", "requestedByDisplayName": "Pat",
  "requestedAt": "…", "requestExpiresAt": "…", "approvedAt": "…", "maxEndAt": "…",
  "iceServers": [ { "urls": ["stun:stun.l.google.com:19302"] },
                  { "urls": ["turn:turn.example.com:3478"], "username": "1763650000:<userId>", "credential": "base64…" } ] }
```

## Validation rules (server-side)
| Field | Rule |
|---|---|
| email | RFC email, ≤ 254; unique case-insensitively |
| password | 12–128 chars |
| displayName | 1–80 |
| device.installationId | `^[A-Za-z0-9-]{8,64}$` |
| pairing code | 8 chars from `23456789ABCDEFGHJKMNPQRSTUVWXYZ` after removing separators |
| usage days | 1–31 per request; unique dates; within [today−35, today+1]; ≤ 500 apps/day |
| foregroundMs | 0–86 400 000 per app; day total ≤ 24 h (recomputed server-side) |
| packageName | `^[A-Za-z0-9_.]+$`, ≤ 255 |
| applications range | from ≤ to, ≤ 93 days; limit 1–200 |
| alert message | ≤ 1000; category ≤ 40 |
| settings | dailyLimitMinutes 15–1440 or null; bedtime both-or-neither and different; retention 7–365 |
| WebSocket message | ≤ 64 KiB; `kind` ∈ offer/answer/ice/bye |

## Error codes
| HTTP | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED, MALFORMED_REQUEST, INVALID_PARAMETER | Body/param validation |
| 400 | INVALID_PAIRING_CODE | Unknown, used or revoked code |
| 400 | INVALID_USAGE_DATE, INVALID_USAGE_TOTAL, DUPLICATE_DAYS, INVALID_ACTIVITY_RANGE, INVALID_RANGE, INVALID_BEDTIME, DEVICE_ID_REQUIRED | Domain validation |
| 401 | INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN, REFRESH_TOKEN_REUSED, REFRESH_TOKEN_EXPIRED, SESSION_REVOKED | Auth |
| 403 | FORBIDDEN_ROLE, SCREEN_SHARING_DISABLED, ALERT_TYPE_NOT_ALLOWED, AUTHORIZATION_REVOKED, INVALID_SIGNAL_DIRECTION, HTTPS_REQUIRED | Authorization / policy |
| 404 | NOT_FOUND, DEVICE_NOT_FOUND, SESSION_NOT_FOUND, ALERT_NOT_FOUND, FAMILY_NOT_FOUND, SETTINGS_NOT_FOUND | Missing or not visible |
| 409 | EMAIL_ALREADY_REGISTERED, DEVICE_ALREADY_PAIRED, ALREADY_IN_ANOTHER_FAMILY, DEVICE_NOT_PAIRED, SESSION_ALREADY_OPEN, INVALID_SESSION_STATE, ALERT_ALREADY_RAISED, CONFLICT | State conflicts |
| 410 | PAIRING_CODE_EXPIRED, SESSION_EXPIRED | Expired |
| 423 | ACCOUNT_LOCKED | Lockout |
| 429 | RATE_LIMITED, TOO_MANY_ACTIVE_CODES | Throttling (`Retry-After`) |
| 500 | INTERNAL_ERROR | Unexpected (details only in server logs) |

## WebSocket (`/ws`)
See `x-websocket` in the OpenAPI file. Summary: authenticate the upgrade with the bearer header; send
`{"type":"ping"}` / `{"type":"signal","sessionId":…,"payload":{"kind":…}}`; receive `screen_share.requested`,
`screen_share.updated`, `signal`, `pairing.completed`, `device.revoked`, `safety.alert`, `usage.synced`,
`safety.settings_updated`, `pong`, `error`.
