# Pipeline Specification

Full specification for the offline Python pipeline that builds the search index.

---

## Dataset

**Source:** CMU Movie Summary Corpus
**URL:** `https://www.cs.cmu.edu/~ark/personas/data/MovieSummaries.tar.gz`
**Expected compressed size:** ~9 MB
**Expected extracted size:** ~480 MB

### `movie.metadata.tsv`

Tab-separated, no header row. 9 columns:

| Index | Field | Type | Notes |
|---|---|---|---|
| 0 | `wikipedia_movie_id` | string | Join key with `plot_summaries.txt` |
| 1 | `freebase_movie_id` | string | Not used |
| 2 | `movie_name` | string | Title |
| 3 | `movie_release_date` | string | e.g. `"2000-01-01"` or `"2000"` or empty |
| 4 | `movie_box_office_revenue` | string | Not used |
| 5 | `movie_runtime` | string | Not used |
| 6 | `movie_languages` | string | JSON map, not used |
| 7 | `movie_countries` | string | JSON map, not used |
| 8 | `movie_genres` | string | JSON map: `{"/m/xxx": "Drama", ...}` — extract values |

### `plot_summaries.txt`

Tab-separated, no header row. 2 columns:

| Index | Field | Type |
|---|---|---|
| 0 | `wikipedia_movie_id` | string |
| 1 | `plot_summary` | string (full text, may be very long) |

### Join Logic

- Join `movie.metadata.tsv` and `plot_summaries.txt` on `wikipedia_movie_id`
- Skip movies with no plot summary (present in metadata but not in summaries file)
- Skip movies with no metadata (present in summaries but not in metadata file)
- Expected result after join: ~42,000 movies

---

## Script Specifications

### `01_download_corpus.py`

- HTTPS GET `https://www.cs.cmu.edu/~ark/personas/data/MovieSummaries.tar.gz`
- Extract to `data/raw/`
- Idempotent: if `data/raw/movie.metadata.tsv` and `data/raw/plot_summaries.txt` both exist, skip download
- Show download progress bar via `tqdm`

### `02_export_model.py`

- Load `sentence-transformers/all-MiniLM-L6-v2` from HuggingFace (downloads on first run)
- Export ONNX model to `model-repository/all-minilm-l6-v2/1/model.onnx`
- Verification: run one inference with `onnxruntime.InferenceSession`, confirm output shape is `[1, 384]`
- Idempotent: skip if `model.onnx` already exists

### `03_embed_corpus.py`

- Reads `data/raw/movie.metadata.tsv` and `data/raw/plot_summaries.txt`
- Joins on `wikipedia_movie_id`
- Sends 1 warmup request to Triton before batch embedding (avoids cold-start latency skewing first batch)
- Batch size: 64 (configurable via `--batch-size` CLI argument)
- gRPC endpoint: `localhost:8001` (configurable via `--triton-host`, `--triton-port`)
- Outputs:
  - `data/embeddings/embeddings.npy` — `float32` array, shape `[N, 384]`
  - `data/embeddings/metadata.json` — JSON array of objects, same order as embeddings:
    ```json
    [
      {
        "movie_id": "975900",
        "title": "Cast Away",
        "release_year": 2000,
        "genres": ["Drama", "Adventure"],
        "summary_snippet": "A FedEx executive undergoes..."
      }
    ]
    ```
- `summary_snippet`: first 300 characters of the plot summary text
- `release_year`: parse from `movie_release_date` — try `YYYY-MM-DD` then `YYYY`; null if unparseable

### `04_ingest_qdrant.py`

- Qdrant endpoint: `http://localhost:6333` (configurable via `--qdrant-url`)
- Creates (or recreates) collection `movies`:
  - `vectors.size = 384`
  - `vectors.distance = Cosine`
- Reads `data/embeddings/embeddings.npy` and `data/embeddings/metadata.json`
- Upserts all points in batches of 256
- Point ID: integer index (0-based)
- Payload: all fields from `metadata.json` plus `thumbnail_url: null`
- Idempotent: collection is dropped and recreated on each run

### `05_enrich_tmdb.py`

- Requires `TMDB_API_KEY` environment variable (TMDB v3 API key)
- Qdrant endpoint: `http://localhost:6333` (configurable via `--qdrant-url`)
- Scrolls all Qdrant points where `thumbnail_url` is null
- For each unenriched point:
  1. Search TMDB: `GET https://api.themoviedb.org/3/search/movie?query={title}&year={year}&api_key={key}`
  2. Take `results[0].poster_path` (first result only)
  3. If no results or `poster_path` is null: leave `thumbnail_url` as null, continue
  4. Update Qdrant payload: `{"thumbnail_url": "/abc123.jpg"}`
- Rate limiting: sleep as needed to stay at or below **40 requests/second** (TMDB free tier)
- Idempotent: only processes points where `thumbnail_url` is null; safe to re-run after interruption

---

## Re-run Safety

All scripts are designed to be re-run without side effects:

| Script | Re-run behavior |
|---|---|
| `01_download_corpus.py` | Skips if output files exist |
| `02_export_model.py` | Skips if `model.onnx` exists |
| `03_embed_corpus.py` | Overwrites `embeddings.npy` and `metadata.json` |
| `04_ingest_qdrant.py` | Drops and recreates collection |
| `05_enrich_tmdb.py` | Only processes null `thumbnail_url` entries |

---

## Environment Variables

| Variable | Required By | Description |
|---|---|---|
| `TMDB_API_KEY` | `05_enrich_tmdb.py` | TMDB v3 API key |
| `TRITON_HOST` | `03_embed_corpus.py` | Triton host (default: `localhost`) |
| `TRITON_PORT` | `03_embed_corpus.py` | Triton gRPC port (default: `8001`) |
| `QDRANT_URL` | `04_ingest_qdrant.py`, `05_enrich_tmdb.py` | Qdrant base URL (default: `http://localhost:6333`) |
