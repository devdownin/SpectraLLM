# Référence API Spectra

Spectra fournit une API REST complète pour interagir avec la plateforme de manière programmatique. Cela vous permet d'intégrer l'ingestion de documents, les requêtes RAG et le déclenchement de fine-tuning directement dans vos propres applications ou pipelines CI/CD.

## OpenAPI / Swagger UI

Le backend de Spectra est construit avec Spring Boot et utilise `springdoc-openapi` pour générer automatiquement la documentation de l'API basée sur le code source.

Lorsque vous exécutez la stack Spectra localement (via `docker compose up`), vous pouvez accéder à l'interface interactive Swagger UI et aux spécifications OpenAPI brutes directement depuis le service backend.

### Accéder à l'interface interactive

Vous pouvez explorer et tester les endpoints de l'API de manière interactive via l'interface Swagger UI.
Assurez-vous que votre backend (`spectra-api`) est en cours d'exécution, puis accédez à :

👉 **[http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)**

### Spécification OpenAPI brute

Si vous avez besoin de générer des clients API (par exemple, en utilisant OpenAPI Generator) ou d'intégrer les définitions d'API dans des outils comme Postman, vous pouvez récupérer les fichiers OpenAPI bruts au format JSON ou YAML :

- **Format JSON :** [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
- **Format YAML :** [http://localhost:8080/v3/api-docs.yaml](http://localhost:8080/v3/api-docs.yaml)

## Aperçu des endpoints clés

*Remarque : Consultez Swagger UI pour obtenir les détails complets des schémas, les paramètres requis et les structures de réponse.*

### Ingestion & Datasets
*   `POST /api/ingest` – Uploader des documents (PDF, DOCX, etc.) pour qu'ils soient analysés, découpés et intégrés (embeddings) dans ChromaDB.
*   `POST /api/dataset/generate` – Déclencher la génération en arrière-plan d'un dataset synthétique de Q&A basé sur les documents ingérés.
*   `GET /api/dataset/stats` – Récupérer les statistiques sur la taille et la composition du dataset actuel.

### Génération & RAG
*   `POST /api/query` – Exécuter une requête RAG (Retrieval-Augmented Generation) sur la base de connaissances.
*   `POST /api/chat` – Endpoint de chat direct (sans RAG).

### Fine-Tuning
*   `POST /api/finetune/start` – Lancer une tâche de fine-tuning local (QLoRA/DPO) en utilisant le dataset actuel.
*   `GET /api/finetune/jobs` – Lister les tâches de fine-tuning (historique et en cours).

### Système & Télémétrie (SSE)
*   `GET /api/sse/system-load` – Flux Server-Sent Events (SSE) fournissant les métriques de charge CPU/GPU en temps réel.
*   `GET /api/sse/training-logs` – Flux SSE diffusant en direct les logs (stdout/stderr) d'une tâche de fine-tuning en cours.
*   `GET /api/status` – Statut de santé agrégé du backend, d'Ollama et de ChromaDB.
