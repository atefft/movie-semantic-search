# API Contract

All endpoints are served by the Spring Boot API on `:8080`.

---

## `GET /api/search`

Embed the query via Triton and return the top matching movies from Qdrant by cosine similarity.

### Query Parameters

| Parameter | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `q` | string | Yes | — | 1–500 characters |
| `limit` | integer | No | 10 | 1–50 |

### Response `200 OK`

```json
{
  "query": "films about loneliness in space",
  "count": 10,
  "results": [
    {
      "title": "Cast Away",
      "year": 2000,
      "genres": ["Drama", "Adventure"],
      "score": 0.8741,
      "summary_snippet": "A FedEx executive undergoes a physical and personal transformation...",
      "thumbnail_url": "/uVlUu174iiKLBgcNnDOCFR8LNKP.jpg"
    }
  ]
}
```

### Response Fields

| Field | Type | Notes |
|---|---|---|
| `query` | string | Echoed from request |
| `count` | integer | Number of results returned |
| `results[].title` | string | Movie title |
| `results[].year` | integer \| null | Release year; null if not available |
| `results[].genres` | string[] | e.g. `["Drama", "Thriller"]` |
| `results[].score` | float | Cosine similarity, 0.0–1.0 |
| `results[].summary_snippet` | string | First 300 chars of plot summary |
| `results[].thumbnail_url` | string \| null | TMDB poster path; prepend `https://image.tmdb.org/t/p/w200` to display. Null if not enriched. |

### Error Responses

| Status | Body | Condition |
|---|---|---|
| `400` | `{"error": "q parameter is required"}` | `q` is missing or blank |
| `400` | `{"error": "limit must be between 1 and 50"}` | `limit` out of range |
| `503` | `{"error": "Embedding service unavailable"}` | Triton unreachable |
| `503` | `{"error": "Search service unavailable"}` | Qdrant unreachable |

---

## `GET /api/autocomplete`

Same as `/api/search` but results are always capped at 3. Implemented as a thin wrapper that calls the same service with `limit=3`.

### Query Parameters

| Parameter | Type | Required | Constraints |
|---|---|---|---|
| `q` | string | Yes | 1–500 characters |

### Response `200 OK`

Same schema as `/api/search`. `count` will be ≤ 3.

### Error Responses

Same as `/api/search` (except no `limit` parameter errors).

---

## `GET /actuator/health`

Spring Boot Actuator health endpoint.

### Response `200 OK`

```json
{
  "status": "UP"
}
```

---

## Notes

- `thumbnail_url` is a TMDB poster **path only** (e.g. `/abc123.jpg`). Clients must prepend the base URL: `https://image.tmdb.org/t/p/w200`
- `score` is cosine similarity in the range 0.0–1.0. Higher is more similar.
- Results are returned in descending score order.
- The autocomplete endpoint is intended for debounced UI calls (300ms debounce, min 2 chars). It is not rate-limited on the server side.
