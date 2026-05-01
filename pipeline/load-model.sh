#!/usr/bin/env bash
set -euo pipefail

run_script() {
  local name="$1"
  local tmpfile
  tmpfile=$(mktemp)
  echo "Running ${name}..."
  python3 "$name" 2>&1 | tee "$tmpfile"
  local code=${PIPESTATUS[0]}
  if [ "$code" -ne 0 ]; then
    echo "--- last output from ${name} ---"
    tail -10 "$tmpfile" || true
    rm -f "$tmpfile"
    echo "ERROR: ${name} failed with exit code ${code}"
    exit "$code"
  fi
  rm -f "$tmpfile"
}

run_script 01_download_corpus.py
run_script 02_export_model.py
