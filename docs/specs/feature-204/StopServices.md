# StopServices Spec

**Feature:** #204 — Add stop-services.sh — single entry-point to stop all services
**Component:** `stop-services.sh` (`stop-services.sh`)

## Overview

`stop-services.sh` is a root-level shell script that provides a single, consistent entry point for stopping all Docker Compose services. By default it preserves volumes so that a subsequent `./start-services.sh` is fast (no data reload required). With the `--clean` flag it performs a full teardown including named volumes and bind-mount data directories, requiring `./start-services.sh --rebuild` on next startup. The script detects whether the Docker Compose V2 plugin (`docker compose`) or the legacy standalone binary (`docker-compose`) is available, preferring V2, so it runs correctly across development environments.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `--clean` flag | CLI argument | Requests full teardown including volumes | Optional. Absent → preserve volumes. Present → remove volumes. |
| Exit code | integer | Script exit status | `0` on success. Non-zero on: docker compose failure, unrecognised flag. |
| Stdout/stderr | string | Human-readable status messages | Confirmation message on success; warning with volume list on `--clean`; usage line and error messages to stderr on failure. |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `docker compose` / `docker-compose` | Shell command | Detected at runtime via `docker compose version` / `docker-compose version` |

### Dependency Mock Behaviors

#### `docker compose` / `docker-compose`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path (V2 plugin) | `docker compose version` exits 0 | Normal modern Docker installation |
| Happy path (V1 standalone) | `docker compose version` exits non-zero; `docker-compose version` exits 0 | Legacy environment |
| Neither available | Both version checks exit non-zero | Script must error with clear message |
| `docker compose down` succeeds | Command exits 0 | Normal stop |
| `docker compose down` fails | Command exits non-zero (e.g. exit 1) | Script must print error and exit non-zero |
| `docker compose down -v` succeeds | Command exits 0 | Normal clean stop |
| `docker compose down -v` fails | Command exits non-zero | Script must print error and exit non-zero |

**Mock data structures:**
```bash
# Happy-path docker compose down output (stdout)
 Container movie-semantic-search-api-1      Stopped
 Container movie-semantic-search-qdrant-1   Stopped
 Container movie-semantic-search-triton-1   Stopped
 Network movie-semantic-search_default      Removed

# docker compose down -v additional output
 Volume movie-semantic-search_qdrant_data   Removed
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | No flags | Exit 0; prints "Services stopped. Volumes preserved — run ./start-services.sh to restart quickly." | Default happy path | `docker compose down` exits 0 |
| 2 | `--clean` | Exit 0; prints "WARNING: Volumes removed (qdrant_data, model-repository, data). Run ./start-services.sh --rebuild to reinitialise." | Clean happy path | `docker compose down -v` exits 0 |
| 3 | Unrecognised flag (e.g. `--foo`) | Exit 1; prints usage line to stderr: "Usage: stop-services.sh [--clean]" | Unrecognised flag | N/A |
| 4 | `docker compose down` exits non-zero | Exit 1; prints "Error: docker compose down failed (exit <N>)" to stderr | Down command failure | `docker compose down` exits 1 |
| 5 | `docker compose down -v` exits non-zero | Exit 1; prints "Error: docker compose down -v failed (exit <N>)" to stderr | Clean down command failure | `docker compose down -v` exits 1 |
| 6 | Neither `docker compose` nor `docker-compose` available | Exit 1; prints "Error: neither 'docker compose' nor 'docker-compose' is available" to stderr | Missing docker compose | Both version commands exit non-zero |
| 7 | Called from a subdirectory | Works correctly; docker compose finds docker-compose.yml | `cd "$(dirname "$0")"` ensures repo root is CWD | N/A |
| 8 | V1 standalone (`docker-compose`) present, V2 absent | Uses `docker-compose down`; exit 0; prints confirmation | Legacy environment fallback | `docker compose version` exits 1; `docker-compose version` exits 0 |

## Unit Test Checklist

Unit tests for this script are implemented as Bash shell tests (using `bats` or plain shell assertions with a mock `docker` binary on `PATH`). The mock overrides `docker` (or `docker-compose`) to simulate success and failure scenarios.

- [ ] Happy path (no flags): `docker compose down` called; exit 0; stdout contains "Volumes preserved" and "./start-services.sh"
- [ ] Happy path (`--clean`): `docker compose down -v` called; exit 0; stdout contains "WARNING" and all three volume names (qdrant_data, model-repository, data) and "./start-services.sh --rebuild"
- [ ] Unrecognised flag: exit 1; stderr contains "Usage: stop-services.sh [--clean]"
- [ ] `docker compose down` fails: exit 1; stderr contains "Error: docker compose down failed"
- [ ] `docker compose down -v` fails (with `--clean`): exit 1; stderr contains "Error: docker compose down -v failed"
- [ ] Neither docker compose nor docker-compose available: exit 1; stderr contains "neither 'docker compose' nor 'docker-compose' is available"
- [ ] V1 fallback: when `docker compose version` fails and `docker-compose version` succeeds, uses `docker-compose` command
- [ ] Script is executable (`-x` bit set)
- [ ] Script changes to its own directory (`cd "$(dirname "$0")"`) before running docker compose
