# GEMINI.md - SpectraLLM

## Project Overview
SpectraLLM is a comprehensive, 100% local platform designed to turn business documents into specialized, production-ready AI assistants. It uniquely combines **RAG (Retrieval-Augmented Generation)** and **Fine-Tuning** in a single, automated pipeline.

The platform handles the entire lifecycle:
1. **Ingestion**: Multi-format support (PDF, DOCX, HTML, JSON, etc.) with layout-aware parsing.
2. **Indexing**: Hybrid search combining ChromaDB (Vector) and BM25 (Full-text) with Reciprocal Rank Fusion (RRF).
3. **Retrieval**: Advanced techniques like Multi-Query RAG, Context Compression, and Semantic Deduplication.
4. **Generation**: Standard, Hybrid, and Agentic (ReAct) RAG modes.
5. **Fine-Tuning**: Automatic dataset synthesis and QLoRA training using Unsloth.
6. **GED**: Full document lifecycle management and audit trails.

## Architecture
The system follows a microservices architecture orchestrated by Docker Compose:
- **`spectra-api`**: Core Spring Boot backend (Java 21, Virtual Threads).
- **`llm-chat`**: Chat inference engine (llama.cpp server).
- **`llm-embed`**: Dedicated embedding engine (llama.cpp server).
- **`chromadb`**: Vector database for document embeddings.
- **`frontend`**: React 19 application served by Nginx.
- **`docparser`** (Optional): Layout-aware PDF parsing service (Python).
- **`reranker`** (Optional): Cross-encoder re-ranking service (Python).

## Tech Stack
- **Backend**: Java 21, Spring Boot 4.0, Project Loom (Virtual Threads), JPA/H2, Resilience4j.
- **Frontend**: React 19, Vite, Tailwind CSS 4, TanStack Query, Recharts.
- **Inference**: llama.cpp (GGUF format).
- **Vector DB**: ChromaDB (API v2).
- **Extraction**: Apache PDFBox, Apache POI, jsoup, PyMuPDF4LLM/Docling.
- **Training**: Python 3.10+, Unsloth, QLoRA.

## Building and Running

### Prerequisites
- Docker & Docker Compose v2.
- Java 21 (for local backend development).
- Node.js 22+ (for local frontend development).

### Key Commands

#### Convenience Scripts (Recommended)
- **Setup everything**: `./setup.sh` (detects env, creates folders, builds).
- **Build the stack**: `./build.sh`.
- **Start the stack**: `./start.sh` (includes layout-parser and reranker profiles).
- **Stop the stack**: `./stop.sh`.
- **Full pipeline test**: `./pipeline.sh`.

#### Full Stack (Docker)
- **Initialize environment**: `./detect-env.sh` (detects hardware and creates `.env`).
- **Start base stack**: `docker compose up -d`.
- **Start with optional services**: `docker compose --profile layout-parser --profile reranker up -d`.
- **Stop stack**: `docker compose down`.

#### Backend (spectra-api)
- **Build**: `mvn clean package`.
- **Run tests**: `mvn test`.
- **Run locally**: `mvn spring-boot:run`.

#### Frontend
- **Install dependencies**: `npm install` (in `frontend/`).
- **Build**: `npm run build`.
- **Dev mode**: `npm run dev`.

## Development Conventions

### Backend (Java)
- **Virtual Threads**: Prefer standard blocking I/O; Project Loom handles scaling.
- **API Style**: RESTful endpoints, documented with Swagger (`/swagger-ui.html`).
- **Configuration**: Use `application.yml` and environment variable overrides (`SPECTRA_*`).
- **Testing**: JUnit 5, AssertJ, and Mockito.

### Frontend (React)
- **TypeScript**: Use strict typing for all components and services.
- **Styling**: Tailwind CSS 4 utility classes.
- **State Management**: React Query for server state, standard React hooks for local state.
- **Components**: Functional components with Lucide icons.

### LLM Integration
- All LLM interactions go through `llm-chat` and `llm-embed` services using OpenAI-compatible APIs.
- Models must be in GGUF format and registered in `data/models/registry.json`.

## Infrastructure
- **Data Persistence**: 
  - `./data/documents`: Raw source files.
  - `./data/models`: GGUF models and registry.
  - `./data/chromadb`: Vector database storage.
  - `./data/dataset`: Generated training data (JSONL).
- **Resource Management**: `ResourceAdvisorService` automatically computes optimal inference parameters based on detected hardware (CPU/RAM/VRAM).
