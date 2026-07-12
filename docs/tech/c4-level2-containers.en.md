# Spectra — C4 Level 2 · Containers

Vue des **conteneurs applicatifs** composant Spectra : leurs technologies, responsabilités et interactions réseau. Chaque conteneur correspond à un service Docker Compose.

```mermaid
%%{init: {
  "theme": "base",
  "themeVariables": {
    "primaryColor": "#1e1b4b",
    "primaryTextColor": "#e2e8f0",
    "primaryBorderColor": "#4f46e5",
    "lineColor": "#6366f1",
    "secondaryColor": "#0f172a",
    "tertiaryColor": "#1e293b",
    "background": "#0a0a0f",
    "mainBkg": "#1e1b4b",
    "nodeBorder": "#4f46e5",
    "clusterBkg": "#0f172a",
    "titleColor": "#a5b4fc",
    "edgeLabelBackground": "#0f172a",
    "fontFamily": "Courier New, monospace"
  }
}}%%
C4Container
    title Spectra — Diagramme de Conteneurs (Level 2)

    Person(user, "Utilisateur", "Accède à l'interface\nvia un navigateur web")

    System_Boundary(compose, "Spectra — Docker Compose Network") {

        Container(frontend, "spectra-frontend", "nginx 1.27 + React 19 / Vite", "Sert l'application SPA.\nProxy /api/* vers spectra-api.\nSSE sans buffering sur /api/sse/*.")

        Container(api, "spectra-api", "Spring Boot 4.1 / Java 25\nVirtual Threads (Loom)", "Orchestre le pipeline complet :\ningestion → dataset → fine-tuning → RAG.\nExpose l'API REST et les flux SSE.")

        Container(ollama, "spectra-ollama", "Ollama 0.18+", "Inférence LLM (chat, génération).\nCalcul d'embeddings.\nCréation de modèles via /api/create.")

        ContainerDb(chroma, "spectra-chromadb", "ChromaDB — API v2", "Base de données vectorielle.\nStocke les chunks et leurs embeddings.\nCollection : spectra_documents.")
    }

    System_Ext(scripts, "Scripts d'entraînement", "train.sh / train_host.py\nVolume monté : ./scripts\n(GPU · CPU · Simulation)")

    SystemDb_Ext(fs, "Système de fichiers hôte", "Volumes Docker montés\ndata/documents · data/dataset\ndata/fine-tuning")

    Rel(user, frontend, "HTTP :80", "Navigateur")
    Rel(frontend, api, "REST /api/*\nSSE /api/sse/*", "HTTP :8080")
    Rel(api, ollama, "POST /api/embeddings\nPOST /api/chat\nPOST /api/create", "HTTP :11434")
    Rel(api, chroma, "REST API v2\n/api/v2/collections/*", "HTTP :8000")
    Rel(api, scripts, "ProcessBuilder\nstdin → stdout", "Sous-processus")
    Rel(api, fs, "Lecture / Écriture\nPDF · JSONL · adapters · rapports", "I/O fichiers")
```
