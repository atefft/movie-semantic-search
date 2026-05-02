#!/usr/bin/env bash
set -euo pipefail

REBUILD=false

for arg in "$@"; do
  case "$arg" in
    --rebuild)
      REBUILD=true
      ;;
    *)
      echo "Usage: ./start-services.sh [--rebuild]" >&2
      exit 1
      ;;
  esac
done

if [[ ! -f .env ]]; then
  echo "Error: .env file not found. Create it with TMDB_API_KEY=<your-key>" >&2
  exit 1
fi

if [[ "$REBUILD" == "true" ]]; then
  echo "--rebuild not yet implemented" >&2
  exit 1
fi

docker compose up -d

HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-120}
deadline=$((SECONDS + HEALTH_TIMEOUT))

while [[ $SECONDS -lt $deadline ]]; do
  if curl -sf http://localhost:8000/v2/health/ready >/dev/null 2>&1 && \
     curl -sf http://localhost:6333/healthz        >/dev/null 2>&1 && \
     curl -sf http://localhost:8080/api/operator/health >/dev/null 2>&1; then
    break
  fi
  sleep 5
done

if ! curl -sf http://localhost:8000/v2/health/ready >/dev/null 2>&1; then
  echo "Error: service triton did not become healthy within ${HEALTH_TIMEOUT}s" >&2
  exit 1
fi
if ! curl -sf http://localhost:6333/healthz >/dev/null 2>&1; then
  echo "Error: service qdrant did not become healthy within ${HEALTH_TIMEOUT}s" >&2
  exit 1
fi
if ! curl -sf http://localhost:8080/api/operator/health >/dev/null 2>&1; then
  echo "Error: service api did not become healthy within ${HEALTH_TIMEOUT}s" >&2
  exit 1
fi

echo "Services are healthy. Access:"
echo "  Search UI:   http://localhost:8080"
echo "  Qdrant:      http://localhost:6333/dashboard"
echo "  Swagger UI:  http://localhost:8080/swagger-ui/index.html"
