# EmbedCorpusPipeline Spec

**Feature:** #267 — Multi-vector ingestion and mean-pooled search for full movie summaries
**Component:** `EmbedCorpusPipeline` (`pipeline/03_embed_corpus.py`)

## Overview

`EmbedCorpusPipeline` (`03_embed_corpus.py`) is updated to replace the 300-char truncation with full-summary chunking. `join_data` now produces a flat list of per-chunk records — one entry per chunk, with all movie-level fields (`movie_id`, `title`, `release_year`, `genres`) plus `chunk_text` (the chunk) and `full_summary` (the complete uncapped summary) on every record. `main` drives `SummaryChunker` per movie, embeds every chunk record independently, and writes the resulting flat `embeddings.npy` and `metadata.json`. Movies with empty summaries are silently skipped (produce zero records). The existing idempotency check (skip if both output files exist) is preserved.

## Data Contract

### Input files (unchanged paths)
| File | Format | Notes |
|------|--------|-------|
| `data/raw/movie.metadata.tsv` | TSV, 9 columns | Column 0: movie_id, 2: title, 3: release_date, 8: genres JSON |
| `data/raw/plot_summaries.txt` | TSV, 2 columns | Column 0: movie_id, 1: full summary text |

### Per-chunk record (element of `metadata.json`)
| Field | Type | Description | Behavior |
|-------|------|-------------|----------|
| `movie_id` | `str` | CMU movie identifier | Repeated on every chunk for the same movie |
| `title` | `str` | Movie title | Repeated on every chunk |
| `release_year` | `int \| null` | Parsed from release_date | Repeated on every chunk |
| `genres` | `list[str]` | Genre names | Repeated on every chunk |
| `chunk_text` | `str` | Decoded text of this chunk | Unique per record |
| `full_summary` | `str` | Complete uncapped summary | Repeated on every chunk; same as the raw summary text from the source file |

### Output files
| File | Format | Notes |
|------|--------|-------|
| `data/embeddings/embeddings.npy` | `float32` array `[N_chunks, 384]` | Row i corresponds to `metadata.json[i]` |
| `data/embeddings/metadata.json` | JSON array of chunk records | One element per chunk (not per movie) |

### CLI flags (new/changed)
| Flag | Default | Description |
|------|---------|-------------|
| `--window-size` | `512` | Token window size passed to SummaryChunker |
| `--overlap` | `64` | Token overlap passed to SummaryChunker |
| `--max-chunks` | `10` | Chunk cap passed to SummaryChunker |
| `--batch-size` | `64` | Embedding batch size (unchanged) |
| `--triton-host` | `localhost` | Unchanged |
| `--triton-port` | `8001` | Unchanged |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `SummaryChunker` | `chunk_summary(text, tokenizer, window_size, overlap, max_chunks) -> list[str]` | Direct call |
| `AutoTokenizer` | HuggingFace tokenizer | Constructed in `main` via `AutoTokenizer.from_pretrained` |
| `_infer_batch` | `(grpcclient, client, input_ids, attention_mask, token_type_ids) -> np.ndarray` | Direct call (monkeypatched in tests) |
| Triton gRPC client | `grpcclient.InferenceServerClient` | Constructed in `main` |

### Dependency Mock Behaviors

#### SummaryChunker (`chunk_summary`)

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Returns list of 1–`max_chunks` strings | Normal multi-chunk movie |
| Empty summary | Returns `[]` | Movie silently skipped |
| Single chunk | Returns `["<text>"]` | Short movie; one point in Qdrant |

#### `_infer_batch`

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Returns `np.ones((B, 384), dtype="float32")` | Used in existing tests; same pattern |
| Triton unavailable | Raises `InferenceServerException` | Not in scope of unit tests (integration concern) |

**Mock data structures:**
```json
// metadata.json output — 2 movies, movie "111" has 2 chunks, movie "222" has 1 chunk
[
  {"movie_id": "111", "title": "Cast Away", "release_year": 2000, "genres": ["Drama"],
   "chunk_text": "A FedEx executive crash-lands on an island.", "full_summary": "A FedEx executive crash-lands on an island. He must survive alone for years."},
  {"movie_id": "111", "title": "Cast Away", "release_year": 2000, "genres": ["Drama"],
   "chunk_text": "He must survive alone for years.", "full_summary": "A FedEx executive crash-lands on an island. He must survive alone for years."},
  {"movie_id": "222", "title": "Alien", "release_year": 1979, "genres": ["Sci-Fi"],
   "chunk_text": "In space, no one can hear you scream.", "full_summary": "In space, no one can hear you scream."}
]
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | Movie with empty summary | Movie absent from metadata.json and embeddings.npy | Silent skip | `chunk_summary` returns `[]` |
| 2 | All movies have empty summaries | Empty metadata.json, 0-row embeddings.npy | Degenerate but valid | All `chunk_summary` calls return `[]` |
| 3 | Movie with summary fitting one chunk | One record for that movie in metadata.json | No truncation | `chunk_summary` returns one-element list |
| 4 | Both output files already exist | `sys.exit(0)` without loading data | Idempotency preserved | No mocks needed |
| 5 | `full_summary` field | Same value on all chunk records for a movie | Not truncated by chunk cap | `chunk_summary` receives full text; `full_summary` written from raw summary |

## Unit Test Checklist

- [ ] Happy path: `join_data` with 2 movies (3 and 1 chunks) returns 4 flat records
- [ ] Each record contains `movie_id`, `title`, `release_year`, `genres`, `chunk_text`, `full_summary`
- [ ] `full_summary` on all records for a movie equals the raw summary string (not capped)
- [ ] Movie with empty summary is absent from `join_data` output
- [ ] `main` writes `embeddings.npy` with shape `[N_chunks, 384]` where N_chunks equals total chunk records
- [ ] `main` writes `metadata.json` with one element per chunk record
- [ ] Row i of `embeddings.npy` corresponds to record i of `metadata.json` (same movie_id at matching index)
- [ ] Idempotency: `main` exits 0 without calling `_infer_batch` when output files already exist
- [ ] `--max-chunks`, `--window-size`, `--overlap` CLI flags are accepted and forwarded to `chunk_summary`
