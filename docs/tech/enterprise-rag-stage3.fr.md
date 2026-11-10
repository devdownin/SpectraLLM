# Étape 3 — audit du retrieval (embeddings, hybride, reranker, Qdrant)

Compare des configurations de retrieval sur `dev-120` en mode `retrieve` (sans génération ni juge),
avec le même corpus, les mêmes questions et 10 passages rendus. Livrable : un tableau rappel/latence par
configuration, produit par `enterprise_rag_matrix.py`. Référence : la [baseline v1](enterprise-rag-baseline.fr.md).

## Deux familles de réglages

| Famille | Réglages | Coût |
|---|---|---|
| **Index** (réindexation, un index et une pile par valeur) | embedding : nomic (baseline), BGE-M3, Qwen3-Embedding ; vecteurs clairsemés (`index-native.env`) ; quantification int8 (`index-int8.env`) ; graphe HNSW `m` / `ef_construct` (`index-hnsw-m32.env`) | ingestion complète |
| **Recherche** (même index) | dense seul, hybride Java, + reranker ; `hnsw_ef`, recherche exacte ; filtre de payload et ACORN ; stratégie de l'hybride natif ; `oversampling` int8 | redémarrage de l'API seule, ou aucun |

Les surcharges Compose sont dans `deploy/bench/stage3/`. Elles s'empilent après la baseline
(`--env-file deploy/bench/enterprise-rag-baseline-v1.env --env-file deploy/bench/stage3/<fichier>`).
`index-hnsw-m32.env` s'appuie sur les nouveaux réglages `SPECTRA_QDRANT_HNSW_M` et
`SPECTRA_QDRANT_HNSW_EF_CONSTRUCT` (défauts : `m` de Qdrant, 16, et `ef_construct` 200, soit le
comportement précédent). Ils ne s'appliquent qu'aux collections créées après le changement.

## Embeddings candidats

| Modèle | Dimension | Préfixe de requête | Pooling | Fichier |
|---|---|---|---|---|
| nomic-embed-text v1.5 (baseline) | 768 | `search_query:` / `search_document:` | modèle | `embed.gguf` |
| BGE-M3 | 1 024 | aucun (schéma `NONE`) | CLS | `embed-bge-m3.env` |
| Qwen3-Embedding 0.6B | 1 024 | instruction de tâche côté requête (schéma `QWEN3`) | dernier token, déduit du nom | `embed-qwen3.env` |

Les noms de fichiers GGUF des deux overlays n'ont pas pu être vérifiés ici : les corriger avant usage.
Le nom logique du modèle (`bge-m3`, `qwen3-embedding-0.6b`) pilote le schéma de préfixes et le pooling ; une
collection mémorise le schéma de sa création. Le fenêtrage par requête reste à 2 048 tokens (chunks de 256).

## Procédure

Pour chaque index (une pile et un stockage Qdrant dédiés par index, voir le guide du banc pour l'isolement),
avec le même `$WORK/documents` et `$WORK/split` :

```bash
# 1. démarrer la pile de l'index, ingérer le corpus (scripts/enterprise_rag_bench.py ingest), vérifier ingest_errors.log
export AUDIT_ENV_FILES="deploy/bench/enterprise-rag-baseline-v1.env deploy/bench/stage3/embed-bge-m3.env"
# 2. audit de cet index : modes de recherche, puis paramètres ANN (et hybride natif si l'index est clairsemé)
scripts/enterprise_rag_audit.sh bge-m3 modes ann
scripts/enterprise_rag_audit.sh bge-m3-native native      # index-native.env
```

L'index de la baseline s'audite de la même façon (`nomic`). Le tableau final se calcule sur tous les
index, avec l’hybride + reranker de l’index actuel comme référence :

```bash
python3 scripts/enterprise_rag_matrix.py --audit-dir "$WORK/audit" --reference nomic/hybrid-rerank
```

Il écrit `matrix.md` et `matrix.json`. Chaque ligne donne recall@10, nDCG@10, MRR@10, all-gold@10,
extra@10, p50 et p95 client ; avec `--reference`, les écarts appariés de recall@10 et nDCG@10 (`*` si l'IC95 %
exclut 0). ★ marque les configurations non dominées en rappel et en p95.

## Variantes mesurées par `enterprise_rag_audit.sh`

| Groupe | Variante | Réglage | Isole |
|---|---|---|---|
| `modes` | `dense` | `hybrid=off`, `rerank=off` | embedding seul |
| | `hybrid` | `hybrid=on`, `rerank=off` | apport de BM25 + RRF |
| | `hybrid-rerank` | `hybrid=on`, `rerank=on` | apport du reranker (baseline) |
| | `*-filter0.1`, `*-filter0.01` | filtre `sources` laissant 10 % ou 1 % du corpus | recherche filtrée |
| `ann` | `dense-ef64/128/256`, `dense-exact` | `SPECTRA_QDRANT_HNSW_EF`, `…_EXACT` | perte de rappel de l'ANN (la recherche exacte est le plafond) |
| | `dense-acorn-filter0.01` | `SPECTRA_QDRANT_ACORN` sous filtre à 1 % | ACORN |
| `native` | `native-dense/sparse/rrf/dbsf`, `native-rrf-rerank` | `SPECTRA_QDRANT_HYBRID_STRATEGY` | fusion et lexical natifs de Qdrant |

Le filtre de payload est bâti par question : documents de référence plus tirage reproductible de
distracteurs jusqu'à la sélectivité visée (`--filter-ratio`). Il ne teste pas la pertinence, mais la tenue du
rappel et de la latence de la recherche approchée filtrée : le rappel ne doit pas baisser sous filtre, la
latence augmente quand la sélectivité est forte (ACORN vise ce cas). Spectra indexe déjà `metadata.sha256`, `sourceFile`, `chunkIndex` et `zipEntry` (type keyword) à la création
d'une collection : le filtre `sources` en profite. Les filtres par catégorie ou par date passent par la fiche GED
et ne sont pas couverts par cet audit.

## Lecture

- Vérifier d'abord `applied` dans chaque `report.json` : `rerankApplied` doit valoir 1 pour les variantes avec
  reranker et 0 pour les autres, `hybridSearchApplied` suivre `hybrid`. Sinon la variante n'a pas mesuré ce
  qu'elle annonce.
- Les écarts de p95 d'un embedding à l'autre incluent le coût de vectorisation de la question.
- La quantification et le graphe se jugent sur le rappel face à `dense-exact` du même index, puis sur la RAM
  de Qdrant (`docker compose stats --no-stream qdrant`), non mesurée par ces scripts.
- Un gain doit se confirmer sur les 380 questions de validation avant d'être retenu.

## Résultats

> **Statut : à mesurer.** Aucune configuration n'a été exécutée (pas de Docker ni d'accès à Hugging Face dans
> l'environnement de préparation). Coller ici le contenu de `matrix.md`.

| Index | Variante | recall@10 | ndcg@10 | p95 (ms) |
|---|---|---|---|---|
| — | — | — | — | — |
