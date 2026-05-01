# MakefilePipelineTarget Spec

**Feature:** #113 — Update documentation for the two-phase pipeline workflow
**Component:** `pipeline` Makefile target (`Makefile`)

## Overview

The `pipeline` Make target orchestrates the two-phase data loading workflow. It runs the three operations in strict order: `load-model` (export ONNX model and populate the Triton model repository), `docker compose up -d` (start Triton + Qdrant + API), and `load-data` (embed corpus and ingest into Qdrant + enrich with TMDB). If any phase exits non-zero, the target prints a clearly labelled error message identifying the failed phase and exits with a non-zero status — preventing silent partial runs.

## Implementation

```makefile
pipeline:
	docker compose run --rm load-model \
	  || (echo "[pipeline] load-model phase failed — aborting" && exit 1)
	docker compose up -d \
	  || (echo "[pipeline] services-up phase failed — aborting" && exit 1)
	docker compose run --rm load-data \
	  || (echo "[pipeline] load-data phase failed — aborting" && exit 1)
```

## Phase Sequence

| # | Phase | Command | Description |
|---|-------|---------|-------------|
| 1 | load-model | `docker compose run --rm load-model` | Exports all-MiniLM-L6-v2 to ONNX and places it in the Triton model repository |
| 2 | services-up | `docker compose up -d` | Starts triton, qdrant, and api containers in detached mode |
| 3 | load-data | `docker compose run --rm load-data` | Downloads corpus, embeds all summaries via Triton, ingests into Qdrant, enriches with TMDB poster URLs |

## Error Behaviour

| Phase that fails | Error message printed | Exit code |
|------------------|-----------------------|-----------|
| load-model | `[pipeline] load-model phase failed — aborting` | 1 |
| services-up | `[pipeline] services-up phase failed — aborting` | 1 |
| load-data | `[pipeline] load-data phase failed — aborting` | 1 |

Subsequent phases do not run after a failure. The partial environment is left in place so the operator can inspect logs before retrying.

## Files to Change

- `Makefile` — replace the `pipeline` target body (lines 9–15) with the three-phase implementation above

## Acceptance Criteria

- [ ] `make pipeline` runs `docker compose run --rm load-model` first
- [ ] `make pipeline` then runs `docker compose up -d`
- [ ] `make pipeline` then runs `docker compose run --rm load-data`
- [ ] If `load-model` exits non-zero, the message `[pipeline] load-model phase failed — aborting` is printed and make exits 1 without running later phases
- [ ] If `docker compose up -d` exits non-zero, the message `[pipeline] services-up phase failed — aborting` is printed and make exits 1
- [ ] If `load-data` exits non-zero, the message `[pipeline] load-data phase failed — aborting` is printed and make exits 1
