# VectorSearchServiceImpl Spec

**Feature:** #267 — Multi-vector ingestion and mean-pooled search for full movie summaries
**Component:** `VectorSearchServiceImpl` (`api/src/main/java/com/moviesearch/service/impl/VectorSearchServiceImpl.java`)

## Overview

`VectorSearchServiceImpl` is updated to support multi-chunk Qdrant results. Instead of requesting `limit` results and mapping 1:1, it requests `limit × oversamplingFactor` chunks, groups them by `movie_id`, computes each movie's score as the arithmetic mean of all its matching chunk scores, and returns up to `limit` distinct movies sorted descending by mean score. The highest-scoring chunk text for each movie is returned as `matchingSegment`; the `full_summary` payload field populates `summarySnippet`. Chunks with a null or missing `movie_id` are silently skipped. The oversampling factor is read from `QdrantProperties.oversamplingFactor` (default 20). `MovieResult` gains a new `matchingSegment` field. `MockVectorSearchService` is updated to populate `matchingSegment` with a hardcoded excerpt per result.

**Backwards compatibility:** this component is designed to be deployed before the pipeline is re-run against the new schema. When Qdrant still holds old-schema data (one point per movie, `summary_snippet` present, `chunk_text`/`full_summary` absent), the service must degrade gracefully: grouping by `movie_id` is a no-op (one chunk per movie), the mean of a single score equals that score, and the missing new fields fall back to `summary_snippet`. Once the pipeline is re-run and new-schema data is ingested, the full multi-chunk behaviour activates automatically.

## Data Contract

### `VectorSearchRequest` (unchanged)
| Field | Type | Behavior |
|-------|------|----------|
| `vector` | `float[]` | Query embedding, 384 dimensions |
| `limit` | `int` | Distinct movies to return (1–50) |

### `VectorSearchResponse` (unchanged)
| Field | Type | Behavior |
|-------|------|----------|
| `results` | `List<MovieResult>` | Up to `limit` entries, fewer if pool yields fewer distinct movies |

### `MovieResult` (updated — new field)
| Field | Type | Behavior |
|-------|------|----------|
| `title` | `String` | Movie title |
| `year` | `Integer` | Release year, nullable |
| `genres` | `List<String>` | Genre list |
| `score` | `float` | Arithmetic mean of all matching chunk scores |
| `summarySnippet` | `String` | Full movie summary from `full_summary` Qdrant payload field |
| `matchingSegment` | `String` | Chunk text of the highest-scoring chunk for this movie (new) |
| `thumbnailUrl` | `String` | Poster URL, nullable |

### `QdrantProperties` (updated — new field)
| Field | Type | Default | Config key |
|-------|------|---------|------------|
| `baseUrl` | `String` | `http://localhost:6333` | `qdrant.base-url` |
| `mock` | `boolean` | `false` | `qdrant.mock` |
| `oversamplingFactor` | `int` | `20` | `qdrant.oversampling-factor` |

`oversamplingFactor` valid range: **1–100** (inclusive). Values outside this range are treated as misconfiguration and clamped to the default of 20 at runtime. This is a runtime guard, not a startup validation failure — the service remains operational with a degraded-but-safe factor.

### Qdrant request sent
- Endpoint: `POST /collections/movies/points/search`
- Body: `{ "vector": [...], "limit": limit * oversamplingFactor, "with_payload": true }`

### Qdrant payload fields read (updated, with backwards-compat fallbacks)
| JSON field | Mapped to | Fallback | Notes |
|------------|-----------|----------|-------|
| `movie_id` | grouping key | — | If null → chunk silently skipped |
| `title` | `MovieResult.title` | — | |
| `release_year` | `MovieResult.year` | — | |
| `genres` | `MovieResult.genres` | — | |
| `chunk_text` | `MovieResult.matchingSegment` | `summary_snippet` | Use `chunk_text` if non-null; fall back to `summary_snippet` for old-schema data |
| `full_summary` | `MovieResult.summarySnippet` | `summary_snippet` | Use `full_summary` if non-null; fall back to `summary_snippet` for old-schema data |
| `thumbnail_url` | `MovieResult.thumbnailUrl` | — | Processed by `absolutePosterUrl` |

The fallback rule for both `matchingSegment` and `summarySnippet`: if the preferred new-schema field is null or absent, use `summary_snippet`. This allows the service to return correct results against an un-migrated Qdrant collection.

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `RestTemplate` | `org.springframework.web.client.RestTemplate` | Constructor |
| `QdrantProperties` | Spring `@ConfigurationProperties` bean | Constructor |
| `TmdbProperties` | Spring `@ConfigurationProperties` bean | Constructor |

### Dependency Mock Behaviors

#### RestTemplate

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | Returns `QdrantSearchResponse` with multiple chunks for multiple movies | Normal aggregation |
| All chunks belong to one movie | Returns chunks all with same `movie_id` | Only one `MovieResult` returned |
| Chunks with null `movie_id` | Some results have `payload.movie_id = null` | Those chunks skipped silently |
| Empty result list | Returns `QdrantSearchResponse` with empty `result` | Returns `VectorSearchResponse` with empty list |
| `ResourceAccessException` | Throws `ResourceAccessException` | Rethrown as `VectorSearchServiceException(CONNECTION_REFUSED)` |
| Non-2xx HTTP status | Throws `HttpStatusCodeException` | Rethrown as `VectorSearchServiceException(NON_2XX_RESPONSE)` |

**Mock data structures:**
```json
// Happy path: 3 chunks across 2 movies (movie "111" has 2 chunks, "222" has 1)
// limit=2, oversamplingFactor=20 → fetch 40 chunks from Qdrant
{
  "result": [
    {"score": 0.90, "payload": {"movie_id": "111", "title": "Cast Away", "release_year": 2000,
      "genres": ["Drama"], "chunk_text": "He crash-lands on an island.",
      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}},
    {"score": 0.70, "payload": {"movie_id": "111", "title": "Cast Away", "release_year": 2000,
      "genres": ["Drama"], "chunk_text": "He builds a raft.",
      "full_summary": "Full Cast Away summary.", "thumbnail_url": null}},
    {"score": 0.85, "payload": {"movie_id": "222", "title": "Alien", "release_year": 1979,
      "genres": ["Sci-Fi"], "chunk_text": "In space no one hears you.",
      "full_summary": "Full Alien summary.", "thumbnail_url": null}}
  ]
}

// Expected VectorSearchResponse:
// movie "111": mean score = (0.90+0.70)/2 = 0.80, matchingSegment = "He crash-lands on an island."
// movie "222": mean score = 0.85, matchingSegment = "In space no one hears you."
// Sorted desc: Alien (0.85), Cast Away (0.80)

// Null movie_id chunk (skipped):
{"score": 0.95, "payload": {"movie_id": null, "title": "Ghost Movie", ...}}
// → not included in any group

// Empty result:
{"result": []}
// → VectorSearchResponse(results=[])
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | All chunks for one movie | Single `MovieResult` with mean of all scores | Oversampling returned only one distinct movie | Chunks all share same `movie_id` |
| 2 | Chunk with `movie_id = null` | Chunk excluded; other movies unaffected | Silent skip | Mix null and non-null `movie_id` in result |
| 3 | Empty Qdrant result | `VectorSearchResponse(results=[])` | No matches | `result = []` |
| 4 | Pool has fewer distinct movies than `limit` | Returns all distinct movies found | Fewer-than-limit is acceptable | Only N < limit distinct `movie_id`s |
| 5 | One movie has higher mean score than one with a higher top chunk | Higher-mean movie ranked first | Mean pooling behavior is intentional | Multiple chunks with varying scores |
| 6 | `ResourceAccessException` from RestTemplate | `VectorSearchServiceException(CONNECTION_REFUSED)` | Qdrant unreachable | `restTemplate.postForEntity` throws |
| 7 | Non-2xx HTTP status from Qdrant | `VectorSearchServiceException(NON_2XX_RESPONSE)` | Bad request or server error | `restTemplate.postForEntity` throws `HttpStatusCodeException` |
| 8 | `oversamplingFactor = 1` | Fetches `limit * 1` chunks | Minimum valid factor | `QdrantProperties.getOversamplingFactor()` returns 1 |
| 9 | `qdrant.oversamplingFactor` missing from config | Defaults to 20 | Property has a default | Spring uses field default value |
| 10 | Old-schema payload: `chunk_text` null, `full_summary` null, `summary_snippet` present | `matchingSegment` = `summary_snippet`; `summarySnippet` = `summary_snippet` | Backwards compat with un-migrated Qdrant data | Payload has only `summary_snippet` set |
| 11 | Old-schema payload: single point per movie | Mean of one score = that score; one `MovieResult` per movie | No-op aggregation on un-migrated data | One result per distinct `movie_id` |
| 12 | `oversamplingFactor` configured as `> 100` | Factor clamped to 20; `limit * 20` chunks fetched | Guard against misconfiguration causing oversized Qdrant queries | `QdrantProperties.getOversamplingFactor()` returns 150 |
| 13 | `oversamplingFactor` configured as `≤ 0` | Factor clamped to 20; `limit * 20` chunks fetched | Guard against zero/negative misconfiguration | `QdrantProperties.getOversamplingFactor()` returns 0 or -1 |
| 14 | `resp.getBody()` is null | Return empty `VectorSearchResponse` | Qdrant returns 200 with empty body | `restTemplate.postForEntity` returns response with null body |
| 15 | `resp.getBody().result` is null | Return empty `VectorSearchResponse` | Body present but result field absent/null | `QdrantSearchResponse` with `result = null` |
| 16 | Chunk has null `payload` | Chunk skipped silently; other chunks unaffected | Qdrant result with no payload object | One result in list has `payload = null` |

## Unit Test Checklist

- [ ] Happy path: 3 chunks (2 movies) → 2 `MovieResult`s sorted by mean score descending
- [ ] `QdrantSearchRequest.limit` = `request.getLimit() * oversamplingFactor`
- [ ] Movie with 2 chunks: `score` = arithmetic mean of both chunk scores
- [ ] `matchingSegment` = `chunk_text` of highest-scoring chunk when `chunk_text` is non-null
- [ ] `matchingSegment` = `summary_snippet` when `chunk_text` is null (old-schema fallback)
- [ ] `summarySnippet` = `full_summary` from Qdrant payload when `full_summary` is non-null
- [ ] `summarySnippet` = `summary_snippet` when `full_summary` is null (old-schema fallback)
- [ ] Chunk with `movie_id = null` is excluded; other results unaffected
- [ ] Empty Qdrant result → empty `VectorSearchResponse`
- [ ] `ResourceAccessException` → `VectorSearchServiceException` with `CONNECTION_REFUSED` message
- [ ] `HttpStatusCodeException` → `VectorSearchServiceException` with `NON_2XX_RESPONSE` message
- [ ] Result count ≤ `request.getLimit()` even when pool has more distinct movies
- [ ] `QdrantProperties`: missing `qdrant.oversampling-factor` uses default of 20
- [ ] `QdrantProperties`: blank `qdrant.base-url` fails startup with `ConstraintViolationException`
- [ ] `oversamplingFactor > 100` → effective factor is 20 (clamp to default)
- [ ] `oversamplingFactor = 0` → effective factor is 20 (clamp to default)
- [ ] `oversamplingFactor = -1` → effective factor is 20 (clamp to default)
- [ ] Null response body → empty `VectorSearchResponse`, no exception
- [ ] Null `result` field in response body → empty `VectorSearchResponse`, no exception
- [ ] Chunk with null `payload` → skipped silently; remaining chunks still aggregated
