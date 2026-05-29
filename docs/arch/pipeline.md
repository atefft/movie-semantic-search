# Pipeline Component

Five Python scripts that build the search index, executed in two phases. Scripts 01–02 (`load-model` phase) run before services start and export the ONNX model to the Triton model repository. Scripts 03–05 (`load-data` phase) run after services are healthy and require Triton and Qdrant to be reachable.

```mermaid
sequenceDiagram
  participant FS as Filesystem
  participant TMDB as TMDB API
  participant Triton as Triton :8001
  participant Qdrant as Qdrant :6333

  Note over FS: 01_download_corpus.py
  FS->>FS: HTTPS fetch MovieSummaries.tar.gz
  FS->>FS: Extract → data/raw/movie.metadata.tsv<br/>data/raw/plot_summaries.txt

  Note over FS: 02_export_model.py
  FS->>FS: Load sentence-transformers/all-MiniLM-L6-v2
  FS->>FS: Export → model-repository/all-minilm-l6-v2/1/model.onnx
  FS->>FS: Verify with onnxruntime inference check

  Note over FS,Triton: 03_embed_corpus.py
  FS->>FS: Join metadata.tsv + plot_summaries.txt on wikipedia_movie_id
  FS->>FS: Tokenize summaries (HuggingFace, max_length=128)
  FS->>Triton: Warmup: 1 dummy request
  loop Batches of 64
    FS->>Triton: gRPC InferRequest (input_ids, attention_mask, token_type_ids)
    Triton-->>FS: token_embeddings float32[batch, seq, 384]
    FS->>FS: Mean-pool (attention-mask weighted) → float32[batch, 384]
  end
  FS->>FS: Save data/embeddings/embeddings.npy + metadata.json

  Note over FS,Qdrant: 04_ingest_qdrant.py
  FS->>Qdrant: Create collection "movies" (384-dim, Cosine)
  FS->>Qdrant: Upsert all points (vector + payload)

  Note over Qdrant,TMDB: 05_enrich_tmdb.py
  Qdrant->>Qdrant: Scroll points lacking thumbnail_url
  loop Per unenriched point (≤40 req/s)
    Qdrant->>TMDB: Search by title + year
    TMDB-->>Qdrant: poster_path
    Qdrant->>Qdrant: Update payload: thumbnail_url = poster_path
  end
```

## Scripts

### `01_download_corpus.py`

- Source: `https://www.cs.cmu.edu/~ark/personas/data/MovieSummaries.tar.gz`
- Extracts to `data/raw/`
- Output files:
  - `movie.metadata.tsv` — 9 columns, tab-separated
  - `plot_summaries.txt` — 2 columns: `wikipedia_movie_id`, plot text
- Idempotent: skips download if files already exist

### `02_export_model.py`

- Loads `sentence-transformers/all-MiniLM-L6-v2` from HuggingFace
- Exports ONNX model and saves tokenizer files to `model-repository/all-minilm-l6-v2/1/`:
  - `model.onnx`
  - `tokenizer.json`
  - `tokenizer_config.json`
  - `vocab.txt`
  - `special_tokens_map.json`
- Runs a verification inference with `onnxruntime` to confirm the export is valid
- Idempotent: skips export if `model.onnx` **and** `tokenizer.json` both exist

ONNX tensor names produced by this export:

| Direction | Name | dtype | Shape |
|---|---|---|---|
| Input | `input_ids` | int64 | `[batch, seq]` |
| Input | `attention_mask` | int64 | `[batch, seq]` |
| Input | `token_type_ids` | int64 | `[batch, seq]` |
| Output | `token_embeddings` | float32 | `[batch, seq, 384]` |

The exported ONNX model returns per-token `token_embeddings`. The **consumer** mean-pools these
over the sequence dimension (weighted by `attention_mask`) to produce the final `[batch, 384]`
sentence embedding — `03_embed_corpus.py` at index time and the Java API at query time. Triton
runs `model.onnx` directly via its ONNX Runtime backend and does no pooling. See `docs/arch/triton.md`.

### `03_embed_corpus.py`

- Reads `movie.metadata.tsv` and `plot_summaries.txt`
- Joins on `wikipedia_movie_id`; skips movies with no summary or no metadata
- Sends warmup request to Triton before batch processing
- Batch size: 64 (configurable via `--batch-size`)
- Saves:
  - `data/embeddings/embeddings.npy` — float32 array of shape `[N, 384]`
  - `data/embeddings/metadata.json` — list of `{movie_id, title, release_year, genres, summary_snippet}` in the same order

### `04_ingest_qdrant.py`

- Creates Qdrant collection `movies` with:
  - `vectors.size = 384`
  - `vectors.distance = Cosine`
- Point ID is the embedding's sequential index (`0 … N-1`), not the Wikipedia movie ID
- Upserts all points. Payload per point:
  ```
  movie_id:        string        (wikipedia_movie_id, cast to string)
  title:           string
  release_year:    integer|null  (parsed from release_date field)
  genres:          string[]      (parsed from JSON map in metadata TSV)
  summary_snippet: string        (first 300 chars of plot_summaries.txt)
  thumbnail_url:   string|null   (null at this stage; populated by script 05)
  ```
- Re-runnable: unconditionally calls `recreate_collection`, which **drops and rebuilds** the
  collection from scratch every run (a full wipe, not a skip-if-exists)
- Upserts in batches of 256

### `05_enrich_tmdb.py`

- Requires `TMDB_API_KEY` environment variable
- Scrolls all Qdrant points where `thumbnail_url` is null
- For each: searches TMDB by `title + release_year`, takes first result's `poster_path`
- Updates Qdrant payload: `thumbnail_url = "/abc123.jpg"` (poster path only; prepend base URL at display time)
- Rate-limited to **40 req/s** (TMDB free tier limit)
- Idempotent: skips points that already have `thumbnail_url` set
- If TMDB returns no match, `thumbnail_url` remains null

## `requirements.txt`

```
transformers==4.39.3
torch>=2.0.0
numpy==1.26.4
onnxruntime>=1.20
onnxscript==0.7.0
tritonclient[grpc]
tqdm
qdrant-client==1.18.0
requests
```

`torch` and `onnxscript` are export-time dependencies (`02_export_model.py`); `tritonclient[grpc]`,
`tqdm`, and `requests` are intentionally unpinned.
