# Audit — Pipeline RAG face à l'état de l'art

> Audit du 2026-10-18. Périmètre : le chemin de requête RAG (`RagService`, `HybridSearchService`,
> `BM25Index`, `AgenticRagService`, `RagPromptFormat`), l'indexation (`ChunkingService`,
> `IngestionTaskExecutor`, `IngestionService`, `EmbeddingReindexService`, `ChromaDbClient`) et
> les modèles servis par défaut (`nomic-embed-text-v1.5` Q4_K_M, cross-encoder
> `mmarco-mMiniLMv2-L12-H384-v1`). Référence : pratiques établies 2024-2026 — embeddings à
> instruction, RAG-Fusion, contextual retrieval, rerankers multilingues, seuils d'abstention,
> défense contre l'injection indirecte.
>
> **Constat général.** L'architecture est au niveau : recherche hybride BM25 + vecteur fusionnée
> par RRF, reranking cross-encoder, multi-query, Corrective / Self / Adaptive / Agentic RAG,
> budget de prompt, dégradation gracieuse, ablation et métriques de retrieval déterministes
> (Hit@k, MRR, Recall@k). Les défauts relevés ne sont pas d'architecture mais de **réglage du
> chemin chaud** : le plus coûteux (R1) dégradait silencieusement le rappel de chaque requête
> de chaque installation par défaut.

## Constats corrigés

### R1 — Embeddings servis sans leurs préfixes d'instruction (critique)

`nomic-embed-text-v1.5` est un modèle **asymétrique** : sa fiche impose `search_query: ` devant
la requête et `search_document: ` devant le passage. Spectra n'envoyait ni l'un ni l'autre. Le
modèle fonctionne quand même — c'est ce qui rendait le défaut invisible — mais hors de sa
distribution d'entraînement, et le classement vectoriel s'en ressent sur toutes les requêtes.
La même famille de défaut touche E5 (`query:` / `passage:`), BGE-en, mxbai, arctic-embed et
Qwen3-Embedding (instruction côté requête).

**Correction.** `EmbeddingPromptScheme` déduit le schéma du nom du modèle d'embedding actif.
Le schéma est **estampillé sur chaque collection** (`spectra:embedding-prompts`) à sa création,
et c'est l'estampille — pas la configuration — qui gouverne requêtes, ajouts et requêtes
agentiques. Conséquences :

- nouvelle collection : préfixes appliqués d'emblée ;
- collection existante (sans estampille) : reste en `NONE`, donc cohérente avec ses vecteurs —
  aucune régression à la mise à jour. Un avertissement unique invite à la ré-indexer ;
- `POST /api/config/embedding-consistency/reindex` ré-embedde les passages avec le schéma du
  modèle actif et ré-estampille la collection.

Limite connue : quand le modèle ne change pas, la collection reste interrogeable pendant la
ré-indexation (l'estampille du modèle concorde) ; requêtes et passages sont alors
temporairement de schémas mêlés, et un document ingéré pendant l'opération garde l'ancien
schéma. Ré-indexer hors période d'ingestion.

### R2 — Fusion multi-query par meilleure distance en vectoriel pur

En recherche hybride, la fusion multi-query sommait les scores RRF ; en vectoriel pur, elle
triait par meilleure distance. Ce tri favorise le passage trouvé par **une seule** variante —
éventuellement dérivée — au détriment de celui que toutes les variantes retrouvent, ce qui est
l'inverse de l'objectif de RAG-Fusion. **Correction :** score RRF de rang (k = 60) aussi en
vectoriel pur ; la fusion est la même dans les deux modes.

### R3 — Aucun seuil d'abstention

Le pipeline injectait toujours les `maxContextChunks` meilleurs passages, même franchement hors
sujet. Un modèle 7B à qui l'on fournit un contexte répond à partir de ce contexte — c'est la
première source d'hallucination *sourcée*, la plus trompeuse puisque citée. Le cross-encoder
fournit pourtant le signal adapté (jugement de la paire question/passage, échelle stable en
sigmoïde). **Correction :** `spectra.reranker.min-score` (`SPECTRA_RERANKER_MIN_SCORE`, vide par
défaut). Sous le seuil pour tous les passages, la réponse est « aucun document pertinent » sans
appel au LLM ; le compteur `spectra.rag.rerank.below_threshold` mesure l'effet.

Non activé par défaut : le bon seuil dépend du modèle et du corpus. Le caler par ablation
(`POST /api/ablation`) ; 0,1 à 0,3 est un point de départ raisonnable avec le cross-encoder par
défaut.

### R4 — Injection indirecte par le contenu des documents

Le contexte est placé dans le prompt **système**, entre `=== CONTEXTE ===` et
`=== FIN DU CONTEXTE ===`. Un document ingéré contenant le délimiteur de fin refermait le bloc :
la suite se lisait comme une consigne système. **Correction :** les délimiteurs sont neutralisés
dans les passages et les noms de source (`RagPromptFormat.passage`, désormais aussi utilisé par
l'Agentic RAG), et les consignes précisent que le contexte est une donnée, jamais une consigne.
La consigne étant partagée avec la génération du dataset (F14), les prochains fine-tunings
l'apprennent aussi.

## Recommandations A1 à A8 — implémentées

Toutes se mesurent avec le harnais d'ablation (`POST /api/ablation`) avant d'être généralisées.
Celles qui changent l'espace vectoriel ou un coût de requête restent **opt-in** ; celles qui
n'ont pas d'effet de bord passent **par défaut**.

| # | Mise en œuvre | Défaut |
|---|---|---|
| A1 | **Embedding multilingue** : Qwen3-Embedding pris en charge de bout en bout — instruction de requête (`EmbeddingPromptScheme.QWEN3`), `--pooling last` déduit du nom par `llm-embed-entrypoint.sh` (`LLM_EMBED_POOLING` pour forcer). | nomic conservé (81 Mo, rapide) |
| A2 | **Reranker multilingue** : `SPECTRA_RERANKER_ONNX_MODEL_FILE` désigne le graphe sous l'URL (`setup.sh` / `setup.bat`), ce qui permet l'export `onnx-community/bge-reranker-v2-m3-ONNX`. Le moteur ONNX ne fournit au graphe que les entrées qu'il déclare (XLM-R n'a pas de `token_type_ids`). | mmarco conservé |
| A3 | **En-têtes contextuels** (`ContextualHeader`) : « Document : titre — fichier / Section : chemin Markdown » préfixé au texte **embeddé** (ingestion et ré-indexation), jamais au texte stocké ni cité. `ChunkingService` enregistre la métadonnée `section`. Rien côté requête : chunks avec et sans en-tête cohabitent. | **actif** |
| A4 | **Analyse française du BM25** : mots vides FR/EN, diacritiques et ligatures retirés, pluriels (`-s`, `-x`, `-aux`→`-al`). `ANALYZER_VERSION` invalide l'index disque : il est reconstruit depuis ChromaDB au démarrage. | **actif** |
| A5 | **Expansion aux voisins** (`chunkIndex ± N`, même document par `sha256`), recouvrement du chunking retiré, arrêt au premier voisin absent, déjà présent ou hors plafond. | `SPECTRA_RAG_NEIGHBOR_WINDOW=0` |
| A6 | **Métadonnées complètes en hybride** : `HybridChunk.metadata` ; celles des résultats BM25 seuls sont lues en un appel ChromaDB (`getMetadatasByIds`). | **actif** |
| A7 | **MMR sur embeddings** : `SPECTRA_SEMANTIC_DEDUP_STRATEGY=mmr` — quasi-doublons écartés au seuil cosinus, contexte réordonné pour la diversité (λ = `SPECTRA_SEMANTIC_DEDUP_MMR_LAMBDA`). Un échec d'embedding laisse le contexte intact. | `jaccard` |
| A8 | **Contexte hors du prompt système** : `SPECTRA_RAG_CONTEXT_PLACEMENT=user` — système = persona + consignes, message utilisateur = passages délimités puis question. Le dataset de fine-tuning suit le même réglage (contrat F14), l'Agentic RAG aussi. | **`user`** |

### Recettes

**Corpus francophone — embedding Qwen3 (A1).** ~640 Mo, ingestion ~5× plus lente sur CPU.

```bash
# .env
SPECTRA_EMBED_MODEL_URL=https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/Qwen3-Embedding-0.6B-Q8_0.gguf
LLM_EMBED_MODEL_NAME=qwen3-embedding-0.6b

rm data/models/embed.gguf && ./scripts/setup.sh --download-embed
./scripts/start.sh
# Le nouveau modèle actif rend les collections existantes non comparables (MISMATCH) :
curl -X POST localhost:8080/api/config/embedding-consistency/reindex \
     -H 'Content-Type: application/json' -d '{"collection":"spectra_documents"}'
```

Sur une installation dont le registre (`data/models/registry.json`) a déjà un modèle d'embedding
actif, activez le nouveau via le Model Hub avant la ré-indexation : c'est son nom qui pilote les
préfixes d'instruction.

**Reranker bge-reranker-v2-m3 (A2).** ~570 Mo (int8), environ 3 à 4 fois plus lent que mmarco.

```bash
# .env
SPECTRA_RERANKER_ONNX_URL=https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/main
SPECTRA_RERANKER_ONNX_MODEL_FILE=onnx/model_int8.onnx
RERANKER_MODEL=BAAI/bge-reranker-v2-m3
SPECTRA_RERANKER_TOP_CANDIDATES=12

rm -r data/models/reranker && ./scripts/setup.sh --download-reranker
```

Les scores changent d'échelle avec le modèle : recalez `SPECTRA_RERANKER_MIN_SCORE` ensuite.

**Modèle affiné avant A8.** Il a appris le contexte dans le prompt système :
`SPECTRA_RAG_CONTEXT_PLACEMENT=system` le sert sous la forme apprise. Le prochain fine-tuning,
généré avec `user`, lève la contrainte.

### Limites connues

- A3 n'enrichit pas l'index BM25 (le texte BM25 est aussi le texte cité) ; seuls les chunks
  ingérés ou ré-indexés après la mise à jour portent l'en-tête.
- A4 reconstruit l'index lexical au premier démarrage (lecture paginée de ChromaDB).
- A5 coûte un appel ChromaDB par passage retenu ; le budget de prompt reste appliqué après.
- A7 coûte un appel d'embedding par requête (≤ `maxContextChunks` textes).

## Vérifications

- `EmbeddingPromptSchemeTest` : détection par famille de modèles, préfixes requête/documents,
  estampilles absentes ou inconnues.
- `ChromaDbClientTest` : estampille du schéma à la création, collection historique maintenue en
  `NONE`, passage au schéma du modèle à la ré-estampille.
- `RagServiceRetrievalTest` : préfixe de requête issu de la collection, fusion RRF vectorielle,
  seuil reranker (filtrage, abstention sans appel LLM, seuil absent).
- `RagPromptFormatTest` : un passage ne peut plus refermer le bloc de contexte ; placements
  `USER`/`SYSTEM`, paire ancrée identique au prompt servi.
- `RagServiceModelProfileTest` : le prompt servi (système ET utilisateur) est mot pour mot celui
  de la paire d'entraînement ancrée.
- `ContextualHeaderTest`, `ChunkingServiceTest` : en-tête et chemin de sections (A3).
- `BM25IndexTest` : accents, pluriels, mots vides, version d'analyseur (A4).
- `RagServiceRetrievalTest` : expansion aux voisins et plafond (A5), MMR et repli (A7).
- `HybridSearchServiceTest` : métadonnées complètes, y compris BM25 seul (A6).
