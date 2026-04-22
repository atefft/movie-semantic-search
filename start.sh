#!/usr/bin/env bash
set -euo pipefail

IMAGE="movie-semantic-search"
CONTAINER="movie-semantic-search"

echo "Building image..."
docker build -t "$IMAGE" .

echo "Starting on http://localhost:8080 ..."
echo "  Search:   http://localhost:8080/"
echo "  Operator: http://localhost:8080/operator.html"

docker run --rm -p 8080:8080 --name "$CONTAINER" "$IMAGE"
