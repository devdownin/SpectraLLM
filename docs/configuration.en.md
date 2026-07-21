# Configuration & Observability

Every setting has a working default — see the essentials note at the top of [.env.example](../.env.example). This reference covers **all** overrides, grouped by concern. The authoritative wiring is [`backend/src/main/resources/application.yml`](../backend/src/main/resources/application.yml) (backend settings) and [`deploy/docker/docker-compose.yml`](../deploy/docker/docker-compose.yml) (container-level settings).

> All shell commands in this document are run from the repository root.

## Configuration Reference

### Core inference

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_LLM_PROVIDER` | `llama-cpp` | Inference provider (`llama-cpp`) |
| `SPECTRA_LLM_BASE_URL` | `http://llm-chat:8081` | Legacy base URL (chat/embedding URLs below take precedence) |
| `SPECTRA_LLM_CHAT_BASE_URL` | `http://llm-chat:8081` | Chat server URL |
| `SPECTRA_LLM_EMBEDDING_BASE_URL` | `http://llm-embed:8082` | Embedding server URL |
| `SPECTRA_LLM_MODEL` | `phi-4-mini` | Model alias for chat |
| `SPECTRA_LLM_EMBEDDING_MODEL` | `nomic-embed-text` | Model alias for embeddings |
| `SPECTRA_LLM_CHAT_FILE` | *(empty)* | GGUF file served by llm-chat (relative to the shared models dir); seeds the local registry |
| `SPECTRA_LLM_EMBEDDING_FILE` | *(empty)* | GGUF file served by llm-embed (same shared volume) |
| `SPECTRA_GENERATION_TIMEOUT` | `120` | Generation timeout (seconds) |
| `SPECTRA_LLM_SWITCH_TIMEOUT` | `300` | Max wait (seconds) for the server to actually serve a newly activated model before an evaluation/benchmark starts measuring — must cover loading the heaviest GGUF |
| `SPECTRA_LLM_SWITCH_POLL_MS` | `2000` | Poll interval (ms) while waiting for that model-switch convergence |
| `CHROMADB_URL` | `http://chromadb:8000` | ChromaDB base URL |

**Container-level (docker-compose):**

| Environment variable | Default | Description |
|---|---|---|
| `LLM_CHAT_MODEL_FILE` | `Phi-4-mini-reasoning-UD-IQ1_S.gguf` | Chat GGUF filename in `data/models/` |
| `LLM_EMBED_MODEL_FILE` | `embed.gguf` | Embedding GGUF filename in `data/models/` |
| `LLM_PARALLEL` | `2` | Parallel inference slots per server |
| `LLAMA_CPP_IMAGE_TAG` | `server-b9828` | Pinned `ghcr.io/ggml-org/llama.cpp` image tag for llm-chat/llm-embed (the floating `server` tag tracks llama.cpp master and can break without warning) |

### Ingestion pipeline

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_CHUNK_MAX_TOKENS` | `512` | Max tokens per chunk |
| `SPECTRA_CHUNK_OVERLAP_TOKENS` | `64` | Token overlap between chunks |
| `SPECTRA_CHUNK_LOCALE` | `fr` | BCP 47 tag for sentence-boundary detection during chunking (`fr`, `en`, `de`…). Does **not** change tokenization |
| `SPECTRA_EMBEDDING_BATCH_SIZE` | `32` | Chunks embedded per HTTP batch (500 chunks = 16 requests instead of 50 with the old default of 10). Lower it on very slow CPUs |
| `SPECTRA_EMBEDDING_TIMEOUT` | `60` | Timeout (seconds) of each `/v1/embeddings` request — must cover a full batch on slow hardware |
| `SPECTRA_CONCURRENT_INGESTIONS` | `1` | Parallel ingestion workers (upload, URL and batch paths all share this semaphore) |
| `SPECTRA_MAX_ACTIVE_INGESTIONS` | `0` | Cap on active ingestion tasks (PENDING/PROCESSING). Beyond it, a new submission is rejected with **HTTP 429** before any temp write — backpressure against a flood of submissions piling up temp files and registry entries. `0` = unlimited |
| `SPECTRA_MAX_UNCOMPRESSED_MB` | `0` | Max uncompressed size per file / ZIP entry (MB). `0` = auto-computed from JVM heap and concurrency (OOM guard) |
| `SPECTRA_INGESTION_MAX_ZIP_ENTRIES` | `10000` | Max entries processed per ZIP archive (ZIP-bomb guard) |
| `SPECTRA_INGESTION_MAX_ENTRY_BYTES` | `52428800` | Legacy per-entry byte cap (50 MB) |
| `SPECTRA_INGESTION_BROWSERLESS_URL` | `http://browserless:3000` | Browserless (headless Chrome) URL for JavaScript-rendered HTML pages |

### Kafka streaming ingestion

Disabled by default — no Kafka bean is created unless `SPECTRA_KAFKA_ENABLED=true`. See the [design note (FR)](tech/design-kafka-streaming-upsert.fr.md).

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_KAFKA_ENABLED` | `false` | Enable the streaming consumer |
| `SPECTRA_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker list |
| `SPECTRA_KAFKA_TOPICS` | *(empty)* | Comma-separated topics to consume |
| `SPECTRA_KAFKA_GROUP_ID` | `spectra-ingestion` | Consumer group id |
| `SPECTRA_KAFKA_COLLECTION` | `spectra_stream` | Dedicated ChromaDB collection for streamed content |
| `SPECTRA_KAFKA_FORMAT` | `json` | Routing extension for the raw payload: `json`, `txt`, `xml`, `avro` |
| `SPECTRA_KAFKA_CONTENT_FIELD` | *(empty)* | (JSON) field to index instead of the raw payload |
| `SPECTRA_KAFKA_METADATA_FIELDS` | *(empty)* | (JSON) comma-separated fields copied into chunk metadata |
| `SPECTRA_KAFKA_CONCURRENCY` | `1` | Listener concurrency |
| `SPECTRA_KAFKA_MAX_POLL_RECORDS` | `20` | Max records per poll |
| `SPECTRA_KAFKA_RETENTION_TTL_DAYS` | `0` | Purge streamed sources older than N days (`0` = keep forever) |
| `SPECTRA_KAFKA_RETENTION_CRON` | `0 30 3 * * *` | Cron of the nightly stream-retention purge |
| `SPECTRA_KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | `PLAINTEXT`, `SASL_PLAINTEXT`, `SASL_SSL`… |
| `SPECTRA_KAFKA_SASL_MECHANISM` | *(empty)* | SASL mechanism (e.g. `PLAIN`, `SCRAM-SHA-512`) |
| `SPECTRA_KAFKA_SASL_JAAS_CONFIG` | *(empty)* | JAAS configuration line |

### Retrieval & RAG strategies

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_HYBRID_SEARCH_ENABLED` | `false` | Enable BM25 + vector fusion at query time (the BM25 index itself is always maintained) |
| `SPECTRA_HYBRID_BM25_TOP` | `20` | BM25 candidates before fusion |
| `SPECTRA_HYBRID_BM25_WEIGHT` | `1.0` | BM25 score weight multiplier |
| `SPECTRA_RERANKER_ENABLED` | `false` | Enable Cross-Encoder reranking |
| `SPECTRA_RERANKER_URL` | `http://reranker:8000` | Reranker service URL |
| `SPECTRA_RERANKER_TIMEOUT` | `30` | Reranker timeout (seconds) |
| `SPECTRA_RERANKER_TOP_CANDIDATES` | `20` | Candidates fed to reranker |
| `RERANKER_MODEL` | `cross-encoder/mmarco-...` | HuggingFace model ID (compose-level; feeds `SPECTRA_RERANKER_MODEL`) |
| `SPECTRA_AGENTIC_RAG_ENABLED` | `false` | Enable ReAct loop |
| `SPECTRA_AGENTIC_MAX_ITERATIONS` | `3` | Max search iterations |
| `SPECTRA_AGENTIC_INITIAL_TOP_K` | `5` | Chunks retrieved per iteration |
| `SPECTRA_AGENTIC_MAX_CONTEXT_TOKENS` | `3000` | Context budget of the agentic loop |
| `SPECTRA_AGENTIC_LANGUAGE` | `fr` | Response language (`fr`/`en`/`auto`) |
| `SPECTRA_MULTI_QUERY_ENABLED` | `false` | Enable multi-query retrieval fusion |
| `SPECTRA_MULTI_QUERY_COUNT` | `2` | Number of query variants to generate |
| `SPECTRA_CONTEXT_COMPRESSION_ENABLED` | `false` | Enable LLM-based passage extraction |
| `SPECTRA_SEMANTIC_DEDUP_ENABLED` | `false` | Enable Jaccard near-duplicate removal |
| `SPECTRA_SEMANTIC_DEDUP_THRESHOLD` | `0.85` | Similarity threshold for dedup (0–1) |
| `SPECTRA_LONG_CONTEXT_RAG_ENABLED` | `false` | Load full corpus when small enough |
| `SPECTRA_LONG_CONTEXT_MAX_CHUNKS` | `100` | Max chunks before switching to vector search |
| `SPECTRA_CONVERSATIONAL_RAG_ENABLED` | `false` | Enable history-aware query rewriting |
| `SPECTRA_CORRECTIVE_RAG_ENABLED` | `false` | Enable LLM chunk relevance grading |
| `SPECTRA_CORRECTIVE_MIN_RELEVANT_CHUNKS` | `1` | Min relevant chunks before corrective retrieval kicks in |
| `SPECTRA_ADAPTIVE_RAG_ENABLED` | `false` | Enable automatic strategy selection |
| `SPECTRA_SELF_RAG_ENABLED` | `false` | Enable self-reflection and refinement |
| `SPECTRA_SELF_RAG_MAX_REFLECTIONS` | `1` | Max self-reflection iterations |

### Layout-aware PDF parsing (docparser)

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_LAYOUT_PARSER_ENABLED` | `false` | Enable the docparser service |
| `SPECTRA_LAYOUT_PARSER_URL` | `http://docparser:8001` | docparser service URL |
| `SPECTRA_LAYOUT_PARSER_TIMEOUT` | `120` | docparser timeout (seconds) |
| `SPECTRA_LAYOUT_PARSER_BUFFER_MB` | `100` | Max in-memory buffer for parsed responses |
| `USE_DOCLING` | `false` | (compose-level) Use Docling instead of PyMuPDF inside docparser |

### GED

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_GED_ARCHIVE_DIR` | `./data/archive` | Archive manifest directory |
| `SPECTRA_GED_AUTO_QUALIFY_THRESHOLD` | `0.0` | Auto-qualify threshold (0 = disabled) |
| `SPECTRA_GED_ARCHIVE_AFTER_DAYS` | `0` | Auto-archive INGESTED docs after N days (0 = disabled) |
| `SPECTRA_GED_PURGE_AFTER_DAYS` | `0` | Auto-purge ARCHIVED docs N days after their **archival date** (0 = disabled) |
| `SPECTRA_GED_AUTO_RETRAIN_THRESHOLD` | `5` | Approved AI comments per auto fine-tuning trigger (0 = disabled) |

### Evaluation & fine-tuning

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_EVALUATION_JUDGE_MODEL` | *(empty)* | Neutral judge model for LLM-as-a-judge scoring. Empty = the evaluated model judges itself (possible bias) |
| `SPECTRA_EVALUATION_MAX_COMPLETED_REPORTS` | `200` | Max COMPLETED evaluation reports kept (oldest evicted) |
| `SPECTRA_FINE_TUNING_DEFAULT_BASE_MODEL` | `phi3` | Default base model when a request names none (catalog alias or full HF repo) |

### Model Hub (llmfit)

| Environment variable | Default | Description |
|---|---|---|
| `LLMFIT_PATH` | `llmfit` | Path to the `llmfit` binary |
| `LLMFIT_MODELS_DIR` | `./data/models` | Target directory for downloaded models — must match the volume shared with llm-chat |
| `LLMFIT_CACHE_DIR` | `~/.llmfit` | llmfit download cache (inventoried by the storage report; safe duplicates purgeable) |
| `LLMFIT_INSTALL_RETENTION_DAYS` | `0` | Purge terminal installation jobs older than N days (0 = keep forever) |

---

## Health & Observability

```bash
# System status (LLM + ChromaDB + optional services)
GET /api/status

# BM25 index stats
GET /api/status/fts
GET /api/status/fts?collection=spectra_documents

# Spring Boot health (used by Docker healthcheck)
GET /actuator/health

# Prometheus metrics (HTTP + RAG latency histograms, tag application=spectrallm)
GET /actuator/prometheus

# Hardware profile and recommended llama-server params
GET /api/config/resources

# Personalization cycle metrics (approved comments, DPO pairs, fine-tuning jobs, eval scores)
GET /api/metrics/personalization

# OpenAPI spec
GET /api-docs
GET /swagger-ui.html
```

**Consistency metrics** — hourly reconciliation between H2, ChromaDB and the BM25 index. Covers the default collection, every collection referenced by the GED, and the Kafka stream collection when enabled:

- `spectra.consistency.chroma.chunks`, `spectra.consistency.fts.chunks`, `spectra.consistency.db.chunks`, `spectra.consistency.fts_chroma_divergence` — default collection (legacy dashboards);
- `spectra.consistency.collection.chroma.chunks{collection=…}`, `spectra.consistency.collection.fts.chunks{collection=…}`, `spectra.consistency.collection.fts_chroma_divergence{collection=…}` — per collection.

---
