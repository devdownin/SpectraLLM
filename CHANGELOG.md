# Changelog — Spectra

Toutes les modifications notables sont documentées dans ce fichier.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.0.0/)
Versionnage : [Semantic Versioning](https://semver.org/lang/fr/)

---

## [1.9.0] — 2026-04-22

### Correctifs — Bugs, sécurité, fiabilisation

#### Pipeline chat / RAG

- **`POST /api/query/stream`** : nouvel endpoint SSE manquant — le Playground était entièrement cassé (404 à chaque message)
  - `RagService.retrieveContext()` extrait la phase retrieval (embed → ChromaDB → re-rank → build sources) ; `query()` et `queryStream()` s'appuient dessus
  - `queryStream()` émet les events SSE `sources → token* → done | error` via `LlmChatClient.chatStream()`
  - Timeout de garde `Flux.timeout(generateTimeout)` côté backend + `AbortController(120s)` côté frontend
- **Temperature & Top-P câblés** : les sliders du Playground étaient sauvegardés mais jamais transmis au LLM
  - `QueryRequest` : nouveaux champs optionnels `temperature` (0.0–2.0, défaut 0.7) et `topP` (0.0–1.0, défaut 0.9)
  - `LlmChatClient.chatStream(String, String, float, float)` : nouvelle surcharge (default fallback dans l'interface)
  - `LlamaCppChatClient` transmet `temperature` et `top_p` à llama-server

#### Ingestion

- **URL encoding** : `UrlFetcherService` — URL de browserless encodée via `UriComponentsBuilder` (fix injection via query param)
- **Validation de schéma URL** : rejet des schémas non-http/https avant tout appel réseau
- **Markdown tables** : `TextCleanerService` préserve les séparateurs de tableaux Markdown lors du nettoyage
- **ZIP depth** : `IngestionService` — limite à 3 niveaux d'imbrication pour prévenir les ZIP bombs

#### GlobalExceptionHandler

- `LlmUnavailableException` → HTTP **503** (était 500 via handler générique)
- `MethodArgumentNotValidException` → HTTP **400** avec détail champ par champ (était 500)

#### ChromaDB

- Cache `ConcurrentHashMap` nom → collectionId : élimine un aller-retour réseau par requête RAG
- `deleteBySource()` : filtre `where` ChromaDB pour ne charger que les IDs concernés (était fullscan)
- Timeouts différenciés : `TIMEOUT_ADD=60s`, `TIMEOUT_QUERY=15s`, `TIMEOUT_BULK_GET=30s`, `TIMEOUT_DEFAULT=10s`
- Null-guard sur `getOrCreateCollection()` + validation du nom (3-63 chars, pattern ChromaDB)

#### Fine-tuning & Dataset

- `DatasetGeneratorService.generatedPairs` : reset complet + réécriture du fichier JSONL à chaque `submit()` (évite l'accumulation de doublons entre runs)
- `POST /api/dataset/generate?maxChunks=N` : paramètre `maxChunks` désormais fonctionnel (était ignoré)
- Protection contre la génération concurrente : `AtomicBoolean generationRunning` → HTTP 409 si déjà en cours
- `DatasetGeneratorService` : persistance JSONL au démarrage + confiance dynamique des paires

#### Asynchrone

- `AsyncConfig` : `ThreadPoolTaskExecutor` → `SimpleAsyncTaskExecutor` avec `setVirtualThreads(true)` — les tâches `@Async` utilisent désormais les virtual threads Project Loom cohérents avec `spring.threads.virtual.enabled: true`

#### Frontend — robustesse

- **Playground** : historique localStorage limité à 50 messages + catch `QuotaExceededError`
- **Datasets** : tous les `setInterval` de polling trackés dans un `useRef` + cleanup complet au unmount du composant
- **Datasets / Comparison** : arrêt automatique du polling après 5 échecs réseau consécutifs

#### Autres correctifs

- `LlamaCppChatClient.checkHealth()` : `activeModelLoaded=false` → HTTP 200 avec status `model-not-loaded` (était HTTP 500)
- `StatusController` : utilise `LlmChatClient` (interface) + `EmbeddingClient` au lieu de `LlmClient` (legacy)
- `ConfigController.setModel()` : catch `IllegalStateException` → HTTP 400 (était 500)
- `LlmFitService.installModel()` : vérification `process.exitValue()` après timeout forcibly destroy (était NPE)
- `FineTuningService` : `ProcessBuilder.directory(workDir)` pour les scripts d'entraînement
- `DpoGenerationService` / `EvaluationService` : null-guard sur self-injection `@Lazy` (`self != null ? self : this`)
- `FineTuningRequest.baseModel` : annotation `@Pattern` pour bloquer les injections de commande

---

## [1.8.0] — 2026-04-15

### Infra — Séparation chat/embed, ChromaDB v2, healthchecks

#### Docker Compose

- `llm-server` → deux services dédiés : **`llm-chat`** (port 8081, chat) et **`llm-embed`** (port 8082, embeddings)
- Nouveau service **`model-init`** : vérifie la présence et la taille (>1 Mo) des fichiers GGUF avant de démarrer les serveurs LLM ; affiche les commandes `huggingface-cli` / `wget` et interrompt la stack si un modèle est absent
- Variables renommées : `LLM_MODEL_FILE` → `LLM_CHAT_MODEL_FILE` + `LLM_EMBED_MODEL_FILE` ; `LLM_MODEL_NAME` → `LLM_CHAT_MODEL_NAME` + `LLM_EMBED_MODEL_NAME`
- `SPECTRA_LLM_PROVIDER=llama-cpp` désormais explicite dans `.env` et `docker-compose.yml` (était absent → fallback silencieux sur `ollama`)
- `SPECTRA_LLM_CHAT_BASE_URL=http://llm-chat:8081` et `SPECTRA_LLM_EMBEDDING_BASE_URL=http://llm-embed:8082` ajoutés
- Chaîne de dépendances complète : `model-init` → `llm-chat` + `llm-embed` (healthy) + `chromadb` (started) → `spectra-api` (healthy) → `frontend`
- `docker-compose.gpu.yml` mis à jour pour surcharger `llm-chat` et `llm-embed` avec l'image CUDA
- `detect-env.sh` et `detect-env.bat` mis à jour pour inclure toutes les nouvelles variables dans le `.env` généré automatiquement

#### ChromaDB API v2

- `ChromaDbClient.java` migré de l'API v1 (supprimée, HTTP 410) vers **l'API v2**
- Toutes les URLs passent par `/api/v2/tenants/default_tenant/databases/default_database/collections/…`
- Heartbeat : `/api/v1/heartbeat` → `/api/v2/heartbeat`
- Healthcheck ChromaDB : `curl` absent de l'image → remplacé par `/dev/tcp` bash natif sur `/api/v2/heartbeat`

#### Healthchecks

- `spectra-api` : `curl` absent dans `eclipse-temurin:21-jre` → remplacé par `wget -qO-` sur `/actuator/health`
- `application.yml` : valeurs par défaut `llm-server:8081` → `llm-chat:8081` ; provider par défaut `ollama` → `llama-cpp` ; ajout des blocs `chat.base-url` et `embedding.base-url`

---

## [1.7.0] — 2026-04-12

### Ajouté — Agentic RAG / Boucle ReAct (I4)

#### I4 — Agentic RAG (boucle de raisonnement ReAct)

- `AgenticRagService` : boucle THOUGHT → ACTION (SEARCH | ANSWER) activée via `SPECTRA_AGENTIC_RAG_ENABLED=true`
- Le LLM reçoit un prompt structuré en deux formats exclusifs (`ACTION: SEARCH` + `QUERY: ...` ou `ACTION: ANSWER` + `RESPONSE: ...`)
- Sur `SEARCH` : embed de la requête affinée → retrieval vectoriel ou hybride (I2) → déduplication par texte (`Set<String>`) → enrichissement du contexte
- Sur `ANSWER` : extraction du bloc `RESPONSE:` → sortie de boucle
- Boucle bornée par `max-iterations` (défaut 3) ; fallback sur génération directe si budget épuisé
- Garde-fous : format LLM inattendu → réponse brute utilisée ; `SEARCH` sans `QUERY` → sortie propre ; contexte vide → message d'indisponibilité
- Compatible I1 (re-ranking) et I2 (hybrid search) : le pipeline d'enrichissement initial s'exécute avant la boucle ; les chunks enrichis sont transmis en entrée à `AgenticRagService`
- `RagService` injecte `Optional<AgenticRagService>` et délègue après le retrieval/re-ranking quand le bean est présent
- `QueryResponse` : nouveaux champs `agenticApplied` (boolean) et `agenticIterations` (int)
- Config : `spectra.agentic-rag.{enabled, max-iterations, initial-top-k}` + variables d'env `SPECTRA_AGENTIC_RAG_ENABLED`, `SPECTRA_AGENTIC_MAX_ITERATIONS`, `SPECTRA_AGENTIC_INITIAL_TOP_K`
- Désactivé par défaut — aucun impact sur les déploiements existants

---

## [1.6.0] — 2026-04-12

### Ajouté — Layout-Aware Parsing PDF (I3)

#### I3 — Parsing PDF avec conscience de la mise en page

- Nouveau microservice Python `docparser/` (FastAPI + `pymupdf4llm`) — convertit les PDF en Markdown structuré : titres `#`/`##`, tableaux `| col |`, listes, blocs de code
- Upgrade optionnel Docling (IBM) via `USE_DOCLING=true` (modèles IA, ~500 Mo, meilleure précision sur tableaux complexes)
- `LayoutParserClient` — client HTTP Spring WebClient multipart (`POST /parse`), timeout 120 s configurable, 50 Mo d'in-memory buffer
- `LayoutAwarePdfExtractor` — remplace `PdfExtractor` quand `spectra.layout-parser.enabled=true` ; fallback automatique vers PDFBox si docparser indisponible
- `PdfExtractor` rendu conditionnel (`@ConditionalOnProperty havingValue="false" matchIfMissing=true`) — aucun conflit de factory, comportement par défaut inchangé
- Métadonnée `parser` (valeur : `pymupdf4llm` | `docling` | `pymupdf4llm-fallback`) ajoutée à chaque chunk issu d'un PDF traité par docparser
- Métadonnée `layoutAware: true` distingue les chunks avec parsing structuré des chunks PDFBox
- Service `docparser` ajouté dans `docker-compose.yml` (port hôte **8003**, healthcheck)
- Activation : `SPECTRA_LAYOUT_PARSER_ENABLED=true` (désactivé par défaut — aucun impact sur les déploiements existants)
- Config : `spectra.layout-parser.{enabled, base-url, timeout-seconds}` + variables d'env

---

## [1.5.0] — 2026-04-12

### Ajouté — Hybrid Search BM25 + Vecteurs (I2)

#### I2 — Recherche hybride (Reciprocal Rank Fusion)

- `BM25Index` : implémentation BM25Okapi en Java pur, thread-safe (`ReentrantReadWriteLock`), tokeniseur Unicode adapté au français (accents, ligatures)
- `FtsService` : gère un index BM25 par collection ChromaDB — rebuild asynchrone depuis ChromaDB au démarrage (`@PostConstruct`), mis à jour à chaque ingestion/suppression
- `HybridSearchService` : lance en parallèle via `CompletableFuture` la recherche vectorielle (ChromaDB) et la recherche BM25 (`FtsService`), fusionne via RRF (k=60, poids BM25 configurable)
- `IngestionTaskExecutor` : appelle `FtsService.indexChunks()` après chaque ajout dans ChromaDB
- `DocumentController` : appelle `FtsService.removeBySource()` à chaque suppression
- `QueryResponse` : nouveau champ `hybridSearchApplied` (boolean) ; `Source` enrichi de `bm25Score` (Float)
- Activation : `SPECTRA_HYBRID_SEARCH_ENABLED=true` (désactivé par défaut)
- Compatible avec le re-ranking I1 : hybrid search → re-ranking Cross-Encoder s'enchaînent automatiquement si les deux sont activés
- Config : `spectra.hybrid-search.{enabled, top-bm25, bm25-weight}` + variables d'env correspondantes

---

## [1.4.0] — 2026-04-12

### Ajouté — Re-ranking Cross-Encoder (I1)

#### I1 — Re-ranking post-retrieval

- Nouveau microservice Python `reranker/` (FastAPI + `sentence-transformers`) : modèle Cross-Encoder configurable via `RERANKER_MODEL` (défaut : `cross-encoder/ms-marco-MiniLM-L-6-v2`, compatible CPU)
- `RerankerClient` interface + `CrossEncoderRerankerClient` implémentation HTTP (activée uniquement si `spectra.reranker.enabled=true`)
- `RagService` modifié : récupère `topCandidates` chunks de ChromaDB (défaut 20), les re-classe via le service Cross-Encoder, ne retient que les `maxContextChunks` meilleurs pour le LLM
- `QueryRequest` : nouveau champ `topCandidates` (1–100, défaut 20)
- `QueryResponse` : nouveau champ `rerankApplied` (boolean) ; `Source` enrichi d'un champ `rerankScore` (Float)
- Service `reranker` ajouté dans `docker-compose.yml` (port hôte **8002**, healthcheck Python)
- Activation : variable d'environnement `SPECTRA_RERANKER_ENABLED=true` (désactivé par défaut — aucun impact sur les déploiements existants)
- `SpectraProperties.RerankerProperties` : `enabled`, `baseUrl`, `model`, `timeoutSeconds`, `topCandidates` — tous configurables via `application.yml` ou variables d'environnement

### Corrigé
- `ModelHubController.installModel` : troisième argument `autoActivate=false` manquant
- `BenchmarkService.query` : appel `QueryRequest` mis à jour pour correspondre à la nouvelle signature (4 champs)

---

## [1.3.0] — 2026-04-11

### Ajouté — Observabilité, benchmarks et déploiement K8s

#### Benchmark API
- `GET /api/benchmark/embedding` : mesure le débit de vectorisation (chunks/s, ms/chunk)
- `GET /api/benchmark/llm` : mesure la latence de génération LLM pure (tokens/s, time-to-first-token)
- `GET /api/benchmark/rag` : mesure la latence RAG bout-en-bout (embed + ChromaDB + LLM)
- `GET /api/benchmark` : suite complète — retourne les 3 mesures dans un seul appel
- `BenchmarkService` : logique de mesure isolée, configuré pour ne pas impacter la production

#### SSE temps réel
- `GET /api/sse/system-load` : flux SSE émettant toutes les secondes l'utilisation CPU/heap JVM
- `GET /api/sse/training-logs` : flux SSE des logs de fine-tuning en temps réel
- `TrainingLogBroadcaster` : canal `Sinks.Many` multicast avec buffer 500 messages ; `FineTuningService` publie chaque ligne stdout/stderr du script

#### Configuration à chaud
- `GET /api/config/model` : retourne le modèle chat actif (depuis `ModelRegistryService`)
- `POST /api/config/model` : bascule le modèle chat sans redémarrage (met à jour `registry.json`)

#### Mode batch programmatique
- `BatchService` : orchestre le pipeline complet ingest-local → dataset → fine-tuning depuis le code Java
- `BatchRunner` : CLI `--batch` pour déclencher le pipeline en ligne de commande (utilisé par `pipeline.bat` / `pipeline.sh`)

#### Déploiement Kubernetes
- Manifests `k8s/` : 10 fichiers YAML + `kustomization.yaml` pour un déploiement complet sur tout cluster K8s ≥ 1.26
  - Namespace, ConfigMap, 4 PVCs, 6 Deployments (spectra-api, spectra-frontend, llm-chat, llm-embed, chromadb, browserless)
  - Ingress unique — seul le frontend est exposé ; les services internes restent en ClusterIP
- `k8s/README.md` : procédure de déploiement + commandes `kubectl apply -k` / rollback

#### Setup automatisé
- `setup.sh` / `setup.bat` : création des répertoires `data/`, détection du profil serveur (via `detect-env`), aide au téléchargement du modèle GGUF initial
- `scripts/requirements.txt` : dépendances Python versionnées (unsloth, trl, transformers, datasets, bitsandbytes, accelerate) pour le fine-tuning QLoRA

---

## [1.2.0] — 2026-04-06

### Ajouté — Fonctionnalités Axolotl (H1–H4)

#### H1 — Alignement DPO (Direct Preference Optimization)
- `DpoGenerationService` : génère des paires (choisi/rejeté) en demandant au LLM une réponse intentionnellement erronée
- Nouveaux endpoints : `POST /api/dataset/dpo/generate`, `GET /api/dataset/dpo/generate/{taskId}`, `GET /api/dataset/dpo/stats`
- `train_host.py` + `train.sh` : flag `--dpo` → `DPOTrainer` (trl), fallback SFT automatique si trl < 0.4
- Checkbox "Alignement DPO" dans le formulaire Fine-Tuning
- `FineTuningService` : export dataset DPO si `dpoEnabled=true`

#### H2 — Évaluation automatique LLM-as-a-judge
- `EvaluationService` : échantillonne 5 % du dataset (min 5, max 50 paires), interroge le modèle actif, utilise le même modèle comme juge (note 1–10 + justification JSON)
- Résultats persistés dans `evaluations.json` (survive au redémarrage)
- Nouveaux endpoints : `POST /api/evaluation`, `GET /api/evaluation`, `GET /api/evaluation/{evalId}`
- Page `Comparison.tsx` entièrement réécrite : score global, barres par catégorie, détail question/réponse/justification

#### H3 — Multipacking
- `PackedDataset` dans `train_host.py` : greedy bin-packing des séquences courtes, affiche le ratio d'utilisation
- Flag `--packing` dans `train_host.py` et variable `$8` dans `train.sh`
- `SFTTrainer` avec `packing=True` (GPU)
- Champ `packingEnabled` dans `FineTuningRequest` + checkbox dans l'interface

#### H4 — Recettes d'entraînement YAML
- `RecipeController` : `GET /api/fine-tuning/recipes`, `GET /api/fine-tuning/recipes/{name}`, `POST /api/fine-tuning/recipe/export`
- 3 recettes prédéfinies : `cpu-rapide.yml`, `gpu-qualite.yml`, `dpo-alignement.yml`
- Sélecteur de recettes dans `FineTuning.tsx` + bouton Export (télécharge `.yml`)
- Sérialisation SnakeYAML (dépendance déjà présente via Spring Boot)

### Corrigé
- `LlamaCppRuntimeOrchestrator` : `--flash-attn on` au lieu du flag nu `--flash-attn` (llama-server attend une valeur)

### Modifié
- `pipeline.bat` : support des flags `--packing` et `--dpo` (transmission à `train_host.py`)
- Documentation : `IMPROVEMENTS.md`, `README.md`, `USER_MANUAL.md` mis à jour avec H1–H4

---

## [1.1.0] — 2026-04-02

### Ajouté — Migration llama-cpp
- Inférence chat et embedding migrées de Ollama vers llama-server (llama-cpp-turboquant)
- `LlamaCppChatClient` + `LlamaCppEmbeddingClient` : clients HTTP OpenAI-compatible
- `LlamaCppRuntimeOrchestrator` : auto-détection CPU/RAM/GPU → paramètres llama-server optimaux
- `GET /api/config/resources` + `POST /api/config/resources/refresh`
- Healthchecks Docker : `wget` sur `/health` (llama-server), retry avec `start_period`
- Cache KV f16 pour le serveur d'embedding
- Streaming SSE (`/api/query/stream`) : `sources` → `token*` → `done | error`
- Circuit breaker sur les appels LLM (3 tentatives, backoff exponentiel)
- Sélecteur de modèle dans le Playground

### Ajouté — Ingestion URL
- `UrlFetcherService` : HEAD → content-type → téléchargement direct (PDF/TXT) ou rendu JS (HTML via browserless/chrome)
- `POST /api/ingest/url` avec `{"urls": [...]}`
- Service `spectra-browserless` dans docker-compose.yml

### Corrigé
- Healthchecks Docker alignés sur les contraintes réelles de chaque image (pas de `curl` dans certains conteneurs)

---

## [1.0.0] — 2026-04-01

### Ajouté — Audit complet et corrections

#### Fiabilité (A1–A6)
- **A1** Persistance H2 : tâches d'ingestion, jobs fine-tuning, paires générées survivent au redémarrage
- **A2** Déduplication SHA-256 à l'ingestion (`?force=true` disponible)
- **A3** Timeout LLM configurable (`spectra.ollama.generate-timeout-minutes: 10`)
- **A4** Support `.doc` (HWPFDocument/POI Scratchpad) en plus de `.docx`
- **A5** Race condition `generatedPairs.clear()` : liste locale par tâche, fusion atomique en fin
- **A6** Pagination ChromaDB `getAllDocuments()` (limit=500/offset)

#### Performance (B1–B4)
- **B1** Cache de l'ID de collection ChromaDB (évite un aller-retour HTTP par requête)
- **B2** Parallélisation des appels LLM (résumé + classification + cas négatif en Virtual Threads)
- **B3** Taille de batch d'embeddings configurable (`spectra.ollama.embedding-batch-size`)
- **B4** Constante collection `"spectra_documents"` centralisée dans `ChromaDbProperties`

#### Qualité dataset (C1–C4)
- **C1** `sourceFile` correctement propagé dans les métadonnées ChromaDB
- **C2** Parsing JSON robuste : nettoyage balises Markdown avant extraction
- **C3** Filtre de qualité RAG : seuil distance cosinus configurable (`spectra.rag.max-distance-threshold: 0.8`)
- **C4** Équilibrage des paires par source (max 20 % par fichier source)

#### Observabilité (D1–D2)
- **D1** Métriques Micrometer/Prometheus : `spectra.ingestion.chunks.total`, `spectra.rag.query.duration`, etc.
- **D2** Logs heap mémoire rétrogradés en DEBUG

#### Sécurité (E1–E3)
- **E1** Filtre `ApiKeyFilter` sur `/api/**` si `SPECTRA_API_KEY` défini
- **E2** Limite upload : `max-file-size: 100MB`, `max-request-size: 500MB`
- **E3** Sanitisation des noms de fichiers uploadés

#### Fonctionnalités (F1–F4)
- **F1** `GET /api/documents` + `DELETE /api/documents/{sourceFile}`
- **F2** Reprise de génération interrompue (WIP JSONL + progress JSON)
- **F3** Support Avro (Apache Avro 1.12.0)
- **F4** Collections multiples ChromaDB (`?collection=` sur ingest et query)

#### Infrastructure (G1–G3)
- **G1** `.gitattributes` : LF pour scripts/java/yml, CRLF pour .bat, binaire pour .gguf
- **G2** `.gitignore` : données, modèles, artefacts Python
- **G3** `GET /api/status/deep` : healthcheck fonctionnel ChromaDB + LLM

---

## [0.9.0] — 2026-03-25

### Ajouté — Fonctionnalités initiales
- Pipeline complet : ingestion → dataset → fine-tuning → RAG
- Inférence via Ollama (phi3, mistral, llama3)
- ChromaDB v2 pour le stockage vectoriel
- Interface React (Vite + Tailwind) : Dashboard, Datasets, Fine-Tuning, Playground
- Scripts : `start.bat`, `stop.bat`, `adddoc.bat`, `pipeline.bat`
- Docker Compose multi-services
- Swagger UI

---

*Spectra — Transformez vos documents en intelligence artificielle locale.*
