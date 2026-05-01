# ComposePreflight Spec

**Feature:** #158 — Pre-flight Docker Compose startup check in integration test suite
**Component:** `compose_preflight` (`pipeline/tests/compose_preflight.py`)

## Overview

`compose_preflight` is a standalone module that implements the pre-flight Docker Compose startup check. It exposes a single entry point, `preflight_check(timeout, project_dir)`, that runs `docker compose up -d` and then polls `docker compose ps` until the `triton`, `qdrant`, and `api` service containers all report a `running` state, or the timeout expires. On any failure it raises `PreflightError` with a human-readable message that includes the failing service names or compose exit code and output. The module has no pytest dependency and is independently testable by mocking `subprocess.run`.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `timeout` | `int` | Maximum seconds to wait for all services to reach running state | Must be > 0; default 60 is applied by the caller (conftest fixture), not here |
| `project_dir` | `pathlib.Path` or `str` | Absolute path to the directory containing `docker-compose.yml` | Passed as `cwd` to every `subprocess.run` call; must exist |
| `PreflightError.message` | `str` | Human-readable failure reason | Includes exit code + compose output on `up -d` failure; lists non-running service names on timeout |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `subprocess.run` | stdlib | Module-level import; mocked in tests |
| `time.sleep` | stdlib | Module-level import; mocked in tests |
| `time.monotonic` | stdlib | Module-level import; mocked in tests |

### Dependency Mock Behaviors

#### `subprocess.run` — `docker compose up -d`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Returns `CompletedProcess(returncode=0, stdout="", stderr="")` | Compose starts successfully |
| Non-zero exit | Returns `CompletedProcess(returncode=1, stdout="some output", stderr="error detail")` | Compose failed to start |

**Mock data structures:**
```json
// Happy path
{"returncode": 0, "stdout": "", "stderr": ""}

// Failure
{"returncode": 1, "stdout": "Creating network...\n", "stderr": "Error response from daemon: port is already allocated"}
```

#### `subprocess.run` — `docker compose ps --format json`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| All running | Returns JSON list where all three services have `"State": "running"` | Poll succeeds immediately |
| Partially running | Returns JSON list where ≥1 service has `"State": "starting"` or `"exited"` | Poller must retry |
| Timeout — still not running | Partial list returned on every call until `time.monotonic()` exceeds deadline | Raises `PreflightError` listing non-running services |
| Empty output | Returns `CompletedProcess(returncode=0, stdout="[]", stderr="")` | Treated as zero services running; poller retries |
| `docker compose ps` fails | Returns `CompletedProcess(returncode=1, stdout="", stderr="no such service")` | Raises `PreflightError` with the stderr message |

**Mock data structures:**
```json
// All running
[
  {"Name": "movie-semantic-search-triton-1", "Service": "triton", "State": "running"},
  {"Name": "movie-semantic-search-qdrant-1", "Service": "qdrant", "State": "running"},
  {"Name": "movie-semantic-search-api-1",    "Service": "api",    "State": "running"}
]

// Partially running (api still starting)
[
  {"Name": "movie-semantic-search-triton-1", "Service": "triton", "State": "running"},
  {"Name": "movie-semantic-search-qdrant-1", "Service": "qdrant", "State": "running"},
  {"Name": "movie-semantic-search-api-1",    "Service": "api",    "State": "starting"}
]

// Empty
[]
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | `docker compose up -d` exits 1 | `PreflightError("docker compose up -d failed (exit 1):\n<stdout+stderr>")` | Compose itself fails to start | `subprocess.run` returns `returncode=1` on first call |
| 2 | All services running on first poll | Returns normally (no exception) | Happy path completes in one poll cycle | `up -d` → 0; `ps` → all running |
| 3 | Services reach running on second poll | Returns normally | Realistic slow-start scenario | First `ps` returns partial; second returns all running |
| 4 | Timeout expires with qdrant not running | `PreflightError("timed out after 60s; services not running: qdrant")` | One service never starts | `ps` always returns qdrant as `"starting"` until deadline |
| 5 | Timeout expires with triton and api not running | `PreflightError("timed out after 60s; services not running: triton, api")` | Multiple services fail | `ps` always returns triton and api as non-running |
| 6 | `docker compose ps` returns empty list | Treated as no services running; poller retries | Compose project not yet visible | `ps` returns `[]` once, then all-running |
| 7 | `docker compose ps` exits non-zero | `PreflightError("docker compose ps failed: <stderr>")` | ps command itself errors | `subprocess.run` returns `returncode=1` on ps call |
| 8 | `project_dir` does not contain `docker-compose.yml` | `PreflightError` propagated from compose exit 1 | Wrong directory | compose returns non-zero |

## Unit Test Checklist

- [ ] Happy path: `preflight_check(60, project_dir)` completes without raising when `up -d` exits 0 and all services report `running` on first poll
- [ ] `up -d` non-zero exit: raises `PreflightError` containing the exit code and combined stdout+stderr; `ps` is never called
- [ ] Partial running then all running: completes normally after two poll cycles; `time.sleep` called at least once
- [ ] Timeout — one service stuck: raises `PreflightError` whose message contains `"timed out"` and the non-running service name
- [ ] Timeout — multiple services stuck: `PreflightError` message lists all non-running service names
- [ ] Empty `ps` output then all running: completes normally (empty treated as not-yet-running, not as an error)
- [ ] `ps` non-zero exit: raises `PreflightError` containing the stderr from the ps command
- [ ] `time.sleep` is called between poll attempts (not a busy-wait loop)
