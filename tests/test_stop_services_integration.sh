#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.ci.yml"
PROJECT_VOLUME="task-215_qdrant_data"
PASS=0
FAIL=0

cleanup() {
  docker compose -f "$COMPOSE_FILE" down -v &>/dev/null || true
  docker volume rm "$PROJECT_VOLUME" &>/dev/null || true
}
trap cleanup EXIT

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Integration test: stop-services.sh ==="

# ---- Test 1: default stop (no --clean) preserves qdrant_data volume ----
echo ""
echo "--- Test 1: default stop preserves qdrant_data volume ---"

docker compose -f "$COMPOSE_FILE" up -d --wait --wait-timeout 120
docker volume create "$PROJECT_VOLUME" &>/dev/null

"$REPO_ROOT/stop-services.sh"

running=$(docker compose -f "$COMPOSE_FILE" ps -q 2>/dev/null)
if [ -z "$running" ]; then
  pass "No containers running after stop-services.sh"
else
  fail "Containers still running after stop-services.sh: $running"
fi

if docker volume inspect "$PROJECT_VOLUME" &>/dev/null; then
  pass "qdrant_data volume preserved after default stop"
else
  fail "qdrant_data volume was removed after default stop (expected to be preserved)"
fi

# ---- Test 2: --clean stop removes qdrant_data volume ----
echo ""
echo "--- Test 2: --clean stop removes qdrant_data volume ---"

docker compose -f "$COMPOSE_FILE" up -d --wait --wait-timeout 120
docker volume create "$PROJECT_VOLUME" &>/dev/null

"$REPO_ROOT/stop-services.sh" --clean

running=$(docker compose -f "$COMPOSE_FILE" ps -q 2>/dev/null)
if [ -z "$running" ]; then
  pass "No containers running after stop-services.sh --clean"
else
  fail "Containers still running after stop-services.sh --clean: $running"
fi

if ! docker volume inspect "$PROJECT_VOLUME" &>/dev/null; then
  pass "qdrant_data volume removed after --clean stop"
else
  fail "qdrant_data volume still exists after --clean stop (expected removal)"
fi

# ---- Summary ----
echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ]
