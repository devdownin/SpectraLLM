# 📚 Spectra documentation

Everything beyond the [README](../README.md), grouped by what you're trying to do.
New here? Start with **[Getting Started](getting-started.en.md)**.

## Start here

| Guide | For |
|---|---|
| **[Getting Started](getting-started.en.md)** | Install step by step, download models, pick a Docker profile, deploy to Kubernetes/GKE. |
| **[User Manual](user/user-manual.en.md)** | A full walkthrough of the web interface — ingestion, playground, fine-tuning, evaluation. |
| **[How Spectra works (FR)](user/documentation-pedagogique.fr.md)** | The ideas in plain language: embeddings, hybrid search + RRF, the RAG strategies, QLoRA/DPO. |

## Architecture & configuration

| Guide | Inside |
|---|---|
| **[Architecture & Services](architecture.en.md)** | Every container and service in depth: RAG internals, ingestion, GED, evaluation, tech stack. |
| **[RAG pipeline internals](tech/rag-pipeline.en.md)** | Why each retrieval step exists — chunking, Multi-Query, RRF fusion, reranking, context compression, long-context bypass. |
| **[Configuration](configuration.en.md)** | All environment variables, health endpoints, Prometheus metrics. |
| **[Technical Reference](tech/technical-doc.en.md)** | Implementation-level detail across the platform. |
| **[Diagrams](tech/)** | C4 container/component views and the technical-stack diagram (`*.md`). |

## Deployment & operations

| Guide | Inside |
|---|---|
| **[Deploy on GKE](tech/deploy-gke.en.md)** | Full GCP setup, cluster creation, GPU / TLS / monitoring variants. |
| **[llama.cpp Guide](tech/llama-cpp.en.md)** | Inference engine details, migration and tuning. |
| **[Reliability](process/reliability.en.md)** | Operational hardening and the improvements log. |
| **[Security](../SECURITY.md)** · **[Security Audit](process/security-audit.en.md)** | Security policy and audit findings. |

## Deep dives & design notes

| Document | Inside |
|---|---|
| **[Kafka streaming ingestion (FR)](adr/design-kafka-streaming-upsert.fr.md)** | Design of streaming ingestion → RAG with upsert. |
| **[Fine-tuning audit (FR)](process/audit-fine-tuning.fr.md)** | Audit of the fine-tuning process. |
| **[Ingestion test plan](process/test-plan-ingestion.en.md)** | Test plan for DOCX and JSON ingestion. |
| **[GED design notes (FR)](adr/ged.en.md)** | Requirements for the document ↔ model linkage layer. |

---

**Project root:** [README](../README.md) · [Français](../README.fr.md) · [Contributing](../CONTRIBUTING.md) · [Changelog](../CHANGELOG.md) · [License](../LICENSE)
