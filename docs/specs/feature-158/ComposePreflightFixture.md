# ComposePreflightFixture Spec

**Feature:** #158 — Pre-flight Docker Compose startup check in integration test suite
**Component:** `compose_preflight_fixture` (`pipeline/tests/conftest.py`)

## Overview

`compose_preflight_fixture` is a session-scoped autouse pytest fixture added to `pipeline/tests/conftest.py`. It reads the `COMPOSE_PREFLIGHT` environment variable; if unset or empty, it returns immediately (opt-in design — default off, so parallel `/execute-task` agent runs are unaffected). When `COMPOSE_PREFLIGHT` is set to any non-empty value, the fixture reads `COMPOSE_STARTUP_TIMEOUT` (default `"60"`), resolves the repo root as `Path(__file__).parent.parent.parent`, and calls `preflight_check(timeout, project_dir)` from `compose_preflight`. If `PreflightError` is raised, the fixture calls `pytest.fail(str(e))`, which aborts the entire session before any test case runs.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `COMPOSE_PREFLIGHT` | env var (`str`) | Opt-in gate | If absent or empty string, fixture returns without action |
| `COMPOSE_STARTUP_TIMEOUT` | env var (`str`) | Timeout in seconds | Parsed as `int`; defaults to `60` if absent or empty; raises `pytest.fail` if non-integer |
| `project_dir` | `pathlib.Path` | Repo root (contains `docker-compose.yml`) | Computed as `Path(__file__).parent.parent.parent` — three levels up from `conftest.py` |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `compose_preflight.preflight_check` | `(timeout: int, project_dir: Path) -> None` | Module-level import |
| `compose_preflight.PreflightError` | Exception class | Module-level import |
| `os.environ` | stdlib | Module-level import |
| `pytest.fail` | pytest stdlib | Module-level import |

### Dependency Mock Behaviors

#### `compose_preflight.preflight_check`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Does nothing (returns `None`) | All services running; fixture completes without error |
| Pre-flight failure | Raises `PreflightError("timed out after 60s; services not running: api")` | Fixture must call `pytest.fail` with that message |

#### `os.environ` (via `monkeypatch.setenv` / `monkeypatch.delenv`)

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| `COMPOSE_PREFLIGHT` unset | `monkeypatch.delenv("COMPOSE_PREFLIGHT", raising=False)` | Fixture must return without calling `preflight_check` |
| `COMPOSE_PREFLIGHT` set to `"1"` | `monkeypatch.setenv("COMPOSE_PREFLIGHT", "1")` | Fixture must call `preflight_check` |
| `COMPOSE_STARTUP_TIMEOUT` unset | `monkeypatch.delenv("COMPOSE_STARTUP_TIMEOUT", raising=False)` | `timeout=60` must be passed to `preflight_check` |
| `COMPOSE_STARTUP_TIMEOUT` set to `"30"` | `monkeypatch.setenv("COMPOSE_STARTUP_TIMEOUT", "30")` | `timeout=30` must be passed to `preflight_check` |
| `COMPOSE_STARTUP_TIMEOUT` set to `"abc"` | `monkeypatch.setenv("COMPOSE_STARTUP_TIMEOUT", "abc")` | Fixture calls `pytest.fail` with a message indicating invalid timeout value |

**Mock data structures:**
```python
# Happy path env
{"COMPOSE_PREFLIGHT": "1", "COMPOSE_STARTUP_TIMEOUT": "60"}

# Disabled (default)
{}  # neither env var set
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | `COMPOSE_PREFLIGHT` unset | Fixture returns; `preflight_check` not called | Default-off behavior; no docker interaction | `delenv("COMPOSE_PREFLIGHT")` |
| 2 | `COMPOSE_PREFLIGHT=""` (empty string) | Fixture returns; `preflight_check` not called | Empty string treated as unset | `setenv("COMPOSE_PREFLIGHT", "")` |
| 3 | `COMPOSE_PREFLIGHT="1"`, preflight succeeds | Fixture returns normally; no `pytest.fail` | Happy path | `preflight_check` returns `None` |
| 4 | `COMPOSE_PREFLIGHT="1"`, `PreflightError` raised | `pytest.fail(error_message)` called with exact error message | Pre-flight failure aborts session | `preflight_check` raises `PreflightError("...")` |
| 5 | `COMPOSE_STARTUP_TIMEOUT` unset | `preflight_check` called with `timeout=60` | Default timeout applied | `delenv("COMPOSE_STARTUP_TIMEOUT")` |
| 6 | `COMPOSE_STARTUP_TIMEOUT="30"` | `preflight_check` called with `timeout=30` | Custom timeout respected | `setenv("COMPOSE_STARTUP_TIMEOUT", "30")` |
| 7 | `COMPOSE_STARTUP_TIMEOUT="abc"` | `pytest.fail("COMPOSE_STARTUP_TIMEOUT must be an integer, got: abc")` | Non-integer value caught early | `setenv("COMPOSE_STARTUP_TIMEOUT", "abc")` |

## Unit Test Checklist

- [ ] `COMPOSE_PREFLIGHT` unset: `preflight_check` is never called
- [ ] `COMPOSE_PREFLIGHT=""`: `preflight_check` is never called
- [ ] `COMPOSE_PREFLIGHT="1"`, preflight succeeds: fixture completes; `pytest.fail` not called
- [ ] `COMPOSE_PREFLIGHT="1"`, `PreflightError` raised: `pytest.fail` called with the exact `PreflightError` message
- [ ] `COMPOSE_STARTUP_TIMEOUT` unset: `preflight_check` called with `timeout=60`
- [ ] `COMPOSE_STARTUP_TIMEOUT="30"`: `preflight_check` called with `timeout=30`
- [ ] `COMPOSE_STARTUP_TIMEOUT="abc"`: `pytest.fail` called with a message containing `"COMPOSE_STARTUP_TIMEOUT"` and `"abc"`
