# Manuel Utilisateur : Spectra (Domain LLM Builder)

Spectra vous permet de créer votre propre assistant d'intelligence artificielle spécialisé dans **votre domaine métier**, à partir de vos propres documents. L'assistant fonctionne **entièrement en local** — aucune donnée ne quitte votre poste.

L'inférence LLM est assurée par [llama-cpp-turboquant](https://github.com/TheTom/llama-cpp-turboquant), un fork de llama.cpp optimisé pour la quantization. Les modèles sont au format **GGUF**, le standard de facto pour l'inférence locale.

---

## 1. Prérequis

Avant le premier lancement, vérifiez que vous avez installé :

- **Docker Desktop** (version 4.x ou plus récente) — démarré et opérationnel

Vous avez besoin de deux fichiers **GGUF** placés dans `data/models/` :

| Variable | Fichier par défaut | Rôle |
|----------|--------------------|------|
| `LLM_CHAT_MODEL_FILE` | `Phi-4-mini-reasoning-UD-IQ1_S.gguf` | Répond aux questions, génère le dataset |
| `LLM_EMBED_MODEL_FILE` | `embed.gguf` | Convertit le texte en vecteurs pour la recherche |

Si un fichier est absent au démarrage, le service `model-init` affiche les commandes de téléchargement exactes et interrompt la stack avant que les serveurs LLM ne démarrent.

### Télécharger les modèles

```bash
# Modèle de chat (~1.1 Go) — Phi-4-mini par défaut
huggingface-cli download unsloth/Phi-4-mini-reasoning-GGUF \
  Phi-4-mini-reasoning-UD-IQ1_S.gguf --local-dir data/models/

# Modèle d'embedding (~81 Mo) — nomic-embed-text par défaut
huggingface-cli download nomic-ai/nomic-embed-text-v1.5-GGUF \
  nomic-embed-text-v1.5.Q4_0.gguf \
  --local-dir data/models/ --filename embed.gguf
```

> **Pas de GPU requis** pour l'ingestion, le RAG et l'interrogation. Le fine-tuning avec poids LoRA réels est optionnel et nécessite Python + CUDA.

---

## 2. Démarrage

```bash
docker compose up -d
```

Cette commande lance les services principaux en une fois :

| Service | Rôle |
|---------|------|
| `spectra-frontend` | Interface web (port **80**) |
| `spectra-api` | Backend API (port 8080) |
| `spectra-llama-chat` | Serveur d'inférence chat (llama.cpp, interne) |
| `spectra-llama-embed` | Serveur d'embeddings (llama.cpp, interne) |
| `spectra-chromadb` | Base de données vectorielle (interne) |
| `spectra-browserless` | Chrome headless pour le rendu des pages web dynamiques (interne) |
| `spectra-reranker` | Re-ranking Cross-Encoder (port **8002**, optionnel — voir §4 Interrogation) |
| `spectra-docparser` | Parsing PDF layout-aware (port **8003**, optionnel — voir §1 Ingestion) |

Les services `llama-chat` et `llama-embed` **ne sont pas accessibles depuis votre navigateur** — ils sont réservés à la communication interne entre `spectra-api` et les serveurs llama.cpp.

Au premier démarrage, les serveurs llama.cpp chargent leur modèle GGUF en mémoire, ce qui prend **30 à 60 secondes**. Attendez que `docker compose ps` affiche `(healthy)` pour tous les services avant d'ouvrir l'interface.

```bash
docker compose ps          # état de chaque conteneur
curl http://localhost:8080/api/status   # santé des services
```

La réponse du `/api/status` liste trois services :

| `name` | Ce qu'il surveille | Champ utile |
|--------|-------------------|-------------|
| `llama-cpp` | Serveur de chat | `details.activeModel`, `details.activeModelLoaded` |
| `llm-embed` | Serveur d'embedding | `details.activeModel`, `details.serverStatus` |
| `chromadb` | Base vectorielle | `available`, `version` |

Exemple de réponse résumée :
```json
{
  "services": [
    {"name": "llama-cpp",       "available": true,  "details": {"activeModel": "spectra-domain", "activeModelLoaded": true}},
    {"name": "llm-embed", "available": true,  "details": {"activeModel": "nomic-embed-text", "serverStatus": "ok"}},
    {"name": "chromadb",        "available": true}
  ]
}
```

---

## 3. Pipeline en 4 étapes

Voici le parcours complet pour créer votre assistant IA spécialisé :

```
[1. INGEST] ──→ [2. GENERATE] ──→ [3. FINE-TUNE] ──→ [4. QUERY]
 Vos documents    Paires Q/A        Modèle GGUF         Réponses RAG
                       │
                  (optionnel)
                  [2b. DPO]
               Paires rejetées
               → entraînement
                 par préférence
```

---

### Étape 1 — Ingestion des documents

**Objectif** : transformer vos documents (fichiers locaux ou pages web) en vecteurs stockés dans ChromaDB.

Spectra accepte deux types de sources : les **fichiers** uploadés depuis votre poste, et les **URLs** pointant vers des pages web ou des fichiers distants.

---

#### 1a — Ingestion de fichiers locaux

##### Via l'interface (recommandé)

1. Cliquez sur **Dataset Pipelines** dans le menu gauche.
2. Repérez l'indicateur de pipeline en haut : `[1 INGEST] ── [2 GENERATE] ── [3 READY]`.
3. Glissez vos fichiers dans la zone pointillée, ou cliquez sur **Browse Files**.
4. Chaque fichier apparaît dans le panneau **Live Ingestion Stream** avec son statut :
   - `UPLOADING` → envoi en cours
   - `PROCESSING` → extraction + vectorisation
   - `COMPLETED` + nombre de chunks créés → succès
   - `FAILED` + message d'erreur → problème à corriger

##### Via l'API

```bash
curl -X POST http://localhost:8080/api/ingest \
  -F "files=@manuel-technique.pdf" \
  -F "files=@lexique-metier.docx"
# → {"taskId": "abc-123", "status": "PENDING"}

# Suivre l'avancement
curl http://localhost:8080/api/ingest/abc-123
# → {"status": "COMPLETED", "chunksCreated": 42}
```

**Formats supportés :** PDF, DOCX (Word 2007+), DOC (Word 97-2003), JSON, XML, TXT, HTML.

> Les scans sans OCR (images sans texte sélectionnable) ne produiront pas de chunks utiles.

**Améliorer la qualité d'extraction PDF (tableaux, titres, hiérarchie) :**

Par défaut, les PDF sont traités avec une extraction textuelle simple. Pour les documents techniques avec tableaux ou titres hiérarchiques, activez le parsing layout-aware :

```bash
SPECTRA_LAYOUT_PARSER_ENABLED=true docker compose up -d
```

Le service `spectra-docparser` démarre et convertit chaque PDF en Markdown structuré avant l'ingestion :
- Les titres deviennent `# Titre`, `## Sous-titre` (préservés dans les chunks)
- Les tableaux deviennent `| colonne A | colonne B |` (lisibles par le LLM)
- Les layouts multi-colonnes sont correctement linéarisés

Si le service de parsing est indisponible lors d'une ingestion, Spectra bascule automatiquement sur l'extraction PDFBox standard.

> **Option avancée :** pour une précision maximale sur les tableaux complexes, activez Docling (modèles IA IBM) : `USE_DOCLING=true SPECTRA_LAYOUT_PARSER_ENABLED=true docker compose up --build docparser`. L'image docparser grossit d'environ 500 Mo.

---

#### 1b — Ingestion depuis des URLs

Spectra peut ingérer directement des pages web ou des fichiers accessibles par HTTP/HTTPS — sans que vous ayez besoin de les télécharger manuellement.

**Deux situations sont gérées automatiquement :**

| Type de page | Traitement |
|---|---|
| **Page HTML statique** (pas de JavaScript requis) | Téléchargement HTTP direct + extraction jsoup |
| **Page HTML dynamique** (JavaScript, SPA, application web) | Rendu via `browserless/chrome` (Chrome headless) avant extraction |
| **Fichier PDF ou TXT distant** | Téléchargement direct, même pipeline que les fichiers locaux |

Spectra détecte le type de contenu via une requête `HEAD` avant de décider du traitement.

##### Via l'interface

Dans **Dataset Pipelines**, repérez la barre d'URL sous la zone de dépôt de fichiers. Collez l'URL et appuyez sur Entrée ou cliquez sur **Ingest URL**. La progression apparaît immédiatement dans le **Live Ingestion Stream**, comme pour un fichier.

##### Via l'API

```bash
# Ingérer une seule URL
curl -X POST http://localhost:8080/api/ingest/url \
  -H "Content-Type: application/json" \
  -d '{"urls": ["https://example.com/notice.pdf"]}'
# → {"taskId": "xyz-456", "status": "PENDING"}

# Ingérer plusieurs URLs en un seul appel (max 20)
curl -X POST http://localhost:8080/api/ingest/url \
  -H "Content-Type: application/json" \
  -d '{"urls": [
    "https://example.com/page-produit",
    "https://intranet/wiki/procedures",
    "https://example.com/doc.pdf"
  ]}'

# Suivi (même endpoint que les fichiers)
curl http://localhost:8080/api/ingest/xyz-456
# → {"status": "PROCESSING", "chunksCreated": 0}
# → {"status": "COMPLETED", "chunksCreated": 18}
```

> **Limite :** maximum 20 URLs par requête. Pour un volume plus important, effectuez plusieurs appels ou uploadez les fichiers téléchargés au préalable.

> **Pages protégées :** Spectra ne gère pas l'authentification (session, cookie, token). Pour les pages derrière un login, téléchargez manuellement le contenu et uploadez-le comme fichier.

> **Fallback browserless :** si le service `browserless` est arrêté ou inaccessible, Spectra tente quand même un téléchargement HTTP direct. Les pages nécessitant du JavaScript ne seront pas rendues correctement, mais les pages statiques fonctionneront.

---

#### Ce que fait Spectra pour chaque source

Qu'il s'agisse d'un fichier ou d'une URL, le traitement en coulisses est identique :

1. **Détection du type** : extension de fichier (fichiers) ou requête HEAD + content-type (URLs)
2. **Extraction du texte** : pdftotext (PDF), Apache POI (DOCX), jsoup (HTML), Jackson (JSON/XML)
3. **Nettoyage** : normalisation unicode, suppression en-têtes/pieds, harmonisation ponctuation
4. **Chunking** : découpage en segments de ~512 tokens avec chevauchement de 64 tokens
5. **Vectorisation** : calcul des embeddings par `nomic-embed-text` (llm-embed)
6. **Stockage** : indexation dans ChromaDB

> **Déduplication SHA-256 :** si le même fichier est soumis deux fois (même contenu, même hash), Spectra l'ignore silencieusement. Utilisez `?force=true` pour forcer la ré-ingestion d'un fichier. Pour les URLs, chaque soumission déclenche un nouveau téléchargement et une nouvelle ingestion (le contenu peut avoir changé).

> **Changement de modèle d'embedding :** si vous remplacez `embed.gguf` par un autre modèle, vous devez ré-ingérer **tous** vos documents. Les vecteurs stockés dans ChromaDB sont propres à un modèle et ne sont pas interchangeables. Utilisez `?force=true` :
> ```bash
> curl -X POST "http://localhost:8080/api/ingest?force=true" -F "files=@fichier.pdf"
> ```

---

### Étape 2 — Génération du dataset d'entraînement

**Objectif** : générer des paires question/réponse à partir de vos documents, pour entraîner le modèle.

#### Via l'interface

1. Toujours dans **Dataset Pipelines**, descendez à la section **Dataset Generation**.
2. Réglez le curseur **Max Chunks** :
   - Valeur `0` (ou `ALL`) = traite tous les documents (plusieurs heures sur CPU)
   - Valeur `5–20` = test rapide pour vérifier que le pipeline fonctionne (~10–30 min sur CPU)
3. Cliquez sur **Initialize Pipeline**.
4. La progression s'affiche en temps réel : nombre de chunks traités, paires générées.

#### Via l'API

```bash
# Lancer la génération (limité à 10 chunks pour un test)
curl -X POST "http://localhost:8080/api/dataset/generate?maxChunks=10"
# → {"taskId": "def-456", "status": "PENDING"}

# Suivre l'avancement (toutes les 30 secondes)
curl http://localhost:8080/api/dataset/generate/def-456
# → {"status": "PROCESSING", "chunksProcessed": 3, "totalChunks": 10, "pairsGenerated": 9}

# Statistiques finales
curl http://localhost:8080/api/dataset/stats
```

**Ce que génère Spectra pour chaque passage de document :**
- Une **paire Question / Réponse** vérifiée par un second appel LLM (auto-correction)
- Un **résumé technique** des points clés
- Une **classification** du contenu (procedures / evenements / nomenclatures / reglementation)
- Dans 30 % des cas, une **question "piège"** avec réponse honnête ("cette information n'est pas dans mes documents") — pour limiter les hallucinations

> **Durée estimée :** 30–120 secondes par chunk sur CPU (selon la taille du chunk et le modèle). Sur GPU, 5–10× plus rapide.
> Conseil : testez d'abord avec `maxChunks=5` avant de lancer sur l'ensemble.

---

### Étape 2b — Génération DPO (optionnel — alignement par préférence)

**Objectif** : générer des paires (réponse correcte / réponse erronée) pour affiner le modèle par alignement DPO plutôt que par SFT classique.

L'alignement DPO enseigne explicitement au modèle à rejeter les hallucinations courantes de votre domaine, en lui présentant des exemples de ce qu'il ne doit pas répondre.

#### Via l'API

```bash
# Générer des paires DPO (maxPairs=0 = toutes les paires SFT disponibles)
curl -X POST "http://localhost:8080/api/dataset/dpo/generate?maxPairs=50"
# → {"taskId": "dpo-123", "status": "PENDING"}

# Suivi
curl http://localhost:8080/api/dataset/dpo/generate/dpo-123
# → {"status": "COMPLETED", "pairsGenerated": 47}

# Statistiques du dataset DPO
curl http://localhost:8080/api/dataset/dpo/stats
```

> Lancez la génération DPO **après** la génération SFT (étape 2). Elle se base sur les paires déjà générées pour créer les réponses rejetées.

---

### Étape 3 — Fine-Tuning (création du modèle spécialisé)

**Objectif** : affiner les poids du modèle sur votre dataset pour qu'il maîtrise votre domaine.

#### Via l'interface (recommandé)

1. Cliquez sur **Fine-Tuning Command** dans le menu gauche.
2. Cliquez sur **New Training Job** (bouton en haut à droite).
3. (Optionnel) Sélectionnez une **recette prédéfinie** en haut du formulaire :
   - **CPU Rapide** : LoRA rank 8, 1 époque, multipacking activé — idéal pour un premier test
   - **GPU Qualité** : LoRA rank 64, 3 époques — pour un modèle de production
   - **DPO Alignement** : alignement DPO, rank 32, 2 époques — pour réduire les hallucinations
4. Remplissez (ou ajustez) le formulaire :
   - **Model Name** : nom du modèle à créer dans le registre local (ex. `spectra-domain`)
   - **Base Model** : modèle de base (ex. `phi3`, `mistral`)
   - **Epochs** : nombre de passes d'entraînement (3 par défaut)
   - **LoRA Rank** : précision du fine-tuning (64 = bon équilibre qualité/vitesse)
   - **Min Confidence** : seuil de qualité des paires utilisées (0.8 par défaut)
   - **Multipacking** : cochez pour concaténer les exemples courts — accélère l'entraînement de 20–30 %
   - **Alignement DPO** : cochez si vous avez généré des paires DPO (étape 2b) — entraîne par préférence plutôt que par SFT
5. Cliquez sur **Launch Training**.
6. Suivez la progression via la **barre d'étapes** :

```
[QUEUED] ──→ [EXPORT] ──→ [TRAINING] ──→ [IMPORT] ──→ [COMPLETE]
```

7. (Optionnel) Cliquez sur **Exporter** pour sauvegarder la configuration courante en fichier YAML réutilisable.

#### Via l'API

```bash
# SFT classique
curl -X POST http://localhost:8080/api/fine-tuning \
  -H "Content-Type: application/json" \
  -d '{"modelName": "spectra-domain", "baseModel": "phi3", "epochs": 3}'

# Avec multipacking (plus rapide sur CPU/GPU)
curl -X POST http://localhost:8080/api/fine-tuning \
  -H "Content-Type: application/json" \
  -d '{"modelName": "spectra-domain", "baseModel": "phi3", "epochs": 3, "packingEnabled": true}'

# Avec DPO (requiert génération DPO préalable)
curl -X POST http://localhost:8080/api/fine-tuning \
  -H "Content-Type: application/json" \
  -d '{"modelName": "spectra-aligned", "baseModel": "phi3", "dpoEnabled": true}'

# Suivi
curl http://localhost:8080/api/fine-tuning/ghi-789
# → {"status": "COMPLETED", "modelName": "spectra-domain", ...}
```

#### Recettes YAML

Pour réutiliser une configuration ou la partager :

```bash
# Lister les recettes disponibles
curl http://localhost:8080/api/fine-tuning/recipes

# Charger une recette (valeurs à injecter dans le formulaire)
curl http://localhost:8080/api/fine-tuning/recipes/cpu-rapide

# Exporter la configuration courante
curl -X POST http://localhost:8080/api/fine-tuning/recipe/export \
  -H "Content-Type: application/json" \
  -d '{"modelName": "mon-modele", "baseModel": "phi3", "epochs": 2, "loraRank": 16, "packingEnabled": true}' \
  -o ma-recette.yml
```

**Ce que fait Spectra selon votre configuration matérielle :**

| Matériel | Mode | Résultat |
|----------|------|---------|
| Sans GPU (Docker seul) | Simulation + system-prompt | Profil logique enregistré dans le registre local |
| CPU + Python installé | HuggingFace PEFT | Adaptateur LoRA CPU (lent mais réel) |
| GPU NVIDIA + Python | Unsloth QLoRA 4-bit | Adaptateur GGUF haute qualité dans `data/fine-tuning/merged/model.gguf` |

Après le fine-tuning, le modèle est automatiquement enregistré dans `data/models/registry.json`. Pour le prendre en compte dans le serveur de chat :

```bash
docker compose restart llm-chat
```

---

### Étape 4 — Interrogation avec RAG

**Objectif** : poser des questions à votre assistant spécialisé.

#### Via l'interface Playground

1. Cliquez sur **Playground** dans le menu gauche.
2. Dans le panneau gauche, ajustez les paramètres :
   - **Temperature** (0–2) : plus bas = réponses plus déterministes, plus haut = plus créatif
   - **Top P** (0–1) : diversité du vocabulaire
   - **Enable Knowledge Base** : active/désactive le RAG (recherche dans vos documents)
3. Tapez votre question dans la zone de saisie et appuyez sur Entrée.

#### Via l'API

```bash
curl -X POST http://localhost:8080/api/query \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary '{"question": "Quelle est la procédure décrite dans le document X ?", "maxContextChunks": 2}'
```

Réponse :
```json
{
  "answer": "Selon le document, la procédure consiste à...",
  "sources": [
    {"text": "extrait du document source...", "sourceFile": "manuel.pdf", "distance": 0.42, "rerankScore": 0.91}
  ],
  "durationMs": 28500,
  "rerankApplied": false
}
```

> **Paramètre `maxContextChunks`** : contrôle le nombre d'extraits injectés dans le contexte du LLM. Valeur par défaut : 5. Si vous obtenez une erreur de contexte dépassé, réduisez à 2 ou 3. Le modèle fine-tuné standard a un contexte maximal de **2048 tokens**, ce qui limite le nombre de chunks utilisables par requête.

> **Paramètre `topCandidates`** : utilisé uniquement si le re-ranking est activé. Contrôle combien de candidats sont récupérés dans ChromaDB avant que le Cross-Encoder les re-classe pour ne garder que `maxContextChunks`. Valeur par défaut : 20. Plus cette valeur est haute, plus le filet est large, mais le service de re-ranking prend un peu plus de temps.

**Ce que fait le RAG :**
1. Votre question est convertie en vecteur par `nomic-embed-text` (llm-embed)
2. Les `topCandidates` extraits les plus proches sémantiquement sont récupérés dans ChromaDB
3. *(Si recherche hybride activée)* Une recherche BM25 parallèle récupère les extraits correspondant le mieux aux mots-clés exacts de la question ; les deux listes sont fusionnées via RRF
4. *(Si re-ranking activé)* Un modèle Cross-Encoder évalue chaque paire `(question, extrait)` et re-classe les candidats par pertinence réelle — les N meilleurs sont conservés
5. *(Si Agentic RAG activé)* Le LLM analyse le contexte disponible ; s'il juge l'information insuffisante, il formule une requête de recherche complémentaire (jusqu'à 3 tours par défaut) avant de répondre
6. Les extraits pertinents sont injectés dans le prompt comme "contexte"
7. Le modèle spécialisé formule une réponse précise et sourcée (llm-chat)

**Activer la recherche hybride (BM25 + vecteurs) :**

La recherche hybride récupère les termes techniques exacts (codes, numéros, acronymes) que l'embedding peut diluer. Aucun service supplémentaire n'est requis — l'index BM25 est en mémoire.

```bash
SPECTRA_HYBRID_SEARCH_ENABLED=true docker compose up -d
```

Au démarrage, Spectra reconstruit automatiquement l'index BM25 depuis ChromaDB en arrière-plan. Les premières requêtes peuvent être vectorielles seules si l'index n'est pas encore prêt.

**Activer le re-ranking :**

Le re-ranking améliore significativement la précision des sources retournées, au prix d'une légère latence supplémentaire (~100–300 ms sur CPU). Il est désactivé par défaut.

```bash
SPECTRA_RERANKER_ENABLED=true docker compose up -d

# Changer de modèle (multilingue, meilleur pour le français)
RERANKER_MODEL=cross-encoder/mmarco-mMiniLMv2-L12-H384-v1 SPECTRA_RERANKER_ENABLED=true docker compose up -d
```

**Activer les deux (meilleure précision) :**

```bash
SPECTRA_HYBRID_SEARCH_ENABLED=true SPECTRA_RERANKER_ENABLED=true docker compose up -d
```

Pipeline complet : `BM25 + Vecteurs → RRF → Cross-Encoder → LLM`

**Activer l'Agentic RAG (raisonnement multi-étapes) :**

Quand le contexte initial est insuffisant pour répondre avec certitude, le LLM peut formuler de nouvelles requêtes de recherche et enrichir son contexte avant de répondre. Cette boucle ReAct peut effectuer jusqu'à 3 tours de recherche par défaut.

> **Prérequis :** votre modèle doit avoir une fenêtre de contexte ≥ 4096 tokens. L'Agentic RAG consomme plusieurs appels LLM par requête — comptez 2–4× plus de temps qu'une requête RAG standard.

```bash
SPECTRA_AGENTIC_RAG_ENABLED=true docker compose up -d

# Augmenter le nombre de tours si les questions sont très complexes
SPECTRA_AGENTIC_RAG_ENABLED=true SPECTRA_AGENTIC_MAX_ITERATIONS=5 docker compose up -d

# Combinaison optimale (recherche hybride + re-ranking + raisonnement agentique)
SPECTRA_HYBRID_SEARCH_ENABLED=true SPECTRA_RERANKER_ENABLED=true SPECTRA_AGENTIC_RAG_ENABLED=true docker compose up -d
```

Pipeline complet : `BM25 + Vecteurs → RRF → Cross-Encoder → boucle ReAct → LLM`

Une fois activés, les champs `hybridSearchApplied`, `rerankApplied`, `agenticApplied` et `agenticIterations` apparaissent dans les réponses API.

> **Délai de réponse :** sur CPU, comptez 20–60 secondes selon la longueur de la réponse. Sur GPU, 2–5 secondes.

---

## 4. Guide de l'Interface

### Dashboard

Le tableau de bord affiche en temps réel trois cartes de santé :

| Carte | Service surveillé | Information affichée |
|-------|------------------|---------------------|
| **Chat** | `llm-chat` | Online/Offline · nom du modèle actif |
| **Embed** | `llm-embed` | Online/Offline · nom du modèle d'embedding |
| **ChromaDB** | `chromadb` | Online/Offline · nombre de chunks indexés |

En dessous : statistiques de la base de connaissances (chunks, paires d'entraînement, score de confiance moyen, nombre de catégories).

### Dataset Pipelines (Étapes 1 et 2)

L'indicateur de pipeline en haut à droite montre la progression globale :
- Cercle **gris** = étape non commencée
- Cercle **animé bleu** = étape en cours
- Cercle **vert** = étape terminée

**Section "Document Ingestion" :**
- **Zone de dépôt** (fichiers) : glisser-déposer ou clic pour sélectionner
- **Barre URL** (sous la zone de dépôt) : collez une URL et appuyez sur Entrée ou cliquez **Ingest URL** — les pages HTML dynamiques sont automatiquement rendues via Chrome headless
- **Live Ingestion Stream** : suivi de chaque source (fichier ou URL) avec son statut, mis à jour toutes les 3 secondes
- **History** : cliquez sur le bouton "History" (en haut du panneau de suivi) pour voir l'historique de tous les documents ingérés depuis le démarrage

### Fine-Tuning Command (Étape 3)

- **Sélecteur de recettes** (en haut du formulaire) : 3 boutons preset (CPU Rapide · GPU Qualité · DPO Alignement). Cliquer sur un preset pré-remplit tous les champs techniques tout en conservant le nom de modèle saisi.
- **New Training Job** : ouvre le formulaire de configuration
- **Multipacking** : case à cocher — concatène les exemples courts pour réduire le padding et accélérer l'entraînement de 20–30 %
- **Alignement DPO** : case à cocher — active l'entraînement par préférence (requiert une génération DPO via Dataset Pipelines)
- **Exporter** : télécharge la configuration du job courant au format YAML
- **Barre d'étapes** : indique visuellement où en est le job (EXPORT → TRAINING → IMPORT → COMPLETE)
- **Telemetry Stream** : logs en temps réel issus du script d'entraînement (Server-Sent Events)
- **Training History** : cliquez sur une ligne de l'historique pour afficher ses détails

### Playground (Étape 4)

- **Sélecteur de modèle** (colonne gauche, section "Active Model") : liste tous les modèles de chat enregistrés dans le registre. Cliquez sur un modèle pour le définir comme modèle actif dans le registre.

  > **Important :** le changement prend effet uniquement au prochain redémarrage de `llm-chat`. Le Playground affiche un toast d'information pour le rappeler. Pour appliquer immédiatement : `docker compose restart llm-chat` (ou modifiez `LLAMA_CHAT_MODEL` dans `.env` si vous changez de fichier GGUF).

- **Temperature et Top P** : ajustent le comportement de génération (déterministe ↔ créatif)
- **Enable Knowledge Base** : active/désactive le RAG — pratique pour comparer les réponses avec et sans contexte documentaire
- Les **sources** (extraits utilisés) apparaissent dans la réponse API

### Model Comparison

Tableau de bord des évaluations **LLM-as-a-judge** : après un fine-tuning, lancez une évaluation pour mesurer objectivement la qualité de votre modèle.

**Comment ça fonctionne :**
1. Spectra sélectionne 5 % du dataset comme set de test (minimum 5 paires, maximum 50).
2. Pour chaque paire, le modèle actif génère une réponse.
3. Le même modèle est réutilisé en tant que "juge" : il note la réponse de 1 à 10 et justifie sa note.
4. Les résultats sont agrégés par catégorie (procédures, événements, nomenclatures…).

**Panneau gauche :** liste de toutes les évaluations avec leur date, modèle et score global.

**Panneau droit (après sélection d'une évaluation) :**
- Score global (0–10) avec jauge colorée
- Scores par catégorie (barres de progression)
- Détail question par question : question · réponse de référence · réponse du modèle · note · justification du juge

**Lancer une évaluation :**
- Cliquez sur **New Evaluation** dans l'interface
- Ou via l'API : `POST /api/evaluation` avec `{"modelName": "spectra-domain", "testSetSize": 20}`

> **Interprétation des scores :** un score ≥ 7 indique que le modèle répond correctement et précisément. Un score entre 4 et 6 suggère des réponses partielles ou trop vagues. En dessous de 4, le modèle hallucine ou hors-sujet.

---

## 5. Gestion des Modèles

Spectra maintient un **registre local** des modèles dans `data/models/registry.json`, géré automatiquement par l'application.

### Voir les modèles disponibles

```bash
curl http://localhost:8080/api/fine-tuning/models
```

### Enregistrer manuellement un modèle GGUF

Vous pouvez enregistrer n'importe quel fichier GGUF placé dans `data/models/` ou `data/fine-tuning/` :

```bash
curl -X POST http://localhost:8080/api/fine-tuning/models/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "mon-modele",
    "type": "chat",
    "source": "./data/models/mon-modele.gguf",
    "activate": true
  }'
```

### Changer de modèle actif

**Via l'interface Playground** (recommandé) : la colonne gauche liste les modèles disponibles. Cliquez sur le modèle souhaité.

**Via l'API :**
```bash
curl -X POST http://localhost:8080/api/config/model \
  -H "Content-Type: application/json" \
  -d '{"model": "mon-modele"}'
# → {"model": "mon-modele", "status": "updated"}
```

> **Note :** avec `runtime.enabled=false` (mode par défaut en Docker), le changement met à jour le registre mais **ne recharge pas llama-server**. Pour que le nouveau modèle soit effectivement servi, redémarrez `llm-chat` :
> ```bash
> # Si le GGUF est déjà dans data/models/
> docker compose restart llm-chat
>
> # Si vous changez de fichier GGUF, modifiez .env puis relancez
> # LLM_CHAT_MODEL_FILE=nouveau-modele.gguf
> docker compose up -d --no-deps llm-chat
> ```

---

## 6. Rapport de Session

Chaque job de fine-tuning produit un rapport `REPORT.md` dans `data/fine-tuning/{jobId}/`. Il contient :
- La date et l'identifiant de session
- La configuration LoRA utilisée (rank, alpha, learning rate, époques)
- La taille et la composition du dataset (nombre de paires, taux d'exemples d'honnêteté)
- Le nom et le chemin exact du modèle produit

---

## 7. Conseils pour de Meilleurs Résultats

**Qualité des documents**
- Préférez des PDF "natifs" (générés par Word/LibreOffice) aux scans. Les scans sans OCR produisent des chunks vides.
- Les documents structurés (procédures, nomenclatures, fiches techniques) donnent de meilleures paires que les textes narratifs.
- Pour les URLs : les pages trop riches en navigation, publicités ou menus seront nettoyées automatiquement (jsoup supprime les balises `nav`, `footer`, `script`, etc.). Préférez les URLs qui pointent directement vers du contenu documentaire plutôt que des pages d'accueil.

**Volume**
- Visez au moins 200–300 pages de documents pertinents pour un dataset utile.
- Ingérez les lexiques et glossaires métier pour que Spectra maîtrise la terminologie de votre domaine.

**Contexte RAG et taille de modèle**
- Le modèle fine-tuné standard a un contexte de 2048 tokens. Avec `maxContextChunks=2`, chaque chunk fait ~600 tokens, laissant ~800 tokens pour le prompt système et la réponse.
- Pour des contextes plus longs, utilisez un modèle de base avec une fenêtre de contexte plus grande (ex. Phi-3 4k = 4096 tokens).

**Workflow recommandé**
1. Testez d'abord le RAG avec `maxContextChunks=2` pour vérifier que Spectra retrouve les bons documents.
2. Lancez une génération avec `maxChunks=5` pour valider le format des paires générées.
3. Si satisfait, relancez sans limite pour le dataset complet.
4. Fine-tunez (recette "CPU Rapide" pour un premier essai) et comparez les réponses avec/sans RAG dans le Playground.
5. Lancez une **évaluation** (onglet Model Comparison) pour mesurer le score moyen avant d'aller en production.
6. Si le score est insuffisant (< 6) : générez des paires DPO (`POST /api/dataset/dpo/generate`) et relancez le fine-tuning avec l'option "Alignement DPO".

---

## 8. Performances et ressources

### Auto-tuning au démarrage

Spectra ajuste automatiquement ses paramètres d'inférence en fonction des ressources disponibles dans chaque conteneur. Cette détection a lieu à chaque démarrage des serveurs `llm-chat` et `llm-embed`, via le script `scripts/llama-autostart.sh`.

**Ce que le système détecte et configure automatiquement :**

- **CPU** : nombre de cœurs disponibles (quotas Docker inclus) → nombre de threads de calcul
- **RAM disponible** → taille de la fenêtre de contexte (plus de RAM = contexte plus grand = réponses plus riches)
- **GPU** : NVIDIA (via `nvidia-smi`), AMD ROCm (via `/dev/kfd`), Vulkan (via `/dev/dri/renderD128`) → nombre de couches du modèle chargées sur le GPU

Sur CPU seul (configuration par défaut), le contexte est fixé à 2048 tokens — ce qui correspond exactement au plafond d'entraînement du modèle fine-tuné standard.

### Vérifier ce qui a été détecté

```bash
# Inspecter le profil détecté et les arguments CLI calculés
curl http://localhost:8080/api/config/resources
```

La réponse indique le type de matériel détecté (CPU-only, NVIDIA, AMD, Vulkan), les paramètres calculés pour le chat et pour l'embedding, et les arguments CLI qui seront passés à `llama-server`.

```bash
# Forcer une nouvelle détection (utile si vous avez ajouté un GPU ou modifié les limites Docker)
curl -X POST http://localhost:8080/api/config/resources/refresh
```

### Surcharger un paramètre

Si les valeurs auto-détectées ne correspondent pas à votre usage, vous pouvez les surcharger via des variables d'environnement dans `.env` (à la racine du projet) :

```env
# Forcer un contexte de 4096 tokens pour le chat
LLAMA_CHAT_CONTEXT_SIZE=4096

# Forcer 8 threads CPU pour le chat
LLAMA_CHAT_THREADS=8

# Désactiver le GPU pour le chat (forcer CPU uniquement)
LLAMA_CHAT_NGL=0
```

Après modification du `.env`, relancez le service concerné :

```bash
docker compose up -d llm-chat
```

**Variables disponibles :**

| Variable | Description |
|----------|-------------|
| `LLAMA_CHAT_CONTEXT_SIZE` | Fenêtre de contexte en tokens pour le chat |
| `LLAMA_CHAT_THREADS` | Threads de calcul pour le chat |
| `LLAMA_CHAT_NGL` | Couches GPU pour le chat (-1 = tout sur GPU, 0 = CPU) |
| `LLAMA_CHAT_FLASH_ATTN` | Flash attention (1 = activé) — réduit la mémoire KV d'environ 2× |
| `LLAMA_CHAT_PARALLELISM` | Conversations simultanées (slots parallèles) |
| `LLAMA_CHAT_CPUSET` | Cœurs CPU réservés au chat (ex. `0-3`) |
| `LLAMA_EMBED_CPUSET` | Cœurs CPU réservés à l'embedding (ex. `4-5`) |
| `LLAMA_EMBED_THREADS` | Threads de calcul pour l'embedding |

### Performances de référence (CPU uniquement)

Ces résultats ont été mesurés sur une configuration CPU-only (aucun GPU), avec le modèle fine-tuné standard et le contexte par défaut de 2048 tokens :

| Scénario | Médiane (P50) | Débit | Succès |
|----------|--------------|-------|--------|
| Embedding (10 requêtes × ~512 tokens) | 801 ms | 639 vecteurs/s | 10/10 |
| Génération LLM pure (3 générations) | 9 234 ms | 36,7 tokens/s | 3/3 |
| RAG complet (5 requêtes, maxChunks=2) | 17 909 ms | 18,0 tokens/s | 5/5 |

**Interprétation pour l'utilisateur :**

- **Embedding** : très rapide — l'indexation de vos documents et la recherche sémantique ne sont pas le goulot d'étranglement.
- **Génération LLM** (~9 s) : c'est le temps de traitement "pur" sans contexte documentaire. Une réponse courte (50 tokens) prend ~1,4 s ; une réponse longue (300 tokens) prend ~8 s.
- **RAG complet** (~18 s) : inclut l'embedding de la question, la recherche dans ChromaDB, et la génération. C'est le temps ressenti lors d'une utilisation normale du Playground.

> Sur GPU NVIDIA avec 8 Go de VRAM, ces temps sont typiquement **5 à 10 fois plus rapides**. Sur un GPU avec 4 Go, le gain est moindre (les couches qui ne tiennent pas en VRAM restent sur CPU).

Pour reproduire ces mesures :
```bash
./scripts/benchmark.sh --api-only
```

---

## 9. Dépannage

**Les services ne démarrent pas**
```bash
docker compose ps                              # état des conteneurs
docker compose logs spectra-api --tail=50      # logs backend
docker compose logs llm-chat --tail=30   # logs serveur chat
docker compose logs llm-embed --tail=30  # logs serveur embedding
```

**L'interface n'est pas accessible sur http://localhost**
```bash
docker compose logs spectra-frontend --tail=20   # logs nginx
# Vérifier que le port 80 n'est pas occupé par un autre service
```

**L'ingestion échoue avec "Echec de l'appel embeddings après 3 tentatives"**
- Vérifiez que `llm-embed` est démarré et healthy : `docker compose ps`
- Vérifiez que `data/models/embed.gguf` existe et est un GGUF valide
- Consultez les logs du serveur d'embedding : `docker compose logs llm-embed --tail=30`
- Si le log indique `input too large` : vos chunks dépassent 2048 tokens — vérifiez la configuration du chunking

**L'ingestion d'URL échoue ou retourne 0 chunks**
- La page peut nécessiter une authentification — Spectra ne gère pas les sessions
- Si la page est dynamique (JavaScript) et que browserless est arrêté, le contenu sera vide ou partiel
- Consultez les logs : `docker compose logs spectra-api --tail=30 | grep -i url`
- Vérifiez que browserless tourne : `docker compose ps spectra-browserless`
- Pour une page statique, vérifiez que l'URL est bien accessible depuis Docker (réseau interne) :
  `docker exec spectra-api wget -qO- "https://example.com/page" | head -20`

**La requête RAG retourne "contexte dépassé"**
- Réduisez `maxContextChunks` à 2 dans votre requête
- Ou augmentez `LLAMA_CHAT_CONTEXT_SIZE` dans `.env` (en divisant par `LLAMA_CHAT_PARALLELISM` pour obtenir le contexte par slot)

**La génération de dataset reste bloquée à 0 paires**
```bash
docker compose logs spectra-api | grep -E "WARN|ERROR|chunk"
```
Causes courantes : modèle trop lent (timeout LLM), chunk trop long pour le contexte du modèle.

**Le modèle génère des caractères illisibles**
- Le fichier GGUF est peut-être corrompu ou tronqué (téléchargement incomplet)
- Vérifiez les 8 premiers octets du fichier : `od -A x -t x1z data/fine-tuning/merged/model.gguf | head -1`
- Les 4 premiers octets doivent être `47 47 55 46` (= `GGUF` en ASCII)

**Réinitialisation complète**
```bash
docker compose down -v    # supprime les volumes (ChromaDB inclus)
docker compose up -d
```

> ⚠️ `down -v` efface toutes les données vectorisées. Vous devrez ré-ingérer vos documents.

---

*Spectra — Transformez vos documents en intelligence artificielle locale.*
