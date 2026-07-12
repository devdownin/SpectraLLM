# Configuration & Observability

Every setting has a working default — see the essentials note at the top of [.env.example](../.env.example). This reference covers all overrides.

> All shell commands in this document are run from the repository root.

## Configuration Reference

All settings have environment variable overrides. The table below shows the most important ones.

### Core inference

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_LLM_PROVIDER` | `llama-cpp` | `llama-cpp` |
| `SPECTRA_LLM_CHAT_BASE_URL` | `http://llm-chat:8081` | Chat server URL |
| `SPECTRA_LLM_EMBEDDING_BASE_URL` | `http://llm-embed:8082` | Embedding server URL |
| `SPECTRA_LLM_MODEL` | `phi-4-mini` | Model alias for chat |
| `SPECTRA_LLM_EMBEDDING_MODEL` | `nomic-embed-text` | Model alias for embeddings |
| `SPECTRA_GENERATION_TIMEOUT` | `120` | Generation timeout (seconds) |
| `SPECTRA_LLM_SWITCH_TIMEOUT` | `300` | Max wait (seconds) for the server to actually serve a newly activated model before an evaluation/benchmark starts measuring — must cover loading the heaviest GGUF |
| `SPECTRA_LLM_SWITCH_POLL_MS` | `2000` | Poll interval (ms) while waiting for that model-switch convergence |
| `LLM_CHAT_MODEL_FILE` | `Phi-4-mini-reasoning-UD-IQ1_S.gguf` | Chat GGUF filename in `data/models/` |
| `LLM_EMBED_MODEL_FILE` | `embed.gguf` | Embedding GGUF filename in `data/models/` |
| `LLM_PARALLEL` | `2` | Parallel inference slots per server |
| `LLAMA_CPP_IMAGE_TAG` | `server-b9828` | Pinned `ghcr.io/ggml-org/llama.cpp` image tag for llm-chat/llm-embed (the floating `server` tag tracks llama.cpp master and can break without warning) |

### Ingestion pipeline

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_CHUNK_MAX_TOKENS` | `512` | Max tokens per chunk |
| `SPECTRA_CHUNK_OVERLAP_TOKENS` | `64` | Token overlap between chunks |
| `SPECTRA_EMBEDDING_BATCH_SIZE` | `10` | Chunks embedded per batch |
| `SPECTRA_EMBEDDING_TIMEOUT` | `30` | Embedding timeout (seconds) |
| `SPECTRA_CONCURRENT_INGESTIONS` | `4` | Parallel ingestion workers |

### Optional features

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_HYBRID_SEARCH_ENABLED` | `false` | Enable BM25 + vector fusion |
| `SPECTRA_HYBRID_BM25_TOP` | `20` | BM25 candidates before fusion |
| `SPECTRA_HYBRID_BM25_WEIGHT` | `1.0` | BM25 score weight multiplier |
| `SPECTRA_RERANKER_ENABLED` | `false` | Enable Cross-Encoder reranking |
| `SPECTRA_RERANKER_TOP_CANDIDATES` | `20` | Candidates fed to reranker |
| `RERANKER_MODEL` | `cross-encoder/mmarco-...` | HuggingFace model ID |
| `SPECTRA_LAYOUT_PARSER_ENABLED` | `false` | Enable docparser service |
| `SPECTRA_LAYOUT_PARSER_TIMEOUT` | `120` | Docparser timeout (seconds) |
| `USE_DOCLING` | `false` | Use Docling instead of PyMuPDF |
| `SPECTRA_AGENTIC_RAG_ENABLED` | `false` | Enable ReAct loop |
| `SPECTRA_AGENTIC_MAX_ITERATIONS` | `3` | Max search iterations |
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
| `SPECTRA_ADAPTIVE_RAG_ENABLED` | `false` | Enable automatic strategy selection |
| `SPECTRA_SELF_RAG_ENABLED` | `false` | Enable self-reflection and refinement |

### GED

| Environment variable | Default | Description |
|---|---|---|
| `SPECTRA_GED_ARCHIVE_DIR` | `./data/archive` | Archive manifest directory |
| `SPECTRA_GED_AUTO_QUALIFY_THRESHOLD` | `0.0` | Auto-qualify threshold (0 = disabled) |
| `SPECTRA_GED_ARCHIVE_AFTER_DAYS` | `0` | Auto-archive INGESTED docs after N days (0 = disabled) |
| `SPECTRA_GED_PURGE_AFTER_DAYS` | `0` | Auto-purge ARCHIVED docs after N days (0 = disabled) |
| `SPECTRA_GED_AUTO_RETRAIN_THRESHOLD` | `5` | Approved AI comments per auto fine-tuning trigger (0 = disabled) |

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

---
