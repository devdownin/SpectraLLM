<!-- short: Domain LLM Builder, Fine Tuning and RAG -->
<!--
  Page de présentation Docker Hub de compagnonsdudev/spectrallm.
  Poussée par .github/workflows/docker-publish.yml (job « describe »).
  Éditez CE fichier, jamais la description dans l'interface Docker Hub : la prochaine
  publication l'écraserait, et l'écart ne se verrait qu'après coup.

  Les liens et images sont ABSOLUS : Docker Hub ne résout aucun chemin relatif.
-->

<img src="https://raw.githubusercontent.com/devdownin/SpectraLLM/main/docs/assets/logo.png" alt="Spectra LLM" width="320">

### Turn your documents into a private, fine-tuned AI — in one application.

**100% local · No cloud · No API keys · No subscriptions**

[GitHub](https://github.com/devdownin/SpectraLLM) · [Documentation](https://github.com/devdownin/SpectraLLM/blob/main/docs/README.md) · [Français](https://github.com/devdownin/SpectraLLM/blob/main/README.fr.md) · AGPL-3.0

![Spectra dashboard](https://raw.githubusercontent.com/devdownin/SpectraLLM/main/docs/assets/dashboard.png)

Your organization's knowledge lives in PDFs, Word docs, wikis and exports. Generic LLMs know none of it — and shipping internal documents to a cloud API is often a non-starter.

> **Spectra reads your documents, answers questions from them, then fine-tunes a local model that permanently knows your domain — exported as a single file you can run anywhere, even air-gapped.**

---

## What this image is

`compagnonsdudev/spectrallm` is the **backend**: Java 25 / Spring Boot — the REST API, the ingestion pipeline, the document store, the hybrid RAG engine and the fine-tuning orchestration. It listens on **8080**.

It is one of **two** images published for this project:

| Image | Role | Port |
|---|---|---|
| **`compagnonsdudev/spectrallm`** | Backend — API, RAG pipeline, document store, fine-tuning | 8080 |
| **`compagnonsdudev/spectrallm-frontend`** | Web interface — React served by Nginx | 80 |

Neither runs alone. A working Spectra stack is five containers: these two, a **ChromaDB** vector store, and **two llama.cpp servers** — one for chat, one for embeddings. The Compose file below wires all five together.

---

## Quick start — nothing is built on your machine

```bash
git clone https://github.com/devdownin/SpectraLLM.git && cd SpectraLLM

# GGUF weights (~1.2 GB for the defaults) — data, not image layers
./scripts/setup.sh --download-embed --download-chat

# Pull the published images and start the stack
docker compose --project-directory . \
  -f deploy/docker/docker-compose.yml -f deploy/docker/docker-compose.hub.yml pull

docker compose --project-directory . \
  -f deploy/docker/docker-compose.yml -f deploy/docker/docker-compose.hub.yml \
  up -d --wait --no-build
```

Then open **http://localhost**, drop a PDF on the Ingestion page, and start asking questions.

`--wait` blocks until every service reports healthy through its own healthcheck, and exits non-zero if one never does. `--no-build` is the guardrail: if an image reference were wrong, Compose would otherwise rebuild it silently and the whole point of pulling would be lost.

**Why a `git clone` and not a Compose file to paste here.** The two llama.cpp servers mount supervisor entrypoints from `scripts/`, and the GGUF weights live in `data/models` — several gigabytes you have to download anyway. The clone carries those; the images carry the code. Nothing is compiled locally: no Maven build, no Vite build. That is exactly what these images are for.

Prefer the guided path? `./scripts/start.sh --first-run` does the download, the start and opens the browser — but it *builds* the images rather than pulling them.

---

## Tags

| Tag | Meaning |
|---|---|
| `latest` | The most recent release. |
| `0.7` | The 0.7 series — patches, without the surprises of a version bump. |
| `0.7.1` | An exact version. **Pin this in production.** |

Release candidates (`1.0.0-rc1`) are published as-is and never carry `latest`.

**Architectures:** `linux/amd64` and `linux/arm64` — Apple Silicon included.

Both images are built and pushed by [a single GitHub Actions workflow](https://github.com/devdownin/SpectraLLM/blob/main/.github/workflows/docker-publish.yml) on each version tag, with provenance and SBOM attestations attached:

```bash
docker buildx imagetools inspect compagnonsdudev/spectrallm:latest
```

---

## Requirements

- **Docker Engine 25+** with Compose v2 (the healthchecks use `start_interval`, which older engines reject).
- **16 GB RAM** for the default 7B chat model. Smaller models work on less.
- **GPU optional** — NVIDIA, AMD/ROCm and Vulkan are auto-detected by `scripts/start.sh`; a Compose overlay handles the offload.

---

## Configuration

Everything has a working default. The variables worth knowing:

| Variable | Default | What it does |
|---|---|---|
| `SPECTRA_IMAGE_TAG` | `latest` | Version of the two images pulled. Pin it in production. |
| `SPECTRA_IMAGE_NAMESPACE` | `compagnonsdudev` | Where the images come from — change it for a private mirror. |
| `SPECTRA_API_KEY` | *(empty)* | Set it and `/api/**` requires the `X-API-Key` header. Empty means **no authentication**. |
| `SPECTRA_BIND_ADDR` | `127.0.0.1` | Interface the ports are published on. `0.0.0.0` exposes the stack to your network — set an API key first, and put TLS in front. |
| `LLM_CHAT_MODEL_FILE` | `Qwen2.5-7B-Instruct-Q4_K_M.gguf` | The chat GGUF served from `data/models/`. |
| `JAVA_OPTS` | `-Xms256m -Xmx1024m -XX:+UseZGC` | JVM heap for this container. |

Full list: [Configuration guide](https://github.com/devdownin/SpectraLLM/blob/main/docs/configuration.en.md).

**Volume.** This image reads and writes `/app/data` — the document store, the H2 database, the GGUF models, the fine-tuning jobs and the logs. In the stack it is bind-mounted to `./data`, alongside the vector index: one directory, one backup, one reset. The container runs as the non-root user `spectra`, so that directory must be writable by others.

**Ports.** By default every port is published on the loopback only. The web UI (80) and the API (8080) are the two you need; 8000/8081/8082 are there for diagnostics.

---

## What you get

- **📥 Ingest** — PDF, DOCX, HTML, Markdown, CSV, JSON, XML, Avro, TXT, ZIP, URLs, even live Kafka streams. Layout-aware parsing keeps tables and headings intact.
- **🔍 Ask** — Hybrid search (keyword + vector) with reranking and **cited sources**. Six retrieval strategies, picked adaptively per question, up to an agentic ReAct loop for multi-hop reasoning — with a live view of how each answer was built.
- **🎓 Fine-tune** — Spectra builds a training dataset from your own corpus, then bakes the knowledge into the model's weights (QLoRA/DPO, CPU or GPU). Approved answers feed a continuous-learning loop.
- **📦 Deploy** — Out comes a single GGUF file, runnable anywhere (llama.cpp, Ollama, LM Studio…), with built-in evaluation and A/B comparison to prove the gain.

| | |
|:---:|:---:|
| ![Ask your documents](https://raw.githubusercontent.com/devdownin/SpectraLLM/main/docs/assets/playground.png) | ![Fine-tune a local model](https://raw.githubusercontent.com/devdownin/SpectraLLM/main/docs/assets/training.png) |
| **Ask** your documents — answers with cited sources | **Fine-tune** a model that keeps the knowledge |

---

## Optional services

Three more services sit behind Compose profiles and are **not** part of this pull — they are built locally on demand, because each weighs several gigabytes and only matters if you turn it on:

```bash
docker compose --project-directory . -f deploy/docker/docker-compose.yml \
  --profile trainer up -d trainer      # in-container fine-tuning (torch)
```

`layout-parser` (layout-aware PDF parsing), `reranker` (cross-encoder reranking — the JVM ONNX engine is the default and needs no container) and `kafka` (streaming ingestion) work the same way.

---

## Documentation

| Guide | Inside |
|---|---|
| [Getting Started](https://github.com/devdownin/SpectraLLM/blob/main/docs/getting-started.en.md) | Step-by-step install, model downloads, Docker profiles |
| [Publier / consommer les images (FR)](https://github.com/devdownin/SpectraLLM/blob/main/docs/tech/docker-hub.fr.md) | These images: how they are published, pinned and pulled |
| [Architecture & Services](https://github.com/devdownin/SpectraLLM/blob/main/docs/architecture.en.md) | Every service in depth |
| [Configuration](https://github.com/devdownin/SpectraLLM/blob/main/docs/configuration.en.md) | All environment variables, health endpoints, metrics |
| [User Manual](https://github.com/devdownin/SpectraLLM/blob/main/docs/user/user-manual.en.md) | Complete walkthrough of the web interface |

---

## License

**GNU AGPL-3.0** — use, modify and self-host freely, in production, on premises or air-gapped. AGPL is a strong copyleft: if you run a modified version as a network service, you must make the corresponding source available to its users. [Full text](https://github.com/devdownin/SpectraLLM/blob/main/LICENSE).

*From raw documents to domain expertise — all on your hardware.*
