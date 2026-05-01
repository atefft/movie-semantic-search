# PipelineDockerfile Spec

**Feature:** #111 — Create pipeline/Dockerfile for containerized script execution
**Component:** `pipeline/Dockerfile` (`pipeline/Dockerfile`)

## Overview

`pipeline/Dockerfile` builds a shared base image for all Docker Compose pipeline services (`load-model`, `load-data`, and any future pipeline service). It installs `libgomp1` via `apt-get` (required by `torch` CPU wheels on slim images), installs all Python dependencies from `pipeline/requirements.txt`, copies the five pipeline scripts into the image, and sets `WORKDIR /app` so that `data/` and `model-repository/` can be bind-mounted by Compose at `/app/data` and `/app/model-repository` respectively. No entrypoint is set — each Compose service specifies its own `command`.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| Base image | string | Docker base image | `python:3.11-slim` — fixed, not a build arg |
| System packages | apt | OS-level libraries | `libgomp1` — installed unconditionally via `apt-get` before pip; required by `torch` CPU wheels; apt lists cleaned after install |
| `WORKDIR` | path | Container working directory | `/app` — all relative paths in scripts (`data/raw`, `model-repository/...`) resolve under this dir |
| `requirements.txt` | file | Python dependency manifest | Copied first for layer-cache efficiency; `pip install --no-cache-dir -r requirements.txt` |
| Pipeline scripts | files | `01_download_corpus.py` … `05_enrich_tmdb.py` | All five `.py` files from `pipeline/` are COPYed into `/app`; no test files are included |
| `ENTRYPOINT` | — | Container entrypoint | Not set — command is provided entirely at the Compose service level |
| `CMD` | — | Default command | Not set — command is provided entirely at the Compose service level |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `python:3.11-slim` | Docker base image | `FROM` instruction |
| `libgomp1` | Debian apt package | `RUN apt-get install -y --no-install-recommends libgomp1` |
| `pipeline/requirements.txt` | Text file listing pip packages | `COPY` + `RUN pip install` |
| Pipeline scripts `01`–`05` | Python source files | `COPY *.py .` |

### Dependency Mock Behaviors

Dockerfile build is a compile-time operation; there are no runtime injected dependencies. The relevant failure modes are build-time:

#### python:3.11-slim

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Image available | Pull succeeds | Normal build operation |
| Registry unreachable | `docker build` fails with pull error | No mitigation — operator must have network access |

#### pip install (requirements.txt)

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| All packages resolve | Exit 0, layer cached | Normal operation |
| Package not found | `pip install` non-zero exit, build fails | Build fails with clear pip error message |
| Version conflict | `pip install` non-zero exit, build fails | Build fails with pip resolver error |

**Mock data structures:**
```json
// Successful build output (abridged)
{
  "step": "RUN pip install --no-cache-dir -r requirements.txt",
  "exit_code": 0,
  "packages_installed": ["transformers==4.39.3", "torch>=2.0.0", "onnxruntime==1.17.3", "tritonclient[grpc]", "tqdm", "qdrant-client", "requests"]
}

// Failed build output
{
  "step": "RUN pip install --no-cache-dir -r requirements.txt",
  "exit_code": 1,
  "error": "ERROR: No matching distribution found for <package>"
}
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | `docker build -f pipeline/Dockerfile .` run from repo root | Build fails — `COPY requirements.txt .` finds no file | Build context must be `pipeline/`, not repo root. Normal usage is `docker build pipeline/` (stays at repo root) or Compose `build: { context: pipeline }` — no `cd` needed. | Run `docker build pipeline/` from repo root; do not pass `.` as the context |
| 2 | `docker run <image>` with no command | Container exits immediately (no CMD/ENTRYPOINT defined) | No default command is a feature — Compose supplies the command | N/A — by design |
| 3 | Compose mounts `./data:/app/data` and `./model-repository:/app/model-repository` | Scripts resolve `pathlib.Path("data/raw")` to `/app/data/raw` | WORKDIR `/app` makes all relative script paths correct | Standard Compose volume bind-mount |
| 4 | `python -c "import torch"` inside built image | Succeeds without `ImportError` | `libgomp1` is pre-installed unconditionally — no runtime library surprise | `docker run --rm pipeline-test python -c "import torch"` exits 0 |

## Unit Test Checklist

The "unit tests" for a Dockerfile are build and smoke checks:

- [ ] `docker build -t pipeline-test pipeline/` exits 0 (build succeeds)
- [ ] `docker run --rm pipeline-test python -c "import transformers, torch, onnxruntime, tritonclient.grpc, tqdm, qdrant_client, requests"` exits 0 (all deps importable)
- [ ] `docker run --rm pipeline-test ls 01_download_corpus.py 02_export_model.py 03_embed_corpus.py 04_ingest_qdrant.py 05_enrich_tmdb.py` exits 0 (all scripts present in /app)
- [ ] `docker inspect pipeline-test --format '{{.Config.WorkingDir}}'` returns `/app`
- [ ] `docker inspect pipeline-test --format '{{.Config.Entrypoint}}'` returns `[]` (no entrypoint set)
- [ ] `docker inspect pipeline-test --format '{{.Config.Cmd}}'` returns `[]` (no default command set)
- [ ] Edge case #1: `docker build -f pipeline/Dockerfile .` (from repo root, wrong context) fails with a COPY error — confirming the build context must be `pipeline/`
