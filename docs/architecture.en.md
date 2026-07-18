# Architecture & Services

Deep dive into every container and service of the Spectra stack. For the big picture, start with the [README](../README.md).

> **Where facts live** — this document is the *overview*: what each service does and how they
> fit together. Implementation-level facts (defaults, limits, endpoint-by-endpoint detail)
> live in the [Technical Reference](tech/technical-doc.fr.md), and the *design rationale* of
> the retrieval pipeline in [RAG pipeline internals](tech/rag-pipeline.fr.md). When numbers
> here and there disagree, the Technical Reference wins.

> All shell commands in this document are run from the repository root.

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                     Browser / Client                     │
└───────────────────────────┬──────────────────────────────┘
                            │ HTTP + SSE
┌───────────────────────────▼──────────────────────────────┐
│           Spring Boot API  :8080                         │
│  Java 25 · Virtual Threads · OpenAI-compatible REST      │
│                                                          │
│  ┌─────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  Ingestion  │  │    RAG     │  │   Fine-Tuning    │  │
│  │  Pipeline   │  │  Service   │  │    Service       │  │
│  └──────┬──────┘  └─────┬──────┘  └────────┬─────────┘  │
│         │               │                  │             │
└─────────┼───────────────┼──────────────────┼─────────────┘
          │               │                  │
    ┌─────▼───────┐  ┌────▼──────┐  ┌────▼──────┐  ┌───────▼────────┐
    │  ChromaDB   │  │ llm-chat  │  │ llm-embed │  │  train.sh      │
    │  :8000      │  │ :8081     │  │ :8082     │  │  (Unsloth)     │
    │  (vectors)  │  │ (chat)    │  │ (embed)   │  │                │
    └─────────────┘  └───────────┘  └───────────┘  └────────────────┘
          │
    ┌─────▼──────────────────────────────────────────┐
    │  Optional services (Docker profiles)           │
    │  docparser :8001  · reranker :8002             │
    │  browserless :3000                             │
    └────────────────────────────────────────────────┘
```

### Component list

| Service | Role | Always on |
|---|---|:---:|
| `model-init` | Checks GGUF model files exist before startup | ✅ |
| `spectra-api` | Spring Boot backend, all business logic | ✅ |
| `llm-chat` | llama.cpp — chat inference (port 8081) | ✅ |
| `llm-embed` | llama.cpp — embedding only (port 8082) | ✅ |
| `chromadb` | Vector database — API v2 | ✅ |
| `docparser` | Layout-aware PDF → Markdown | optional |
| `reranker` | Cross-Encoder re-ranking | optional |
| `browserless` | Headless Chrome for JS-rendered pages | optional |

---

## Services In Depth

### `spectra-api` — The Backend

The core of Spectra. A Spring Boot 4.1 application running on Java 25 with **virtual threads** (Project Loom) enabled. Every blocking I/O operation (embedding calls, ChromaDB queries, LLM generation, file reads) runs on a virtual thread, giving you thousands of concurrent operations without the overhead of a traditional thread pool.

**Key responsibilities:**
- Document ingestion pipeline (extraction → cleaning → chunking → embedding → indexing)
- RAG query pipeline (standard, hybrid, and agentic modes)
- Dataset generation and export
- Fine-tuning orchestration
- Model registry management
- Hardware profiling and llama-server auto-configuration
- REST API + SSE streaming

**Configuration:**
```yaml
spectra:
  llm:
    provider: llama-cpp
    chat:
      base-url: http://llm-chat:8081
    embedding:
      base-url: http://llm-embed:8082
    model: phi-4-mini
    embedding-model: nomic-embed-text
  pipeline:
    chunk-max-tokens: 512        # Max tokens per chunk
    chunk-overlap-tokens: 64     # Overlap between consecutive chunks
    embedding-batch-size: 10     # Embeddings computed in parallel
    embedding-timeout-seconds: 30
    concurrent-ingestions: 4     # Max parallel ingestion tasks
```

**Environment variables:**
```bash
SPECTRA_LLM_PROVIDER=llama-cpp
SPECTRA_LLM_CHAT_BASE_URL=http://llm-chat:8081
SPECTRA_LLM_EMBEDDING_BASE_URL=http://llm-embed:8082
SPECTRA_LLM_MODEL=phi-4-mini
SPECTRA_LLM_EMBEDDING_MODEL=nomic-embed-text
SPECTRA_CHUNK_MAX_TOKENS=512
SPECTRA_EMBEDDING_BATCH_SIZE=10
SPECTRA_CONCURRENT_INGESTIONS=4
```

---

### `llm-chat` and `llm-embed` — Inference Engines

Two separate **llama.cpp** containers (`ghcr.io/ggml-org/llama.cpp:server`), each dedicated to one task:

| Service | Port | Model | Role |
|---|---|---|---|
| `llm-chat` | 8081 | `LLM_CHAT_MODEL_FILE` | `/v1/chat/completions` |
| `llm-embed` | 8082 | `LLM_EMBED_MODEL_FILE` | `/v1/embeddings` |

**Why two containers?** A chat model (instruction-tuned, large context) and an embedding model (small, optimised for vector similarity) have incompatible architectures. Running them as separate processes avoids contention and allows independent scaling.

**Configuration:**
```bash
LLM_CHAT_MODEL_FILE=Phi-4-mini-reasoning-UD-IQ1_S.gguf   # chat GGUF in data/models/
LLM_CHAT_MODEL_NAME=phi-4-mini
LLM_EMBED_MODEL_FILE=embed.gguf                           # embedding GGUF in data/models/
LLM_EMBED_MODEL_NAME=nomic-embed-text
LLM_PARALLEL=2                                            # parallel slots per server
```

**Embedded runtime mode:** Spectra can also launch and manage a local `llama-server` process directly (without Docker), configured in `application.yml`:
```yaml
spectra:
  llm:
    provider: llama-cpp
    runtime:
      enabled: true
      executable: llama-server   # path to llama-server binary
      port: 8081
      context-size: 4096
      threads: 8
      parallelism: 2
```
In this mode, the `ResourceAdvisorService` detects your hardware and fills in optimal parameters automatically.

---

### `chromadb` — Vector Database

**ChromaDB** stores document embeddings (dense float vectors) and supports approximate nearest-neighbor search. Every chunk ingested into Spectra gets embedded and stored here.

When you ask a question, Spectra:
1. Embeds your question using the same model used during ingestion
2. Queries ChromaDB for the `top_k` most similar chunks
3. Passes those chunks as context to the LLM

**Configuration:**
```bash
CHROMADB_URL=http://chromadb:8000
```

Data is persisted in a named Docker volume (`chromadb-data`) and survives container restarts.

---

### `FtsService` + `BM25Index` — Full-Text Search

The in-memory BM25 (Okapi BM25) index runs inside the JVM alongside the API. It's populated during ingestion in parallel with the ChromaDB vector index.

**How BM25 differs from vector search:**
- BM25 is term-frequency based: it scores documents by how often query terms appear relative to document length and corpus frequency
- It's deterministic, fast, and very good at exact matches
- It's weak on synonyms and paraphrases (a document about "vehicles" won't rank for "cars" unless the word appears)

**How they're combined (Hybrid Search):**
Both indexes produce a ranked list. Spectra merges them using **Reciprocal Rank Fusion**:

```
RRF_score(doc) = Σ  1 / (k + rank_i(doc))
                 i
```

Where `k=60` is a smoothing constant. This formula rewards documents that rank well in multiple lists without requiring score normalization between the two systems.

**Configuration:**
```bash
SPECTRA_HYBRID_SEARCH_ENABLED=true   # Enable hybrid mode (default: false)
SPECTRA_HYBRID_BM25_TOP=20           # Candidates fetched from BM25 before fusion
SPECTRA_HYBRID_BM25_WEIGHT=1.0       # Weight multiplier for BM25 scores
```

**Status endpoint:**
```bash
GET /api/status/fts                  # Aggregated BM25 index stats
GET /api/status/fts?collection=name  # Per-collection stats
```

---

### `docparser` — Layout-Aware PDF Service

An optional Python FastAPI microservice (Docker profile: `layout-parser`) that converts PDF files to structured Markdown while preserving document layout.

**Why this matters:** Standard PDF text extraction concatenates text streams in drawing order, which mangles multi-column layouts and tables. The docparser service uses page rendering + spatial analysis to reconstruct logical reading order.

**Two parsing backends:**
- **PyMuPDF4LLM** (default): fast, lightweight, no GPU required, excellent for most business documents
- **Docling** (IBM Research): higher accuracy on complex layouts (scientific papers, financial reports), enabled via `USE_DOCLING=true`

**Activation:**
```bash
SPECTRA_LAYOUT_PARSER_ENABLED=true           # Enable in API
SPECTRA_LAYOUT_PARSER_URL=http://docparser:8001
SPECTRA_LAYOUT_PARSER_TIMEOUT=120            # Seconds per document
SPECTRA_LAYOUT_PARSER_BUFFER_MB=100          # Max upload size (MB)
USE_DOCLING=false                            # Switch to Docling backend
```

Start with the profile:
```bash
docker compose --profile layout-parser up -d
```

---

### `reranker` — Cross-Encoder Re-ranking

An optional Python FastAPI microservice (Docker profile: `reranker`) that re-scores retrieval candidates using a **Cross-Encoder** model.

**The two-stage retrieval problem:**
Vector search and BM25 both use *bi-encoder* architectures — the query and document are embedded independently, then compared by dot product. This is fast (no joint computation) but less accurate.

A Cross-Encoder reads the query and a candidate document *together* as a single sequence, producing a much more accurate relevance score. The trade-off is speed: you can't index with a Cross-Encoder, only re-score.

Spectra solves this with a two-stage approach:
1. **Stage 1** (fast): Vector + BM25 retrieves the top-20 candidates
2. **Stage 2** (accurate): Cross-Encoder re-scores those 20 and returns the top-5

The result is near Cross-Encoder accuracy at near bi-encoder speed.

**Model:** `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` (multilingual, works in French, English, and 25+ languages)

**Configuration:**
```bash
SPECTRA_RERANKER_ENABLED=true
SPECTRA_RERANKER_URL=http://reranker:8000
SPECTRA_RERANKER_TOP_CANDIDATES=20     # Candidates fed to the re-ranker
RERANKER_MODEL=cross-encoder/mmarco-mMiniLMv2-L12-H384-v1
```

Start with the profile:
```bash
docker compose --profile reranker up -d
```

---

### Agentic RAG — ReAct Loop

When enabled, the standard "retrieve once, generate once" pattern is replaced by an iterative reasoning loop. The LLM operates as an agent following the **ReAct** (Reasoning + Acting) framework:

```
THOUGHT: I need to find information about X
ACTION: SEARCH
QUERY: specific search query

[Spectra performs the search, adds results to context]

THOUGHT: I now have enough context to answer
ACTION: ANSWER
RESPONSE: Final answer to the user's question
```

The loop continues until the LLM emits `ACTION: ANSWER` or reaches `max-iterations`. This enables multi-hop reasoning: the model can reformulate its query based on what it found in the previous iteration.

**Configuration:**
```bash
SPECTRA_AGENTIC_RAG_ENABLED=true
SPECTRA_AGENTIC_MAX_ITERATIONS=3      # Max search rounds
SPECTRA_AGENTIC_INITIAL_TOP_K=5       # Chunks retrieved per iteration
SPECTRA_AGENTIC_LANGUAGE=fr           # Response language: fr, en, auto
SPECTRA_AGENTIC_MAX_CONTEXT_TOKENS=3000
```

---

### Ingestion Pipeline

Documents enter Spectra through several channels:

| Source | Endpoint | Supported formats |
|---|---|---|
| File upload | `POST /api/ingest` | PDF, DOCX, DOC, HTML, Markdown, CSV, JSON, XML, Avro, TXT — [full reference table](tech/technical-doc.fr.md#31-extraction) |
| ZIP archive | `POST /api/ingest` | Any combination of the above (recursive, ZIP-bomb-guarded) |
| Remote URL | `POST /api/ingest/url` | HTML pages, PDF, TXT |
| Kafka stream | consumer (opt-in) | See the streaming section below |

**The ingestion pipeline for each document:**

1. **SHA-256 deduplication** — the file's content hash is checked against the GED: an already-ingested document is skipped. `?force=true` **replaces** it instead (the previous chunks are purged from both indexes before reindexing — no duplicates)
2. **Extraction** — format-specific extractor (PDF via PDFBox or docparser, DOCX via Apache POI, HTML via Jsoup, etc.)
3. **Cleaning** — multi-pass normalization: Unicode NFC, OCR ligature replacement, page markers, headers/footers, table borders, bullet point normalization, whitespace compression
4. **Chunking** — sliding window by token count (default: 512 tokens, 64-token overlap; sentence boundaries follow `SPECTRA_CHUNK_LOCALE`). The overlap ensures that sentences crossing chunk boundaries aren't split in half for the embedder
5. **Embedding** — batched calls to the embedding model (default batch: 10 chunks)
6. **Vector indexing** — chunks + embeddings stored in ChromaDB; each chunk carries its document's `sha256` as metadata, so deletion/replacement targets content identity (two same-named documents no longer share their fate)
7. **BM25 indexing** — chunks are **always** added to the in-memory BM25 index (hybrid search only decides whether it is *queried* at retrieval time)
8. **GED record** — the document sheet is created (hash, quality score, lifecycle), with per-file errors surfaced in the task status (`fileErrors`; a task where every file failed ends `FAILED`)

**URL ingestion specifics:**
- A `HEAD` request first determines the content type
- HTML pages are rendered by **Browserless** (headless Chrome) to handle JavaScript-rendered content
- Binary files (PDF, TXT) are downloaded directly
- If Browserless is unavailable, direct download is used as a fallback

---

### Kafka Streaming Ingestion — Living Data, Fed to RAG On the Fly

Beyond one-shot uploads, Spectra can **continuously enrich the RAG index from a Kafka
cluster**. Each message is *upserted*: the model's effective knowledge stays current in
seconds, without any retraining.

**Disabled by default** — no Kafka bean is created and startup is unchanged unless you set
`SPECTRA_KAFKA_ENABLED=true`.

**How it works:**
- The **message key** becomes a stable business identity
  (`sourceFile = kafka://<topic>/<key>`). A new version of the same entity reuses that key,
  so Spectra **deletes the previous version** (from both the vector index and BM25) and
  reindexes the current one — a true upsert. Without a key, it falls back to
  `kafka://<topic>/<partition>-<offset>` (append-only).
- A **null value** (Kafka log-compaction tombstone) **deletes** the entry.
- **Idempotent**: an unchanged payload is a no-op (absorbs at-least-once redeliveries).
- **Raw payload by default**: the message value is passed as-is to the extractor, routed by
  `SPECTRA_KAFKA_FORMAT` (`json`, `txt`, `xml`, `avro`). Optionally set
  `SPECTRA_KAFKA_CONTENT_FIELD` (field name or JSON pointer) to index only one field of a
  structured event, and `SPECTRA_KAFKA_METADATA_FIELDS` to copy chosen fields into chunk
  metadata (for retrieval-time filtering).
- **Freshness**: every chunk carries `ingestedAt` and `eventTime` (Kafka record timestamp)
  metadata, available for recency filtering/weighting at retrieval.
- **Metrics**: Micrometer counter `spectra.kafka.messages{topic,result}` (result =
  upserted/unchanged/deleted/failed) and timer `spectra.kafka.processing{topic}`, exposed on
  `/actuator/prometheus`.
- **At-least-once**: offsets are committed only after successful indexing; a poison message
  is retried then routed to a **Dead Letter Topic** `<topic>.DLT` instead of blocking the
  partition.
- **Retention**: a nightly job purges sources not updated for
  `SPECTRA_KAFKA_RETENTION_TTL_DAYS` days (0 = disabled) so a continuous stream doesn't grow
  the index unbounded.

A **dedicated collection** (`spectra_stream`) isolates living data from the static document
corpus.

**Start the optional broker (Docker profile `kafka`, single-node KRaft):**
```bash
SPECTRA_KAFKA_ENABLED=true SPECTRA_KAFKA_TOPICS=my-topic \
  docker compose --profile kafka up -d
# Produce test messages from the host on localhost:29092; spectra-api reads kafka:9092
```

**Configuration:**

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_KAFKA_ENABLED` | `false` | Enable Kafka streaming ingestion |
| `SPECTRA_KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Broker list (host:port,…) |
| `SPECTRA_KAFKA_TOPICS` | *(empty)* | Topics to consume (comma-separated) |
| `SPECTRA_KAFKA_GROUP_ID` | `spectra-ingestion` | Consumer group id |
| `SPECTRA_KAFKA_COLLECTION` | `spectra_stream` | Dedicated ChromaDB collection |
| `SPECTRA_KAFKA_FORMAT` | `json` | Extractor routing: `json`/`txt`/`xml`/`avro` |
| `SPECTRA_KAFKA_CONTENT_FIELD` | *(empty)* | JSON field/pointer to index (empty = raw payload) |
| `SPECTRA_KAFKA_METADATA_FIELDS` | *(empty)* | JSON fields copied into chunk metadata (comma-separated) |
| `SPECTRA_KAFKA_CONCURRENCY` | `1` | Concurrent consumers (~partitions in parallel) |
| `SPECTRA_KAFKA_MAX_POLL_RECORDS` | `20` | Max records per poll (embedding is the bottleneck) |
| `SPECTRA_KAFKA_RETENTION_TTL_DAYS` | `0` | Purge stale sources after N days (0 = disabled) |
| `SPECTRA_KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | `PLAINTEXT`/`SSL`/`SASL_SSL`/`SASL_PLAINTEXT` |
| `SPECTRA_KAFKA_SASL_MECHANISM` | *(empty)* | e.g. `SCRAM-SHA-512`, `PLAIN` (if SASL) |
| `SPECTRA_KAFKA_SASL_JAAS_CONFIG` | *(empty)* | Full JAAS config (holds credentials) |

See **[design-kafka-streaming-upsert.fr.md](tech/design-kafka-streaming-upsert.fr.md)**
for the detailed design (upsert algorithm, tombstones, idempotency, performance notes).

---

### `TextCleanerService` — 8-Step Text Normalization

Raw extracted text is noisy. The cleaner applies these steps in order:

| Step | What it removes/fixes |
|---|---|
| 1. Unicode NFC | Normalizes composed vs. decomposed characters |
| 2. OCR ligatures | Converts ﬀ ﬁ ﬂ → ff fi fl |
| 3. Page markers | Lines like `- 47 -` or `Page 3` alone on a line |
| 4. Headers/footers | Recurring `Confidentiel`, `© Company`, `Page X/Y` patterns |
| 5. Table borders | Pipe characters from markdown-style tables → spaces |
| 6. Bullet normalization | `•`, `●`, `■`, `▸` → `-` |
| 7. Whitespace | Multiple consecutive spaces → single space |
| 8. Blank lines | 3+ consecutive newlines → 2 newlines |

---

### Dataset Generation

Once documents are indexed, Spectra can automatically generate a fine-tuning dataset from your corpus. The LLM reads each chunk and produces:

- **Q&A pairs**: question + answer grounded in the chunk
- **Summaries**: condensed version of each chunk
- **DPO pairs** (Direct Preference Optimization): chosen vs. rejected responses

This synthetic dataset is exported as JSONL in the format expected by Unsloth.

**Endpoint:** `POST /api/dataset/generate`

---

### GED — Document Lifecycle Management

Every document ingested into Spectra gets a full lifecycle record managed by the **GED** (Gestion Électronique de Documents) module. This enables traceability from raw ingestion to fine-tuning and beyond.

**Lifecycle state machine:**

```
INGESTED ──► QUALIFIED ──► TRAINED
    │▲           │▲           │
    ││           │└───────────┘   (TRAINED → QUALIFIED)
    ││           └──────► ARCHIVED ◄──── (also from INGESTED and TRAINED)
    │└─────────────────────┘   (unarchive: ARCHIVED → INGESTED)
    └──► ARCHIVED
```

Allowed transitions: `INGESTED → QUALIFIED | ARCHIVED` · `QUALIFIED → TRAINED | INGESTED | ARCHIVED` · `TRAINED → ARCHIVED | QUALIFIED` · `ARCHIVED → INGESTED`. Anything else is rejected (state machine), and each transition is recorded in the audit trail. Re-ingestion (`force=true`) bumps the version and resets the document to `INGESTED`. When a fine-tuning job completes, the documents whose dataset pairs fed the training are **automatically** linked `TRAINED_ON` and advanced to `TRAINED` (through `QUALIFIED` if needed; `ARCHIVED` documents are linked but not resurrected).

**Core features:**

| Feature | Description |
|---|---|
| **Audit trail** | Every action (ingest, qualify, tag, archive, delete) is logged with actor and timestamp |
| **Tags** | Free-form thematic labels — add, remove, bulk-assign |
| **Model links** | Associate a document to a model as `TRAINED_ON` or `EVALUATED_ON` |
| **Quality score** | 0–1 score assigned at ingestion (chunk count + format bonus, max 1.0); drives auto-qualification |
| **Auto-qualification** | If `autoQualifyThreshold > 0`, documents scoring above it are auto-promoted to `QUALIFIED` at ingestion |
| **Retention policies** | Nightly cron: auto-archive INGESTED after N days, auto-purge ARCHIVED M days after their **archival date** (`archivedAt`) — the purge removes the DB record *and* the indexed chunks |
| **Synchronized deletion** | Deleting a document removes it from the GED (H2), ChromaDB and the BM25 index in one call, targeting the chunk `sha256` identity; `DELETE /api/documents/{sourceFile}` follows the same path |
| **Statistics** | Lifecycle distribution, quality histogram, top tags, total indexed chunks |
| **Article commenting** | Human and AI-generated comments per document; rated comments export as DPO training pairs |

**API endpoints:**

```
GET    /api/ged/documents                              # Paginated list with filters
GET    /api/ged/documents/{sha256}                     # Full document sheet + model links + audit
DELETE /api/ged/documents/{sha256}                     # Delete from GED + ChromaDB
PUT    /api/ged/documents/{sha256}/lifecycle           # Transition lifecycle
POST   /api/ged/documents/{sha256}/tags                # Add tags
DELETE /api/ged/documents/{sha256}/tags                # Remove tags
POST   /api/ged/documents/{sha256}/models              # Link to a model
GET    /api/ged/models/{modelName}/documents           # Documents linked to a model
GET    /api/ged/documents/{sha256}/audit               # Audit trail
GET    /api/ged/stats                                  # Aggregate statistics
POST   /api/ged/documents/bulk/lifecycle               # Bulk lifecycle transition
POST   /api/ged/documents/bulk/tags                    # Bulk tag assignment

# Article commenting
GET    /api/ged/documents/{sha256}/comments            # List comments for a document
POST   /api/ged/documents/{sha256}/comments            # Add human comment or generate AI comment via RAG
PATCH  /api/ged/documents/{sha256}/comments/{id}/rating  # Rate an AI comment (APPROVED / REJECTED)
DELETE /api/ged/documents/{sha256}/comments/{id}       # Delete a comment
POST   /api/ged/documents/export/comments-dpo          # Export rated comments as DPO JSONL
```

**Filtering the document list:**

```bash
# All QUALIFIED documents with tag "contrat", quality ≥ 0.7, page 2
GET /api/ged/documents?lifecycle=QUALIFIED&tag=contrat&minQuality=0.7&page=2&size=20
```

Available filters: `lifecycle`, `tag`, `collection`, `minQuality`, `from` (ISO-8601), `to` (ISO-8601), `q` (server-side file-name search).

**Configuration:**

```bash
SPECTRA_GED_ARCHIVE_DIR=./data/archive           # Where archive manifests are written
SPECTRA_GED_AUTO_QUALIFY_THRESHOLD=0.75          # 0.0 = disabled; 0.0 < x ≤ 1.0 = auto-qualify
SPECTRA_GED_ARCHIVE_AFTER_DAYS=90                # 0 = disabled; archive INGESTED docs after N days
SPECTRA_GED_PURGE_AFTER_DAYS=365                 # 0 = disabled; purge ARCHIVED docs after N days
```

---

### Article Commenting — RAG Generation + DPO Feedback Loop

Every GED document supports a comment thread. Comments can be written by a human analyst or **generated automatically by the LLM**, grounded by the document's own indexed chunks via RAG.

**How AI-generated comments work:**

```
User provides a focus angle (optional)
           ↓
  RAG: retrieve top-6 chunks from the document's collection
           ↓
  LLM generates an analytical comment (temperature 0.4 — factual mode)
           ↓
  User rates the comment:  👍 APPROVED  /  👎 REJECTED
           ↓
  countByCommentTypeAndRating(AI_GENERATED, APPROVED)
           ↓ (when approvedCount % threshold == 0)
  ↺ Auto-trigger: export DPO pairs → submit fine-tuning job automatically
           ↓ (or manually)
  POST /export/comments-dpo  →  comments_dpo.jsonl
           ↓
  Fine-tune on the rated pairs (next training cycle)
```

**Jaccard similarity guard on DPO pairs:**

Before accepting a `(chosen, rejected)` pair, Spectra computes the Jaccard similarity of the two responses' word sets:

```
J(A, B) = |A ∩ B| / |A ∪ B|
```

If `J > 0.85`, the pair is rejected and a warning is logged — a pair where chosen and rejected are nearly identical provides no useful training signal.

**Model registry ↔ llama-server sync:**

After every `setActiveModel()` call, an async health check (via `CompletableFuture.runAsync`) verifies that llama-server is actually serving the newly activated model. A `WARN` is logged if the registry and server are out of sync.

**Why this combination is optimal:**

| Approach | What it adds |
|---|---|
| RAG alone | Comment is grounded in the actual document chunks — no hallucination |
| Fine-tuning alone | Model learns domain style and terminology — but may invent details |
| RAG + DPO fine-tuning | RAG grounds each comment; DPO trains the model to prefer the kind of comments **you** approve — quality compounds over cycles |

**Adding a comment via the API:**

```bash
# Human comment
curl -X POST http://localhost:8080/api/ged/documents/{sha256}/comments \
  -H 'Content-Type: application/json' \
  -d '{"content": "This document covers section R23 of the operational protocol.", "generate": false}'

# AI-generated comment (RAG + LLM)
curl -X POST http://localhost:8080/api/ged/documents/{sha256}/comments \
  -H 'Content-Type: application/json' \
  -d '{"content": "safety procedures and emergency contacts", "generate": true}'
# "content" is used as the focus/retrieval query when generate=true

# Rate an AI comment
curl -X PATCH "http://localhost:8080/api/ged/documents/{sha256}/comments/42/rating?rating=APPROVED"

# Export as DPO pairs
curl -X POST http://localhost:8080/api/ged/documents/export/comments-dpo
# → {"pairs": 18, "file": "./data/dataset/comments_dpo.jsonl", "exportedAt": "..."}
```

The exported JSONL file uses the same `{"prompt","chosen","rejected","source","exportedAt"}` schema as the existing DPO dataset, so it is **directly compatible** with the `DPOTrainer` fine-tuning pipeline.

---

### `EvaluationService` — LLM-as-a-Judge & multi-model comparison

After dataset generation, you can evaluate model quality automatically. Spectra samples 5% of the dataset (min 5, max 50 pairs), loads the target model (switching the active model for the run, then restoring it), and scores each response from 1 to 10 — also recording generation **latency** and **estimated throughput** (tokens/s). Scores are aggregated by category (`qa`, `summary`, `classification`, `negative`), giving a quantitative baseline before and after fine-tuning.

**Compare your custom models against each other:**

- **Batch-evaluate** several models on the *same* shared test set (`POST /api/evaluation/batch`) — apples-to-apples.
- **Compare** completed runs (`GET /api/evaluation/compare`): per-category deltas vs a movable baseline, an overlaid radar, latency/throughput, document attribution (GED `TRAINED_ON` / `EVALUATED_ON`), and each delta flagged `sig`/`ns` via a 95% confidence interval.
- **A/B head-to-head** (`POST /api/evaluation/ab`): a judge picks the better of two answers per pair, with randomized order to cancel position bias → win rates, more robust than comparing absolute means.
- **Neutral judge** (`SPECTRA_EVALUATION_JUDGE_MODEL`): a fixed third model scores everyone impartially (two-phase evaluation — generate, then judge).

**Endpoints:** `POST /api/evaluation` · `POST /api/evaluation/batch` · `GET /api/evaluation/compare` · `POST /api/evaluation/ab`

---

### `RagAblationService` — A/B Ablation (measure the gain of every option)

Where `EvaluationService` scores the **raw model**, the ablation runs each benchmark question through the **full RAG pipeline** and compares several configurations (*arms*) on the same held-out set. The delta between two arms is the **marginal gain** of one option — read against its cost.

Each arm reports three families of metrics:

| Family | Metrics |
|---|---|
| **Generation** | exactitude (LLM-judge /10), hallucination rate, refusal accuracy |
| **Retrieval** (deterministic) | Hit@k, MRR, Recall@k — computed from returned sources vs. the benchmark's `expectedSources` |
| **Cost** | context tokens (deterministic) + p50/avg latency |

- **Per-module ablation**: each arm can force any RAG module on/off via `overrides` (tri-state) — `rerank`, `hybrid`, `multiQuery`, `corrective`, `compression`, `selfRag`, `adaptive`, `conversational`. `appliedCounts` confirms a module actually fired.

### Algorithm Trace (Playground)

To demystify the retrieval pipeline, the Playground features a **Trace** mode. For every generated answer, you can click "Trace" to inspect exactly how the AI reached its conclusion:
- Which **RAG strategy** was used (e.g., Agentic loop, Standard, Direct).
- How many iterations the agentic loop took.
- Which **optimizations** were triggered (Hybrid Search, Multi-Query, Context Compression, etc.).
- The **exact source passages** that were fed into the prompt after filtering and reranking.
- **Two axes**: RAG gain (`useRag` false vs. true) and fine-tuning gain (`model` base vs. fine-tuned).
- **Confidence**: set `runs` (1–10) to repeat each arm → mean ± std per metric; non-significant deltas (≤ combined σ) are flagged.

```bash
# Default matrix: LLM-only vs RAG on the active model
curl -X POST http://localhost:8080/api/ablation

# Cumulative ablation with 3 repetitions for confidence
curl -X POST http://localhost:8080/api/ablation -H 'Content-Type: application/json' -d '{
  "runs": 3,
  "arms": [
    {"label": "vector-only", "useRag": true, "overrides": {"hybrid": false, "rerank": false}},
    {"label": "+ hybrid",    "useRag": true, "overrides": {"hybrid": true,  "rerank": false}},
    {"label": "+ rerank",    "useRag": true, "overrides": {"hybrid": true,  "rerank": true}}
  ]
}'
```

The annotated benchmark `highway_benchmark.jsonl` + aligned corpus `examples/highway/` activate the retrieval metrics out of the box (ingest the corpus, see `examples/README.md`).

**Endpoint:** `POST /api/ablation` · **UI:** the **Optimization** screen (pedagogical option cards, presets, colored delta table with ±σ, cost/quality & marginal-gain charts, CSV export)

---

### `BenchmarkService` — Performance Measurement

Three built-in benchmarks to measure and compare configurations:

| Benchmark | What it measures |
|---|---|
| `rag` | End-to-end latency: embed + search + generate |
| `embedding` | Throughput: tokens/sec for a fixed 512-token input |
| `llm` | Pure generation latency, no retrieval |

Use these to compare quantizations (Q4_K_M vs. IQ3_M) or hardware configurations.

**Endpoint:** `POST /api/benchmark/run?type=rag`

---

### `ResourceAdvisorService` — Hardware Auto-Detection

Runs at startup, detects your hardware, and computes optimal `llama-server` parameters:

**Detection sources (in priority order):**
1. **cgroups v2** — container-aware CPU and memory quotas
2. **`/proc/meminfo`** + `Runtime.availableProcessors()` — host-level values
3. **`nvidia-smi`** — NVIDIA GPU type and VRAM
4. **`/dev/kfd`** — AMD ROCm GPU
5. **`/dev/dri/renderD128`** — Vulkan-compatible GPU

**What it computes:**
- Thread count (based on CPU cores)
- Context size (based on available RAM)
- GPU layer offload count (based on VRAM)
- KV cache type (`f16` or `q8_0`)
- Flash attention eligibility
- Separate profiles for chat and embedding models

**Endpoint:** `GET /api/config/resources`

---

### Batch Mode

Fully automated overnight pipeline. Point Spectra at a directory, set `spectra.batch.enabled=true`, and it will:

1. Ingest all supported files in `spectra.batch.source-dir`
2. Generate a training dataset from the indexed corpus
3. Submit a fine-tuning job
4. Export the resulting GGUF model

```yaml
spectra:
  batch:
    enabled: true
    source-dir: ./data/source
    model-name: my-domain-model
```

---

### Model Hub

The Model Hub UI and API allow you to:

- Browse recommended GGUF models from HuggingFace
- Download models directly to `data/models/`
- Hot-swap the active model without restarting the stack
- Manage the model registry (aliases, metadata)

The registry is a JSON file at `data/models/registry.json`. It maps logical model names (e.g., `mistral-7b`) to physical GGUF filenames.

---

## Technology Stack

| Layer | Technology | Why |
|---|---|---|
| **Backend** | Java 25 + Spring Boot 4.1 | Virtual threads, mature ecosystem, strong typing |
| **Frontend** | React 19 + Vite + Tailwind CSS v4 + Recharts | Fast builds, component model, utility CSS, rich visualizations |
| **Inference** | llama.cpp (GGUF) | CPU+GPU, quantization support, OpenAI-compatible |
| **Vector DB** | ChromaDB | Embedded or standalone, simple HTTP API |
| **Full-text** | BM25Okapi (custom Java) | No external dependency, same JVM, thread-safe |
| **Reranker** | sentence-transformers (Python) | Best-in-class Cross-Encoders, multilingual |
| **PDF parsing** | PyMuPDF4LLM / Docling | Layout-aware, Markdown output |
| **HTML rendering** | Browserless (headless Chrome) | Handles JS-rendered pages |
| **Fine-tuning** | Unsloth + PEFT (QLoRA) | 2× faster than standard PEFT, low VRAM |
| **Persistence** | H2 (embedded SQL) | Zero-dependency, task history |
| **Testing** | JUnit 5 + AssertJ + Mockito | 270 tests, full pipeline coverage |

---
