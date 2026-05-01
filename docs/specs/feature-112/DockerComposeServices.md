# DockerComposeServices Spec

**Feature:** #112 — Add load-model and load-data services to docker-compose.yml
**Component:** `DockerComposeServices` (`docker-compose.yml`, `.env.example`)

## Overview

Two new one-shot services — `load-model` and `load-data` — are added to `docker-compose.yml`. Both are gated behind Compose profiles so they never start automatically on `docker compose up -d`. `load-model` runs pipeline scripts 01–02 with no service dependencies, binding `data/` and `model-repository/` so outputs persist on the host. `load-data` depends on `triton` and `qdrant` being healthy, binds `data/` for read access, and passes `TMDB_API_KEY` from the `.env` file. Additionally, `.env.example` is updated to remove the stale `PROJECT_ROOT` variable.

## Operator Commands

These are the only commands operators use to invoke the two services. No other form is correct.

| Step | Command | When |
|------|---------|------|
| 1 — Export model | `docker compose run --rm load-model` | Before starting the stack; exports ONNX model and downloads corpus |
| 2 — Start the stack | `docker compose up -d` | After step 1; starts triton, qdrant, api — does NOT start load-model or load-data |
| 3 — Load data | `docker compose run --rm load-data` | After step 2, once triton and qdrant are healthy |

**Why `docker compose run` works without `--profile`:** `docker compose run` bypasses profile filtering — it starts a named service directly regardless of its `profiles` key. Profiles only affect `docker compose up`. So `docker compose run --rm load-model` is the correct invocation; adding `--profile load-model` is unnecessary and misleading.

## Data Contract

### `load-model` service

| Property | Value | Description |
|----------|-------|-------------|
| `profiles` | `[load-model]` | Prevents auto-start on `docker compose up -d`; invoke with `docker compose run --rm load-model` |
| `build` | `./pipeline` | Build context pointing to `pipeline/Dockerfile` |
| `volumes` | `./data:/app/data`, `./model-repository:/app/model-repository` | Host-persist model and data outputs |
| `entrypoint` (implicit) | Defined in `pipeline/Dockerfile` CMD/ENTRYPOINT | Should resolve to `/app/load-model.sh` |
| `depends_on` | none | Runs before Triton starts |
| `restart` | `no` (default) | One-shot; must not restart on failure |

### `load-data` service

| Property | Value | Description |
|----------|-------|-------------|
| `profiles` | `[load-data]` | Prevents auto-start on `docker compose up -d`; invoke with `docker compose run --rm load-data` |
| `build` | `./pipeline` | Same image as `load-model` |
| `volumes` | `./data:/app/data` | Reads embeddings written by `load-model` |
| `env_file` | `.env` | Passes all vars from `.env`, including `TMDB_API_KEY` |
| `depends_on` | `triton: service_healthy`, `qdrant: service_healthy` | Will not start until both pass healthcheck |
| `restart` | `no` (default) | One-shot |
| `command` | `["/app/load-data.sh"]` | Overrides CMD to run the load-data entrypoint |

### `.env.example`

| Property | Before | After |
|----------|--------|-------|
| `PROJECT_ROOT` description | `"Absolute path to the repo root — used by pipeline scripts referencing data/ and model-repository/ directories"` | Line removed entirely (variable is unused by pipeline scripts) |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `pipeline/Dockerfile` | Docker build context | Referenced via `build: ./pipeline` (from sibling feature #111) |
| `triton` service | Compose service with `service_healthy` condition | `depends_on` in `load-data` |
| `qdrant` service | Compose service with `service_healthy` condition | `depends_on` in `load-data` |

### Dependency Mock Behaviors

#### `triton` service

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Healthy | Triton passes its healthcheck | `load-data` is allowed to start |
| Unhealthy / not started | Triton fails healthcheck | `load-data` is blocked by Compose before the container runs at all |

#### `qdrant` service

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Healthy | Qdrant passes its healthcheck | `load-data` is allowed to start |
| Unhealthy / not started | Qdrant fails healthcheck | `load-data` is blocked by Compose |

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | `docker compose up -d` | `load-model` and `load-data` containers are NOT started | Profile gate prevents auto-start | Run without any `--profile` flag |
| 2 | `docker compose run --rm load-model` | Container starts, runs 01+02, exits | Correct operator invocation for step 1 | No service dependencies required |
| 3 | `docker compose run --rm load-data` with triton and qdrant healthy | Container starts, runs 03–05 (or skips 05), exits | Correct operator invocation for step 3 | Triton and Qdrant healthy |
| 4 | `docker compose run --rm load-data` with triton or qdrant unhealthy | Compose blocks start (`depends_on` not met) | `depends_on: service_healthy` enforced by Compose | Triton or Qdrant not passing healthcheck |
| 5 | After `docker compose run --rm load-model`, `./data/` and `./model-repository/` contain outputs | Files visible on the host | Volume bind-mounts work | load-model succeeds |
| 6 | `.env` contains `TMDB_API_KEY=abc123` | Variable available inside load-data container | `env_file` passthrough | `.env` file present |

## Unit Test Checklist

Compose service definitions are not Python-testable. All behavior is verified by the integration test task:

- [ ] `docker compose up -d` does not start `load-model` or `load-data`
- [ ] `load-model` service mounts `./data` and `./model-repository` as `rw` volumes
- [ ] `load-data` service `depends_on` triton and qdrant with `service_healthy` condition
- [ ] `load-data` service passes `TMDB_API_KEY` from `.env` via `env_file`
- [ ] `.env.example` no longer contains the `PROJECT_ROOT` variable
