# 📚 Spectra documentation

Everything beyond the [README](../README.md), grouped by what you're trying to do.
New here? Start with **[Getting Started](getting-started.en.md)**.

## Start here

| Guide | For |
|---|---|
| **[Getting Started](getting-started.en.md)** | Install step by step, download models, pick a Docker profile, deploy to Kubernetes/GKE. |
| **[User Manual](user/user-manual.en.md)** ([FR](user/user-manual.fr.md)) | A full walkthrough of the web interface — ingestion, playground, fine-tuning, evaluation. |
| **[How Spectra works (FR)](user/documentation-pedagogique.fr.md)** | The ideas in plain language: embeddings, hybrid search + RRF, the RAG strategies, QLoRA/DPO. |

## Architecture & configuration

| Guide | Inside |
|---|---|
| **[Architecture & Services](architecture.en.md)** | Every container and service in depth: RAG internals, ingestion, GED, evaluation, tech stack. |
| **[RAG pipeline internals](tech/rag-pipeline.fr.md)** | Why each retrieval step exists — chunking, Multi-Query, RRF fusion, reranking, context compression, long-context bypass. |
| **[Configuration](configuration.en.md)** | All environment variables, health endpoints, Prometheus metrics. |
| **[Technical Reference](tech/technical-doc.fr.md)** | Implementation-level detail across the platform. |
| **[Diagrams](tech/)** | C4 container/component views ([level 2](tech/c4-level-2-containers.fr.md), [level 3](tech/c4-level-3-components.fr.md)) and the [technical-stack diagram](tech/technical-stack-architecture.en.md), all in Markdown/Mermaid. |

## Deployment & operations

| Guide | Inside |
|---|---|
| **[llama.cpp Guide](tech/llama-cpp.fr.md)** | Inference engine details, migration and tuning. |
| **[Kubernetes / GKE](../deploy/k8s/README.md)** | Manifests, kustomize overlays (GPU, GKE, monitoring) and model seeding. |
| **[Reliability](process/reliability.fr.md)** | Operational hardening and the improvements log. |
| **[Security](../SECURITY.md)** | Security policy and how to report vulnerabilities. |

## Deep dives & design notes

| Document | Inside |
|---|---|
| **[Kafka streaming ingestion (FR)](tech/design-kafka-streaming-upsert.fr.md)** | Design of streaming ingestion → RAG with upsert. |
| **[Ingestion test plan (FR)](process/test-plan-ingestion.fr.md)** | Test plan for DOCX and JSON ingestion. |
| **[Ingestion & GED audit (FR)](process/archive/audit-ingestion-ged.fr.md)** | Audit of the ingestion/GED pipeline and its fixes. |
| **[Documentation audit (FR)](process/audit-documentation.fr.md)** | Audit of this documentation set (conformity, completeness). |
| **[Security audit (FR)](process/audit-securite.fr.md)** | Technical security findings (auth model, exposure, DoS) — companion to [SECURITY.md](../SECURITY.md). |

## Direction

| Document | Inside |
|---|---|
| **[Roadmap (FR)](../ROADMAP.fr.md)** | Where Spectra is heading — short / medium / long-term evolutions, grounded in the audits and the changelog. |

---

**Project root:** [README](../README.md) · [Français](../README.fr.md) · [Roadmap](../ROADMAP.fr.md) · [Contributing](../CONTRIBUTING.md) · [Changelog](../CHANGELOG.md) · [License](../LICENSE)
