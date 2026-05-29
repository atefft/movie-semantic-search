# Movie Semantic Search

A portfolio project demonstrating AI-powered semantic search over the CMU Movie Summary Corpus (~42,000 plot summaries). Built to showcase skills relevant to ML platform engineering: Triton Inference Server, Docker Compose orchestration, Java/Spring Boot API development, and cloud-scale ML serving patterns.

---

## Motivation

Traditional keyword search fails when vocabulary doesn't match. Searching for "films about loneliness in space" won't find *Cast Away* because the plot summary never uses those words. Semantic search solves this by converting both the corpus and the query into dense vectors that capture *meaning*, not just tokens. This project implements that pipeline end-to-end, from raw text to a live search API.

The target audience for this project is ML platform teams that care about:
- Serving ML models at scale with Triton Inference Server
- Containerized infrastructure with Docker
- Backend APIs in Java/Spring Boot
- Vector similarity search patterns

---

## Architecture

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the full multi-layer Mermaid diagram with clickable component nodes.

Each component has a dedicated detail page:

| Component | Detail Page |
|---|---|
| Python Pipeline (5 scripts) | [docs/arch/pipeline.md](docs/arch/pipeline.md) |
| Triton Inference Server | [docs/arch/triton.md](docs/arch/triton.md) |
| Qdrant Vector Database | [docs/arch/qdrant.md](docs/arch/qdrant.md) |
| Spring Boot API + UI | [docs/arch/api.md](docs/arch/api.md) |

**Quick view — two-phase system:**

```
OFFLINE (run once):
  CMU Corpus → Python Pipeline → Triton (batch embed 42k) → Qdrant (index) → TMDB (enrich posters)

ONLINE (per request):
  Browser → Spring Boot :8080 → Triton :8001 (embed query) → Qdrant :6333 (search) → JSON response
```

---

## The Two Phases

### Phase 1: Offline Pipeline

A one-time Python pipeline that prepares the search index. Steps 1–2 run before services start; step 3 (embedding) requires Triton to be running, so it runs after `docker compose up`. The pipeline does not need to run again unless the corpus changes.

Steps:
1. Download and parse the CMU Movie Summary Corpus (metadata + plot summaries)
2. Export `all-MiniLM-L6-v2` from sentence-transformers to ONNX format
3. Send each plot summary through Triton to get a 384-dimensional embedding vector
4. Ingest all vectors + movie metadata into Qdrant
5. Enrich Qdrant payloads with TMDB poster URLs

### Phase 2: Online Serving

Three Docker containers run continuously to serve search requests:

- **Triton** exposes the embedding model over gRPC
- **Qdrant** stores and searches the vector index
- **Spring Boot API** ties them together and serves the UI

At query time: the user's text is embedded by Triton, then Qdrant finds the nearest neighbors by cosine similarity, and the API returns the top results as JSON.

---

## The Four Black Boxes

### Triton Inference Server
NVIDIA's open-source model serving platform. Hosts the `all-MiniLM-L6-v2` model using the ONNX Runtime backend, which runs the exported `model.onnx` directly. Clients tokenize text on their side and send three tensors (`input_ids`, `attention_mask`, `token_type_ids`); Triton returns per-token contextual embeddings, which the client mean-pools into a single 384-dim sentence vector. Supports dynamic batching to efficiently handle bursts of requests.

**Why Triton instead of calling the model directly?**
It decouples model serving from application logic. The Java API hosts the tokenizer and pooling math, but the heavy transformer inference lives behind a well-defined gRPC contract — the model can be retrained, re-exported, or swapped without redeploying the API. This mirrors how ML platforms operate in production: models are deployed and versioned independently of the applications that use them.

### Qdrant
A vector database purpose-built for similarity search. Stores each movie as a vector (384 floats) plus a payload (title, year, genres, summary snippet). Given a query vector, returns the top-N closest movies by cosine similarity in milliseconds. Exposes REST and gRPC APIs and includes a web dashboard at port 6333.

### Java/Spring Boot API
The application layer. Receives HTTP requests from the browser, calls Triton to embed the query, calls Qdrant to find similar movies, and returns structured JSON. Built with Spring Boot 3 and Java 21.

### Python Pipeline
Offline scripts that build the index. Uses `sentence-transformers` to export the model, `onnxruntime` to verify inference locally, and `qdrant-client` to load vectors. Runs once; not part of the serving stack.

---

## The Key Insight: Same Model, Both Directions

The entire system depends on one property: **the same model must embed both the corpus and the query**.

When we index the corpus, we convert each plot summary to a vector. When the user searches, we convert their query to a vector using the same model. Cosine similarity then measures how "close" the query is to each movie in vector space.

This works because `all-MiniLM-L6-v2` was trained to place semantically similar text near each other. "Films about isolation and survival" and "A man is stranded alone on a deserted island" end up near each other in 384-dimensional space, even though they share no words.

If you used different models for indexing and querying, or re-exported the model differently, the vectors would not be comparable and the search would return garbage.

---

## Tech Stack

| Component | Technology | Rationale |
|---|---|---|
| Embedding model | `all-MiniLM-L6-v2` (ONNX) | 384-dim, fast CPU inference, well-known benchmark model, clean ONNX export via sentence-transformers |
| Model serving | Triton Inference Server + ONNX Runtime backend | Core requirement for target role; industry standard for ML serving; decouples model from app |
| Vector database | Qdrant (Docker) | Single container, built-in dashboard, REST + gRPC, official Java client, designed for ML workloads |
| Backend API | Java 21 + Spring Boot 3 | Target job posting preference; C#/.NET patterns transfer well; strong ecosystem |
| Frontend | Vanilla HTML/JS (served from Spring Boot) | Minimal surface area; not the portfolio signal; no build toolchain needed |
| Offline pipeline | Python 3.11 scripts | sentence-transformers ecosystem; standard for ML data prep; one-time job |
| Orchestration | Docker Compose | Single-command local setup for three services; clear dependency ordering |

---

## Design Decisions and Tradeoffs

### Qdrant vs pgvector

**pgvector** (PostgreSQL extension) is a valid alternative, especially for:
- Small datasets where SQL joins to relational data are valuable
- Teams already running PostgreSQL
- Simpler operational footprint (one less technology to learn)

**Qdrant** was chosen here because:
- The project narrative is ML platform engineering, where dedicated vector stores are the norm
- Qdrant's API and client design reflects how production ML search systems are built
- Better demonstrates familiarity with the ML infrastructure stack (vs. "we used Postgres")
- Built-in dashboard aids in exploring the index during development
- Designed to scale horizontally if the corpus grew (42k movies is small; 42M is not)

For a production system serving millions of users, a managed vector DB (Pinecone, Weaviate Cloud, Qdrant Cloud) or a purpose-built ANN index (FAISS, ScaNN) would be considered based on latency SLAs and cost.

### Triton ONNX Runtime Backend vs Python Backend

Triton supports multiple backends. The Python backend accepts raw text and runs custom pre/post-processing (tokenize → encode → pool → normalize) inside a `model.py` wrapper. The ONNX Runtime backend runs `model.onnx` directly with no wrapper — clients are responsible for tokenization and pooling. The ONNX Runtime backend is used here because:
- It keeps the model server thin — no custom Python code to maintain inside Triton
- The Java API and Python pipeline both need a tokenizer anyway, so there's no extra burden
- Native backends have lower per-request overhead than the Python backend

Tradeoff: clients must keep their tokenizer in lock-step with the exported `tokenizer.json`. Both the Java API and the Python pipeline load the same tokenizer files from the model repository to guarantee parity.

### CPU vs GPU

TensorRT (GPU optimization) is excluded because no NVIDIA GPU is available in the development environment. The ONNX Runtime CPU backend is used instead. `all-MiniLM-L6-v2` is fast enough on CPU for a portfolio demo (sub-100ms per embedding). A production system would use GPU inference with TensorRT for throughput at scale.

---

## Repository Structure

```
movie-semantic-search/
├── README.md
├── Makefile                        # Convenience targets: up, down, pipeline, clean
├── docker-compose.yml              # triton + qdrant + api + load-model + load-data services
├── .env.example                    # Environment variable template
├── .gitignore
│
├── docs/
│   ├── ARCHITECTURE.md             # Top-level component diagram and data flow
│   ├── API_CONTRACT.md             # REST endpoint specs, request/response shapes
│   ├── PIPELINE_SPEC.md            # Dataset details, pipeline steps, config options
│   └── arch/                       # Per-component detail pages (linked from ARCHITECTURE.md)
│       ├── pipeline.md
│       ├── triton.md
│       ├── qdrant.md
│       ├── api.md
│       └── operator.md
│
├── data/
│   ├── raw/                        # gitignored: CMU corpus TSV/TXT files
│   └── embeddings/                 # gitignored: embeddings.npy, metadata.json
│
├── model-repository/
│   └── all-minilm-l6-v2/
│       ├── config.pbtxt            # Triton model config (ONNX Runtime backend)
│       └── 1/
│           ├── model.onnx          # gitignored: generated by pipeline step 02
│           ├── tokenizer.json      # generated by pipeline step 02; loaded by clients
│           ├── tokenizer_config.json
│           ├── vocab.txt
│           └── special_tokens_map.json
│
├── pipeline/
│   ├── requirements.txt
│   ├── Dockerfile
│   ├── load-model.sh               # Entrypoint: export model to ONNX (Docker Compose service)
│   ├── load-data.sh                # Entrypoint: download → embed → ingest → enrich (Docker Compose service)
│   ├── 01_download_corpus.py       # Fetch and extract CMU dataset
│   ├── 02_export_model.py          # Export all-MiniLM-L6-v2 to ONNX + tokenizer files
│   ├── 03_embed_corpus.py          # Batch-embed all plot summaries via Triton
│   ├── 04_ingest_qdrant.py         # Create collection and load vectors + payloads
│   ├── 05_enrich_tmdb.py           # Add TMDB poster URLs to existing Qdrant payloads
│   └── tests/                      # pytest suite for the pipeline scripts
│
└── api/
    ├── pom.xml
    └── src/
        └── main/
            ├── java/com/moviesearch/
            │   ├── MovieSearchApplication.java
            │   ├── config/         # @ConfigurationProperties, gRPC/REST client beans, CORS
            │   ├── controller/     # SearchController, OperatorController
            │   ├── exception/      # Service-specific exceptions + GlobalExceptionHandler
            │   ├── model/          # Immutable Lombok @Value request/response DTOs
            │   └── service/        # Interfaces + impl/ (real and mock implementations)
            └── resources/
                ├── application.yml
                └── static/
                    ├── index.html      # Search UI
                    └── operator.html   # Pipeline operator UI
```

---

## Prerequisites

### To run the project
- **Docker Desktop** 4.x+ (with Compose v2)
- ~4 GB disk space for corpus + model + vectors

### To develop or run tests locally
- **Python 3.11+** with pip (pipeline tests)
- **Java 21+** (e.g., Eclipse Temurin) + **Maven 3.9+** (API tests)

---

## Quick Start

> **Port 8080 must be free** before starting. If something else is using it, set `API_PORT` to an available port (e.g. `API_PORT=8081`) when running the commands below and open that port in step 4 instead.

```bash
# 1. Export the ONNX model (Phase 1 — run once before starting services)
docker compose run --rm load-model

# 2. Start infrastructure (Triton + Qdrant + API)
docker compose up -d
# If port 8080 is taken:
# API_PORT=8081 docker compose up -d

# 3. Load the movie data (Phase 2 — run once after services are healthy)
docker compose run --rm load-data

# 4. Open the UI
open http://localhost:8080
```

Or with Make:

```bash
make pipeline   # run full pipeline: export model, start services, load data
make up         # start all services (skips pipeline)
make down       # stop all services
```

---

## REST API

See **[docs/API_CONTRACT.md](docs/API_CONTRACT.md)** for the full specification including all error responses and field types.

```
GET /api/search?q={query}&limit={n}       # full search, default limit 10, max 50
GET /api/autocomplete?q={partial}         # top 3 results for autocomplete dropdown
GET /actuator/health                      # Spring Boot health check

Response shape (both search endpoints):
{
  "query": "films about loneliness in space",
  "count": 10,
  "results": [
    {
      "title": "Cast Away",
      "year": 2000,
      "genres": ["Drama", "Adventure"],
      "score": 0.87,
      "summary_snippet": "A FedEx executive undergoes a physical and personal transformation...",
      "thumbnail_url": "https://image.tmdb.org/t/p/w200/uVlUu174iiKLBgcNnDOCFR8LNKP.jpg"
    }
  ]
}
```

---

## Data Flow (Online)

```
User query
  → GET /api/search?q=...
  → SearchController (Spring Boot)
  → SearchService
      → EmbeddingService: tokenize client-side, call Triton (gRPC :8001),
        receive token_embeddings, mean-pool → float32[384]
      → VectorSearchService: cosine similarity search against Qdrant (REST :6333)
        → top-N results
  → SearchResponse JSON → browser
```

---

## Dataset

Source: CMU Movie Summary Corpus
URL: `https://www.cs.cmu.edu/~ark/personas/data/MovieSummaries.tar.gz`

Relevant files:
- `movie.metadata.tsv` — Wikipedia movie ID, title, release date, genres, etc.
- `plot_summaries.txt` — Tab-separated: Wikipedia movie ID + full plot summary

Join key: `wikipedia_movie_id`

Qdrant collection: `movies`
- Dimensions: 384
- Distance: Cosine
- Payload per point: `movie_id`, `title`, `release_year`, `genres` (list), `summary_snippet` (first 300 chars of plot)
