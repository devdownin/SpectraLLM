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

## Recommandations non implémentées

Classées par rapport gain/coût. Chacune se mesure avec le harnais d'ablation existant avant
d'être adoptée.

| # | Recommandation | Gain attendu | Coût / risque |
|---|---|---|---|
| A1 | **Modèle d'embedding multilingue.** Le corpus cible est francophone (`chunk-locale: fr`), nomic v1.5 est anglo-centré. Candidats GGUF : `Qwen3-Embedding-0.6B` (multilingue, instruction gérée par R1, `--pooling last`), `bge-m3`, `multilingual-e5-base`. | Fort sur corpus FR | Ré-indexation ; vérifier mémoire `llm-embed` |
| A2 | **Reranker multilingue récent.** `mmarco-mMiniLMv2-L12` date de 2021 ; `bge-reranker-v2-m3` (export ONNX) le surclasse nettement en français. | Fort | Latence CPU ×3-4 : réduire `top-candidates` à 10-15 |
| A3 | **En-têtes contextuels de chunk** (version déterministe du *Contextual Retrieval*) : préfixer, à l'indexation seulement, titre du document et chemin de sections. Un chunk « La valeur est 512 » ne contient pas son sujet. | Fort sur documents structurés | Ré-ingestion ; l'en-tête ne doit pas entrer dans le texte cité |
| A4 | **Analyse française du BM25** : stop-words et racinisation légère (pluriels, élisions déjà gérées). | Moyen (hybride) | Reconstruction de l'index FTS |
| A5 | **Expansion aux chunks voisins** (`chunkIndex ± 1`) après reranking, dans la limite du budget. | Moyen sur réponses à cheval sur deux chunks | Budget de prompt |
| A6 | **Métadonnées conservées en hybride** : `executeSingleQuery` ne garde que `sourceFile` ; page et `chunkIndex` sont perdus, ce qui bloque A5 et des citations plus fines. | Prérequis A5 | Faible |
| A7 | **Déduplication réellement sémantique / MMR** : la « semantic dedup » actuelle est un Jaccard lexical. Un MMR sur embeddings diversifie le contexte. | Faible à moyen | Embeddings des candidats à conserver |
| A8 | **Contexte hors du prompt système** : le placer dans le message utilisateur réduit l'autorité accordée au texte des documents (R4 en profondeur). | Sécurité | Contrat F14 : à faire avec un ré-entraînement |

## Vérifications

- `EmbeddingPromptSchemeTest` : détection par famille de modèles, préfixes requête/documents,
  estampilles absentes ou inconnues.
- `ChromaDbClientTest` : estampille du schéma à la création, collection historique maintenue en
  `NONE`, passage au schéma du modèle à la ré-estampille.
- `RagServiceRetrievalTest` : préfixe de requête issu de la collection, fusion RRF vectorielle,
  seuil reranker (filtrage, abstention sans appel LLM, seuil absent).
- `RagPromptFormatTest` : un passage ne peut plus refermer le bloc de contexte.
