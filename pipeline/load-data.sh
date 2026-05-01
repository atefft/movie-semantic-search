#!/usr/bin/env bash
set -euo pipefail

unreachable=""
curl -sf http://triton:8000/v2/health/ready  > /dev/null 2>&1 || unreachable="triton"
curl -sf http://qdrant:6333/healthz          > /dev/null 2>&1 || unreachable="${unreachable:+${unreachable}, }qdrant"

if [ -n "$unreachable" ]; then
  echo "ERROR: the following services are not reachable: ${unreachable}"
  exit 1
fi

run_script() {
  local name="$1"; shift
  echo "Running ${name}..."
  python3 "$name" "$@"
  local code=$?
  if [ "$code" -ne 0 ]; then
    echo "ERROR: ${name} failed with exit code ${code}"
    exit "$code"
  fi
}

run_script 03_embed_corpus.py --triton-host triton --triton-port 8001
run_script 04_ingest_qdrant.py --qdrant-url http://qdrant:6333

if [ -z "${TMDB_API_KEY:-}" ]; then
  echo "WARNING: TMDB_API_KEY is not set — skipping 05_enrich_tmdb.py"
else
  run_script 05_enrich_tmdb.py --qdrant-url http://qdrant:6333
fi
