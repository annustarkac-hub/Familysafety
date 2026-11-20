#!/usr/bin/env bash
# Runs every automated check that does not need a physical device:
#   backend unit + integration + WebSocket + E2E tests (Testcontainers PostgreSQL; requires Docker)
#   Android JVM unit tests, Robolectric/Compose UI tests, and Android Lint
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "== Backend tests =="
(cd "$ROOT/backend" && ./gradlew --no-daemon test)

echo "== Android unit/UI tests =="
(cd "$ROOT/android" && ./gradlew --no-daemon test)

echo "== Android lint =="
(cd "$ROOT/android" && ./gradlew --no-daemon lint)

echo "All automated checks passed."
echo "Reports:"
echo "  backend/build/reports/tests/test/index.html"
echo "  android/*/build/reports/tests/*/index.html"
echo "  android/*/build/reports/lint-results-debug.html"
