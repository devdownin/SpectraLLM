# Getting Started — Step by Step

The [README quick start](../README.md#-quick-start) (`./start.sh --first-run`) does all of this in one command. This guide is for when you want control over each stage, or a development setup.

> All shell commands in this document are run from the repository root.

## Getting Started

### Development Environment

Spectra requires **Java 25 (LTS)**. To set up your local development environment, you can use one of the following methods:

- **SDKMAN!**: A `.sdkmanrc` file is provided at the root. Run `sdk env install` then `sdk env use` to automatically switch to the correct Java version.
- **VS Code DevContainer**: A pre-configured `.devcontainer` is available. When opening the project in VS Code, click "Reopen in Container".
- **Manual**: Install **Eclipse Temurin 25 (LTS)** from [Adoptium](https://adoptium.net/).

You can verify your environment by running:
```bash
bash scripts/setup-java.sh
```

### Prerequisites

- **Java 25 (LTS)** — for local compilation
- **Docker Desktop** (or Docker Engine + Compose v2)
- **16 GB RAM** minimum (32 GB recommended for 7B models)
- A `.gguf` model file placed in `data/models/`

GPU is optional but strongly recommended for inference speed. NVIDIA, AMD (ROCm), and Vulkan are all supported.

### Quick start — one command

```bash
git clone https://github.com/your-org/Spectra.git
cd Spectra
./start.sh --first-run        # Windows: start.bat --first-run
```

This downloads the default models (embedding ~81 MB + chat ~1.1 GB), starts the full stack in the background, waits for every service to be ready, then opens the Web UI at **http://localhost**. Steps 1–4 below do the same thing manually, for when you want control over each stage.

### 1. Clone and prepare

```bash
git clone https://github.com/your-org/Spectra.git
cd Spectra
./detect-env.sh               # auto-detects hardware and writes .env
mkdir -p data/models data/documents data/dataset
```

### 2. Download the models

Two GGUF files are required — one for chat, one for embeddings:

```bash
# Chat model (~1.1 GB) — Phi-4-mini by default
huggingface-cli download unsloth/Phi-4-mini-reasoning-GGUF \
  Phi-4-mini-reasoning-UD-IQ1_S.gguf --local-dir data/models/

# Embedding model (~81 MB) — nomic-embed-text by default
huggingface-cli download nomic-ai/nomic-embed-text-v1.5-GGUF \
  nomic-embed-text-v1.5.Q4_0.gguf \
  --local-dir data/models/ --filename embed.gguf
```

If the models are missing at startup, `model-init` will print exact download instructions and abort before the LLM servers start.

### 3. Start the stack

```bash
# Base stack (inference + vector DB)
docker compose up -d

# With layout-aware PDF parsing
docker compose --profile layout-parser up -d

# With cross-encoder reranking
docker compose --profile reranker up -d

# With both optional services
docker compose --profile layout-parser --profile reranker up -d
```

### 4. Access

| Interface | URL |
|---|---|
| **Web UI** | `http://localhost:80` |
| **API Docs** (Swagger) | `http://localhost:8080/swagger-ui.html` |
| **System status** | `http://localhost:8080/api/status` |
| **llama.cpp chat** | `http://localhost:8081` |
| **llama.cpp embed** | `http://localhost:8082` |
| **Prometheus metrics** | `http://localhost:8080/actuator/prometheus` |

### 5. Deploy to Kubernetes / GKE (optional)

Spectra ships complete Kubernetes manifests (`k8s/`, kustomize) and a one-push CI/CD pipeline for **Google Kubernetes Engine**:

```bash
# 1. Seed the GGUF models onto the PVCs (idempotent)
./scripts/gke-seed-models.sh

# 2. Deploy the stack (minikube, kind, k3s, GKE…)
kubectl apply -k k8s/base

# Variants (kustomize overlays)
kubectl apply -k k8s/overlays/gpu    # GPU acceleration (NVIDIA, opt-in)
kubectl apply -k k8s/overlays/gke    # GKE native Ingress + Google-managed TLS
kubectl apply -k k8s/monitoring      # Prometheus alerts + Grafana dashboard
```

A GitHub Actions workflow (`.github/workflows/deploy-gke.yml`) builds and pushes the images and rolls out to GKE on every push to `main`, authenticated via **Workload Identity Federation** (no JSON keys). Highlights:

- **One-command model seeding** — a Job downloads the GGUF models directly onto the PVCs (no manual `kubectl cp`).
- **Managed HTTPS** — `ManagedCertificate` + HTTP→HTTPS redirect, with SSE-friendly backend timeouts.
- **Observability** — `/actuator/prometheus` metrics, ready-to-apply `ServiceMonitor`, alert rules and a Grafana dashboard.

See **[deploy-gke.en.md](tech/deploy-gke.en.md)** for the full GCP setup, cluster creation, and the GPU / TLS / monitoring variants.

---
