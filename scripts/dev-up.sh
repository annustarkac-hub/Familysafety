#!/usr/bin/env bash
# Starts the local development stack. Creates .env with fresh random secrets on first run.
# Usage: ./scripts/dev-up.sh [--emulator]   (--emulator sets REQUIRE_HTTPS=false so 10.0.2.2:8080 works)
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "Creating .env with random secrets (not committed)…"
  cp .env.example .env
  rand() { openssl rand -base64 48 | tr -d '\n'; }
  sed -i.bak \
    -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -hex 24)|" \
    -e "s|^JWT_SECRET=.*|JWT_SECRET=$(rand)|" \
    -e "s|^PAIRING_PEPPER=.*|PAIRING_PEPPER=$(rand)|" .env
  rm -f .env.bak
  chmod 600 .env
fi

if [[ "${1:-}" == "--emulator" ]]; then
  sed -i.bak -e "s|^REQUIRE_HTTPS=.*|REQUIRE_HTTPS=false|" .env && rm -f .env.bak
  echo "REQUIRE_HTTPS=false (local emulator development only)."
fi

./scripts/build-backend.sh
docker compose up -d --build postgres backend
echo "Waiting for backend health…"
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q UP; then echo "Backend is UP on http://127.0.0.1:8080"; exit 0; fi
  sleep 2
done
echo "Backend did not become healthy; see: docker compose logs backend" >&2
exit 1
