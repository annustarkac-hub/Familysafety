# Deployment

> Family Safety is a prototype. Complete the physical-device test plan and an independent security review before
> serving real families.

## 1. Backend

### Requirements
* Linux host or container platform, JDK 21 runtime (image provided), PostgreSQL 16 (managed service recommended).
* A public DNS name for the API and TLS (Caddy in `docker-compose.yml` obtains certificates automatically).
* Optional coturn host with a public IP for TURN.

### Configuration (environment variables)

| Variable | Required | Description |
|---|---|---|
| `DATABASE_URL` | yes | `jdbc:postgresql://host:5432/familysafety` (use `?sslmode=require` for remote DBs) |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | yes | Least-privilege role owning the schema |
| `JWT_SECRET` | yes | base64, ≥ 32 bytes (`openssl rand -base64 48`) |
| `PAIRING_PEPPER` | yes | base64, ≥ 32 bytes |
| `REQUIRE_HTTPS` | default `true` | Must stay `true` in production |
| `FORWARD_HEADERS_STRATEGY` | default `native` | Trust `X-Forwarded-*` only from your proxy network |
| `ACCESS_TOKEN_TTL` / `REFRESH_TOKEN_TTL` | defaults `PT15M` / `P30D` | ISO-8601 durations |
| `STUN_URLS` | default Google STUN | Comma-separated |
| `TURN_URLS` / `TURN_SECRET` | optional | e.g. `turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349` + coturn `static-auth-secret` |
| `RATE_LIMIT_ENABLED` | default `true` | |
| `PORT` | default 8080 | |

Store secrets in your platform's secret manager; never in images or the repository.

### Docker Compose (single host)
```bash
cp .env.example .env && $EDITOR .env           # set secrets and API_HOSTNAME=api.your-domain.example
./scripts/build-backend.sh
docker compose up -d --build                   # postgres, backend, caddy (443)
docker compose --profile turn up -d coturn     # optional TURN
curl https://api.your-domain.example/actuator/health
python3 scripts/e2e-smoke.py https://api.your-domain.example
```
Open ports: 443/tcp (Caddy); for coturn 3478/udp+tcp, 5349/tcp, 49160–49200/udp.
In production remove the `127.0.0.1:8080` and `127.0.0.1:5432` port mappings if not needed.

### Database
Flyway migrates automatically on startup. Back up with `pg_dump` or managed snapshots; test restores.

### Operations
* Health: `GET /actuator/health` (liveness/readiness probes enabled).
* Logs: stdout, include `requestId`; audit events under logger `audit`.
* Scaling: run **one instance** today (in-process WebSocket fan-out, in-memory rate limiter, expiry sweep). See
  SYSTEM_DESIGN.md "Horizontal scaling" before adding instances.
* Secret rotation: rotating `JWT_SECRET` invalidates access tokens (clients refresh transparently).

## 2. Android release

1. Build with your API URL and signing key — see [BUILD_GUIDE.md](../BUILD_GUIDE.md#android--release):
   `./scripts/build-apk.sh -Pfamilysafety.apiBaseUrl=https://api.your-domain.example/`
2. Verify: `apksigner verify --print-certs dist/*.apk`, compare SHA-256 from `dist/*.sha256`.
3. Distribute:
   * **Google Play**: build an App Bundle (`./gradlew bundleRelease`), enrol in Play App Signing. Complete the Data
     safety form using PRIVACY_POLICY.md. Expect review of `PACKAGE_USAGE_STATS` and the
     `FOREGROUND_SERVICE_MEDIA_PROJECTION` declaration (provide a video of the consent flow). Families/parental-control
     policy requirements apply (prominent disclosure, no covert functionality — this app is designed for that).
   * **Direct install (sideload)**: users must allow installs from the source; share the SHA-256 for verification.

## Push notifications
No push provider is wired in. Consequences: screen-share requests reach a child device only while the app is open (or
the next time it is opened, within the 90-second request window); alerts in the background arrive via WorkManager
polling (~15 min, deferrable). To add FCM: create a Firebase project, add `google-services.json` (untracked), register
FCM tokens per device, and send high-priority data messages from the backend where events are published today
(`ConnectionRegistry` call sites). The server credential must be stored as a secret.

## 3. Checklist before go-live
- [ ] Physical-device test plan passed (TESTING.md)
- [ ] TURN deployed and tested on mobile networks
- [ ] Privacy policy adapted and published; in-app link added
- [ ] Independent security assessment
- [ ] Backups and restore tested; monitoring/alerting on health and error rates
- [ ] Rate limiting backed by shared store if more than one instance
- [ ] Release keystore backed up securely (loss = cannot update the app outside Play App Signing)
