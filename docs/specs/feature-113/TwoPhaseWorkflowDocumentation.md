# TwoPhaseWorkflowDocumentation Spec

**Feature:** #113 — Update documentation for the two-phase pipeline workflow
**Component:** Documentation files (6 files)

## Overview

All user-facing and developer-facing documentation must reflect the two-phase pipeline workflow: **Phase 1** (`load-model`) runs before services start; **Phase 2** (`load-data`) runs after services are up. Every existing reference to `docker compose run --rm pipeline`, `make pipeline` (as a one-shot pipeline runner), and the old sequencing bug (Triton started before model export) must be replaced with the correct three-step sequence.

The authoritative three-step sequence is:
```
docker compose run --rm load-model   # Phase 1: export model
docker compose up -d                 # start services
docker compose run --rm load-data    # Phase 2: embed + ingest
```

## Files and Required Changes

### 1. `README.md`

**Section: "Quick Start (for future reference)"** (lines 211–239)

Replace the current quick-start block with the three-step sequence:

```bash
# 1. Export the ONNX model (Phase 1 — run once before starting services)
docker compose run --rm load-model

# 2. Start infrastructure (Triton + Qdrant + API)
docker compose up -d

# 3. Load the movie data (Phase 2 — run once after services are healthy)
docker compose run --rm load-data

# 4. Open the UI
open http://localhost:8080
```

**Section: "Or with Make:"** (lines 232–238)

Replace the `make pipeline` description comment so it reads:
```bash
make pipeline   # export model, start services, load data
make up         # start all services
make down       # stop all services
```

**Why:** The old quick start starts Triton before exporting `model.onnx`, which causes Triton to fail to load the model on startup (sequencing bug).

---

### 2. `docs/arch/pipeline.md`

**Opening description** (line 3): Update the first paragraph to reflect the two-phase execution model:

> Five Python scripts that build the search index, executed in two phases. Scripts 01–02 (`load-model` phase) run before services start and export the ONNX model to the Triton model repository. Scripts 03–05 (`load-data` phase) run after services are healthy and require Triton and Qdrant to be reachable.

**Embedded quick start** (if present): Replace any `docker compose run --rm pipeline` or `make pipeline` reference in this file with the three-step sequence.

---

### 3. `docs/executive-guide.html`

**Section: "Getting Started"** — the numbered list currently has 3 steps. Step 2 ("Load the movie data") must be replaced with three full numbered steps, renumbering the original step 3 ("Open the Search Interface") as step 5:

1. **Start the services.** (unchanged)
2. **Export the embedding model.** Run `docker compose run --rm load-model` from the repository root. This exports the ONNX model and must complete before the services start.
3. **Start the services** — replace the previous "start the services" step with `docker compose up -d` (move here since the model export must come first).
4. **Load the movie data.** Run `docker compose run --rm load-data` from the repository root. This embeds all movie summaries and loads them into the search database.
5. **Open the Search Interface.** (unchanged, renumbered from 3)

> Note: The current step 1 says "Start the services" and step 2 says "Load the movie data." With the new sequence, model export must happen BEFORE services start. Restructure accordingly: the description that was "start services then run pipeline" becomes "export model, start services, load data."

**Section: "End-to-End Test Walkthrough" — Prerequisites** (line 128–137):

Current step 4 is `docker compose up -d` (start all services) and step 5 is `docker compose run --rm pipeline` (load data). Replace with:

- Step 4: `docker compose run --rm load-model` — export the embedding model
- Step 5: `docker compose up -d` — start all services (wait ~30 seconds for healthy)
- Step 6: `docker compose run --rm load-data` — load all movie records

Update step numbering downstream (old step 6 "Confirm system is ready" becomes step 7).

**Troubleshooting references** in the walkthrough failure blocks: replace all occurrences of `docker compose run --rm pipeline` with `docker compose run --rm load-data` (the appropriate phase for re-running data ingestion).

---

### 4. `docs/specs/feature-88/WalkthroughSection.html`

Four occurrences of `docker compose run --rm pipeline` must be updated:

| Location | Old text | New text |
|----------|----------|----------|
| Prerequisites step 5 (operator runs the pipeline) | `docker compose run --rm pipeline` | `docker compose run --rm load-data` |
| Remediation block — Step 1 (Qdrant empty) | `docker compose run --rm pipeline` | `docker compose run --rm load-data` |
| Remediation block — Step 2 (vectors missing) | `docker compose run --rm pipeline` | `docker compose run --rm load-data` |
| Remediation block — Step 5 (irrelevant results) | `docker compose run --rm pipeline` | `docker compose run --rm load-data` |

---

### 5. `docs/specs/feature-6/TritonModelRepository.html`

**Model Directory Layout section** (line 112): The paragraph ending with:
> Run `make pipeline` (or `python pipeline/02_export_onnx.py` directly) to produce it before starting Triton.

Replace with:
> Run `docker compose run --rm load-model` to produce it before starting the services. Alternatively, run `python pipeline/02_export_model.py` directly.

---

### 6. `docs/specs/feature-6/MakefileAndEnv.html`

**Makefile Targets table — `pipeline` row:**

| Field | Old | New |
|-------|-----|-----|
| Command(s) | `pip install -r requirements.txt` then scripts 01–05 in order | `docker compose run --rm load-model`, then `docker compose up -d`, then `docker compose run --rm load-data` |
| Description | Install Python dependencies and run all five pipeline steps end-to-end. | Orchestrate the two-phase data loading workflow: export the ONNX model, start all services, then load all movie data. Exits with a labelled error message if any phase fails. |

**Pipeline Target Detail paragraph**: Replace current text describing `01_fetch_tmdb.py` through `05_enrich_tmdb.py` with description of the three-phase orchestration.

**Environment Variables table — `PROJECT_ROOT` row:**

| Field | Old | New |
|-------|-----|-----|
| Description | Absolute path to the repository root; used by pipeline scripts to resolve data and model directory paths. | Absolute path to the repo root — present in `.env.example` for historical reasons; not used by any pipeline script. |
| Required | Yes | No |

### 7. `.env.example`

Remove the `PROJECT_ROOT` variable and its comment line entirely. The file should only retain the `TMDB_API_KEY` variable and its header comment.

## Acceptance Criteria

- [ ] `README.md` quick start shows `load-model` → `docker compose up -d` → `load-data` in that order
- [ ] `README.md` Make section comment describes the new orchestration behaviour
- [ ] `docs/arch/pipeline.md` opening description reflects two-phase execution model
- [ ] `docs/arch/pipeline.md` contains no references to `docker compose run --rm pipeline` or `make pipeline` as a pipeline runner
- [ ] `docs/executive-guide.html` Getting Started section has the three-step sequence (model export → services up → load data)
- [ ] `docs/executive-guide.html` walkthrough prerequisites have the three-step sequence (load-model → docker compose up -d → load-data)
- [ ] `docs/executive-guide.html` troubleshooting remediation commands use `load-data` not `pipeline`
- [ ] `docs/specs/feature-88/WalkthroughSection.html` has zero occurrences of `docker compose run --rm pipeline`
- [ ] `docs/specs/feature-6/TritonModelRepository.html` references `docker compose run --rm load-model` instead of `make pipeline`
- [ ] `docs/specs/feature-6/MakefileAndEnv.html` pipeline target row describes the three-phase orchestration
- [ ] `docs/specs/feature-6/MakefileAndEnv.html` PROJECT_ROOT row shows Required: No and description says it is unused by pipeline scripts
- [ ] `.env.example` does not contain PROJECT_ROOT
