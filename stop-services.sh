#!/usr/bin/env bash

cd "$(dirname "$0")"

# Detect docker compose command
if docker compose version &>/dev/null 2>&1; then
  DC="docker compose"
elif docker-compose version &>/dev/null 2>&1; then
  DC="docker-compose"
else
  echo "Error: neither 'docker compose' nor 'docker-compose' is available" >&2
  exit 1
fi

CLEAN=false

for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=true ;;
    *) echo "Usage: stop-services.sh [--clean]" >&2; exit 1 ;;
  esac
done

if [ "$CLEAN" = true ]; then
  $DC down -v
  status=$?
  if [ $status -ne 0 ]; then
    echo "Error: docker compose down -v failed (exit $status)" >&2
    exit 1
  fi
  echo "WARNING: Volumes removed (qdrant_data, model-repository, data). Run ./start-services.sh --rebuild to reinitialise."
else
  $DC down
  status=$?
  if [ $status -ne 0 ]; then
    echo "Error: docker compose down failed (exit $status)" >&2
    exit 1
  fi
  echo "Services stopped. Volumes preserved — run ./start-services.sh to restart quickly."
fi
