#!/usr/bin/env bash

# Unit tests for stop-services.sh
# Uses a mock docker binary on PATH — no bats required.

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MOCK_BIN="$(mktemp -d)"
PASS=0
FAIL=0

cleanup() {
  rm -rf "$MOCK_BIN"
}
trap cleanup EXIT

run_test() {
  local name="$1"
  local result="$2"
  if [ "$result" = "pass" ]; then
    echo "PASS: $name"
    PASS=$((PASS + 1))
  else
    echo "FAIL: $name"
    FAIL=$((FAIL + 1))
  fi
}

# Write a mock docker that responds to subcommands
setup_mock_docker() {
  local compose_v2_exit="${1:-0}"   # exit code for "docker compose version"
  local compose_down_exit="${2:-0}" # exit code for "docker compose down"

  cat > "$MOCK_BIN/docker" <<EOF
#!/usr/bin/env bash
if [ "\$1" = "compose" ] && [ "\$2" = "version" ]; then
  exit $compose_v2_exit
fi
if [ "\$1" = "compose" ] && [ "\$2" = "down" ]; then
  exit $compose_down_exit
fi
exit 0
EOF
  chmod +x "$MOCK_BIN/docker"
  rm -f "$MOCK_BIN/docker-compose"
}

setup_mock_docker_v1_only() {
  local v1_down_exit="${1:-0}"

  # docker (V2 plugin) always fails for compose subcommand
  cat > "$MOCK_BIN/docker" <<'DOCKEREOF'
#!/usr/bin/env bash
if [ "$1" = "compose" ]; then
  exit 1
fi
exit 0
DOCKEREOF
  chmod +x "$MOCK_BIN/docker"

  cat > "$MOCK_BIN/docker-compose" <<EOF
#!/usr/bin/env bash
if [ "\$1" = "version" ]; then
  exit 0
fi
if [ "\$1" = "down" ]; then
  exit $v1_down_exit
fi
exit 0
EOF
  chmod +x "$MOCK_BIN/docker-compose"
}

setup_mock_no_docker() {
  # Neither docker compose nor docker-compose available
  cat > "$MOCK_BIN/docker" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
  chmod +x "$MOCK_BIN/docker"
  rm -f "$MOCK_BIN/docker-compose"
}

# -----------------------------------------------------------------------
# Test 1: script exists and is executable
# -----------------------------------------------------------------------
test_script_executable() {
  if [ -x "$SCRIPT_DIR/stop-services.sh" ]; then
    run_test "script exists and is executable" "pass"
  else
    run_test "script exists and is executable" "fail"
  fi
}

# -----------------------------------------------------------------------
# Test 2: default mode (no flags) — exits 0, stdout contains expected text
# -----------------------------------------------------------------------
test_default_mode() {
  setup_mock_docker 0 0
  output=$(PATH="$MOCK_BIN:$PATH" bash "$SCRIPT_DIR/stop-services.sh" 2>&1)
  exit_code=$?
  if [ $exit_code -eq 0 ] \
      && echo "$output" | grep -q "Volumes preserved" \
      && echo "$output" | grep -q "./start-services.sh"; then
    run_test "default mode: exits 0 and stdout contains expected text" "pass"
  else
    run_test "default mode: exits 0 and stdout contains expected text" "fail"
    echo "  exit_code=$exit_code output=$output"
  fi
}

# -----------------------------------------------------------------------
# Test 3: --clean mode — exits 0, stdout contains WARNING and volume names
# -----------------------------------------------------------------------
test_clean_mode() {
  setup_mock_docker 0 0
  output=$(PATH="$MOCK_BIN:$PATH" bash "$SCRIPT_DIR/stop-services.sh" --clean 2>&1)
  exit_code=$?
  if [ $exit_code -eq 0 ] \
      && echo "$output" | grep -q "WARNING" \
      && echo "$output" | grep -q "qdrant_data" \
      && echo "$output" | grep -q "model-repository" \
      && echo "$output" | grep -q "data" \
      && echo "$output" | grep -q "./start-services.sh --rebuild"; then
    run_test "--clean mode: exits 0 and stdout contains WARNING and volume names" "pass"
  else
    run_test "--clean mode: exits 0 and stdout contains WARNING and volume names" "fail"
    echo "  exit_code=$exit_code output=$output"
  fi
}

# -----------------------------------------------------------------------
# Test 4: compose command exits non-zero in default mode
# -----------------------------------------------------------------------
test_compose_failure_default() {
  setup_mock_docker 0 1
  stderr=$(PATH="$MOCK_BIN:$PATH" bash "$SCRIPT_DIR/stop-services.sh" 2>&1 1>/dev/null)
  exit_code=$?
  if [ $exit_code -ne 0 ] && echo "$stderr" | grep -q "Error: docker compose down failed"; then
    run_test "docker compose down failure: exits non-zero and stderr contains error message" "pass"
  else
    run_test "docker compose down failure: exits non-zero and stderr contains error message" "fail"
    echo "  exit_code=$exit_code stderr=$stderr"
  fi
}

# -----------------------------------------------------------------------
# Test 5: compose command exits non-zero in --clean mode
# -----------------------------------------------------------------------
test_compose_failure_clean() {
  # Need a mock that fails on "down -v"
  cat > "$MOCK_BIN/docker" <<'EOF'
#!/usr/bin/env bash
if [ "$1" = "compose" ] && [ "$2" = "version" ]; then
  exit 0
fi
if [ "$1" = "compose" ] && [ "$2" = "down" ]; then
  exit 1
fi
exit 0
EOF
  chmod +x "$MOCK_BIN/docker"

  stderr=$(PATH="$MOCK_BIN:$PATH" bash "$SCRIPT_DIR/stop-services.sh" --clean 2>&1 1>/dev/null)
  exit_code=$?
  if [ $exit_code -ne 0 ] && echo "$stderr" | grep -q "Error: docker compose down"; then
    run_test "docker compose down -v failure: exits non-zero and stderr contains error message" "pass"
  else
    run_test "docker compose down -v failure: exits non-zero and stderr contains error message" "fail"
    echo "  exit_code=$exit_code stderr=$stderr"
  fi
}

# -----------------------------------------------------------------------
# Test 6: unrecognised flag — exits non-zero, stderr contains usage
# -----------------------------------------------------------------------
test_unrecognised_flag() {
  setup_mock_docker 0 0
  stderr=$(PATH="$MOCK_BIN:$PATH" bash "$SCRIPT_DIR/stop-services.sh" --unknown 2>&1 1>/dev/null)
  exit_code=$?
  if [ $exit_code -ne 0 ] && echo "$stderr" | grep -q "Usage: stop-services.sh \[--clean\]"; then
    run_test "unrecognised flag: exits non-zero and stderr contains usage" "pass"
  else
    run_test "unrecognised flag: exits non-zero and stderr contains usage" "fail"
    echo "  exit_code=$exit_code stderr=$stderr"
  fi
}

# -----------------------------------------------------------------------
# Test 7: neither docker compose nor docker-compose available
# -----------------------------------------------------------------------
test_no_docker() {
  setup_mock_no_docker
  stderr=$(PATH="$MOCK_BIN:$PATH" bash "$SCRIPT_DIR/stop-services.sh" 2>&1 1>/dev/null)
  exit_code=$?
  if [ $exit_code -eq 1 ] && echo "$stderr" | grep -q "neither 'docker compose' nor 'docker-compose' is available"; then
    run_test "no docker available: exits 1 and stderr contains expected message" "pass"
  else
    run_test "no docker available: exits 1 and stderr contains expected message" "fail"
    echo "  exit_code=$exit_code stderr=$stderr"
  fi
}

# -----------------------------------------------------------------------
# Test 8: V1 fallback — uses docker-compose when docker compose version fails
# -----------------------------------------------------------------------
test_v1_fallback() {
  setup_mock_docker_v1_only 0
  output=$(PATH="$MOCK_BIN:$PATH" bash "$SCRIPT_DIR/stop-services.sh" 2>&1)
  exit_code=$?
  if [ $exit_code -eq 0 ] && echo "$output" | grep -q "Volumes preserved"; then
    run_test "V1 fallback: uses docker-compose when V2 unavailable" "pass"
  else
    run_test "V1 fallback: uses docker-compose when V2 unavailable" "fail"
    echo "  exit_code=$exit_code output=$output"
  fi
}

# -----------------------------------------------------------------------
# Test 9: V2 preferred over V1 when both available
# -----------------------------------------------------------------------
test_v2_preferred() {
  setup_mock_docker 0 0
  # Also add docker-compose that would fail if called
  cat > "$MOCK_BIN/docker-compose" <<'EOF'
#!/usr/bin/env bash
exit 42
EOF
  chmod +x "$MOCK_BIN/docker-compose"

  output=$(PATH="$MOCK_BIN:$PATH" bash "$SCRIPT_DIR/stop-services.sh" 2>&1)
  exit_code=$?
  if [ $exit_code -eq 0 ] && echo "$output" | grep -q "Volumes preserved"; then
    run_test "V2 preferred over V1 when both available" "pass"
  else
    run_test "V2 preferred over V1 when both available" "fail"
    echo "  exit_code=$exit_code output=$output"
  fi
}

# -----------------------------------------------------------------------
# Run all tests
# -----------------------------------------------------------------------
test_script_executable
test_default_mode
test_clean_mode
test_compose_failure_default
test_compose_failure_clean
test_unrecognised_flag
test_no_docker
test_v1_fallback
test_v2_preferred

echo ""
echo "Results: $PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
