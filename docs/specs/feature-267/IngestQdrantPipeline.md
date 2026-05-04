# IngestQdrantPipeline Spec

**Feature:** #267 — Multi-vector ingestion and mean-pooled search for full movie summaries
**Component:** `IngestQdrantPipeline` (`pipeline/04_ingest_qdrant.py`)

## Overview

`IngestQdrantPipeline` (`04_ingest_qdrant.py`) is updated to consume the flat multi-chunk metadata format produced by `EmbedCorpusPipeline`. `build_points` keeps the same `(embeddings, metadata)` signature — each metadata record now corresponds to one chunk and carries `chunk_text`, `full_summary`, and `movie_id` instead of `summary_snippet`. Point IDs remain global sequential integers (row index into the flat array). The `setup_collection` call and upsert loop are unchanged; only the payload field set changes.

## Data Contract

### `build_points(embeddings, metadata) -> list[PointStruct]`

| Parameter | Type | Description | Behavior |
|-----------|------|-------------|----------|
| `embeddings` | `np.ndarray[float32, (N, 384)]` | One row per chunk | Row i must correspond to `metadata[i]` |
| `metadata` | `list[dict]` | Flat chunk records from `metadata.json` | Each dict must have the fields below |

### Payload fields written per point

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `movie_id` | `str` | `metadata[i]["movie_id"]` | Used by API to group chunks |
| `title` | `str` | `metadata[i]["title"]` | |
| `release_year` | `int \| null` | `metadata[i]["release_year"]` | |
| `genres` | `list[str]` | `metadata[i]["genres"]` | |
| `chunk_text` | `str` | `metadata[i]["chunk_text"]` | Replaces `summary_snippet` |
| `full_summary` | `str` | `metadata[i]["full_summary"]` | New field |
| `thumbnail_url` | `null` | Hardcoded | Enriched later by step 05 |

### Point ID scheme
- ID = row index `i` (integer, 0-based, global across all chunks)
- With N total chunk records, IDs are `0 .. N-1`

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `qdrant_client.QdrantClient` | Qdrant Python client | Constructed in `main` |
| `qdrant_client.http.models.PointStruct` | Data class | Imported |

### Dependency Mock Behaviors

#### QdrantClient

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | `recreate_collection` and `upsert` both succeed silently | Normal operation |
| Qdrant unreachable | `upsert` raises `Exception` | Not in scope of unit tests |

**Mock data structures:**
```json
// Sample metadata input (2 chunks for movie 111, 1 for movie 222)
[
  {"movie_id": "111", "title": "Cast Away", "release_year": 2000, "genres": ["Drama"],
   "chunk_text": "A FedEx executive crash-lands.", "full_summary": "A FedEx executive crash-lands. He survives."},
  {"movie_id": "111", "title": "Cast Away", "release_year": 2000, "genres": ["Drama"],
   "chunk_text": "He survives.", "full_summary": "A FedEx executive crash-lands. He survives."},
  {"movie_id": "222", "title": "Alien", "release_year": 1979, "genres": ["Sci-Fi"],
   "chunk_text": "In space no one hears you.", "full_summary": "In space no one hears you."}
]

// Expected PointStruct for record 0:
// id=0, vector=[...384 floats...], payload={movie_id:"111", title:"Cast Away",
//   release_year:2000, genres:["Drama"], chunk_text:"A FedEx executive crash-lands.",
//   full_summary:"A FedEx executive crash-lands. He survives.", thumbnail_url:null}
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | Single-chunk metadata (1 record) | 1 PointStruct with id=0 | Minimum valid case | N/A |
| 2 | Two records for same movie_id | 2 PointStructs with ids 0, 1 | Multiple chunks per movie are independent points | N/A |
| 3 | `thumbnail_url` not in metadata dict | Payload sets `thumbnail_url=None` | Consistent with existing behavior | N/A |
| 4 | `recreate_collection` called before `upsert` | `recreate_collection` call index < `upsert` call index | Collection must be reset before inserting | Check `mock_client.method_calls` order |

## Unit Test Checklist

- [ ] `build_points` returns one `PointStruct` per metadata record
- [ ] IDs are `0, 1, 2, ...` matching record index
- [ ] Payload includes `chunk_text` and `full_summary` from the metadata record
- [ ] Payload does **not** include `summary_snippet`
- [ ] Payload includes `movie_id`
- [ ] `thumbnail_url` is `None` in the payload
- [ ] `setup_collection` is called before the first `upsert` in `main`
- [ ] All points are upserted across batches (total count equals len(metadata))
- [ ] Two records with the same `movie_id` produce two independent points with different IDs
