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

## R5 — Contrat d'embedding, taille réelle des entrées, ré-indexation versionnée

**Constats.**
- Le chunking compte en **cl100k**, un vocabulaire que le modèle d'embedding n'utilise pas.
  nomic-embed-text tokenise en WordPiece (BERT, anglais) : sur du français, nettement plus de
  tokens pour le même texte, auxquels s'ajoutent préfixe de tâche et en-tête contextuel. Rien ne
  vérifiait que le texte réellement embeddé tenait dans la fenêtre.
- La fenêtre elle-même était mal connue : le GGUF officiel de nomic sert **2048 tokens** sous
  llama.cpp (8192 seulement avec RoPE YaRN, que `llm-embed` ne configure pas), et `llm-embed`
  partage `-c 8192` entre `--parallel 4` requêtes, soit 2048 par requête.
- **Le chevauchement faisait déborder les chunks** : après un chunk émis, les 64 tokens repris
  précédaient le paragraphe suivant sans revérifier la somme — jusqu'à 565 tokens pour une
  limite de 512 (reproduit par `ChunkingServiceTest.overlapNeverPushesAChunkBeyondTheLimit`).

**Corrections.**
- `EmbeddingContract` : modèle, préfixes (`EmbeddingPromptScheme`, contrat officiel de chaque
  famille), version de la construction du texte embeddé, fenêtre = min(limite de la famille,
  `SPECTRA_EMBEDDING_MAX_INPUT_TOKENS`). Le contrat d'une collection se lit de ses estampilles ;
  la fenêtre vient du modèle, pas des préfixes estampillés.
- `EmbeddingInputGuard` : borne sûre sans appel (un token couvre au moins un octet UTF-8), sinon
  comptage par `/tokenize` du GGUF servi ; redécoupage par phrases et renumérotation des
  `chunkIndex` à l'ingestion, troncature du texte embeddé à la ré-indexation (identité figée,
  texte stocké intact), troncature des questions trop longues. Tokenizer indisponible :
  avertissement unique, rien n'est modifié.
- `ChunkingService` : le chevauchement est borné par la place laissée au segment suivant, et
  recompté après décodage (une coupe au milieu d'un caractère multi-octets se ré-encode plus long).
- Ré-indexation versionnée : `spectra:embedding-text-version` (1 = texte brut, 2 = préfixe +
  en-tête) ; `GET /api/config/embedding-consistency` publie `contract`, `currentContract` et
  `reindexRecommended` ; la ré-indexation trace `fromContract` → `toContract` et estampille le
  contrat courant en dernier.

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

- A3 : seuls les chunks ingérés ou ré-indexés après la mise à jour portent l'en-tête embeddé.
  Côté BM25, titre, nom de fichier et section sont indexés dans un **champ séparé** du texte cité
  (statistiques propres, poids 0,5) : une question qui nomme un document ou une section remonte
  ses chunks aussi par mots-clés. Le changement d'analyse (`ANALYZER_VERSION` 3) reconstruit
  l'index lexical depuis ChromaDB au premier démarrage ; les métadonnées y étant déjà, tous les
  chunks en profitent, sans ré-indexation des vecteurs.
- A4 reconstruit l'index lexical au premier démarrage (lecture paginée de ChromaDB).
- A5 coûte un appel ChromaDB par passage retenu ; le budget de prompt reste appliqué après.
- A7 coûte un appel d'embedding par requête (≤ `maxContextChunks` textes).

## Fiabilité de bout en bout — citations, calibration, filtres

### Vérification des citations après génération

Le pipeline choisissait ses passages ; rien ne contrôlait que la réponse s'y tenait. Un 7B cite
volontiers `[2]` derrière une affirmation que le passage 2 ne contient pas. `CitationVerifier`
découpe la réponse en phrases, retire les marqueurs `[n]` et note chaque phrase citée contre ses
seuls passages cités avec le cross-encoder déjà chargé pour le reranking : aucun appel LLM, un
résultat reproductible, quelques dizaines de millisecondes.

La réponse (`POST /api/query`, et l'étape `citations` du streaming) porte un rapport
`citations` : phrases étayées / non étayées, citations invalides (`[7]` pour 5 passages),
phrases sans citation, `faithfulness` = étayées / citées, et le détail par phrase.

| Variable | Défaut | Effet |
|---|---|---|
| `SPECTRA_RAG_CITATION_CHECK` | `true` | Vérification active (sans effet si le reranker n'est pas chargé). |
| `SPECTRA_RAG_CITATION_MIN_SCORE` | `0.2` | Score sigmoïde minimal du meilleur passage cité. |
| `SPECTRA_RAG_CITATION_STRICT` | `false` | Retire les phrases non étayées de la réponse JSON ; si rien ne reste, réponse d'abstention. En streaming, la réponse est déjà partie : le rapport la qualifie seulement. |

Limite : le cross-encoder juge la **pertinence** de la phrase pour le passage, pas
l'implication logique. Une phrase qui contredit son passage sur le même sujet peut passer : c'est
un signal de fidélité, pas une preuve. Le mode strict est à réserver aux usages où une phrase
retirée à tort coûte moins qu'une phrase fausse sourcée.

### Calibration sur le corpus

Les seuils par défaut sont des valeurs génériques ; seule une mesure sur **vos** documents les
justifie.

1. **Jeu d'évaluation.** `POST /api/quality-benchmark/draft?size=40` tire un passage par
   document, à tour de rôle, et en fait une question et sa réponse attendue
   (`expectedSources` = le document du passage), écrites dans
   `data/benchmarks/draft-<horodatage>.jsonl`. C'est un **brouillon** : reformuler les questions
   trop proches du texte (sinon le BM25 est avantagé), corriger les réponses, retirer les passages
   sans intérêt, et ajouter 10 à 20 % de questions hors corpus (`"answerable": false`), que le
   générateur ne peut pas produire. Le fichier relu se déclare par
   `SPECTRA_BENCHMARK_QUALITY_FILE` (chemin vu du conteneur, ex. `/app/data/benchmarks/corpus.jsonl`).
2. **Grille.** `POST /api/ablation/calibrate` transforme une grille en bras d'ablation (16 au
   plus), chacun surchargeant pour la seule requête le seuil du reranker, la fenêtre de voisins et
   la déduplication :

   ```json
   {"rerankMinScores": [0, 0.1, 0.2, 0.3], "neighborWindows": [0, 1],
    "dedupStrategies": ["jaccard", "mmr"], "runs": 1}
   ```

   Le job se suit comme une ablation (`GET /api/ablation/jobs/{jobId}`).
3. **Lecture.** Chaque bras porte, en plus du rappel, du MRR, du score du juge et du taux
   d'hallucination, la fidélité moyenne des citations. Le rapport désigne `recommendedArm`, qui
   maximise `0.35·recall@k + 0.25·MRR + 0.25·fidélité + 0.15·score/10 − 0.25·hallucination`
   (à égalité, le plus rapide). Reporter ses valeurs dans `.env` ; un écart inférieur à l'écart-type
   (`runs` > 1) n'est pas significatif.

### Filtres de recherche

`QueryRequest.filter` restreint la recherche **avant** le classement, aux deux index :

```json
{"question": "Quel est le délai de préavis ?",
 "filter": {"categories": ["juridique"], "sources": ["contrat-2026.pdf"],
            "ingestedAfter": "2026-01-01T00:00:00Z", "ingestedBefore": "2026-07-01T00:00:00Z"}}
```

Catégories GED et date d'ingestion ne vivent que sur la fiche du document : `RetrievalFilterResolver`
les résout en empreintes `sha256` (et noms de source pour les chunks historiques sans
empreinte), transmises à ChromaDB en clause `where` et au BM25 en prédicat. Les recopier dans
chaque chunk aurait obligé à réécrire l'index à chaque reclassification. Un filtre qui ne retient
aucun document répond sans interroger les index ; un filtre actif désactive le Long-Context RAG
(qui charge tout le corpus). Sans accès à la GED, un filtre par catégorie ou par date est refusé
plutôt qu'ignoré.

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
- `CitationVerifierTest` : découpage en phrases (citation après le point ou à la ligne),
  étayée / non étayée / invalide, seuil, indisponibilité, mode strict.
- `RetrievalFilterResolverTest`, `HybridSearchServiceTest`, `BM25IndexTest`, `ChromaDbClientTest` :
  résolution GED par collection, catégorie et date, clause `where`, prédicat BM25.
- `RagServiceFilterCitationTest` : filtre transmis aux index, filtre vide sans recherche, refus
  sans GED, seuil du reranker surchargé, rapport et mode strict sur `query`.
- `CalibrationRequestTest`, `RagAblationServiceTest`, `BenchmarkDraftServiceTest` : grille et
  plafond, objectif et bras recommandé, brouillon tiré du corpus.
- `BM25IndexTest`, `FtsServiceTest`, `ContextualHeaderTest` : champ d'en-tête du BM25 (appoint
  au texte, texte cité intact, suppression et recopie, relecture depuis ChromaDB).
