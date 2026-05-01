# load-data.sh Spec

**Feature:** #112 — Add load-model and load-data services to docker-compose.yml
**Component:** `load-data.sh` (`pipeline/load-data.sh`)

## Overview

`load-data.sh` is the container entrypoint for the `load-data` compose service. Before running any scripts, it probes Triton (`http://triton:8000/v2/health/ready`) and Qdrant (`http://qdrant:6333/healthz`) with `curl -sf`. If either probe fails, it prints one error message listing all unreachable services and exits 1. If both are reachable it runs scripts 03 (`03_embed_corpus.py`), 04 (`04_ingest_qdrant.py`), and 05 (`05_enrich_tmdb.py`) in sequence. Script 05 is skipped with a warning if `TMDB_API_KEY` is unset or empty. If any script exits non-zero, the entrypoint prints which script failed and its exit code, then stops immediately.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `TMDB_API_KEY` | env var, string | TMDB v3 API key for poster enrichment | If unset or empty, script 05 is skipped with a warning; not an error |
| Exit code | integer | Exit code of the entrypoint | 0 on full success (or TMDB skip); 1 if services unreachable; N (failing script's code) if a script fails |
| stdout/stderr | string | Progress, warnings, and error messages | See message formats below |

### Message Formats

| Event | Message |
|-------|---------|
| Triton unreachable (alone) | `"ERROR: the following services are not reachable: triton"` |
| Qdrant unreachable (alone) | `"ERROR: the following services are not reachable: qdrant"` |
| Both unreachable | `"ERROR: the following services are not reachable: triton, qdrant"` |
| TMDB_API_KEY unset/empty | `"WARNING: TMDB_API_KEY is not set — skipping 05_enrich_tmdb.py"` |
| Script N fails | `"ERROR: 03_embed_corpus.py failed with exit code N"` (etc.) |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| Triton health endpoint | `GET http://triton:8000/v2/health/ready` → HTTP 200 | Probed via `curl -sf` |
| Qdrant health endpoint | `GET http://qdrant:6333/healthz` → HTTP 200 | Probed via `curl -sf` |
| `03_embed_corpus.py` | Python script at `/app/03_embed_corpus.py` | Invoked as `python3 03_embed_corpus.py --triton-host triton --triton-port 8001` |
| `04_ingest_qdrant.py` | Python script at `/app/04_ingest_qdrant.py` | Invoked as `python3 04_ingest_qdrant.py --qdrant-url http://qdrant:6333` |
| `05_enrich_tmdb.py` | Python script at `/app/05_enrich_tmdb.py` | Invoked as `python3 05_enrich_tmdb.py --qdrant-url http://qdrant:6333` (only when `TMDB_API_KEY` is set) |

### Dependency Mock Behaviors

#### Triton health endpoint

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Reachable | `curl -sf` exits 0 | Normal operation |
| Unreachable | `curl -sf` exits non-zero | Network down or service not started |

#### Qdrant health endpoint

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Reachable | `curl -sf` exits 0 | Normal operation |
| Unreachable | `curl -sf` exits non-zero | Network down or service not started |

#### `03_embed_corpus.py`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Script exits 0 | Embeddings written to data/embeddings/ |
| Script fails | Script exits N | Entrypoint prints error and exits N; scripts 04 and 05 are not run |

#### `04_ingest_qdrant.py`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Script exits 0 | Points ingested into Qdrant |
| Script fails | Script exits N | Entrypoint prints error and exits N; script 05 is not run |

#### `05_enrich_tmdb.py`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path (key set) | `TMDB_API_KEY=abc123`, script exits 0 | Poster URLs enriched |
| Skipped (key unset) | `TMDB_API_KEY` not set | Warning printed; entrypoint exits 0 after scripts 03 and 04 |
| Skipped (key empty) | `TMDB_API_KEY=""` | Same as unset — warning printed, script skipped |
| Script fails | `TMDB_API_KEY=abc123`, script exits N | Entrypoint prints error and exits N |

**Observable output examples:**
```
WARNING: TMDB_API_KEY is not set — skipping 05_enrich_tmdb.py
```
```
ERROR: the following services are not reachable: triton, qdrant
```
```
ERROR: 03_embed_corpus.py failed with exit code 1
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | Both services reachable, all scripts succeed, TMDB key set | Exit code 0; no error or warning lines in stdout | Full success | All probes and scripts succeed |
| 2 | Triton unreachable, Qdrant reachable | Exit code 1; stdout contains exactly `"ERROR: the following services are not reachable: triton"`; no scripts run | Partial unreachability | Triton probe fails |
| 3 | Both unreachable | Exit code 1; stdout contains exactly `"ERROR: the following services are not reachable: triton, qdrant"`; no scripts run | Both services down | Both probes fail |
| 4 | Services reachable, `TMDB_API_KEY` unset | Exit code 0; stdout contains `"WARNING: TMDB_API_KEY is not set — skipping 05_enrich_tmdb.py"`; scripts 03 and 04 run; script 05 not invoked | TMDB key missing | `TMDB_API_KEY` not in environment |
| 5 | Services reachable, `TMDB_API_KEY=""` | Exit code 0; stdout contains `"WARNING: TMDB_API_KEY is not set — skipping 05_enrich_tmdb.py"`; script 05 not invoked | Empty string treated identically to unset | `TMDB_API_KEY` set to empty string |
| 6 | Script 03 fails with exit code 2 | Exit code 2; stdout contains `"ERROR: 03_embed_corpus.py failed with exit code 2"`; scripts 04 and 05 not invoked | Fail fast | Script 03 exits 2 |
| 7 | Scripts 03 and 04 succeed, script 05 fails with exit code 1 (key set) | Exit code 1; stdout contains `"ERROR: 05_enrich_tmdb.py failed with exit code 1"` | Last script failure | Scripts 03 and 04 succeed; 05 exits 1 |

## Unit Test Checklist

Shell scripts have no Python unit tests. All behavior is verified by the integration test task. The observable contract above defines what integration tests must assert:

- [ ] Happy path (key set): exit code 0; no error or warning lines in stdout
- [ ] Triton unreachable alone: exit code 1; stdout contains exactly `"ERROR: the following services are not reachable: triton"`; no scripts run
- [ ] Qdrant unreachable alone: exit code 1; stdout contains exactly `"ERROR: the following services are not reachable: qdrant"`; no scripts run
- [ ] Both unreachable: exit code 1; stdout contains exactly `"ERROR: the following services are not reachable: triton, qdrant"`; no scripts run
- [ ] `TMDB_API_KEY` unset: exit code 0; stdout contains `"WARNING: TMDB_API_KEY is not set — skipping 05_enrich_tmdb.py"`; script 05 not invoked
- [ ] `TMDB_API_KEY` empty string: identical output to unset
- [ ] Script 03 fails with exit code 2: exit code 2; stdout contains `"ERROR: 03_embed_corpus.py failed with exit code 2"`; scripts 04 and 05 not invoked
- [ ] Script 05 fails with exit code 1 (key set): exit code 1; stdout contains `"ERROR: 05_enrich_tmdb.py failed with exit code 1"`
- [ ] Script 03 invoked with `--triton-host triton --triton-port 8001`
- [ ] Scripts 04 and 05 invoked with `--qdrant-url http://qdrant:6333`
