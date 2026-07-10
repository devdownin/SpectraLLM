<div align="center">

# ⚡ Spectra

### Turn your documents into a private, fine-tuned AI — in one command.

**100% local · No cloud · No API keys · No subscriptions**

[![Java CI with Maven](https://github.com/devdownin/SpectraLLM/actions/workflows/ci.yml/badge.svg)](https://github.com/devdownin/SpectraLLM/actions/workflows/ci.yml)
[![Code Coverage](https://codecov.io/gh/devdownin/SpectraLLM/branch/main/graph/badge.svg)](https://codecov.io/gh/devdownin/SpectraLLM)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/devdownin/SpectraLLM/badge)](https://securityscorecards.dev/viewer/?uri=github.com/devdownin/SpectraLLM)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

[Quick start](#-quick-start) · [Why Spectra](#-why-spectra) · [How it works](#-how-it-works) · [Documentation](#-documentation) · [Français](./README.fr.md)

<img src="docs/assets/dashboard.png" alt="Spectra — the local document-to-model workspace" width="100%">

</div>

---

Your organization's knowledge lives in PDFs, Word docs, wikis and exports. Generic LLMs know none of it — and shipping internal documents to a cloud API is often a non-starter.

> **Spectra reads your documents, answers questions from them, then fine-tunes a local model that permanently knows your domain — exported as a single file you can run anywhere, even air-gapped.**

Most tools give you RAG *or* fine-tuning and leave the wiring to you. Spectra does the whole journey — automatically, on your own hardware:

```
 your documents ─► 📥 ingest ─► 🔍 ask (RAG) ─► 🧪 build dataset
                                                      │
     deploy anywhere ◄─ 📦 export ◄─ 🎓 fine-tune ◄───┘
```

One `docker compose up`. One web interface for the whole journey. Your data never leaves your machine.

## 🚀 Quick start

```bash
git clone https://github.com/devdownin/SpectraLLM.git && cd SpectraLLM
./scripts/start.sh --first-run      # Windows: scripts\start.bat --first-run
```

Spectra downloads the default models (~1.2 GB), starts the stack, and opens the web UI at **http://localhost**. Drop a PDF on the Ingestion page and start asking questions.

> **Requirements:** Docker (Compose v2) and 16 GB RAM. GPU optional — NVIDIA, AMD/ROCm and Vulkan supported, auto-detected. Prefer step-by-step control? → **[Getting Started](docs/GETTING_STARTED.md)**

## 🏆 Why Spectra

| | Spectra | LangChain | Haystack | Open WebUI |
|---|:--------:|:---------:|:---------:|:---------:|
| End-to-end platform | ✅ | ❌ | ❌ | ❌ |
| Advanced hybrid RAG | ✅ | ⚠️ | ✅ | ❌ |
| Agentic RAG | ✅ | ⚠️ | ⚠️ | ❌ |
| Synthetic dataset generation | ✅ | ❌ | ❌ | ❌ |
| QLoRA fine-tuning | ✅ | ❌ | ❌ | ❌ |
| DPO / continuous learning | ✅ | ❌ | ❌ | ❌ |
| Built-in model evaluation | ✅ | ❌ | ❌ | ❌ |
| One-file GGUF deployment | ✅ | ❌ | ❌ | ⚠️ |
| 100% local | ✅ | ✅ | ✅ | ✅ |

> ✅ Built-in &nbsp; ⚠️ Requires custom integration &nbsp; ❌ Not available

Building this yourself means stitching together an orchestration framework, a vector database, a chunker, an embedding server, a fine-tuning pipeline, an evaluation harness and a frontend — each with its own config and failure modes. Spectra ships all of it, integrated.

## ⚙️ How it works

Four stages, one continuous flow — all driven from a guided web interface (FR/EN):

- **📥 Ingest** — PDF, DOCX, HTML, JSON, XML, TXT, ZIP, URLs, even live Kafka streams. Layout-aware parsing keeps tables and headings intact.
- **🔍 Ask** — Hybrid search (keyword + vector) with reranking and **cited sources**. Six retrieval strategies, picked adaptively per question — up to an agentic ReAct loop for multi-hop reasoning.
- **🎓 Fine-tune** — Spectra builds a training dataset from your own corpus, then bakes the knowledge into the model's weights (QLoRA/DPO, CPU or GPU). Approved answers feed a continuous-learning loop.
- **📦 Deploy** — Out comes a single GGUF file, runnable anywhere (llama.cpp, Ollama, LM Studio…). Built-in evaluation, A/B comparison and ablation benchmarks prove the gain at every step.

| | |
|:---:|:---:|
| <img src="docs/assets/playground.png" alt="Chat over your documents with cited sources" width="100%"> | <img src="docs/assets/training.png" alt="Fine-tune a local model on your corpus" width="100%"> |
| **Ask** your documents — answers with cited sources | **Fine-tune** a local model that keeps the knowledge |

*Curious how the hybrid fusion, reranking and fine-tuning recipes actually work under the hood? → **[Architecture & internals](docs/ARCHITECTURE.md)***

## 📚 Documentation

| Guide | What's inside |
|---|---|
| **[Getting Started](docs/GETTING_STARTED.md)** | Step-by-step install, model downloads, Docker profiles, Kubernetes/GKE deploy |
| **[Architecture & Services](docs/ARCHITECTURE.md)** | Every service in depth: RAG internals, ingestion, evaluation, tech stack |
| **[Configuration](docs/CONFIGURATION.md)** | All environment variables, health endpoints, Prometheus metrics |
| **[User Manual](docs/user/USER_MANUAL.md)** | Complete walkthrough of the web interface |
| **[Technical Reference](docs/tech/TECHNICAL_DOC.md)** | Implementation-level detail |
| **[How Spectra works (FR)](docs/user/DOCUMENTATION_PEDAGOGIQUE.fr.md)** | The ideas in plain language: embeddings, BM25 + RRF, the RAG strategies, DPO/QLoRA |
| **[llama.cpp Guide](docs/tech/llama.cpp.md)** | Inference engine details and tuning |
| **[Reliability](docs/process/RELIABILITY.md)** · **[Security](SECURITY.md)** | Operational guarantees and security policy |

**Stack:** Java 25 / Spring Boot 4 · React 19 · llama.cpp · ChromaDB · Python (fine-tuning, parsing, reranking) — detailed in [Architecture](docs/ARCHITECTURE.md#technology-stack).

## 🤝 Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). If Spectra is useful to you, a ⭐ helps others find it.

## 📄 License

**GNU AGPL-3.0** — use, modify and self-host freely, in production, on premises or air-gapped. AGPL is a strong copyleft: if you run a modified version as a network service, you must make the corresponding source available to its users. Full text in [LICENSE](LICENSE).

---

<div align="center">

*From raw documents to domain expertise — all on your hardware.*

</div>
