# DockerComposeStartupWorkflow Spec

**Feature:** #159 — CI job for Docker Compose service startup verification
**Component:** `DockerComposeStartupWorkflow` (`.github/workflows/docker-compose-check.yml` + `docker-compose.ci.yml`)

## Overview

This component adds a GitHub Actions workflow that verifies the three core Docker Compose services (triton stub, qdrant, api) can start and reach a healthy state on every pull request targeting `main` and every push to `main`. Task-branch-to-feature-branch PRs are excluded to avoid rate limits. Because the real Triton image (`nvcr.io/nvidia/tritonserver:24.01-py3`) is ~15–20 GB and requires a GPU, a `docker-compose.ci.yml` override replaces the Triton service with a lightweight Python HTTP stub that satisfies Triton's healthcheck (`GET /v2/health/ready → 200`). The api service builds from the actual repo Dockerfile, making this a real smoke test of the application build and startup path. Qdrant starts from its published image unmodified.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `STARTUP_TIMEOUT` | `integer` (seconds) | Max seconds to wait for all services to become healthy | Workflow-level env var, default `60`. Passed to `--wait-timeout`. |
| Workflow trigger | GitHub event | When the workflow fires | `pull_request` with `branches: [main]`; `push` with `branches: [main]` |
| Required status check | GitHub branch protection | Whether the job blocks merges to `main` | Must be enabled in repo Settings → Branches → main after the workflow file is merged |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `docker compose` CLI | Docker Compose v2 | Pre-installed on `ubuntu-latest` runner |
| `docker-compose.yml` | Compose file | Base service definitions (triton, qdrant, api, profiles) |
| `docker-compose.ci.yml` | Compose override file | Replaces triton with HTTP stub; checked into repo |
| `Dockerfile` (repo root) | Docker build context | Used by the `api` service build |

### Dependency Mock Behaviors

This is a CI workflow, not a unit-testable class. The table below describes failure modes of each dependency and the expected workflow behavior.

#### `docker compose up -d --wait`

| Scenario | Behavior | Notes |
|----------|----------|-------|
| All services healthy within timeout | Step exits 0; workflow passes | Normal operation |
| A service fails healthcheck within timeout | Step exits non-zero; workflow fails | `docker compose ps` still logged; `down` still runs |
| Timeout exceeded before all healthy | Step exits non-zero; workflow fails | `--wait-timeout` enforces the deadline |
| `docker compose up` itself errors (bad YAML, missing image) | Step exits non-zero; workflow fails | Logged to runner stdout |

#### `docker-compose.ci.yml` Triton stub

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | `python:3.12-alpine` serving `{}` + 200 on port 8000 | Responds to `/v2/health/ready` |
| Container fails to start | Workflow fails; `docker compose ps` shows container state | Diagnose via runner logs |

**Triton stub service definition (in `docker-compose.ci.yml`):**
```yaml
services:
  triton:
    image: python:3.12-alpine
    ports:
      - "8000:8000"
      - "8001:8001"
      - "8002:8002"
    command: >
      python3 -c "
      from http.server import HTTPServer, BaseHTTPRequestHandler;
      class H(BaseHTTPRequestHandler):
        def do_GET(self): self.send_response(200); self.end_headers(); self.wfile.write(b'{}')
        def log_message(self, *a): pass
      HTTPServer(('', 8000), H).serve_forever()"
    healthcheck:
      test: ["CMD", "python3", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/v2/health/ready')"]
      interval: 5s
      timeout: 3s
      retries: 3
      start_period: 5s
```

**Note:** The real Triton image is excluded from CI due to its size (~15–20 GB) and GPU requirements. This is an intentional scope decision for a portfolio project. The gRPC channel in `TritonGrpcConfig.java` is lazy — no connection to port 8001 is made at startup, so the stub does not need to handle gRPC traffic.

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | api Dockerfile build fails (compile error) | Workflow fails at `docker compose up` step | Build errors surface before healthcheck | None — real Dockerfile used |
| 2 | qdrant container fails to start | Workflow fails; api never starts (depends_on) | Cascading failure is visible in `docker compose ps` output | None — real qdrant image used |
| 3 | api healthcheck fails (`/api/operator/health` non-200) | Workflow fails after timeout | App started but is unhealthy | None — real api build used |
| 4 | `STARTUP_TIMEOUT` too low for api build time | Workflow fails with timeout | api build can take 60–90s on cold Maven cache; timeout must account for build + startup | Set `STARTUP_TIMEOUT` to at least 120 on first run |
| 5 | `docker compose down` fails after a failed startup | Runner step still exits; no resource leak because runner is ephemeral | `if: always()` ensures teardown runs | N/A — GitHub Actions runner is discarded after job |
| 6 | PR targets a branch other than `main` (e.g. feature → feature) | Workflow does not trigger | Correct by trigger configuration | `pull_request: branches: [main]` |

## Unit Test Checklist

This component is a CI workflow file — it is not unit-tested. Acceptance is verified by observing the workflow run in GitHub Actions.

- [ ] Workflow triggers on a PR targeting `main` (visible in Actions tab)
- [ ] Workflow does NOT trigger on a PR targeting a non-`main` branch
- [ ] All three services (triton stub, qdrant, api) reach healthy state: `docker compose ps` shows all running
- [ ] `docker compose ps` output is visible in the job log
- [ ] `docker compose down` runs even when a service fails (inject a failure and confirm teardown step runs)
- [ ] Job appears as a required status check on a PR to `main` after branch protection is configured
- [ ] `STARTUP_TIMEOUT` env var is respected (default 60 visible in workflow YAML)
