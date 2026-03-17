# Qdrant Component

Vector database storing 384-dimensional embeddings and movie metadata. Handles cosine similarity search at query time.

```mermaid
classDiagram
  class Point {
    +string id
    +float32[384] vector
    +Payload payload
  }

  class Payload {
    +string movie_id
    +string title
    +integer|null release_year
    +string[] genres
    +string summary_snippet
    +string|null thumbnail_url
  }

  class SearchRequest {
    +float32[384] vector
    +integer limit
    +bool with_payload
    +bool with_vectors
  }

  class SearchResponse {
    +ScoredPoint[] result
  }

  class ScoredPoint {
    +string id
    +float score
    +Payload payload
  }

  Point "1" --> "1" Payload
  SearchRequest --> SearchResponse
  SearchResponse "1" --> "*" ScoredPoint
  ScoredPoint "1" --> "1" Payload
```

## Collection Configuration

```
name:              movies
vectors.size:      384
vectors.distance:  Cosine
```

## Payload Schema

| Field | Type | Notes |
|---|---|---|
| `movie_id` | `string` | Wikipedia movie ID, stored as string for safety |
| `title` | `string` | Movie title from CMU metadata |
| `release_year` | `integer \| null` | Parsed from `release_date` field; null if unparseable |
| `genres` | `string[]` | Parsed from JSON map in metadata TSV (keys only) |
| `summary_snippet` | `string` | First 300 characters of plot summary |
| `thumbnail_url` | `string \| null` | TMDB poster path, e.g. `"/abc123.jpg"` — prepend base URL to display |

**TMDB poster base URL:** `https://image.tmdb.org/t/p/w200{poster_path}`

Example full URL: `https://image.tmdb.org/t/p/w200/abc123.jpg`

## Search Request

`POST /collections/movies/points/search`

```json
{
  "vector": [0.021, -0.047, 0.183, "...384 floats total"],
  "limit": 10,
  "with_payload": true,
  "with_vectors": false
}
```

## Search Response

```json
{
  "result": [
    {
      "id": "975900",
      "score": 0.8741,
      "payload": {
        "movie_id": "975900",
        "title": "Cast Away",
        "release_year": 2000,
        "genres": ["Drama", "Adventure"],
        "summary_snippet": "A FedEx executive undergoes a physical and personal transformation...",
        "thumbnail_url": "/uVlUu174iiKLBgcNnDOCFR8LNKP.jpg"
      }
    }
  ]
}
```

## Docker Configuration

- **Image:** `qdrant/qdrant:v1.8.4`
- **Ports:**
  - `:6333` — REST API + web dashboard (used by API and pipeline)
  - `:6334` — gRPC
- **Volume mount:** `./qdrant_storage:/qdrant/storage`
