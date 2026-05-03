#!/usr/bin/env bash
# Integration tests for start-services.sh
# Run from the repository root: bash pipeline/tests/test_start_services_integration.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PASS=0
FAIL=0
FAILURES=()

pass() { echo "PASS: $1"; ((PASS++)); }
fail() { echo "FAIL: $1"; ((FAIL++)); FAILURES+=("$1"); }

# ---------------------------------------------------------------------------
# Test 1 — .env guard
# ---------------------------------------------------------------------------
run_test1() {
  local name="test1_env_guard"
  local saved=false
  if [[ -f "$REPO_ROOT/.env" ]]; then
    mv "$REPO_ROOT/.env" "$REPO_ROOT/.env.bak"
    saved=true
  fi

  local output exit_code
  output=$(bash "$REPO_ROOT/start-services.sh" 2>&1)
  exit_code=$?

  if $saved; then
    mv "$REPO_ROOT/.env.bak" "$REPO_ROOT/.env"
  fi

  if [[ $exit_code -eq 1 ]] && echo "$output" | grep -qi "not found"; then
    pass "$name"
  else
    fail "$name (exit=$exit_code, output=$output)"
  fi
}

# ---------------------------------------------------------------------------
# Test 2 — unknown flag
# ---------------------------------------------------------------------------
run_test2() {
  local name="test2_unknown_flag"
  local output exit_code
  output=$(bash "$REPO_ROOT/start-services.sh" --unknown-flag 2>&1)
  exit_code=$?

  if [[ $exit_code -eq 1 ]] && echo "$output" | grep -qi "usage"; then
    pass "$name"
  else
    fail "$name (exit=$exit_code, output=$output)"
  fi
}

# ---------------------------------------------------------------------------
# Test 3 — default path healthy poll
# ---------------------------------------------------------------------------
run_test3() {
  local name="test3_default_path_healthy"

  if [[ ! -f "$REPO_ROOT/.env" ]]; then
    if [[ -f "$REPO_ROOT/.env.example" ]]; then
      cp "$REPO_ROOT/.env.example" "$REPO_ROOT/.env"
    else
      fail "$name (skipped: .env file not present)"
      return
    fi
  fi

  docker compose -f "$REPO_ROOT/docker-compose.yml" up -d 2>&1

  local output exit_code
  output=$(HEALTH_TIMEOUT=180 bash "$REPO_ROOT/start-services.sh" 2>&1)
  exit_code=$?

  if [[ $exit_code -eq 0 ]] && echo "$output" | grep -q "http://localhost:8080"; then
    pass "$name"
  else
    fail "$name (exit=$exit_code, output=$output)"
  fi
}

# ---------------------------------------------------------------------------
# Test 4 — --rebuild abort
# ---------------------------------------------------------------------------
run_test4() {
  local name="test4_rebuild_abort"
  local output exit_code
  output=$(echo "no" | bash "$REPO_ROOT/start-services.sh" --rebuild 2>&1)
  exit_code=$?

  if [[ $exit_code -eq 1 ]] && echo "$output" | grep -q "Aborting."; then
    pass "$name"
  else
    fail "$name (exit=$exit_code, output=$output)"
  fi
}

# ---------------------------------------------------------------------------
# Test 5 — --rebuild full pipeline
# ---------------------------------------------------------------------------
run_test5() {
  local name="test5_rebuild_full_pipeline"

  if [[ ! -f "$REPO_ROOT/.env" ]]; then
    if [[ -f "$REPO_ROOT/.env.example" ]]; then
      cp "$REPO_ROOT/.env.example" "$REPO_ROOT/.env"
    else
      fail "$name (skipped: .env file not present)"
      return
    fi
  fi

  local output exit_code
  output=$(echo "yes" | HEALTH_TIMEOUT=300 bash "$REPO_ROOT/start-services.sh" --rebuild 2>&1)
  exit_code=$?

  if [[ $exit_code -eq 0 ]] && echo "$output" | grep -q "http://localhost:8080"; then
    pass "$name"
  else
    fail "$name (exit=$exit_code, output=$output)"
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
echo "=== start-services.sh integration tests ==="
echo "Repo root: $REPO_ROOT"
echo ""

run_test1
run_test2
run_test3
run_test4
run_test5

echo ""
echo "Results: $PASS passed, $FAIL failed"

if [[ $FAIL -gt 0 ]]; then
  echo "Failed tests:"
  for t in "${FAILURES[@]}"; do
    echo "  - $t"
  done
  exit 1
fi

exit 0
