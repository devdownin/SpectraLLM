# Baseline EnterpriseRAG-Bench v1 — cadrage de l'optimisation Qdrant

Première étape de l'optimisation de SpectraLLM sur
[EnterpriseRAG-Bench](enterprise-rag-bench.fr.md) : inventaire du pipeline, métriques
et baseline chiffrée sur une configuration figée. Les essais suivants se comparent à cette
baseline avec `enterprise_rag_experiment.py compare`, sur les mêmes questions et le même index.

| Élément figé | Fichier |
|---|---|
| Variables de la pile (Compose) | [`deploy/bench/enterprise-rag-baseline-v1.env`](../../deploy/bench/enterprise-rag-baseline-v1.env) |
| Profil consigné dans chaque `run.json` | [`deploy/bench/enterprise-rag-baseline-v1.profile.json`](../../deploy/bench/enterprise-rag-baseline-v1.profile.json) |
| Exécution de bout en bout | [`scripts/enterprise_rag_baseline.sh`](../../scripts/enterprise_rag_baseline.sh) |

Ne pas modifier ces fichiers après la première mesure. Une variante se décrit dans un nouveau
fichier `.env` et un nouveau profil.

## 1. Inventaire du pipeline

| Étape | Baseline v1 | Réglage |
|---|---|---|
| Parsing | Fichiers `.txt` produits par `prepare` (en-tête `[doc_id] [source] titre`), extraction texte, layout-parser désactivé | `SPECTRA_LAYOUT_PARSER_ENABLED` |
| Chunking | 256 tokens, chevauchement 64, coupe aux phrases (locale `fr`), en-têtes contextuels | `SPECTRA_CHUNK_MAX_TOKENS`, `…_OVERLAP_TOKENS`, `spectra.pipeline.chunk-locale` |
| Embedding | nomic-embed-text v1.5 Q4_K_M (768 dim.) via llama.cpp, préfixes `search_query:` / `search_document:`, 2 048 tokens max | `LLM_EMBED_MODEL_*` |
| Qdrant | v1.16.3, vecteur dense cosinus, HNSW `ef_construct=200`, `ef` par défaut, sans int8, sans groupes, hybride natif désactivé | `SPECTRA_QDRANT_*` |
| Lexical | BM25 Java (top 20, poids 1) fusionné par RRF | `SPECTRA_HYBRID_*` |
| Reranking | ONNX `mmarco-mMiniLMv2-L12-H384-v1`, 20 candidats, 10 passages en contexte | `SPECTRA_RERANKER_*`, `maxContextChunks` |
| Prompt | `RagPromptFormat` : consignes en français, passages numérotés `[n]`, contexte dans le message utilisateur, style concis, suffixe « Answer in English. » | `SPECTRA_RAG_*` |
| Génération | Qwen2.5-7B-Instruct Q4_K_M, llama.cpp `server-b12110`, 8 192 tokens par requête, température 0 | `LLM_CHAT_*`, `LLM_CONTEXT`, `LLM_PARALLEL` |
| Modules | multi-query, dédup sémantique, Self-RAG, CRAG, adaptatif, agentique : désactivés | `SPECTRA_*_ENABLED` |

Points d'attention relevés pour les étapes suivantes, sans les corriger dans la baseline :

- Le corpus est en anglais, mais la segmentation utilise la locale `fr`, et les consignes du prompt
  sont en français.
- Compose fixe les chunks à 256 tokens, alors que la valeur par défaut d'`application.yml` est de 512.
  Toujours préciser la valeur dans le profil.
- `top-candidates` vaut 50 par défaut dans `enterprise_rag_experiment.py run` et 20 côté serveur.
  La baseline force 20.

## 2. Métriques

Toutes les métriques sont calculées par question puis moyennées. `report` donne la moyenne et son
IC95 % bootstrap ; `compare` donne l'écart apparié et son IC95 %.

| Famille | Métrique | Définition | Source |
|---|---|---|---|
| Retrieval | `recall@k` (k = 1, 3, 5, 10) | part des documents de référence dans les k premiers documents distincts | `report`, `compare` (@10) |
| | `mrr@10` | 1 / rang du premier document de référence, 0 au-delà de 10 | `report`, `compare` |
| | `ndcg@10` | nDCG binaire, idéal = références en tête | `report`, `compare` |
| | `all-gold@10` | questions à ≥ 2 références dont toutes sont retrouvées | `report`, `compare` |
| | `extra@10` | documents hors référence dans le top 10 (bruit) | `report`, `compare` |
| Génération | exactitude (`correct`) | réponse jugée correcte par le juge Onyx, en % | juge |
| | complétude | `completeness_pct` du juge | juge |
| | `score` | complétude si correcte, 0 sinon (critère du classement) | juge |
| Fidélité | `citation_rate` | réponses citant au moins un passage `[n]` valide | `report`, sans juge |
| | `cited_gold_precision` | part des documents cités qui sont des références | `report`, sans juge |
| | `extra` (juge) | documents cités jugés invalides par Onyx | juge |
| Latence | p50, p95, max (ms) | `client` : temps vu par le client ; `server` : `durationMs` de l'API | `report`, `compare` |

Les questions sans documents de référence (`info_not_found`, `high_level`) ne comptent pas
dans les métriques de retrieval. Le mode `retrieve` sert au réglage rapide sans génération.
Le mode `answer` est jugé par l'évaluateur Onyx figé (même modèle de juge pour toutes les
variantes).

## 3. Mesurer la baseline

```bash
docker compose --project-directory . -f deploy/docker/docker-compose.yml \
  --env-file deploy/bench/enterprise-rag-baseline-v1.env up -d --wait
git clone https://github.com/onyx-dot-app/EnterpriseRAG-Bench ../EnterpriseRAG-Bench
git -C ../EnterpriseRAG-Bench checkout --detach d36685e273713975ee20299bbf1ab64165575b3c
python3 -m venv ../EnterpriseRAG-Bench/.venv
../EnterpriseRAG-Bench/.venv/bin/python -m pip install -r ../EnterpriseRAG-Bench/requirements.txt
export LLM_API_KEY=…   # juge
scripts/enterprise_rag_baseline.sh
```

Le script prépare le corpus (références + 5 000 distracteurs), recrée le lot `dev-120`, indexe,
exécute les modes `retrieve` puis `answer`, juge les réponses et écrit `report.json` dans
`data/onyx-baseline-v1/baseline-retrieve` et `…/baseline-answer`. Avant de reporter les chiffres,
vérifier que `applied.rerankApplied` et `applied.hybridSearchApplied` valent 1, puis remplacer
`backend_revision` du profil par le digest réellement déployé (dans un nouveau répertoire si la
mesure a déjà commencé).

## 4. Résultats de la baseline v1 (dev-120)

> **Statut : à mesurer.** Les chiffres doivent provenir de `report.json`. L'environnement de
> préparation de ce cadrage n'avait accès ni à Docker ni à Hugging Face.

| Métrique | Valeur | IC95 % |
|---|---|---|
| recall@10 | — | — |
| mrr@10 | — | — |
| ndcg@10 | — | — |
| all-gold@10 | — | — |
| extra@10 | — | — |
| exactitude (%) | — | — |
| complétude (%) | — | — |
| score (%) | — | — |
| citation_rate | — | — |
| cited_gold_precision | — | — |
| latence retrieve p50 / p95 (ms) | — | |
| latence answer p50 / p95 (ms) | — | |
