<div align="center">

# ⚡ Spectra

### Turn your documents into a private, fine-tuned AI assistant — in one command.

**100% local · No cloud · No API keys · No subscriptions**

[![Java CI with Maven](https://github.com/devdownin/SpectraLLM/actions/workflows/ci.yml/badge.svg)](https://github.com/devdownin/SpectraLLM/actions/workflows/ci.yml)
[![Code Coverage](https://codecov.io/gh/devdownin/SpectraLLM/branch/main/graph/badge.svg)](https://codecov.io/gh/devdownin/SpectraLLM)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/devdownin/SpectraLLM/badge)](https://securityscorecards.dev/viewer/?uri=github.com/devdownin/SpectraLLM)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

[Quick start](#-quick-start) · [Why Spectra](#-why-spectra) · [How it works](#-how-it-works) · [Documentation](#-documentation) · [Français](./README.fr.md)

</div>

---

Your organization's knowledge is locked inside PDFs, Word documents, wikis and data exports. Generic LLMs know none of it — and sending internal documents to a cloud API is often not an option.

**Spectra ingests your documents, answers questions from them (RAG), then goes further: it fine-tunes a local model that permanently knows your domain** — and exports it as a single GGUF file you can deploy anywhere, even air-gapped.

Most tools make you choose between RAG *or* fine-tuning, then leave the integration to you. Spectra does both, in sequence, automatically, on your own hardware:

```
 your documents ─► 📥 ingest ─► 🔍 search & ask (RAG) ─► 🧪 generate dataset
                                                              │
      deploy anywhere ◄─ 📦 export GGUF ◄─ 🎓 fine-tune ◄─────┘
```

One Docker stack. A web interface for the whole journey. Your data never leaves your machine.

## 🚀 Quick start

```bash
git clone https://github.com/devdownin/SpectraLLM.git && cd SpectraLLM
./start.sh --first-run        # Windows: start.bat --first-run
```

That's it. Spectra downloads the default models (~1.2 GB total), starts the stack, and opens the web UI at **http://localhost**. Drop a PDF on the Ingestion page and start asking questions.

> **Requirements:** Docker (Compose v2) and 16 GB RAM. GPU optional — NVIDIA, AMD/ROCm and Vulkan supported, auto-detected. Prefer step-by-step control? See **[Getting Started](docs/GETTING_STARTED.md)**.

## 🏆 Why Spectra

| Feature | Spectra | LangChain | Haystack | Open WebUI |
|---------|:--------:|:---------:|:---------:|:---------:|
| End-to-end platform | ✅ | ❌ | ❌ | ❌ |
| Advanced Hybrid RAG | ✅ | ⚠️ | ✅ | ❌ |
| Agentic RAG | ✅ | ⚠️ | ⚠️ | ❌ |
| Synthetic Dataset Generation | ✅ | ❌ | ❌ | ❌ |
| QLoRA Fine-tuning | ✅ | ❌ | ❌ | ❌ |
| DPO Training | ✅ | ❌ | ❌ | ❌ |
| Continuous Learning | ✅ | ❌ | ❌ | ❌ |
| Model Evaluation | ✅ | ❌ | ❌ | ❌ |
| GGUF Deployment | ✅ | ❌ | ❌ | ⚠️ |
| Kubernetes Ready | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 100% Local | ✅ | ✅ | ✅ | ✅ |

> ✅ Built-in &nbsp;&nbsp; ⚠️ Requires custom integration &nbsp;&nbsp; ❌ Not available

Building this yourself means stitching together an orchestration framework, a vector database, a chunker, an embedding server, a fine-tuning pipeline, an evaluation harness and a frontend — each with its own configuration model and failure modes. Spectra ships all of it in a single `docker compose up`.

## ⚙️ How it works

1. **📥 Ingest** — PDF, DOCX, HTML, JSON, XML, TXT, ZIP, URLs, even live Kafka streams. Layout-aware PDF parsing keeps tables and headings intact; an 8-step cleaner and semantic chunking prepare the text.
2. **🔍 Retrieve** — Hybrid search: BM25 keyword matching + vector similarity, fused with Reciprocal Rank Fusion, then cross-encoder reranking, semantic dedup and context compression.
3. **💬 Answer** — Six RAG strategies, picked adaptively per question: Standard, Hybrid, Multi-Query, Corrective, Self-RAG, and an Agentic ReAct loop for multi-hop questions. Streaming answers with cited sources.
4. **🧪 Synthesize** — Spectra generates a Q&A / DPO training dataset from your own corpus, scored by an LLM-as-a-judge.
5. **🎓 Fine-tune** — QLoRA/DPO recipes for CPU or GPU bake the knowledge into the model's weights. Approved answers feed a continuous-learning loop that retrains automatically.
6. **📦 Export & measure** — One GGUF file out, deployable anywhere (llama.cpp, Ollama, LM Studio…). Built-in evaluation, A/B model comparison and ablation benchmarks prove the gain at every step.

Everything is driven from a guided web interface (FR/EN) — dashboard, ingestion, document management, fine-tuning, playground, evaluation — with hardware auto-detection choosing sane inference parameters for your machine.

## 📚 Documentation

| Guide | What's inside |
|---|---|
| **[Getting Started](docs/GETTING_STARTED.md)** | Step-by-step install, model downloads, Docker profiles, Kubernetes/GKE deploy |
| **[Architecture & Services](docs/ARCHITECTURE.md)** | Every container and service in depth: RAG internals, ingestion, GED, evaluation, tech stack |
| **[Configuration](docs/CONFIGURATION.md)** | All environment variables, health endpoints, Prometheus metrics |
| **[User Manual](USER_MANUAL.md)** | Complete walkthrough of the web interface |
| **[Technical Doc](TECHNICAL_DOC.md)** | Implementation-level reference |
| **[Pedagogical Mini-Book (FR)](DOCUMENTATION_PEDAGOGIQUE.fr.md)** | The ideas behind Spectra: embeddings, HNSW, BM25 + RRF, the six RAG strategies, DPO/QLoRA — each with a concrete example |
| **[llama.cpp Guide](llama.cpp.md)** | Inference engine details and tuning |
| **[Reliability](RELIABILITY.md)** · **[Security](SECURITY.md)** | Operational guarantees and security policy |

**Stack:** Java 25 / Spring Boot 4 · React 19 · llama.cpp · ChromaDB · Python (fine-tuning, parsing, reranking) — detailed in [Architecture](docs/ARCHITECTURE.md#technology-stack).

## 🤝 Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). If Spectra is useful to you, a ⭐ helps others find it.

## 📄 License

**GNU AGPL-3.0** — use, modify and self-host freely, in production, on premises or air-gapped. AGPL is a strong copyleft: if you run a modified version as a network service, you must make the corresponding source available to its users. Full text in [LICENSE](LICENSE).

---

<div align="center">

*From raw documents to domain expertise — all on your hardware.*

</div>
