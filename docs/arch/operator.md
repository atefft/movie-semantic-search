# Operator Dashboard

A web page served by the Spring Boot app at `/operator`. Lets the operator inspect service health, monitor pipeline progress, and trigger pipeline steps — all from a browser without touching a terminal.

---

## UI Layout

```
┌─ Movie Semantic Search — Operator ───────────────────────────────────┐
│  Services                                                             │
│  ● Triton  [Start] [Stop]        ● Qdrant  [Start] [Stop]           │
├───────────────────────────────────────────────────────────────────────┤
│  Pipeline                [▶ Run Full Pipeline] [↺ Reset & Run All]   │
│                                                                       │
│  ✓  1. Download Corpus       prereqs: none          [Run]  [▾ Logs]  │
│  ✓  2. Export ONNX Model     prereqs: none          [Run]  [▾ Logs]  │
│  ●  3. Embed Corpus          prereqs: Triton, #1    [Run]  [▾ Logs]  │
│  ○  4. Ingest to Qdrant      prereqs: Qdrant, #3    [Run]  [▾ Logs]  │
│  ○  5. Enrich with TMDB      prereqs: Qdrant, #4    [Run]  [▾ Logs]  │
│       TMDB API Key: [________________________]                        │
│                                                                       │
│  ▾ Logs (step 3) ─────────────────────────────────────────────────── │
│    [stdout] Warming up Triton...                                      │
│    [stdout] Embedding batch 1/663 (64 items)...                      │
└───────────────────────────────────────────────────────────────────────┘
```

The page is a single static HTML file served from `src/main/resources/static/operator.html`. No build toolchain. Polls the API every 5 s via `setInterval`.

---

## Service Status & Control

Two cards — **Triton** and **Qdrant** — each showing:

- Health indicator: green dot (healthy) or red dot (unhealthy), polled every 5 s
- **Start** button: calls `POST /api/operator/service/{name}/start`
- **Stop** button: calls `POST /api/operator/service/{name}/stop`

Health checks are performed server-side by `OperatorController` on each `/api/operator/health` poll:

| Service | Endpoint | Healthy when |
|---|---|---|
| Triton | `GET http://localhost:8000/v2/health/ready` | HTTP 200 |
| Qdrant | `GET http://localhost:6333/healthz` | HTTP 200 |

---

## Pipeline Steps

### Step table

| # | Script | Prereqs |
|---|---|---|
| 1 | `01_download_corpus.py` | none |
| 2 | `02_export_model.py` | none |
| 3 | `03_embed_corpus.py` | Triton healthy + step 1 done |
| 4 | `04_ingest_qdrant.py` | Qdrant healthy + step 3 done |
| 5 | `05_enrich_tmdb.py` | Qdrant healthy + step 4 done + TMDB key set |

### "Already done" checks

All file paths are relative to `project.root` (configured in `application.yml`).

| # | Condition |
|---|---|
| 1 | `data/raw/movie.metadata.tsv` **and** `data/raw/plot_summaries.txt` both exist |
| 2 | `model-repository/all-minilm-l6-v2/1/model.onnx` **and** `model-repository/all-minilm-l6-v2/1/tokenizer.json` both exist |
| 3 | `data/embeddings/embeddings.npy` **and** `data/embeddings/metadata.json` both exist |
| 4 | `GET {qdrant.base-url}/collections/movies` returns `points_count > 0` |
| 5 | At least 1 Qdrant point has a non-null `thumbnail_url` field |

### Step status icons

| Icon | Meaning |
|---|---|
| `○` | Prereqs not met — Run button disabled |
| `●` | Prereqs met, not yet run — Run button enabled |
| `⟳` | Currently running |
| `✓` | Completed |

---

## Run Modes

| Action | Behaviour |
|---|---|
| **Run Full Pipeline** | Runs steps 1–5 in order; skips steps whose "already done" check passes |
| **Reset & Run All** | Runs steps 1–5 in order; ignores "already done" checks (equivalent to `?force=true` on `/api/operator/run/all`) |
| **Individual step [Run]** | Runs that step only; server validates prereqs and returns 409 if not met |

Only one step can execute at a time. Starting a second step while one is running returns 409.

---

## Log Streaming

Each step row has a collapsible log panel (`<details>`). When a step runs:

- The panel auto-opens and appends output via SSE (`text/event-stream`)
- Each stdout/stderr line arrives as: `data: <line>`
- On completion: `event: done` with `data: {"exitCode": 0}`
- Panel stays open after completion; can be manually collapsed

---

## TMDB API Key

Step 5's row includes a text input for the TMDB API key:

- Sent as `?tmdbKey=<value>` on the run request
- Validated server-side (non-blank) before the Python script is spawned; returns 400 if blank
- Never persisted — must be re-entered on page reload (no secrets stored in the app)

---

## API Reference (`OperatorController`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/operator/health` | `{"triton": bool, "qdrant": bool}` |
| `GET` | `/api/operator/status` | Array of 5 step objects (see below) |
| `GET` | `/api/operator/run/{step}` | SSE stream; runs step 1–5. Step 5 requires `?tmdbKey=`. Returns 409 if another step is running or prereqs not met. |
| `GET` | `/api/operator/run/all` | SSE stream; runs all steps in order, skipping done ones. `?force=true` skips the "already done" check. |
| `POST` | `/api/operator/service/{name}/start` | Starts `triton` or `qdrant` via `docker compose up -d {name}` |
| `POST` | `/api/operator/service/{name}/stop` | Stops `triton` or `qdrant` via `docker compose stop {name}` |

### Step status object

```json
{
  "step": 3,
  "name": "Embed Corpus",
  "done": false,
  "prereqsMet": true,
  "running": false
}
```

### HTTP status codes

| Code | When |
|---|---|
| 200 | Success |
| 400 | `tmdbKey` is blank on a step-5 run request |
| 409 | Another step is already running, or prereqs for the requested step are not met |
| 500 | `docker compose` or Python process failed to start |

---

## Implementation Notes (for later)

### `DockerService`
- Calls `docker compose up -d <name>` or `docker compose stop <name>` via `ProcessBuilder`
- Working directory set to `project.root`

### `PipelineService`
- Spawns each Python script via `ProcessBuilder`; working directory = `project.root`
- Merges environment variables per step (e.g. `TMDB_API_KEY` for step 5)
- Holds a single `AtomicReference<Process>` to enforce one-at-a-time execution

### `OperatorController`
- Uses Spring `SseEmitter` for `/run/*` endpoints
- Reads stdout/stderr from the spawned process on a virtual thread, forwarding each line as an SSE `data:` event
- Sends `event: done` with exit code on process exit

### `application.yml` additions

```yaml
project:
  root: /path/to/project

triton:
  health-url: http://localhost:8000/v2/health/ready

qdrant:
  base-url: http://localhost:6333
```
