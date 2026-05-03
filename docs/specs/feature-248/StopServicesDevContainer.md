# StopServicesDevContainer Spec

**Feature:** #248 — Add --using-dev-container flag to stop-services.sh
**Component:** `stop-services.sh` (`stop-services.sh`)

## Overview

`stop-services.sh` currently runs `docker compose down` without accounting for a dev container that may be connected to the compose project network. Docker will not remove a network that still has active endpoints, so if a dev container is attached (either by a prior `start-services.sh --using-dev-container` run or by a manual `docker network connect`), the `docker compose down` call will fail to remove the network. This component extends `stop-services.sh` with a `--using-dev-container` flag that, when present, disconnects the dev container from the compose network before running `docker compose down` or `docker compose down -v`. The disconnect is a silent no-op if the dev container is not currently connected.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `--using-dev-container` flag | CLI argument | Signals the script is running inside a dev container | Optional. Combinable with `--clean` in any order. When present: disconnect step runs before `docker compose down`. When absent: existing behavior unchanged. |
| `--clean` flag | CLI argument | Requests full teardown including volumes | Optional. Unchanged from prior behavior. |
| Exit code | integer | Script exit status | `0` on success. Non-zero on: docker compose failure, unrecognised flag. The disconnect step never causes a non-zero exit. |
| Stdout/stderr | string | Human-readable status messages | Same confirmation messages as before on success; usage line updated to include `--using-dev-container`. |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `docker compose` / `docker-compose` | Shell command | Detected at runtime (existing logic unchanged) |
| `docker network disconnect` | Shell command | Called directly before `docker compose down` |
| `/etc/hostname` | File | Read to obtain the dev container's own container ID |

### Dependency Mock Behaviors

#### `docker network disconnect`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path: dev container connected | `docker network disconnect` exits 0 | Normal case — container was connected by a prior `start-services.sh --using-dev-container` or manual connect |
| No-op: dev container not connected | `docker network disconnect` exits non-zero (e.g. exit 1) | Script must suppress this via `2>/dev/null \|\| true` |
| No-op: network does not exist | `docker network disconnect` exits non-zero | Same suppression — network may already be gone if compose project never started |

**Mock data structures:**
```bash
# Happy-path disconnect (exit 0, no output needed)
# docker network disconnect movie-semantic-search_default <container_id>

# Already-disconnected error (suppressed by 2>/dev/null || true)
# Error response from daemon: container <id> is not connected to network movie-semantic-search_default
```

#### `/etc/hostname`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | File contains short container ID (12 hex chars) | Normal Docker runtime |

**Mock data structures:**
```bash
# /etc/hostname contents
a3f2c1d4e5b6
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | `--using-dev-container` only | Exit 0; disconnect runs silently; prints "Services stopped. Volumes preserved" | Happy path — dev container was connected | `docker network disconnect` exits 0; `docker compose down` exits 0 |
| 2 | `--using-dev-container --clean` | Exit 0; disconnect runs silently; prints WARNING with volume names | Combined with --clean in order 1 | Same mocks; `docker compose down -v` exits 0 |
| 3 | `--clean --using-dev-container` | Exit 0; identical to edge case 2 | Combined with --clean in order 2 | Same mocks |
| 4 | `--using-dev-container` but dev container not connected | Exit 0; disconnect command fails silently; `docker compose down` proceeds normally | Disconnect is a no-op | `docker network disconnect` exits 1; `docker compose down` exits 0 |
| 5 | `--using-dev-container` but network doesn't exist | Exit 0; disconnect command fails silently; `docker compose down` proceeds normally | Network already gone | `docker network disconnect` exits 1; `docker compose down` exits 0 |
| 6 | No flags | Exit 0; disconnect step not run; "Volumes preserved" message | Existing default behavior unchanged | `docker compose down` exits 0 |
| 7 | `--clean` only | Exit 0; disconnect step not run; WARNING message | Existing --clean behavior unchanged | `docker compose down -v` exits 0 |
| 8 | Unrecognised flag | Exit 1; stderr contains updated usage line | Usage now includes `--using-dev-container` | N/A |

## Unit Test Checklist

Tests are implemented as Bash shell tests in `tests/test_stop_services.sh` using a mock `docker` binary on `PATH`. The mock intercepts `docker network disconnect` and `docker compose` subcommands.

- [ ] `--using-dev-container` accepted without error (exit 0, no "Usage:" in output)
- [ ] `--using-dev-container --clean` accepted without error (exit 0, no "Usage:" in output)
- [ ] `--clean --using-dev-container` accepted without error (exit 0, no "Usage:" in output)
- [ ] When `--using-dev-container` is present, `docker network disconnect` is called with `<network> <container_id>` before `docker compose down`
- [ ] When `docker network disconnect` exits non-zero, script continues and exits 0 (disconnect is a no-op)
- [ ] When `--using-dev-container` is absent, `docker network disconnect` is NOT called
- [ ] Usage line (printed on unrecognised flag) contains `--using-dev-container`
- [ ] All existing tests (default mode, --clean, compose failure, unrecognised flag, V1 fallback, V2 preferred) continue to pass

### Network name derivation

The compose network name is derived as `$(basename "$(pwd)")_default`, where `$(pwd)` is the repo root (the script `cd`s to its own directory at line 3). For this repo, the network is `movie-semantic-search_default`.

### Container ID source

The dev container's own container ID is read from `/etc/hostname`, which Docker sets to the container's short ID at runtime. In tests, override this by writing a known value to a temp file and symlinking/passing it in, or by controlling the mock `cat /etc/hostname` behavior via a mock `cat` binary on PATH — or more simply, by having the script read from a variable that tests can export.

> **Note:** Because `/etc/hostname` cannot be easily overridden in shell tests without a mock `cat`, the implementation should expose the container ID as a variable (`DEV_CONTAINER_ID`) set once at the top of the `--using-dev-container` block. Tests that need to verify the disconnect call should mock `docker` to capture arguments and assert on `$CONTAINER_ID` separately, or accept that the test runs inside a container where `/etc/hostname` produces a valid (if random) ID.
