# Getting Started — Step by Step

The [README quick start](../README.md#-quick-start) (`./scripts/start.sh --first-run`) does all of this in one command. This guide is for when you want control over each stage, or a development setup.

> All shell commands in this document are run from the repository root.
> The Docker Compose stack lives in `deploy/docker/` — every `docker compose` command below
> therefore uses `--project-directory . -f deploy/docker/docker-compose.yml` (which is exactly
> what `scripts/start.sh` does for you).

## Getting Started

### Development Environment

Spectra requires **Java 25 (LTS)** — the version targeted by `backend/pom.xml`, validated by CI and used by the Docker images (Temurin 25). To set up your local development environment, you can use one of the following methods:

- **SDKMAN!**: A `.sdkmanrc` file is provided at the root. Run `sdk env install` then `sdk env use` to automatically switch to the correct Java version.
- **VS Code DevContainer**: A pre-configured `.devcontainer` is available. When opening the project in VS Code, click "Reopen in Container".
- **Manual**: Install **Eclipse Temurin 25 (LTS)** from [Adoptium](https://adoptium.net/).

You can verify your environment by running:
```bash
bash scripts/setup-java.sh
```

### Prerequisites

- **JDK 25 (LTS)** — for local compilation
- **Docker Desktop** (or Docker Engine **25+** + Compose v2) — 25 is the floor because the
  stack's healthchecks use `start_interval`, which probes every 3 s during startup instead
  of every 20–30 s. Older engines reject the field at container creation, so `up` fails
  outright rather than degrading quietly.
- **16 GB RAM** minimum (32 GB recommended for 7B models)
- A `.gguf` model file placed in `data/models/`

GPU is optional but strongly recommended for inference speed. NVIDIA, AMD (ROCm), and Vulkan are all supported.

### Quick start — one command

```bash
git clone https://github.com/devdownin/SpectraLLM.git
cd SpectraLLM
./scripts/start.sh --first-run        # Windows: scripts\start.bat --first-run
```

This downloads the default models (embedding ~81 MB + chat ~1.1 GB), starts the full stack in the background, waits for every service to be ready, then opens the Web UI at **http://localhost**. Steps 1–4 below do the same thing manually, for when you want control over each stage.

### 1. Clone and prepare

```bash
git clone https://github.com/devdownin/SpectraLLM.git
cd SpectraLLM
./scripts/detect-env.sh       # auto-detects hardware and writes .env
mkdir -p data/models data/documents data/dataset
```

`detect-env.sh` writes a marked **auto block** at the top of `.env` — hardware profile, JVM
heap, LLM context/parallelism, GPU flags — and regenerates it on every start. Everything
below the `SPECTRA:AUTO:END` marker is yours and is never touched; since the **last**
assignment of a key wins, your lines always override the detected ones.

Do **not** `cp .env.example .env`. That file is a commented catalogue, not a starter
config: copying it wholesale pins ~39 keys, which would override both the hardware sizing
and the container-only defaults from `docker-compose.yml`. Copy the individual lines you
want to change instead, below the marker. Use `./scripts/detect-env.sh --force` to start
over from a clean file (the previous one is kept as `.env.bak`).

### 2. Download the models

Two GGUF files are required — one for chat, one for embeddings:

```bash
# Chat model (~1.1 GB) — Phi-4-mini by default
huggingface-cli download bartowski/Qwen2.5-7B-Instruct-GGUF \
  Qwen2.5-7B-Instruct-Q4_K_M.gguf --local-dir data/models/

# Embedding model (~81 MB) — nomic-embed-text by default
huggingface-cli download nomic-ai/nomic-embed-text-v1.5-GGUF \
  nomic-embed-text-v1.5.Q4_0.gguf \
  --local-dir data/models/ --filename embed.gguf
```

If the models are missing at startup, the stack still comes up: `llm-chat` and `llm-embed` log `EN ATTENTE: modèle introuvable` and poll until the GGUF appears, then start serving on their own. Nothing aborts, so you can drop the files in — or download them from the Model Hub in the UI — while the stack is already running.

### 3. Start the stack

```bash
# The stack lives in deploy/docker/ — alias the invocation once:
alias spectra-compose='docker compose --project-directory . -f deploy/docker/docker-compose.yml'

# Base stack (inference + vector DB). --wait blocks until every service reports healthy
# via its Compose healthcheck, and exits non-zero if one never does — this is exactly
# what scripts/start.sh does for you.
spectra-compose up -d --wait

# With layout-aware PDF parsing
spectra-compose --profile layout-parser up -d

# With cross-encoder reranking
spectra-compose --profile reranker up -d

# With both optional services
spectra-compose --profile layout-parser --profile reranker up -d
```

> Rebuilding after a code change: `./scripts/start.sh --build` (or `spectra-compose up -d --build`).
> Without it, an already-built image is reused as-is and the **previous** code restarts.

> **Don't want to build at all?** The backend and frontend images are published to Docker
> Hub for each release. The shortest path is `./scripts/start.sh --hub` (Windows:
> `scripts\start.bat --hub`), which pulls them instead of running `mvn package` and the
> Vite build on your machine. By hand, that is the `docker-compose.hub.yml` overlay:
>
> ```bash
> spectra-compose -f deploy/docker/docker-compose.hub.yml pull spectra-api frontend
> spectra-compose -f deploy/docker/docker-compose.hub.yml up -d --wait --no-build
> ```
>
> Namespace and version are set with `SPECTRA_IMAGE_NAMESPACE` / `SPECTRA_IMAGE_TAG` —
> full details in **[Publier Spectra sur Docker Hub (FR)](tech/docker-hub.fr.md)**.

### 4. Access

| Interface | URL |
|---|---|
| **Web UI** | `http://localhost:80` |
| **API Docs** (Swagger) | `http://localhost:8080/swagger-ui.html` |
| **System status** | `http://localhost:8080/api/status` |
| **llama.cpp chat** | `http://localhost:8081` |
| **llama.cpp embed** | `http://localhost:8082` |
| **Prometheus metrics** | `http://localhost:8080/actuator/prometheus` |

---
