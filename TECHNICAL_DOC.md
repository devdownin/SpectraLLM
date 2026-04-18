# Documentation Technique : Spectra (Domain LLM Builder)

Spectra est une plateforme de construction et d'optimisation de modèles de langage (LLM) spécialisés à partir de documents métier. Elle transforme des fichiers hétérogènes en une base de connaissances structurée et un modèle fine-tuné, entièrement en local.

L'inférence repose sur [llama-cpp-turboquant](https://github.com/TheTom/llama-cpp-turboquant), un fork de llama.cpp exposant une API compatible OpenAI. Les modèles sont au format **GGUF**.

---

## 1. Architecture Globale

### Stack technique

| Couche | Technologie | Version | Notes |
|--------|-------------|---------|-------|
| Backend | Java 21 / Spring Boot | 3.4.3 | Virtual Threads (Project Loom) |
| Frontend | React 19 / Vite / Tailwind CSS | — | Servi par nginx dans Docker |
| Inférence LLM (chat) | llama-cpp-turboquant (`llama-server`) | master | API OpenAI-compatible `/v1/chat/completions` |
| Inférence LLM (embeddings) | llama-cpp-turboquant (`llama-server`) | master | API OpenAI-compatible `/v1/embeddings` |
| Format modèle | GGUF | v3 | Standard llama.cpp, quantization Q4–Q8 |
| Base vectorielle | ChromaDB | latest | API v2 uniquement |
| Extraction PDF | `pdftotext` (poppler-utils) | — | Sous-processus, hors heap JVM |
| Extraction DOCX | Apache POI (XWPF) | — | `.docx` uniquement |
| Extraction HTML | jsoup 1.18.1 | — | Parsing DOM, suppression éléments non-contenu |
| Rendu JavaScript | browserless/chrome | latest | Chrome headless via API REST `GET /content?url=` |
| Entraînement GPU | Python 3.10+ / Unsloth / QLoRA | — | Sur l'hôte, optionnel |
| Conteneurisation | Docker & Docker Compose | — | 6 services |

### Services Docker Compose

```
┌──────────────────────────────────────────────────────────────────┐
│                         docker network                            │
│                                                                   │
│  ┌─────────────┐      ┌──────────────────┐                      │
│  │  spectra-   │:80   │   spectra-api    │:8080                 │
│  │  frontend   │─────▶│  Spring Boot     │                      │
│  │  (nginx)    │      │  Java 21 + Loom  │                      │
│  └─────────────┘      └────────┬─────────┘                      │
│                                │                                  │
│              ┌─────────────────┼───────────────┬──────────┐     │
│              ▼                 ▼               ▼          ▼     │
│     ┌──────────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────┐│
│     │ llama-cpp-   │ │ llama-cpp-   │ │chromadb  │ │browser-  ││
│     │ chat         │ │ embed        │ │  :8000   │ │ less     ││
│     │ (llama-server│ │ (llama-server│ └──────────┘ │  :3000   ││
│     │  :8081       │ │  :8082       │              └──────────┘│
│     │  --chat)     │ │  --embeddings│                           │
│     └──────────────┘ └──────────────┘                           │
└──────────────────────────────────────────────────────────────────┘
```

Aucun service interne n'est exposé sur le réseau hôte. Seul `spectra-api` communique avec eux :
- `http://llm-chat:8081` — inférence chat
- `http://llm-embed:8082` — embeddings
- `http://chromadb:8000` — base vectorielle (API v2)
- `http://browserless:3000` — rendu Chrome headless (URL ingestion)
- `http://reranker:8000` — Cross-Encoder re-ranking (optionnel, port hôte 8002)
- `http://docparser:8001` — parsing PDF layout-aware (optionnel, port hôte 8003)

**Health checks** : `llm-chat` et `llm-embed` utilisent `curl -sf http://localhost:{port}/health` (curl disponible dans l'image llama.cpp). `spectra-api` utilise `wget -qO-` sur `/actuator/health` (wget disponible dans l'image eclipse-temurin). `chromadb` utilise `/dev/tcp` bash natif sur `/api/v2/heartbeat` (ni curl ni wget disponibles dans l'image ChromaDB). La dépendance de démarrage suit la chaîne : `model-init` → `llm-chat` + `llm-embed` → `spectra-api` → `frontend`.

### Diagramme de flux de données

```
[Documents PDF/DOCX/JSON/XML/TXT/HTML]     [URLs distantes]
            │                                      │
            │                       ┌──────────────┘
            │                       ▼
            │              [UrlFetcherService]
            │               HEAD → content-type
            │               HTML → GET browserless:3000/content?url=
            │               PDF/TXT → download direct (WebClient)
            │               → InputStream + nom de fichier dérivé
            │                       │
            └───────────────────────┘
                                    │
                                    ▼
[Extraction] PdfExtractor (pdftotext subprocess)
             DocxExtractor (Apache POI XWPF — .docx uniquement)
             HtmlExtractor (jsoup — supprime nav/footer/script/style)
             JsonExtractor / XmlExtractor / TxtExtractor
            │
            ▼
[TextCleanerService] 8 étapes de normalisation
            │
            ▼
[ChunkingService] 512 tokens max, overlap 64 tokens
            │
            ▼
[EmbeddingService] → POST http://llm-embed:8082/v1/embeddings
            │  model="nomic-embed-text", batch size 2048, retry exponentiel (1s/2s/4s)
            ▼
[ChromaDB] API v2 — collection "spectra_documents"
            │
            ▼
[DatasetGeneratorService] → POST http://llm-chat:8081/v1/chat/completions
            │  3–4 appels LLM / chunk (Q&A + résumé + classif + négatifs)
            ▼
[FineTuningService] → scripts/train.sh (GPU/CPU/simulation)
            │  Résultat : data/fine-tuning/merged/model.gguf
            ▼
[ModelRegistryService] → data/models/registry.json
            │  Enregistre le modèle produit avec source, type, alias
            ▼
[RagService] → POST /v1/embeddings (requête) + ChromaDB + POST /v1/chat/completions
            │  Filtre qualité : chunks > seuil distance cosinus exclus du contexte
            ▼
[Réponse sourcée avec durationMs]
```

---

## 2. Provider LLM : llama-cpp-turboquant

### Pourquoi llama-cpp-turboquant

`TheTom/llama-cpp-turboquant` est un fork de `ggml-org/llama.cpp` centré sur les techniques de quantization avancées (1.5-bit à 8-bit). Il expose **la même API OpenAI-compatible** que le projet upstream :

| Endpoint | Usage dans Spectra |
|----------|-------------------|
| `GET /v1/models` | Health check, listing des modèles chargés |
| `POST /v1/chat/completions` | Chat RAG, génération dataset |
| `POST /v1/embeddings` | Vectorisation des chunks et des requêtes |

Cette compatibilité API signifie que les clients `LlamaCppChatClient` et `LlamaCppEmbeddingClient` n'ont aucune dépendance aux spécificités de ce fork — ils fonctionneraient avec n'importe quelle version standard de llama.cpp.

### Architecture provider (abstraction)

```
LlmChatClient (interface)
└── LlamaCppChatClient    ← @ConditionalOnProperty(provider=llama-cpp)

EmbeddingClient (interface)
├── checkHealth() → ServiceStatus  [méthode default — retourne unavailable]
└── LlamaCppEmbeddingClient ← @ConditionalOnProperty(provider=llama-cpp) [override : GET /health]
```

Le provider actif est `llama-cpp` (configuré via `spectra.llm.provider`). Les services métier (`RagService`, `DatasetGeneratorService`, `EmbeddingService`) dépendent des interfaces, pas des implémentations.

### Alias de modèle et cohérence llama-server

llama-server identifie son modèle chargé via le flag `-a <alias>`. Le champ `"model"` des requêtes OpenAI doit correspondre à cet alias — sinon llama-server retourne 400.

La chaîne de cohérence :

```
registry.json
  activeChatModel = "spectra-domain"
        │
        ▼
LlamaCppChatClient.activeModel = "spectra-domain"
        │
        ▼ POST /v1/chat/completions {"model": "spectra-domain", ...}
        │
        ▼
llama-server (llm-chat) démarré avec -a spectra-domain
```

Le même principe s'applique aux embeddings :
- `activeEmbeddingModel = "nomic-embed-text"` dans le registre
- `SPECTRA_LLM_EMBEDDING_MODEL=nomic-embed-text` dans docker-compose
- `llama-server` (llm-embed) démarré avec `-a nomic-embed-text`

### Paramètres llama-server critiques

**Serveur de chat (`llm-chat`) :**

| Flag | Valeur par défaut | Rôle |
|------|------------------|------|
| `-m` | `/fine-tuning/merged/model.gguf` | Chemin du modèle GGUF |
| `-a` | `spectra-domain` | Alias (doit correspondre à `activeChatModel` du registre) |
| `-c` | `8192` | Taille totale du contexte (KV cache) |
| `-np` | `1` | Nombre de slots parallèles |
| `--host` | `0.0.0.0` | Écoute sur toutes les interfaces |
| `--port` | `8080` | Port d'écoute |

> **Contexte par slot = `-c` / `-np`.** Avec `-c 8192 -np 1`, chaque requête dispose de 8192 tokens. Avec `-np 4`, chaque slot n'a que 2048 tokens. Le modèle fine-tuné standard ayant un contexte d'entraînement de 2048 tokens, `-np 1` est la valeur correcte pour utiliser pleinement ce contexte sans dépasser la limite interne du modèle.

**Serveur d'embedding (`llm-embed`) :**

| Flag | Valeur par défaut | Rôle |
|------|------------------|------|
| `-m` | `/models/embed.gguf` | Chemin du modèle GGUF d'embedding |
| `-a` | `nomic-embed-text` | Alias |
| `--embeddings` | — | Active le mode embedding (obligatoire) |
| `-b` | `2048` | Batch size physique (tokens par batch) |
| `-ub` | `2048` | Micro-batch size logique |

> **Pourquoi `-b 2048` ?** La valeur par défaut de llama-server est `-b 512`. Les chunks produits par Spectra peuvent atteindre ~635 tokens (selon la configuration du chunking). Avec `-b 512`, llama-server retourne une erreur `input too large`. `-b 2048` couvre tous les chunks inférieurs à 2048 tokens.

### Registre local des modèles (`ModelRegistryService`)

Le registre est persisté dans `data/models/registry.json` :

```json
{
  "activeChatModel": "spectra-domain",
  "activeEmbeddingModel": "nomic-embed-text",
  "models": [
    {
      "name": "spectra-domain",
      "type": "chat",
      "backend": "llama-cpp",
      "source": "./data/fine-tuning/merged/model.gguf",
      "sourceType": "gguf",
      "systemPrompt": "Tu es un assistant spécialisé...",
      "parameters": {"temperature": 0.3, "top_p": 0.9},
      "createdAt": "2026-03-31T18:45:00Z",
      "provenance": "manual"
    },
    {
      "name": "nomic-embed-text",
      "type": "embedding",
      "backend": "llama-cpp",
      "source": "./data/models/embed.gguf",
      "sourceType": "gguf",
      "parameters": {},
      "createdAt": "2026-03-31T18:45:00Z",
      "provenance": "bootstrap"
    }
  ]
}
```

**`sourceType`** : inféré automatiquement depuis l'extension du fichier source.
- `gguf` : fichier `.gguf` — directement servisable par llama-server
- `alias` : référence logique sans fichier GGUF associé (ex. profil CPU sans poids réels)
- `file` / `directory` : chemin existant sans extension `.gguf`

**`LlamaCppRuntimeOrchestrator`** : optionnel (`runtime.enabled=false` par défaut). Quand activé, il démarre et redémarre automatiquement `llama-server` en local quand le modèle actif change. Non utilisé dans le mode docker-compose avec services séparés.

### `ResourceAdvisorService` — détection des ressources disponibles

`ResourceAdvisorService` est un `@Service` Spring qui réplique côté Java la logique de détection du script `llama-autostart.sh`. Il s'initialise via `@PostConstruct` au démarrage de `spectra-api` et expose le profil détecté au reste de l'application.

**Sources de détection (dans l'ordre de priorité) :**

| Ressource | Source primaire | Fallback |
|-----------|----------------|---------|
| Quota CPU | `/sys/fs/cgroup/cpu.max` (cgroups v2) | `Runtime.getRuntime().availableProcessors()` |
| RAM disponible | `/proc/meminfo` (MemAvailable), plafonnée par `/sys/fs/cgroup/memory.max` | mémoire JVM disponible |
| GPU NVIDIA | Appel `nvidia-smi --query-gpu=memory.total --format=csv,noheader` | — |
| GPU AMD ROCm | Existence de `/dev/kfd` | — |
| GPU Vulkan | Existence de `/dev/dri/renderD128` | — |

**Résultat mis en cache** : après la détection initiale (`@PostConstruct`), le profil est stocké en mémoire. L'endpoint `POST /api/config/resources/refresh` force une nouvelle détection sans redémarrage du conteneur.

**Intégration avec `LlamaCppRuntimeOrchestrator`** : quand le mode runtime est activé (`runtime.enabled=true`), `LlamaCppRuntimeOrchestrator.buildChatCommand()` consulte `ResourceAdvisorService` pour construire les arguments CLI de `llama-server` de façon adaptée à l'environnement courant, plutôt que d'utiliser des valeurs codées en dur.

**Pourquoi dupliquer la logique shell en Java ?** Le service `spectra-api` peut s'exécuter en dehors de Docker (développement local, tests d'intégration). Dans ce contexte, `scripts/llama-autostart.sh` n'est pas invoqué ; `ResourceAdvisorService` prend le relais pour la détection et le calcul des paramètres.

---

## 3. Pipeline de Traitement des Documents

### 3.1 Extraction

**PDF — deux modes selon configuration :**

| Mode | Activé quand | Extracteur Java | Backend Python |
|------|-------------|-----------------|----------------|
| Standard (défaut) | `spectra.layout-parser.enabled=false` | `PdfExtractor` (PDFBox) | — |
| Layout-aware | `spectra.layout-parser.enabled=true` | `LayoutAwarePdfExtractor` | `docparser/` microservice |

**`PdfExtractor`** (mode standard) : Apache PDFBox — extraction textuelle linéaire. Simple et fiable, mais perd la structure des tableaux et les hiérarchies de titres.

**`LayoutAwarePdfExtractor`** (mode layout-aware) : délègue au microservice Python `docparser/` qui retourne du Markdown structuré. Fallback automatique vers PDFBox si le service est indisponible. Les chunks produits ont les métadonnées `parser` et `layoutAware: true`.

**DOCX — `DocxExtractor`** : Apache POI (XWPF). Ne supporte **que** le format `.docx` (XML zippé). Le format `.doc` (binaire OLE2) est explicitement rejeté.

**JSON/XML/TXT** : extracteurs dédiés via Jackson.

**HTML — `HtmlExtractor`** : jsoup 1.18.1. Parsing DOM complet du HTML (encodage UTF-8). Suppressions avant extraction :
- Éléments structurels non-contenu : `script`, `style`, `nav`, `footer`, `header`, `aside`
- Éléments par rôle ARIA : `[role=navigation]`, `[role=banner]`, `[role=complementary]`
- Classes CSS communes : `.nav`, `.menu`, `.sidebar`, `.advertisement`, `.cookie-banner`

Extraction du texte depuis les éléments de contenu uniquement : `h1`–`h6`, `p`, `li`, `td`, `th`, `blockquote`, `pre`, `dt`, `dd`, `figcaption`. Le titre de la page (`<title>`) est ajouté en tête de texte. Fallback : si aucun élément structuré ne produit de texte, `body.text()` est utilisé en dernier recours.

**Résolution de content-type** (`DocumentExtractorFactory`) : basée sur l'extension du fichier ou du nom dérivé de l'URL, jamais sur le Content-Type HTTP d'une réponse.

| Extension | Content-type résolu | Extracteur |
|-----------|--------------------|----|
| `.pdf` | `application/pdf` | `PdfExtractor` (défaut) ou `LayoutAwarePdfExtractor` (si layout-parser activé) |
| `.docx` | `application/vnd.openxmlformats-officedocument...` | `DocxExtractor` |
| `.doc` | `application/msword` | `DocxExtractor` |
| `.json` | `application/json` | `JsonExtractor` |
| `.xml` | `application/xml` | `XmlExtractor` |
| `.txt` | `text/plain` | `TxtExtractor` |
| `.html`, `.htm` | `text/html` | `HtmlExtractor` |
| `.avro` | `application/avro` | `AvroExtractor` |

### 3.2 Service de parsing layout-aware (`docparser/`)

Microservice Python dédié à l'extraction structurée des PDF. Séparé de `spectra-api` pour isoler les dépendances Python lourdes (PyMuPDF, optionnellement Docling) et permettre une mise à l'échelle indépendante.

**Endpoint :**
```
POST /parse
  Body : multipart/form-data, champ "file" = PDF binaire
  Réponse :
  {
    "text":       "# Titre de la section\n\n| Col A | Col B |\n|-------|-------|\n| val 1 | val 2 |\n\nTexte de paragraphe...",
    "page_count": 12,
    "metadata":   {"title": "Manuel d'exploitation", "author": "..."},
    "parser":     "pymupdf4llm"  ← ou "docling" | "pymupdf4llm-fallback"
  }

GET /health
  → {"status": "ok", "parser": "pymupdf4llm"}
```

**Parsers disponibles :**

| Parser | Dépendance | Taille image | Tableaux | Titres | GPU |
|--------|-----------|-------------|---------|--------|-----|
| `pymupdf4llm` (défaut) | PyMuPDF + pymupdf4llm | ~80 Mo | Bonne | Bonne | Non |
| `docling` (optionnel) | IBM Docling + modèles DL | ~600 Mo | Excellente | Excellente | Optionnel |

Pour activer Docling : `USE_DOCLING=true docker compose up --build docparser`

**Sélection du parser (Java → Python) :**

```
DocumentExtractorFactory.getExtractor("application/pdf")
    │
    ├── spectra.layout-parser.enabled=false → PdfExtractor (PDFBox, Spring bean actif)
    │
    └── spectra.layout-parser.enabled=true  → LayoutAwarePdfExtractor (Spring bean actif)
            │
            ├── LayoutParserClient.parse(fileName, bytes)
            │     POST http://docparser:8001/parse (multipart)
            │     ← {text, page_count, metadata, parser}
            │     [timeout: 120s configurable]
            │
            ├── Succès → ExtractedDocument avec text=Markdown, metadata.layoutAware=true
            │
            └── Échec (service hors ligne, timeout, erreur 5xx)
                  → fallback → PdfExtractor.extract() directement (instance locale, sans Spring)
```

**Configuration :**

```yaml
spectra:
  layout-parser:
    enabled:         ${SPECTRA_LAYOUT_PARSER_ENABLED:false}    # désactivé par défaut
    base-url:        ${SPECTRA_LAYOUT_PARSER_URL:http://docparser:8001}
    timeout-seconds: ${SPECTRA_LAYOUT_PARSER_TIMEOUT:120}      # PDFs longs peuvent prendre >30s
```

### 3.3 Ingestion depuis des URLs (`UrlFetcherService` + `UrlIngestionService`)

#### UrlFetcherService — détection et récupération

`UrlFetcherService` est responsable de récupérer le contenu d'une URL distante et de le livrer sous forme d'`InputStream` avec un nom de fichier dérivé (utilisé par `DocumentExtractorFactory` pour choisir l'extracteur).

**Algorithme de détection du content-type :**

```
1. Requête HEAD (timeout 10 s)
   └── Header "Content-Type" reçu ?
       ├── application/pdf → téléchargement direct
       ├── text/plain → téléchargement direct
       ├── application/*word* ou *officedocument* → téléchargement direct
       └── tout autre cas (ou échec HEAD) → traitement HTML
```

**Rendu HTML via browserless :**

```
GET http://browserless:3000/content?url=<url_encodée>
  timeout : 60 s (rendu JS peut prendre du temps)
  réponse : HTML complet après exécution du JavaScript
```

browserless lance une instance Chrome headless, charge l'URL, attend le rendu complet, puis retourne le HTML sérialisé. Cela permet d'ingérer des SPAs (React, Vue, Angular) ou tout site qui construit son contenu via des appels API JavaScript.

**Fallback automatique :** si la requête vers browserless échoue (service arrêté, timeout), `UrlFetcherService` tente un téléchargement HTTP direct de la même URL. Les pages statiques fonctionneront normalement ; les pages dynamiques donneront un HTML partiel ou vide.

**Dérivation du nom de fichier :**
```
URL → dernier segment du path si contient une extension → sinon host + extension
https://example.com/docs/guide.pdf  →  guide.pdf
https://example.com/page            →  example_com.html
```

#### UrlIngestionService — orchestration asynchrone

`UrlIngestionService` orchestre l'ingestion de listes d'URLs en tâche de fond, en réutilisant le registre de tâches partagé d'`IngestionService`.

```
UrlIngestionService.submit(urls)
  │
  ├── ingestionService.registerTask(taskId, urls)  ← tâche visible via GET /api/ingest/{taskId}
  │
  └── Thread.ofVirtual().start(() → {
        updateTask(PROCESSING)
        pour chaque url :
          content = urlFetcher.fetch(url)          ← HEAD + browserless ou direct
          chunks  = ingestionService.ingest(       ← même pipeline que les fichiers
                      filename, inputStream, collectionId)
          totalChunks += chunks
        updateTask(COMPLETED ou FAILED)
      })
```

**Points de conception :**
- Les tâches URL sont enregistrées dans la même `ConcurrentHashMap` qu'`IngestionService` via `registerTask()` / `updateTask()`. Elles sont donc interrogeables avec le même endpoint `GET /api/ingest/{taskId}`.
- Un Virtual Thread par soumission (pas de pool de threads) — cohérent avec l'utilisation de Virtual Threads dans le reste de Spectra.
- Échec partiel : si une URL sur N échoue, les chunks des autres URLs sont tout de même comptabilisés. La tâche passe en `FAILED` uniquement si **aucun** chunk n'a été produit.

### 3.4 Nettoyage (`TextCleanerService`)

Pipeline en 8 étapes :
1. Normalisation Unicode NFC
2. Remplacement des ligatures OCR (`ﬀ`→`ff`, `ﬁ`→`fi`)
3. Suppression des marqueurs de page (`- 12 -`, `— Page 3 —`)
4. Suppression des en-têtes/pieds récurrents (`Confidentiel`, `Page X/Y`)
5. Nettoyage des bordures de tableaux ASCII (`│`, `┃`, `═`)
6. Normalisation des puces (`•`, `●`, `■` → `-`)
7. Normalisation des espaces multiples et sauts de ligne
8. `StringBuilder` en O(n) (évite l'O(n²) de concaténations `String`)

### 3.5 Chunking Sémantique (`ChunkingService`)

- Taille max : 512 tokens (~2048 caractères, approximation 4 car./token)
- Chevauchement : 64 tokens (~256 caractères)
- Algorithme : découpage par paragraphes (`\n\n`), puis par mots si dépassement
- **Garantie anti-boucle infinie** : `offset = nextOffset > offset ? nextOffset : end`

### 3.6 Embeddings et Indexation

- Endpoint : `POST http://llm-embed:8082/v1/embeddings`
- Corps : `{"model": "nomic-embed-text", "input": "<texte>"}`
- Réponse : `{"data": [{"embedding": [...float...]}]}`
- Retry exponentiel : 3 tentatives, délais 1 s / 2 s / 4 s
- Storage : ChromaDB API v2
- **Buffer WebClient étendu** à 16 Mo pour ChromaDB (réponses `getAllDocuments()` dépassent 256 Ko sur 400+ chunks)

> **Compatibilité vectorielle :** les vecteurs produits par `nomic-embed-text-v1.5.Q4_K_M.gguf` ne sont pas interchangeables avec ceux produits par la version Ollama du même modèle (différences de quantization et de normalisation). Tout changement de modèle d'embedding impose une ré-ingestion complète.

---

## 4. Génération de Dataset (`DatasetGeneratorService`)

### Types de paires générées par chunk

| Type | Prompt système | Format JSON |
|------|---------------|-------------|
| Q&A | Expert du domaine | `{"question": "...", "answer": "..."}` |
| Q&A raffinée | Instructeur validant la précision | idem (étape d'auto-correction) |
| Résumé | Expert synthétisant le document | `{"instruction": "...", "summary": "..."}` |
| Classification | Archiviste documentaire | `{"category": "...", "reason": "..."}` |
| Négatif (30 %) | Apprentissage honnêteté LLM | question hors-contexte + réponse "je ne sais pas" |

### Asynchronisme (`@Async` + self-injection)

`@Async` ne fonctionne que via le proxy CGLIB. Un appel `this.generateAsync()` depuis la même instance contourne le proxy. Solution : auto-injection `@Autowired @Lazy` :

```java
@Autowired @Lazy
private DatasetGeneratorService self;

// Appel via le proxy → @Async fonctionne
self.generateAsync(taskId, maxChunks);
```

Même pattern dans `FineTuningService` pour `self.runAsync()`.

---

## 5. Fine-Tuning (`FineTuningService` + `scripts/train.sh`)

### Modes de fonctionnement

`train.sh` détecte automatiquement le backend disponible :

| Condition | Mode | Résultat |
|-----------|------|---------|
| GPU CUDA + Python + unsloth | **GPU** | `adapter.gguf` (QLoRA 4-bit) dans `data/fine-tuning/merged/` |
| CPU + Python + transformers | **CPU** | Dossier `adapter/` (HF PEFT format) |
| Python absent | **Simulation** | `adapter/training_complete.json` + logs epoch simulés |

### Étapes du pipeline fine-tuning

```
PENDING → EXPORTING_DATASET → TRAINING → IMPORTING_MODEL → COMPLETED
                                                 │
                                    ModelRegistryService.registerChatModel()
                                    → data/models/registry.json
```

1. **Export dataset** : filtre les paires par `minConfidence`, écrit `dataset.jsonl`
2. **Entraînement** : `ProcessBuilder` → `./scripts/train.sh`
3. **Enregistrement** : `ModelRegistryService.registerChatModel()` avec source GGUF et métadonnées
4. **Rapport** : génère `REPORT.md` dans le dossier du job

### Telemetry Stream (SSE en temps réel)

`TrainingLogBroadcaster` : Spring `@Service` basé sur `Sinks.Many<Map>` Reactor (multicast, buffer 500 messages). Publié par `FineTuningService` et consommé via `/api/sse/training-logs`.

---

## 6. RAG (`RagService`)

### Flux d'une requête

```
POST /api/query {"question": "...", "maxContextChunks": N, "topCandidates": K}
        │
        ▼
[1] EmbeddingService.embed(question)
      → POST llm-embed:8080/v1/embeddings
      ← vecteur Float[]
        │
        ▼
[2a] Recherche VECTORIELLE (toujours active)
      ChromaDbClient.query(collectionId, vector, topCandidates)
      ← K documents + métadonnées + distances cosinus
      │
      │  (si spectra.hybrid-search.enabled=true : en parallèle avec [2a])
[2b] Recherche BM25 (si hybride activé)
      FtsService.search(question, collection, topBm25)
      ← M documents triés par score BM25
        │
        ▼
[3] Fusion RRF (si hybride activé)
      HybridSearchService : score(d) = w_v/(k+rank_v) + w_bm25/(k+rank_bm25)
      ← topCandidates docs triés par RRF desc
      (si hybride désactivé → on utilise directement les K résultats vectoriels)
        │
        ▼
[4] Re-ranking Cross-Encoder (si spectra.reranker.enabled=true)
      → POST reranker:8000/rerank {"query": "...", "documents": [...K...], "top_n": N}
      ← N indices triés par score Cross-Encoder décroissant
      (si reranker désactivé → on garde les N premiers résultats)
        │
        ▼
[5] Délégation Agentic RAG (si spectra.agentic-rag.enabled=true)
      → AgenticRagService.query(request, contextChunks, ...) — boucle ReAct
        THOUGHT/ACTION: SEARCH → nouveau retrieval → déduplique → enrichit le contexte
        THOUGHT/ACTION: ANSWER → extraction RESPONSE → sortie
      (si agentic désactivé → suite du pipeline standard)
        │
        ▼
[6] Si sources vides → réponse sans LLM ("aucun document pertinent")
    Si sources présentes →
        │
        ▼
[7] Construction du systemPrompt :
      "=== CONTEXTE ===\n[Source: fichier.pdf]\n<texte chunk>\n..."
        │
        ▼
[8] LlamaCppChatClient.chat(systemPrompt, question)
      → POST llm-chat:8080/v1/chat/completions
        {"model": "spectra-domain", "stream": false, "messages": [...]}
      ← answer (String)
        │
        ▼
[9] QueryResponse {answer, sources, durationMs, rerankApplied, hybridSearchApplied,
                   agenticApplied, agenticIterations}
        sources[i] = {text, sourceFile, distance, rerankScore, bm25Score}
```

### Re-ranking Cross-Encoder (`CrossEncoderRerankerClient`)

La recherche vectorielle pure (embeddings cosinus) est rapide mais parfois imprécise : elle compare un vecteur de requête à des vecteurs de chunks pré-calculés, sans modéliser l'interaction directe entre la question et le texte.

Le re-ranking utilise un modèle **Cross-Encoder** (architecture bi-encoder vs cross-encoder) : il reçoit la paire `(question, chunk)` complète et produit un score de pertinence fin.

**Implémentation :**

```
RagService
  ├── ChromaDbClient.query(..., topCandidates=20)   ← large retrieval pool
  ├── CrossEncoderRerankerClient.rerank(q, docs, topN=5)
  │     POST http://reranker:8000/rerank
  │     → Python service (sentence-transformers CrossEncoder)
  │     ← [{index: 2, score: 0.94}, {index: 7, score: 0.81}, ...]
  └── contexte = docs reordonnés [2, 7, ...]  ← top-N
```

**Service Python `reranker/` :**

| Fichier | Rôle |
|---------|------|
| `app.py` | FastAPI — `POST /rerank`, `GET /health` |
| `requirements.txt` | `sentence-transformers`, `torch` (CPU), `fastapi`, `uvicorn` |
| `Dockerfile` | Python 3.11-slim, modèle pré-téléchargé au build (`ARG RERANKER_MODEL`) |

**Modèles recommandés :**

| Modèle | Taille | Langues | Usage |
|--------|--------|---------|-------|
| `cross-encoder/ms-marco-MiniLM-L-6-v2` | ~22 Mo | EN (défaut) | CPU, latence ~50–200 ms |
| `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` | ~120 Mo | Multilingue (FR ✓) | Meilleur pour documents FR |
| `BAAI/bge-reranker-base` | ~280 Mo | EN + ZH | Haute précision |

**Configuration :**

```yaml
spectra:
  reranker:
    enabled: ${SPECTRA_RERANKER_ENABLED:false}   # désactivé par défaut
    base-url: ${SPECTRA_RERANKER_URL:http://reranker:8000}
    model: ${SPECTRA_RERANKER_MODEL:cross-encoder/ms-marco-MiniLM-L-6-v2}
    timeout-seconds: ${SPECTRA_RERANKER_TIMEOUT:30}
    top-candidates: ${SPECTRA_RERANKER_TOP_CANDIDATES:20}  # K initial
```

**Fallback :** si le service reranker est hors ligne ou retourne une erreur, `RagService` continue avec les `maxContextChunks` premiers résultats vectoriels. Le champ `rerankApplied` de la réponse sera `false`.

### Recherche hybride BM25 + Vecteurs (`HybridSearchService`)

La recherche vectorielle pure peut rater des termes techniques précis (codes de procédure, numéros d'article, abréviations métier) dont l'embedding dilue la spécificité. La recherche hybride combine les deux signaux.

**Composants :**

| Classe | Rôle |
|--------|------|
| `BM25Index` | BM25Okapi en mémoire, thread-safe (`ReentrantReadWriteLock`). Tokeniseur Unicode (accents FR inclus). k1=1.5, b=0.75. |
| `FtsService` | Un `BM25Index` par collection. Rebuild asynchrone au démarrage depuis ChromaDB. Mis à jour à chaque ingestion / suppression. |
| `HybridSearchService` | Lance vecteur + BM25 en parallèle (`CompletableFuture`). Fusionne via RRF. Activé par `@ConditionalOnProperty`. |

**Reciprocal Rank Fusion (RRF) :**

```
score_rrf(d) = w_vec   / (k + rank_vec(d))
             + w_bm25  / (k + rank_bm25(d))

k = 60  (constante standard — réduit l'impact des documents très bien classés dans un seul signal)
w_vec  = 1.0 (fixe)
w_bm25 = 1.0 (configurable via spectra.hybrid-search.bm25-weight)
```

Si un document n'apparaît que dans l'un des deux signaux, sa contribution depuis l'autre signal est 0.

**Cycle de vie de l'index BM25 :**

```
Démarrage :
  FtsService.@PostConstruct → CompletableFuture → rebuildCollection(defaultColl)
    ChromaDbClient.getDocumentsPaged(limit=500, offset) × N pages
    → BM25Index.add(id, text, sourceFile)   [rebuild en arrière-plan, sans bloquer]

Ingestion :
  IngestionTaskExecutor.ingestOne()
    → ChromaDbClient.addDocuments()   [vecteurs → ChromaDB]
    → FtsService.indexChunks()        [texte → BM25Index]

Suppression :
  DocumentController.deleteDocument()
    → ChromaDbClient.deleteBySource()   [ChromaDB]
    → FtsService.removeBySource()       [BM25Index]
```

**Configuration :**

```yaml
spectra:
  hybrid-search:
    enabled:    ${SPECTRA_HYBRID_SEARCH_ENABLED:false}  # désactivé par défaut
    top-bm25:   ${SPECTRA_HYBRID_BM25_TOP:20}           # candidats BM25 récupérés
    bm25-weight: ${SPECTRA_HYBRID_BM25_WEIGHT:1.0}      # poids relatif du signal BM25
```

**Combinaison I1 + I2 :** quand les deux sont activés, le pipeline complet est :
`BM25 + Vecteurs → RRF → pool K candidats → Cross-Encoder rerank → top N vers LLM`

**Combinaison I1 + I2 + I4 :** quand les trois sont activés, le re-ranking s'applique au retrieval initial, puis `AgenticRagService` prend le relais avec ce contexte enrichi pour itérer si nécessaire.

### Agentic RAG — boucle de raisonnement ReAct (`AgenticRagService`)

La recherche RAG standard est un pipeline linéaire sans rétroaction : si le contexte initial est insuffisant, aucun mécanisme ne permet au modèle de formuler une requête complémentaire. L'Agentic RAG implémente un schéma **ReAct** (Reasoning and Acting) où le LLM peut demander des recherches supplémentaires avant de répondre.

**Schéma de la boucle :**

```
RagService : retrieval initial → reranking (I1 opt.) → contextChunks₀
    │
    ▼ (si spectra.agentic-rag.enabled=true)
AgenticRagService.query(request, contextChunks₀, ...)
    │
    ▼ Itération 1..maxIterations
    ┌──────────────────────────────────────────────────────┐
    │  Construire contextStr (chunks actuels)              │
    │  LlmClient.chat(REACT_PROMPT, question + context)    │
    │       ↓                                              │
    │  Parser la réponse LLM :                             │
    │    THOUGHT: ...                                      │
    │    ACTION: SEARCH  →  QUERY: <requête affinée>       │
    │          │    embed(query) → retrieval (vector|hybrid)│
    │          │    filtrer chunks déjà vus (dedup texte)  │
    │          │    ajouter nouveaux chunks au contexte    │
    │          └────────────────────── itération suivante  │
    │    ACTION: ANSWER  →  RESPONSE: <réponse finale>    │
    │          └─── sortie de boucle                       │
    └──────────────────────────────────────────────────────┘
    │ (si max itérations atteint sans ANSWER)
    ▼
    Génération directe (fallback systemPrompt standard)
    │
    ▼
QueryResponse {answer, sources, ..., agenticApplied: true, agenticIterations: N}
```

**Prompt ReAct :**

Le LLM reçoit un système structuré en deux formats exclusifs :

```
Format RECHERCHE :
THOUGHT: <raisonnement>
ACTION: SEARCH
QUERY: <requête affinée et distincte>

Format RÉPONSE :
THOUGHT: <raisonnement final>
ACTION: ANSWER
RESPONSE: <réponse complète>
```

Règles incluses dans le prompt : base-toi uniquement sur le contexte ; n'invente rien ; réponds en français.

**Déduplication inter-itérations :** un `Set<String>` accumule les textes de tous les chunks vus depuis le début de la requête. Le retrieval complémentaire récupère `maxChunks × 2` candidats et ne retient que ceux absents du set — garantit que chaque itération apporte une information nouvelle.

**Fallbacks :**
- Format LLM inattendu (pas de `ACTION:`) → la réponse brute est utilisée directement
- `ACTION: SEARCH` sans `QUERY:` → sortie de boucle
- Budget `maxIterations` épuisé → génération directe sur les chunks cumulés
- Aucun chunk initial → message d'indisponibilité sans appel LLM

**Configuration :**

```yaml
spectra:
  agentic-rag:
    enabled:        ${SPECTRA_AGENTIC_RAG_ENABLED:false}   # désactivé par défaut
    max-iterations: ${SPECTRA_AGENTIC_MAX_ITERATIONS:3}    # max tours SEARCH avant réponse forcée
    initial-top-k:  ${SPECTRA_AGENTIC_INITIAL_TOP_K:5}     # chunks initiaux transmis par RagService
```

**Coût en tokens :** chaque itération consomme un appel LLM complet (contexte croissant + question). Avec `max-iterations=3` et 5 chunks par itération, la fenêtre de contexte peut atteindre ~4000 tokens. Assurez-vous que votre modèle a un contexte ≥ 4096 tokens avant d'activer ce mode.

### Contrainte de contexte

Le prompt RAG complet (system prompt + chunks + question) doit tenir dans la fenêtre de contexte du modèle :

```
[system prompt (~100 tokens)]
[chunks : N × ~600 tokens]
[question (~20 tokens)]
[réponse générée (~200–400 tokens)]
```

Avec le modèle fine-tuné (contexte = 2048 tokens) :
- 1 chunk = ~720 tokens utilisés → ~1328 tokens pour la réponse ✅
- 2 chunks = ~1320 tokens utilisés → ~728 tokens pour la réponse ✅
- 3 chunks = ~1920 tokens utilisés → ~128 tokens pour la réponse ⚠️ (réponse tronquée)
- 5 chunks = ~3100 tokens → dépassement → **400 Bad Request** ❌

La valeur recommandée de `maxContextChunks` est donc **2** pour le modèle fine-tuné standard.

---

## 7. Interface Utilisateur (React 19 + Vite + Tailwind)

### Architecture frontend

```
frontend/
├── Dockerfile          ← multi-stage (node:22-alpine → nginx:alpine)
├── nginx.conf          ← proxy /api/* → spectra-api:8080, SSE sans buffering
├── src/
│   ├── pages/
│   │   ├── Datasets.tsx      ← pipeline 3 étapes + polling temps réel
│   │   ├── FineTuning.tsx    ← formulaire + step bar + historique API
│   │   ├── Playground.tsx    ← chat RAG · sélecteur de modèle · sliders temp/top-p
│   │   ├── Dashboard.tsx     ← 3 cartes (Chat · Embed · ChromaDB) + modèle actif
│   │   └── Comparison.tsx    ← tableau comparatif modèles
│   ├── services/api.ts       ← client Axios /api/*
│   ├── hooks/
│   │   ├── useSse.ts         ← EventSource SSE
│   │   └── useStatus.ts      ← polling /api/status (30 s)
│   └── types/api.ts          ← types TypeScript miroir des DTO Java
```

**Dashboard — service health (3 cartes) :**
- `Chat` : trouve le service `llama-cpp` ; affiche `details.activeModel`
- `Embed` : trouve `llm-embed` ; affiche `details.activeModel` ; indicateur secondary (bleu-vert)
- `ChromaDB` : affiche `available` + nombre de chunks depuis les stats dataset

**Dataset Pipelines — ingestion URL :**
- Barre URL sous la zone de dépôt fichiers : `<input type="url">` + bouton **Ingest URL**
- Validation côté frontend : `new URL(trimmed)` — lève une exception si l'URL est syntaxiquement invalide
- Appel : `POST /api/ingest/url` via `ingestApi.ingestUrls([url])`
- Polling : réutilise le même `pollIngest(id, taskId)` à 3 s que les fichiers
- La progression apparaît dans **Live Ingestion Stream** avec `fileName = url`

**Playground — sélecteur de modèle :**
- Au montage : `GET /api/fine-tuning/models` (filtre `type === 'chat'`) + `GET /api/config/model`
- Clic sur un modèle → `POST /api/config/model` + toast : *"Effectif au prochain redémarrage de llm-chat"*
- N'affiche la section que si au moins un modèle de chat est présent dans le registre

### Nginx et Server-Sent Events

```nginx
location /api/sse/ {
    proxy_pass http://spectra-api:8080;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
    proxy_set_header Connection '';
    proxy_http_version 1.1;
    chunked_transfer_encoding on;
}
```

Sans `proxy_buffering off`, nginx accumule les événements SSE et les envoie en bloc — le client ne voit rien pendant la session.

---

## 8. API REST

### Ingestion

```
POST /api/ingest
  Corps : multipart/form-data, champ "files" (1..N fichiers)
  Réponse : {"taskId": "...", "status": "PENDING"}

POST /api/ingest/url
  Corps : {"urls": ["https://...", "https://..."]}  — max 20 URLs
  Réponse : {"taskId": "...", "status": "PENDING", "files": ["https://..."]}
  Note : "files" contient les URLs soumises (même structure que pour les fichiers)

GET /api/ingest/{taskId}
  Réponse : {"status": "COMPLETED|FAILED|PROCESSING",
             "chunksCreated": N, "files": [...], "error": null}
  Note : fonctionne pour les tâches fichiers ET les tâches URL (registre partagé)

GET /api/ingest
  Réponse : liste de toutes les tâches d'ingestion actives (fichiers + URLs)

GET /api/ingest/files
  Réponse : historique des fichiers ingérés (depuis la base H2)
```

### Dataset

```
POST /api/dataset/generate?maxChunks=N
  maxChunks : nombre de chunks à traiter (0 = tous)
  Réponse : {"taskId": "...", "status": "PENDING"}

GET /api/dataset/generate/{taskId}
  Réponse : {"status": "...", "pairsGenerated": N,
             "chunksProcessed": N, "totalChunks": N}

GET /api/dataset/stats
GET /api/dataset/export (→ fichier JSONL)
```

### Fine-tuning et Modèles

```
POST /api/fine-tuning
  Corps : {"modelName": "spectra-domain", "baseModel": "phi3",
           "loraRank": 64, "loraAlpha": 128, "epochs": 3,
           "learningRate": 2e-4, "minConfidence": 0.8}

GET /api/fine-tuning/models
  Réponse : liste des modèles dans registry.json

POST /api/fine-tuning/models/register
  Corps : {"name": "mon-modele", "type": "chat",
           "source": "./data/models/mon-modele.gguf", "activate": true}
  → Enregistre un GGUF dans le registre local sans passer par un daemon

POST /api/fine-tuning/models/{name}/pull
  → Non supporté avec llama-cpp (lève UnsupportedOperationException)
    Les modèles doivent être gérés localement sous forme de GGUF
```

### Statut et RAG

```
GET /api/status
  Réponse :
  {
    "services": [
      {
        "name": "llama-cpp",                      ← serveur de chat
        "url": "http://llm-chat:8081",
        "available": true,
        "details": {
          "activeModel": "spectra-domain",
          "activeModelLoaded": true,
          "availableModels": ["spectra-domain"],
          "registeredModels": [...],
          "runtime": {"enabled": false, "running": false}
        }
      },
      {
        "name": "llm-embed",                ← serveur d'embedding
        "url": "http://llm-embed:8082",
        "available": true,
        "version": "ok",
        "details": {
          "activeModel": "nomic-embed-text",
          "serverStatus": "ok"
        }
      },
      {
        "name": "chromadb",
        "available": true
      }
    ]
  }

GET /api/status/deep
  Vérifie les 3 services (chat + embed + chromadb)
  → 200 {"status": "UP", ...} si tous disponibles
  → 503 {"status": "DOWN", ...} si au moins un est down

POST /api/query
  Corps : {
    "question": "...",
    "maxContextChunks": 2,      ← chunks finaux passés au LLM (défaut 5, max 20)
    "topCandidates": 20,        ← candidats récupérés avant re-ranking (défaut 20, max 100)
    "collection": "optional"    ← collection ChromaDB cible (défaut : collection configurée)
  }
  Réponse : {
    "answer": "...",
    "sources": [{"text": "...", "sourceFile": "...", "distance": 0.42, "rerankScore": 0.91, "bm25Score": 3.14}],
    "durationMs": N,
    "rerankApplied": true,            ← false si reranker désactivé ou fallback vectoriel
    "hybridSearchApplied": true,      ← false si hybride désactivé
    "agenticApplied": true,           ← false si agentic-rag désactivé
    "agenticIterations": 2            ← nombre de tours SEARCH effectués (0 si non agentique)
  }

GET  /api/config/model → {"model": "spectra-domain"}
POST /api/config/model ← {"model": "autre-modele"}
  → Met à jour le registre (ne recharge pas llama-server automatiquement
    en mode runtime.enabled=false)

GET /api/config/resources
  Réponse : profil de ressources détecté par ResourceAdvisorService + arguments CLI calculés
  {
    "profile": "CPU_ONLY",          ← ou "NVIDIA_HIGH", "NVIDIA_MID", "AMD_ROCM", "VULKAN"
    "detectedCpuThreads": 4,
    "detectedRamMb": 7800,
    "gpuVramMb": 0,
    "chat": {
      "threads": 4,
      "contextSize": 2048,
      "batch": 512,
      "nGpuLayers": 0,
      "flashAttn": true,
      "cacheTypeK": "q8_0",
      "cacheTypeV": "q8_0",
      "cliArgs": "--threads 4 -c 2048 -b 512 --flash-attn --cache-type-k q8_0 --cache-type-v q8_0"
    },
    "embed": {
      "threads": 2,
      "flashAttn": false,
      "cliArgs": "--threads 2 -b 2048 -ub 2048"
    }
  }

POST /api/config/resources/refresh
  Déclenche une nouvelle détection des ressources (ResourceAdvisorService.refresh())
  Utile si un GPU a été ajouté ou si les limites cgroup ont changé sans redémarrage
  Réponse : même structure que GET /api/config/resources, avec le nouveau profil
```

---

## 9. Infrastructure Docker

### Dockerfile — multi-stage

```dockerfile
# Stage 1 : compilation Java
FROM maven:3.9-eclipse-temurin-21 AS build
RUN mvn package -DskipTests -B

# Stage 2 : compilation llama-server depuis les sources
FROM debian:bookworm-slim AS llama_cpp_build
ARG LLAMA_CPP_REPO=https://github.com/TheTom/llama-cpp-turboquant.git
ARG LLAMA_CPP_REF=master
RUN git clone --depth=1 && cmake -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build -t llama-server -j$(nproc)
# Produit : /src/llama-cpp/build/bin/llama-server + lib*.so*

# Stage 3 : image runtime minimale pour les conteneurs llama-server
FROM debian:bookworm-slim AS llama_cpp_runtime
RUN apt-get install -y libstdc++6 libgomp1 wget  # wget requis pour les health checks HTTP
COPY --from=llama_cpp_build /src/llama-cpp/build/bin/llama-server /usr/local/bin/
COPY --from=llama_cpp_build /src/llama-cpp/build/bin/lib*.so*     /usr/local/lib/
RUN ldconfig   # ← indispensable pour que llama-server trouve libmtmd.so.0 et consorts

# Stage 4 : image runtime Spring Boot
FROM eclipse-temurin:21-jre
COPY --from=build /app/target/*.jar app.jar
COPY --from=llama_cpp_build /src/llama-cpp/build/bin/llama-server /usr/local/bin/
# (binaire embarqué dans spectra-api pour le mode runtime.enabled=true)
```

**Bibliothèques partagées de llama-server :**

| Bibliothèque | Rôle |
|-------------|------|
| `libllama.so.0` | Cœur de l'inférence llama.cpp |
| `libggml.so.0` | Opérations tensorielles GGML |
| `libggml-base.so.0` | Primitives de base GGML |
| `libggml-cpu.so.0` | Backend CPU (BLAS, AVX, etc.) |
| `libmtmd.so.0` | Support multimodal (texte + images) |

Ces `.so` sont copiés depuis le stage `llama_cpp_build` vers `llama_cpp_runtime`, puis `ldconfig` les rend accessibles. Sans cette étape, llama-server échoue avec `libmtmd.so.0: cannot open shared object file`.

### Variables d'environnement `spectra-api`

| Variable | Valeur Docker | Description |
|----------|--------------|-------------|
| `SPECTRA_LLM_PROVIDER` | `llama-cpp` | Provider actif |
| `SPECTRA_LLM_REGISTRY_PATH` | `/app/data/models/registry.json` | Chemin du registre local |
| `SPECTRA_LLM_RUNTIME_ENABLED` | `false` | Orchestration interne désactivée (services séparés) |
| `SPECTRA_LLM_CHAT_BASE_URL` | `http://llm-chat:8081` | URL interne du serveur chat |
| `SPECTRA_LLM_CHAT_MODEL` | `spectra-domain` | Alias du modèle (doit correspondre à `-a` de llama-server) |
| `SPECTRA_LLM_EMBEDDING_BASE_URL` | `http://llm-embed:8082` | URL interne du serveur embedding |
| `SPECTRA_LLM_EMBEDDING_MODEL` | `nomic-embed-text` | Alias du modèle d'embedding |
| `SPECTRA_CHROMADB_BASE_URL` | `http://chromadb:8000` | URL ChromaDB |
| `SPECTRA_INGESTION_BROWSERLESS_URL` | `http://browserless:3000` | URL du service Chrome headless pour le rendu JS |
| `SPECTRA_LAYOUT_PARSER_ENABLED` | `false` | Active le parsing PDF layout-aware (`true` pour activer) |
| `SPECTRA_LAYOUT_PARSER_URL` | `http://docparser:8001` | URL interne du service docparser |
| `SPECTRA_LAYOUT_PARSER_TIMEOUT` | `120` | Timeout d'appel au docparser (secondes) |
| `SPECTRA_RERANKER_ENABLED` | `false` | Active le re-ranking Cross-Encoder (`true` pour activer) |
| `SPECTRA_RERANKER_URL` | `http://reranker:8000` | URL interne du service reranker |
| `SPECTRA_RERANKER_MODEL` | `cross-encoder/ms-marco-MiniLM-L-6-v2` | Modèle Cross-Encoder (voir table section 6) |
| `SPECTRA_RERANKER_TOP_CANDIDATES` | `20` | Nombre de candidats récupérés avant re-ranking |
| `SPECTRA_HYBRID_SEARCH_ENABLED` | `false` | Active la recherche hybride BM25 + vecteurs |
| `SPECTRA_HYBRID_BM25_TOP` | `20` | Candidats BM25 récupérés avant fusion RRF |
| `SPECTRA_HYBRID_BM25_WEIGHT` | `1.0` | Poids du signal BM25 dans le score RRF |
| `SPECTRA_AGENTIC_RAG_ENABLED` | `false` | Active la boucle de raisonnement ReAct (`true` pour activer) |
| `SPECTRA_AGENTIC_MAX_ITERATIONS` | `3` | Nombre maximal de tours de recherche complémentaire avant réponse forcée |
| `SPECTRA_AGENTIC_INITIAL_TOP_K` | `5` | Chunks du retrieval initial transmis à la boucle agentique |

### Paramètres JVM (`Dockerfile` runtime Java)

```
-Xms256m -Xmx2g -XX:+UseG1GC -Djava.net.preferIPv4Stack=true
```

`preferIPv4Stack` : force le résolveur JDK à choisir l'adresse IPv4. Reactor Netty utilise son propre résolveur DNS (Netty) qui ignore ce flag. Le `WebClient` est configuré avec `DefaultAddressResolverGroup.INSTANCE` pour forcer le résolveur JDK côté HTTP.

### Volumes montés sur `spectra-api`

```yaml
volumes:
  - ./data/documents:/app/data/documents
  - ./data/dataset:/app/data/dataset
  - ./data/fine-tuning:/app/data/fine-tuning    ← modèle GGUF fine-tuné
  - ./data/models:/app/data/models              ← registre + embed.gguf
  - ./scripts:/app/scripts                      ← train.sh accessible depuis FineTuningService
```

### Volumes montés sur les serveurs llama

```yaml
# llm-chat et llm-embed partagent le même volume
volumes:
  - ./data/models:/models:ro  ← LLM_CHAT_MODEL_FILE / LLM_EMBED_MODEL_FILE
```

### `scripts/llama-autostart.sh` — point d'entrée intelligent des conteneurs llama-server

Ce script remplace le `command:` statique qui était précédemment codé en dur dans `docker-compose.yml` pour les services `llm-chat` et `llm-embed`. Il est défini comme `entrypoint` de chaque conteneur et s'exécute avant `llama-server`.

**Rôle :** inspecter les ressources disponibles dans le conteneur au moment du démarrage, calculer les paramètres optimaux, puis lancer `llama-server` avec ces paramètres. Les variables d'environnement (définies dans `.env` ou `docker-compose.yml`) prennent toujours la priorité sur les valeurs calculées.

**Séquence de détection :**

```
1. CPU
   └── Lire /sys/fs/cgroup/cpu.max
       ├── Format "quota period" (ex. "400000 100000" = 4 cœurs)
       ├── "max period" → pas de quota → nproc
       └── Fichier absent → nproc (cgroups v1 ou sans limite)
   → threads = min(CPUs_disponibles, 8)  [plafonné pour éviter la contention]

2. RAM
   └── Lire /proc/meminfo → MemAvailable
       └── Lire /sys/fs/cgroup/memory.max
           ├── Valeur numérique → min(MemAvailable, memory.max)
           └── "max" → pas de limite cgroup → utiliser MemAvailable
   → context_size = f(RAM) : <2Go→768, <4Go→1536, <8Go→2048, <16Go→4096, ≥16Go→8192

3. GPU
   ├── nvidia-smi disponible ?
   │   ├── Oui → VRAM totale (Mo)
   │   │   ├── ≥8192 → ngl=-1, cacheTypeK=iq4_nl, context=8192
   │   │   └── ≥4096 → ngl=-1, cacheTypeK=q8_0, context=4096
   │   └── Non → étape suivante
   ├── /dev/kfd existe ? → AMD ROCm → ngl=-1, cacheTypeK=q8_0
   ├── /dev/dri/renderD128 existe ? → Vulkan → ngl=20, cacheTypeK=q8_0
   └── Rien détecté → CPU-only → ngl=0

4. Paramètres dérivés
   batch = min(context_size / 4, 512)
   flashAttn = 1 (chat) / 0 (embed)
   cacheTypeK = cacheTypeV = q8_0 (sauf NVIDIA haute VRAM → iq4_nl)

5. Surcharge par variables d'environnement
   LLAMA_CHAT_CONTEXT_SIZE → écrase context_size calculé
   LLAMA_CHAT_THREADS      → écrase threads calculé
   LLAMA_CHAT_NGL          → écrase ngl calculé
   (et toutes les autres variables du tableau d'overrides)

6. Résumé imprimé dans les logs Docker
   [llama-autostart] Profile : CPU_ONLY
   [llama-autostart] Threads : 4, Context : 2048, Batch : 512
   [llama-autostart] GPU layers : 0, Flash-attn : 1
   [llama-autostart] Cache KV : q8_0 / q8_0
   [llama-autostart] Launching: llama-server -m /fine-tuning/merged/model.gguf -a spectra-domain ...

7. exec llama-server <arguments calculés + overrides>
```

**Pourquoi un script shell plutôt qu'une configuration statique ?** Les environnements cibles varient fortement : machine de développement à 4 cœurs / 8 Go RAM, serveur de production à 32 cœurs / 128 Go RAM, ou instance cloud avec GPU. Un `command:` statique sur-configure les petites machines (OOM) et sous-configure les grandes (performance laissée sur la table). Le script adapte les paramètres au contexte réel sans nécessiter d'édition manuelle du `docker-compose.yml`.

---

## 10. Points Techniques Notables

### Séparation chat / embedding en deux processus

llama-server ne peut charger qu'un modèle à la fois. Chat et embedding utilisent des modèles différents (modèle instruction-tuned vs. modèle d'embedding). Deux instances séparées (`llm-chat` et `llm-embed`) sont donc nécessaires.

Avec `--embeddings`, une même instance peut faire du chat ET des embeddings si le modèle le supporte. Non utilisé ici car les modèles optimaux pour chaque tâche sont différents.

### `@Async` et self-invocation Spring

`@Async` ne fonctionne que via le proxy CGLIB. Appeler `this.generateAsync()` depuis la même instance contourne le proxy → exécution synchrone. Correction : auto-injection `@Autowired @Lazy`.

Symptôme avant correction : `curl` sur `POST /api/dataset/generate` bloquait sur le thread HTTP et tombait en timeout (exit code 52 = réponse vide).

### Multipart et fichiers temporaires

Tomcat supprime les fichiers temporaires multipart à la fin de la requête HTTP. Les `MultipartFile` sont copiés dans des fichiers persistants (`Files.copy`) **avant** le retour du contrôleur, puis nettoyés dans le `finally` du traitement asynchrone.

### Buffer WebClient ChromaDB

La réponse de `getAllDocuments()` pour 400+ chunks dépasse 256 Ko (buffer par défaut Spring WebClient). Le client ChromaDB est configuré avec `ExchangeStrategies` à 16 Mo.

### Encodage UTF-8 et curl sur Windows

Les requêtes curl depuis un shell Git Bash/MINGW sur Windows peuvent envoyer les caractères accentués en ISO-8859-1 plutôt qu'UTF-8. Jackson lève alors `JsonParseException: Invalid UTF-8 middle byte`. Solution : utiliser `--data-binary` avec `-H "Content-Type: application/json; charset=utf-8"`, et éviter les accents dans les chaînes de test ou les passer en entités JSON (`\u00e9` pour `é`).

---

## 11. Optimisations de Performance

Cette section documente les optimisations appliquées à la configuration de `llama-server` et explique pourquoi elles ont été choisies. La plupart sont activées automatiquement par `llama-autostart.sh` ; elles peuvent être désactivées via les variables d'environnement correspondantes.

### Flash Attention (`--flash-attn`)

**Activé pour le chat, désactivé pour l'embedding.**

Flash Attention est un algorithme de calcul de l'attention (Vaswani et al.) qui fusionne les opérations de matrice en un seul kernel GPU/CPU, réduisant les transferts mémoire et la consommation de KV cache d'environ **2×**. Concrètement :

- Sur un modèle à contexte 2048 et cache `f16` (défaut), le KV cache occupe ~256 Mo
- Avec Flash Attention + cache `q8_0`, cette empreinte descend à ~64 Mo
- Vitesse d'attention : gain de 20–40 % sur CPU selon l'implémentation GGML

Pour l'embedding, Flash Attention est désactivé car le modèle `nomic-embed-text` ne génère pas de KV cache long (chaque chunk est indépendant), et certaines architectures d'embedding ne sont pas compatibles avec ce mode.

**Variable de contrôle :** `LLAMA_CHAT_FLASH_ATTN=1` (ou `0` pour désactiver).

### Quantization du cache KV (`--cache-type-k`, `--cache-type-v`)

Par défaut, llama-server stocke le cache KV en `f16` (flottants 16 bits). Les options disponibles :

| Type | Taille relative | Perte de qualité | Cas d'usage |
|------|----------------|-----------------|-------------|
| `f16` | 100 % (référence) | 0 % | Précision maximale |
| `q8_0` | ~50 % | ~1–2 % | **Défaut Spectra (CPU / GPU <8 Go)** |
| `iq4_nl` | ~25 % | ~3–5 % | NVIDIA ≥ 8 Go VRAM |

Le choix `q8_0` offre le meilleur rapport entre réduction mémoire et fidélité des réponses. Avec `iq4_nl` (NVIDIA haute VRAM), on réduit encore la mémoire KV pour augmenter la taille de contexte servable, au prix d'une légère dégradation de précision acceptable en RAG.

**Variables de contrôle :** `LLAMA_CHAT_CACHE_TYPE_K` et `LLAMA_CHAT_CACHE_TYPE_V`.

### CPU Pinning via `cpuset`

Les deux serveurs llama-server (`chat` et `embed`) s'exécutent en parallèle dans des conteneurs distincts sur la même machine. Sans isolation, ils se disputent le cache L3 du processeur, ce qui dégrade les performances de chacun.

La configuration `cpuset` de Docker alloue des cœurs physiques dédiés :

```yaml
# docker-compose.yml
llm-chat:
  cpuset: "0-3"    # cœurs 0, 1, 2, 3 pour le chat

llm-embed:
  cpuset: "4-5"    # cœurs 4, 5 pour l'embedding
```

**Pourquoi cette répartition ?** Le chat est plus demandeur en calcul (génération autoregressive token par token) → 4 cœurs. L'embedding est plus simple (un seul passage en avant par chunk) → 2 cœurs suffisent. Sur une machine à moins de 6 cœurs, supprimez les variables `LLAMA_CHAT_CPUSET` et `LLAMA_EMBED_CPUSET` pour laisser le scheduler OS décider.

### Réduction du contexte par défaut (2048 tokens)

La valeur `LLAMA_CHAT_CONTEXT_SIZE=2048` peut sembler conservatrice, mais elle est justifiée par plusieurs contraintes :

1. **Plafond d'entraînement** : le modèle fine-tuné standard est entraîné sur des séquences de 2048 tokens. Au-delà, la qualité de génération se dégrade (interpolation de position hors distribution).
2. **Usage RAG** : avec `maxContextChunks=2`, le prompt complet (system + 2 chunks + question + réponse) consomme ~1500 tokens. Un contexte de 2048 est suffisant.
3. **Mémoire** : le KV cache croît linéairement avec la taille de contexte. Réduire de 8192 à 2048 réduit la consommation mémoire d'un facteur 4, ce qui permet de faire tourner le service sur des machines avec 4–8 Go de RAM.

Pour des contextes plus longs, utilisez un modèle de base avec une fenêtre plus large (ex. Phi-3-mini-4k → 4096 tokens) et augmentez `LLAMA_CHAT_CONTEXT_SIZE=4096`.

---

## 12. Benchmarks de référence

Ces mesures constituent la baseline de performance de Spectra sur CPU-only (configuration par défaut). Elles permettent de détecter une régression de performance ou de comparer l'impact d'un changement de configuration.

### Conditions de mesure

- **Matériel** : CPU-only, sans GPU
- **Modèle de chat** : modèle fine-tuné standard (format GGUF Q4)
- **Modèle d'embedding** : `nomic-embed-text-v1.5.Q4_K_M.gguf`
- **Contexte** : 2048 tokens, 1 slot parallèle
- **Optimisations actives** : Flash Attention, cache KV `q8_0`

### Résultats

| Scénario | P50 | Débit | Taux de succès |
|----------|-----|-------|---------------|
| Embedding (10 requêtes × ~512 tokens) | 801 ms | 639 vecteurs/s | 10/10 |
| Génération LLM pure (3 générations) | 9 234 ms | 36,7 tokens/s | 3/3 |
| RAG bout en bout (5 requêtes, maxChunks=2) | 17 909 ms | 18,0 tokens/s | 5/5 |

**Lecture des résultats :**
- Le débit de 36,7 tokens/s est la vitesse de génération pure (hors temps de préfill du contexte). Une réponse de 100 tokens prend environ 2,7 s.
- Le RAG bout en bout (~18 s) inclut : embedding de la question (~0,8 s) + recherche ChromaDB (<0,1 s) + génération LLM (~17 s). Le goulot est exclusivement la génération.
- L'embedding à 639 vecteurs/s signifie qu'un document de 100 chunks (~50 pages) s'indexe en ~10 s.

### Reproduire les benchmarks

```bash
./scripts/benchmark.sh --api-only
```

Ce script envoie des requêtes réelles à l'API Spectra et mesure les temps de réponse. L'option `--api-only` utilise les serveurs Docker déjà démarrés (pas de démarrage local de llama-server).

### Impact des optimisations GPU

À titre indicatif, sur GPU NVIDIA avec 8 Go de VRAM (toutes couches GPU, cache `iq4_nl`) :

| Scénario | Gain typique vs CPU |
|----------|---------------------|
| Embedding | ×3 à ×5 |
| Génération LLM | ×8 à ×15 |
| RAG bout en bout | ×6 à ×12 |

---

## 13. Limitations et Points d'Attention

| Limitation | Impact | Contournement |
|------------|--------|---------------|
| Contexte modèle fine-tuné = 2048 tokens | RAG limité à ~2 chunks par requête | Utiliser un modèle avec contexte plus large (Phi-3 4k) ou `maxContextChunks=2` |
| Inférence CPU uniquement | 20–60 s par réponse RAG | GPU via `nvidia` runtime Docker (non configuré par défaut) |
| Pas de rechargement à chaud de llama-server | Changement de modèle = restart du conteneur | `docker compose restart llm-chat` ou `docker compose restart llm-embed` |
| Vecteurs non portables entre modèles d'embedding | Changement de modèle = ré-ingestion complète | Prévoir une plage de maintenance pour la ré-ingestion |
| `pullModel` non supporté | `POST /api/fine-tuning/models/{name}/pull` lève une exception | Les modèles doivent être gérés localement (téléchargement manuel GGUF) |
| Paires dataset en mémoire | Perdues au redémarrage du conteneur | Export JSONL avant redémarrage |
| `.doc` non supporté | Apache POI XWPF ne lit pas le format binaire OLE2 | Convertir en `.docx` |
| ChromaDB API v2 uniquement | L'API v1 retourne 501 | Ne pas rétrograder l'image ChromaDB |
| Historique jobs en mémoire | Perdu au redémarrage de l'API | Persistance DB non implémentée |
| Ingestion URL : pas d'authentification | Pages derrière login inaccessibles | Télécharger manuellement et uploader comme fichier |
| Ingestion URL : max 20 URLs par requête | Volume limité par appel API | Effectuer plusieurs appels successifs |
| Rendu JS (browserless) : timeout 60 s | Pages très lentes non rendues | Augmenter `BROWSERLESS_TIMEOUT` dans `UrlFetcherService` |
| Extraction HTML : sélecteurs CSS fixes | Certains sites avec structures non-standards perdent du contenu | Ajouter les sélecteurs dans `HtmlExtractor.java` et recompiler |
