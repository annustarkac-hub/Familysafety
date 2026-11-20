#!/usr/bin/env bash
# Builds the backend jar (tests are run separately by scripts/test-all.sh).
set -euo pipefail
cd "$(dirname "$0")/../backend"
./gradlew --no-daemon bootJar
ls -l build/libs/family-safety-backend.jar
