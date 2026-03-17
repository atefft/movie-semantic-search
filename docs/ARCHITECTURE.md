# Architecture Overview

End-to-end system for semantic search over ~42,000 CMU movie plot summaries. Click any component node to drill into its detail page.

```mermaid
flowchart TB
  subgraph Offline["Offline Pipeline (run once)"]
    CORPUS["CMU Corpus\n42k movies + TMDB posters"]
    CORPUS --> PIPELINE["Python Pipeline\n5 scripts"]
    PIPELINE --> TRITON_LOAD["Triton Server\nbatch embed :8001"]
    TRITON_LOAD --> QDRANT_INDEX["Qdrant\nvectors + metadata"]
  end

  subgraph Online["Online Serving (per request)"]
    BROWSER["Browser\nautocomplete + results UI"]
    BROWSER -->|"GET /api/search or /api/autocomplete"| API["Spring Boot API\n:8080"]
    API -->|"gRPC :8001"| TRITON_QUERY["Triton Server\nembed query"]
    API -->|"REST :6333"| QDRANT_SEARCH["Qdrant\nvector search"]
  end

  click PIPELINE "https://github.com/atefft/movie-semantic-search/blob/main/docs/arch/pipeline.md"
  click API "https://github.com/atefft/movie-semantic-search/blob/main/docs/arch/api.md"
  click TRITON_LOAD "https://github.com/atefft/movie-semantic-search/blob/main/docs/arch/triton.md"
  click TRITON_QUERY "https://github.com/atefft/movie-semantic-search/blob/main/docs/arch/triton.md"
  click QDRANT_INDEX "https://github.com/atefft/movie-semantic-search/blob/main/docs/arch/qdrant.md"
  click QDRANT_SEARCH "https://github.com/atefft/movie-semantic-search/blob/main/docs/arch/qdrant.md"
```

## Components

| Component | Detail Page | Role |
|---|---|---|
| Python Pipeline | [pipeline.md](arch/pipeline.md) | Offline: download corpus, export model, embed, ingest, enrich with TMDB posters |
| Triton Inference Server | [triton.md](arch/triton.md) | Serve `all-MiniLM-L6-v2` over gRPC; text in → float32[384] out |
| Qdrant | [qdrant.md](arch/qdrant.md) | Store 384-dim vectors + movie metadata; cosine similarity search |
| Spring Boot API | [api.md](arch/api.md) | HTTP endpoints, orchestrate Triton + Qdrant, serve UI |

## Data Flow

**Offline (once):** CMU corpus → Python scripts → Triton (batch embed 42k summaries) → Qdrant (index vectors) → TMDB enrichment (add poster URLs)

**Online (per request):** Browser query → Spring Boot → Triton (embed query) → Qdrant (top-N cosine search) → JSON response → Browser
