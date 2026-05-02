# StartServicesScript Spec

**Feature:** #203 — Add start-services.sh — single entry-point to start all services
**Component:** `start-services.sh` (`start-services.sh`)

## Overview

`start-services.sh` is a Bash script at the repository root that provides a single entry point for the service lifecycle. It has two modes: a fast default path (`./start-services.sh`) that brings up containers using existing images and volumes, polls until all three services are healthy, and prints access URLs; and a full-rebuild path (`./start-services.sh --rebuild`) that wipes containers, volumes, locally-built images, and model/data files, then runs the complete pipeline from scratch. The script validates that `.env` exists before doing any Docker work, enforces a configurable health-check timeout, and exits non-zero with a clear message on any failure.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| (no flag) | mode | Default fast path | Runs `docker compose up -d` then polls health |
| `--rebuild` | mode | Full clean rebuild path | Requires confirmation, wipes everything, runs pipeline |
| unrecognised flag | exit | Any other argument | Prints usage line, exits 1 |
| `HEALTH_TIMEOUT` | env var (integer, seconds) | Max seconds to wait for all services to become healthy | Default: `120`; must be a positive integer; overridable at invocation time |
| `.env` | file | Must exist in the working directory | If absent, script exits 1 with a descriptive message before any Docker call |

## Dependencies

| Dependency | Interface / Type | Invoked As |
|------------|-----------------|-------------|
| docker compose | CLI sub-command | `docker compose up -d`, `docker compose down --rmi local -v`, `docker compose run --rm load-model`, `docker compose run --rm load-data` |
| curl | CLI | `curl -sf <url>` for health-endpoint polling |
| `.env` file | File presence | Checked via `[[ -f .env ]]` before any docker calls |

### Dependency Behavior

#### docker compose up -d (default path)

| Scenario | Behavior | Notes |
|----------|----------|-------|
| Success | Exits 0, containers start in background | Normal operation |
| Failure (e.g. port conflict) | Exits non-zero | Script propagates exit code due to `set -euo pipefail` |

#### Health polling (curl -sf)

| Scenario | Behavior | Notes |
|----------|----------|-------|
| Service returns 2xx | Service marked healthy | Loop continues to next service |
| Service not yet up (connection refused / non-2xx) | Retry after 5 s | Expected during startup |
| `HEALTH_TIMEOUT` seconds elapsed, service still unhealthy | Script exits 1 with message naming the unhealthy service(s) | Clear error for the operator |

Endpoints polled:
- triton: `http://localhost:8000/v2/health/ready`
- qdrant: `http://localhost:6333/healthz`
- api: `http://localhost:8080/api/operator/health`

#### docker compose down --rmi local -v (--rebuild path)

| Scenario | Behavior | Notes |
|----------|----------|-------|
| Success | Exits 0, containers+volumes+local images removed | `--rmi local` removes only locally-built images (api, pipeline), not pulled images |
| No running containers | Exits 0 (no-op) | Safe to run on a clean machine |
| Failure | Script propagates exit code | Rebuild aborted |

#### docker compose run --rm load-model / load-data (--rebuild path)

| Scenario | Behavior | Notes |
|----------|----------|-------|
| Success | Exits 0 | Phase complete |
| Non-zero exit | Script exits 1 with phase name in error message | e.g. "load-model phase failed" |

## Edge Cases

| # | Input | Expected Output | Description | Verification |
|---|-------|----------------|-------------|--------------|
| 1 | `.env` missing | Exit 1, message: "Error: .env file not found. Create it with TMDB_API_KEY=..." | Guard before any Docker work | `bash start-services.sh; echo $?` in a dir without .env |
| 2 | Unknown flag `--foo` | Exit 1, usage line printed | Catch-all for unrecognised flags | `bash start-services.sh --foo; echo $?` |
| 3 | `HEALTH_TIMEOUT=5`, service takes 10 s | Exit 1, message names the unhealthy service | Timeout enforced | Requires a mock or slow service |
| 4 | `--rebuild` without typing "yes" | Exit 1, aborted | Confirmation guard | `echo "no" \| bash start-services.sh --rebuild` |
| 5 | `--rebuild` with "yes" confirmation | Proceeds to wipe and rebuild | Happy path for rebuild | `echo "yes" \| bash start-services.sh --rebuild` |
| 6 | `load-model` phase fails during `--rebuild` | Exit 1, "load-model phase failed" message | Phase failure propagation | Requires compose returning non-zero |

## File cleanup performed by --rebuild

To match `make clean` semantics, `--rebuild` also removes these files before running the pipeline (in addition to docker compose teardown):

```
data/raw/
data/embeddings/
model-repository/all-minilm-l6-v2/1/model.onnx
model-repository/all-minilm-l6-v2/1/tokenizer.json
model-repository/all-minilm-l6-v2/1/tokenizer_config.json
model-repository/all-minilm-l6-v2/1/vocab.txt
model-repository/all-minilm-l6-v2/1/special_tokens_map.json
```

## Access URLs printed on success

```
Services are healthy. Access:
  Search UI:    http://localhost:8080
  Qdrant:       http://localhost:6333/dashboard
  Swagger UI:   http://localhost:8080/swagger-ui/index.html
```

## Unit Test Checklist

Since this is a shell script, "unit tests" are verified via `bash -n` (syntax check) and targeted invocation with controlled inputs:

- [ ] `bash -n start-services.sh` exits 0 (no syntax errors)
- [ ] Missing `.env`: exits 1, stderr contains "not found"
- [ ] Unrecognised flag `--foo`: exits 1, stdout/stderr contains "Usage"
- [ ] `--rebuild` with input "no": exits 1 (aborted)
- [ ] `--rebuild` with input "yes": proceeds past confirmation (further docker calls will fail in unit context — acceptable)
- [ ] `HEALTH_TIMEOUT` is read from environment when set
