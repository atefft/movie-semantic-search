# load-model.sh Spec

**Feature:** #112 — Add load-model and load-data services to docker-compose.yml
**Component:** `load-model.sh` (`pipeline/load-model.sh`)

## Overview

`load-model.sh` is the container entrypoint for the `load-model` compose service. It runs pipeline scripts 01 (`01_download_corpus.py`) and 02 (`02_export_model.py`) in sequence from the `/app` working directory. Each script's stdout and stderr are streamed live via `tee` to a temporary file. If a script exits non-zero, the entrypoint prints the last 10 lines of that script's output followed by a clearly-labelled error line, then exits with the same code. On success it exits 0. It has no service dependencies and is intended to run before Triton starts.

### Why capture output

Scripts 01 and 02 can fail for distinct, diagnosable reasons:
- Script 01 (`01_download_corpus.py`): network error fetching the CMU corpus URL, disk-full or permission error writing to `data/raw/`, or a corrupt/incomplete tarball during extraction.
- Script 02 (`02_export_model.py`): HuggingFace download failure for the tokenizer or model, OOM during ONNX export, ONNX runtime load error, or shape assertion failure on the verification pass.

Python already prints tracebacks to stderr. By replaying the last 10 lines inline, the error summary is self-contained in `docker compose logs` without needing to scroll up or re-run the container.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| Exit code | integer | Exit code of the entrypoint | 0 on full success; non-zero (the failing script's exit code) on any script failure |
| stdout/stderr | string | Progress and error messages | See message formats below |

### Message Formats

| Event | Message |
|-------|---------|
| Script starts | `"Running 01_download_corpus.py..."` (repeated for each script) |
| Output replay separator | `"--- last output from 01_download_corpus.py ---"` (printed before the error line on failure) |
| Script fails | `"ERROR: 01_download_corpus.py failed with exit code N"` (etc.) |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `01_download_corpus.py` | Python script at `/app/01_download_corpus.py` | Invoked via `python3 01_download_corpus.py` |
| `02_export_model.py` | Python script at `/app/02_export_model.py` | Invoked via `python3 02_export_model.py` |

### Dependency Mock Behaviors

#### `01_download_corpus.py`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Script exits 0 | Normal download and extraction |
| Already downloaded | Script exits 0, prints skip message | Idempotent; entrypoint exits 0 |
| Network error | Script raises `urllib.error.URLError`, exits 1 | Traceback visible in last-10-lines replay |
| Disk full / permission denied | Script raises `OSError`, exits 1 | Traceback visible in replay |
| Corrupt archive | Script raises `tarfile.TarError`, exits 1 | Traceback visible in replay |

#### `02_export_model.py`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Script exits 0 | Normal ONNX export |
| Already exported | Script exits 0, prints skip message | Idempotent; entrypoint exits 0 |
| HuggingFace download failure | `AutoTokenizer.from_pretrained` raises `OSError`, exits 1 | Traceback names the model and URL |
| OOM during export | `torch.onnx.export` raises `RuntimeError`, exits 1 | Traceback names the step |
| Shape assertion failure | `assert pooled.shape == (1, 384)` fails, exits 1 | Traceback includes actual shape |

**Observable output examples:**
```
Running 01_download_corpus.py...
Downloading corpus from https://www.cs.cmu.edu/~ark/...
Traceback (most recent call last):
  File "01_download_corpus.py", line 27, in download_and_extract
    with urllib.request.urlopen(url) as response:
  ...
urllib.error.URLError: <urlopen error [Errno -2] Name or service not known>
--- last output from 01_download_corpus.py ---
ERROR: 01_download_corpus.py failed with exit code 1
```
```
Running 02_export_model.py...
...
Verifying ONNX model...
Traceback (most recent call last):
  File "02_export_model.py", line 89, in main
    assert pooled.shape == (1, 384), f"Unexpected shape: {pooled.shape}"
AssertionError: Unexpected shape: (1, 256)
--- last output from 02_export_model.py ---
ERROR: 02_export_model.py failed with exit code 1
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | Both scripts exit 0 | Exit code 0; stdout ends with `"Running 02_export_model.py..."` and the script's own output; no error lines | Full success | Both scripts succeed |
| 2 | Script 01 exits 1 | Exit code 1; stdout contains `"--- last output from 01_download_corpus.py ---"` followed by `"ERROR: 01_download_corpus.py failed with exit code 1"`; script 02 never started | Fail fast on first failure | Script 01 exits 1 |
| 3 | Script 01 exits 0, script 02 exits 3 | Exit code 3; stdout contains `"--- last output from 02_export_model.py ---"` followed by `"ERROR: 02_export_model.py failed with exit code 3"` | Exact exit code propagated | Script 02 exits 3 |
| 4 | Script 01 raises `urllib.error.URLError` (exits 1) | Exit code 1; stdout contains the `urllib.error.URLError` traceback, then `"--- last output from 01_download_corpus.py ---"`, then `"ERROR: 01_download_corpus.py failed with exit code 1"` | Network error surfaced inline | Script 01 raises network error |
| 5 | Script 02 raises shape `AssertionError` (exits 1) | Exit code 1; stdout contains `"AssertionError: Unexpected shape: (1, N)"`, then `"--- last output from 02_export_model.py ---"`, then `"ERROR: 02_export_model.py failed with exit code 1"` | Shape error surfaced inline | Script 02 fails assertion |

## Unit Test Checklist

Shell scripts have no Python unit tests. All behavior is verified by the integration test task. The observable contract above defines what integration tests must assert:

- [ ] Happy path: exit code 0; no error lines in stdout
- [ ] Script 01 fails with exit code 1: exit code 1; stdout contains `"--- last output from 01_download_corpus.py ---"` followed by `"ERROR: 01_download_corpus.py failed with exit code 1"`; script 02 not invoked
- [ ] Script 02 fails with exit code 3: exit code 3; stdout contains `"--- last output from 02_export_model.py ---"` followed by `"ERROR: 02_export_model.py failed with exit code 3"`
- [ ] Script output is streamed live (not buffered until completion)
