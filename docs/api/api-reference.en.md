# Spectra API Reference

Spectra provides a comprehensive REST API to interact with the platform programmatically. This allows you to integrate document ingestion, RAG querying, and fine-tuning triggers into your own applications or CI/CD pipelines.

## OpenAPI / Swagger UI

Spectra's backend is built with Spring Boot and uses `springdoc-openapi` to automatically generate API documentation based on the codebase.

When you run the Spectra stack locally using `docker compose up`, you can access the interactive Swagger UI and the raw OpenAPI specifications directly from the backend service.

### Accessing the Interactive UI

You can explore and test the API endpoints interactively via the Swagger UI interface.
Make sure your backend (`spectra-api`) is running, then navigate to:

👉 **[http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)**

### Raw OpenAPI Specification

If you need to generate API clients (e.g., using OpenAPI Generator) or integrate the API definitions into tools like Postman, you can retrieve the raw OpenAPI JSON or YAML files:

- **JSON Format:** [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
- **YAML Format:** [http://localhost:8080/v3/api-docs.yaml](http://localhost:8080/v3/api-docs.yaml)

## Key Endpoints Overview

*Note: View the Swagger UI for complete schema details, required parameters, and response structures.*

### Ingestion & Datasets
*   `POST /api/ingest` – Upload documents (PDF, DOCX, etc.) to be parsed, chunked, and embedded into ChromaDB.
*   `POST /api/dataset/generate` – Trigger background generation of a synthetic Q&A dataset based on ingested documents.
*   `GET /api/dataset/stats` – Retrieve statistics about the current dataset size and composition.

### Generation & RAG
*   `POST /api/query` – Execute a Retrieval-Augmented Generation (RAG) query against the knowledge base. Supports various advanced routing strategies.
*   `POST /api/chat` – Direct chat endpoint (bypassing RAG).

### Fine-Tuning
*   `POST /api/finetune/start` – Initiate a local fine-tuning job (QLoRA/DPO) using the current dataset.
*   `GET /api/finetune/jobs` – List historical and active fine-tuning jobs.

### System & Telemetry (SSE)
*   `GET /api/sse/system-load` – Server-Sent Events (SSE) stream providing real-time CPU/GPU load metrics.
*   `GET /api/sse/training-logs` – Server-Sent Events (SSE) stream broadcasting live stdout/stderr logs from an active fine-tuning job.
*   `GET /api/status` – Aggregated health status of the backend, Ollama, and ChromaDB.
