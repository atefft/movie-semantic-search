# StartServicesScript Spec

**Feature:** #247 — Add --using-dev-container flag to start-services.sh
**Component:** `start-services.sh` (`start-services.sh`)

## Overview

`start-services.sh` gains a `--using-dev-container` flag that makes health-check polling work correctly when the script is run from inside a dev container. Without the flag, the existing `localhost`-based health polling is unchanged. With the flag, the script: derives the compose network name from the working directory, ensures that network exists (creating it if needed), connects the dev container to the network via `docker network connect`, sets a `trap ... EXIT` to always disconnect on normal exit, error, or interruption, and polls health endpoints using Docker internal DNS service names (`triton`, `qdrant`, `api`) instead of `localhost`. This makes health checks reliable and self-contained inside dev-container environments without any manual network wiring.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `--using-dev-container` | flag | Enables dev-container mode | Combinable with `--rebuild` in any order; absent → existing behavior unchanged |
| `--rebuild` | flag | Full clean rebuild | Unchanged behavior; when combined with `--using-dev-container`, network connect/disconnect still applies |
| unrecognised flag | exit | Any other argument | Prints updated usage line (including `--using-dev-container`), exits 1 |
| `HEALTH_TIMEOUT` | env var (integer, seconds) | Max seconds to wait for all services | Default: `120`; applies in both modes |
| `.env` | file | Must exist in working directory | Checked before any Docker work in both modes |
| `COMPOSE_NETWORK` | internal variable | Derived as `$(basename "$(pwd)")_default` | Not configurable externally; represents the default compose project network |
| `DEV_CONTAINER_ID` | internal variable | Read from `$(cat /etc/hostname)` | Docker sets hostname to short container ID by default |

## Dependencies

| Dependency | Interface / Type | Invoked As |
|------------|-----------------|-------------|
| docker compose | CLI sub-command | `docker compose up -d`, `docker compose down --rmi local -v`, `docker compose run --rm load-model/load-data` (unchanged) |
| docker network | CLI sub-command | `docker network inspect`, `docker network create`, `docker network connect`, `docker network disconnect` |
| curl | CLI | `curl -sf <url>` for health-endpoint polling (both modes) |
| `/etc/hostname` | file | Read via `$(cat /etc/hostname)` to get dev container's own ID |
| `.env` file | File presence | Checked via `[[ -f .env ]]` before any docker calls |

### Dependency Behavior

#### docker network connect (--using-dev-container only)

| Scenario | Behavior | Notes |
|----------|----------|-------|
| Network exists, container not yet connected | Exits 0, container joins network | Happy path |
| Network does not exist | Script creates it first with `docker network create "$COMPOSE_NETWORK"`, then connects | Handles first-run before `docker compose up` |
| Container already connected to network | `docker network connect` exits non-zero; script continues (`|| true`) | Idempotent; safe for re-runs |
| `docker network connect` fails for other reason | Error printed to stderr; script exits non-zero (propagated by `set -euo pipefail` unless suppressed) | Should not happen in normal use |

#### docker network disconnect (trap, --using-dev-container only)

| Scenario | Behavior | Notes |
|----------|----------|-------|
| Normal exit | Disconnect runs, container leaves network | Clean teardown |
| Script errors out (`set -e` triggered) | Trap fires on EXIT, disconnect runs | Network not left lingering |
| User presses Ctrl+C (SIGINT) | Trap fires on EXIT, disconnect runs | Same as error path |
| Disconnect fails (e.g. already disconnected) | Error suppressed (`|| true`); exit code unaffected | Trap must not mask the original exit code |

#### Health polling (curl -sf) — dev-container mode

Endpoints polled (replacing `localhost` with Docker internal DNS service names):
- triton: `http://triton:8000/v2/health/ready`
- qdrant: `http://qdrant:6333/healthz`
- api: `http://api:8080/api/operator/health`

| Scenario | Behavior | Notes |
|----------|----------|-------|
| Service returns 2xx | Service marked healthy | Same as localhost mode |
| Service not yet up | Retry after 5 s | Same as localhost mode |
| `HEALTH_TIMEOUT` elapsed | Exit 1, message names the unhealthy service | Same format as existing behavior |

#### Health polling (curl -sf) — default mode (unchanged)

Endpoints polled: `http://localhost:8000/v2/health/ready`, `http://localhost:6333/healthz`, `http://localhost:8080/api/operator/health`

## Edge Cases

| # | Input | Expected Output | Description | Verification |
|---|-------|----------------|-------------|--------------|
| 1 | `--using-dev-container` alone | Script runs, connects to network, polls dev-container endpoints, exits appropriately | Basic flag acceptance | `bash -n start-services.sh; bash start-services.sh --using-dev-container` (in test, mock docker calls) |
| 2 | `--rebuild --using-dev-container` | Both flags parsed; network connect happens; rebuild pipeline runs; dev-container health polling used | Flag ordering: rebuild first | Integration test or syntax check |
| 3 | `--using-dev-container --rebuild` | Same as #2 | Flag ordering: dev-container first | Same |
| 4 | `--using-dev-container` with `.env` missing | Exit 1, ".env not found" message, no network connect attempted | `.env` guard fires before network logic | Remove `.env`, run script |
| 5 | Network already exists before script runs | `docker network inspect` succeeds; create step skipped; connect proceeds | Idempotent create | Manually pre-create network |
| 6 | Container already connected to network | `docker network connect` exits non-zero; script ignores and continues | Idempotent connect | Pre-connect container |
| 7 | `HEALTH_TIMEOUT=5` in dev-container mode, service takes 10 s | Exit 1, message names the unhealthy service using Docker service name | Timeout enforced with new endpoints | Requires slow/mock service |
| 8 | Unknown flag alongside `--using-dev-container` | Exit 1, usage line printed (includes `--using-dev-container`) | Catch-all still works | `bash start-services.sh --using-dev-container --bogus` |
| 9 | Ctrl+C during health polling | Trap fires, dev container disconnected from network | Network not left lingering | Manual interruption |

## Unit Test Checklist

Since this is a shell script, "unit tests" are verified via `bash -n` (syntax check) and targeted invocations:

- [ ] `bash -n start-services.sh` exits 0 (no syntax errors after changes)
- [ ] `--using-dev-container` alone accepted: exits with any code other than the "Usage:" error path (i.e., flag is parsed without printing usage)
- [ ] `--rebuild --using-dev-container` accepted: exits with any code other than the "Usage:" error path
- [ ] `--using-dev-container --rebuild` accepted: exits with any code other than the "Usage:" error path
- [ ] Unknown flag: exits 1 and stdout/stderr contains both `Usage:` and `--using-dev-container`
- [ ] Usage line contains `--using-dev-container`
- [ ] `pipeline/tests/test_start_services_integration.sh` test for `--using-dev-container` accepted without error passes
- [ ] `pipeline/tests/test_start_services_integration.sh` test for usage output includes `--using-dev-container` passes
- [ ] `.env` guard fires before any network logic when `.env` is absent
