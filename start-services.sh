#!/usr/bin/env bash
set -euo pipefail

if docker compose version &>/dev/null 2>&1; then
  DC="docker compose"
elif docker-compose version &>/dev/null 2>&1; then
  DC="docker-compose"
else
  echo "Error: neither 'docker compose' nor 'docker-compose' is available" >&2
  exit 1
fi

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

if [[ "$REBUILD" == "true" ]]; then
  echo "WARNING: --rebuild will stop all containers, remove volumes (all loaded data will be lost),"
  echo "and remove locally-built Docker images. This cannot be undone."
  read -rp "Type 'yes' to continue: " confirm
  if [[ "$confirm" != "yes" ]]; then
    echo "Aborting."
    exit 1
  fi
fi

if [[ ! -f .env ]]; then
  echo "Error: .env file not found. Create it with TMDB_API_KEY=<your-key>" >&2
  exit 1
fi

HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-120}

wait_for_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  while [[ $SECONDS -lt $deadline ]]; do
    if curl -sf http://localhost:8000/v2/health/ready >/dev/null 2>&1 && \
       curl -sf http://localhost:6333/healthz        >/dev/null 2>&1 && \
       curl -sf http://localhost:8080/api/operator/health >/dev/null 2>&1; then
      return 0
    fi
    sleep 5
  done
  for name_url in "triton http://localhost:8000/v2/health/ready" \
                  "qdrant http://localhost:6333/healthz" \
                  "api http://localhost:8080/api/operator/health"; do
    name=${name_url%% *}; url=${name_url#* }
    curl -sf "$url" >/dev/null 2>&1 || echo "Error: $name did not become healthy within ${HEALTH_TIMEOUT}s" >&2
  done
  return 1
}

if [[ "$REBUILD" == "true" ]]; then
  $DC down --rmi local -v

  # Files written by containers may be owned by root or container UIDs that
  # the host user cannot remove directly. Delete them from inside a container.
  docker run --rm -v "$(pwd):/work" -w /work alpine rm -rf \
    data/raw data/embeddings \
    model-repository/all-minilm-l6-v2/1/model.onnx \
    model-repository/all-minilm-l6-v2/1/tokenizer.json \
    model-repository/all-minilm-l6-v2/1/tokenizer_config.json \
    model-repository/all-minilm-l6-v2/1/vocab.txt \
    model-repository/all-minilm-l6-v2/1/special_tokens_map.json

  $DC run --rm load-model || (echo "[rebuild] load-model phase failed — aborting" && exit 1)
  $DC up -d || (echo "[rebuild] services-up phase failed — aborting" && exit 1)
  $DC run --rm load-data || (echo "[rebuild] load-data phase failed — aborting" && exit 1)
else
  $DC up -d
fi

wait_for_healthy || exit 1

echo "Services are healthy. Access:"
echo "  Search UI:   http://localhost:8080"
echo "  Qdrant:      http://localhost:6333/dashboard"
echo "  Swagger UI:  http://localhost:8080/swagger-ui/index.html"
