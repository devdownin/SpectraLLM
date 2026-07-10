# 📚 Spectra documentation

Everything beyond the [README](../README.md), grouped by what you're trying to do.
New here? Start with **[Getting Started](GETTING_STARTED.md)**.

## Start here

| Guide | For |
|---|---|
| **[Getting Started](GETTING_STARTED.md)** | Install step by step, download models, pick a Docker profile, deploy to Kubernetes/GKE. |
| **[User Manual](user/USER_MANUAL.md)** | A full walkthrough of the web interface — ingestion, playground, fine-tuning, evaluation. |
| **[How Spectra works (FR)](user/DOCUMENTATION_PEDAGOGIQUE.fr.md)** | The ideas in plain language: embeddings, hybrid search + RRF, the RAG strategies, QLoRA/DPO. |

## Architecture & configuration

| Guide | Inside |
|---|---|
| **[Architecture & Services](ARCHITECTURE.md)** | Every container and service in depth: RAG internals, ingestion, GED, evaluation, tech stack. |
| **[Configuration](CONFIGURATION.md)** | All environment variables, health endpoints, Prometheus metrics. |
| **[Technical Reference](tech/TECHNICAL_DOC.md)** | Implementation-level detail across the platform. |
| **[Diagrams](tech/)** | C4 container/component views and the technical-stack diagram (`*.html`). |

## Deployment & operations

| Guide | Inside |
|---|---|
| **[Deploy on GKE](tech/DEPLOY_GKE.md)** | Full GCP setup, cluster creation, GPU / TLS / monitoring variants. |
| **[llama.cpp Guide](tech/llama.cpp.md)** | Inference engine details, migration and tuning. |
| **[Reliability](process/RELIABILITY.md)** | Operational hardening and the improvements log. |
| **[Security](../SECURITY.md)** · **[Security Audit](process/SECURITY_AUDIT.md)** | Security policy and audit findings. |

## Deep dives & design notes

| Document | Inside |
|---|---|
| **[Kafka streaming ingestion (FR)](tech/DESIGN_KAFKA_STREAMING_UPSERT.fr.md)** | Design of streaming ingestion → RAG with upsert. |
| **[Fine-tuning audit (FR)](process/AUDIT_FINE_TUNING.fr.md)** | Audit of the fine-tuning process. |
| **[Ingestion test plan](process/TEST_PLAN_INGESTION.md)** | Test plan for DOCX and JSON ingestion. |
| **[GED design notes (FR)](user/ged.md)** | Requirements for the document ↔ model linkage layer. |

---

**Project root:** [README](../README.md) · [Français](../README.fr.md) · [Contributing](../CONTRIBUTING.md) · [Changelog](../CHANGELOG.md) · [License](../LICENSE)
