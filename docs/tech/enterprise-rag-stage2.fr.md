# Étape 2 — piste recherche (reranker, multi-query, Qdrant)

Compare des variantes de recherche à la [baseline v1](enterprise-rag-baseline.fr.md) avec la commande
`suite` d'`enterprise_rag_experiment.py` (voir [le guide du banc](enterprise-rag-bench.fr.md)), en
mode `retrieve` sur `dev-120`, avec le même corpus et le même index. Les 380 questions de
validation ne servent qu'à confirmer le réglage retenu.

| Variante | Réglage | Redémarrage |
|---|---|---|
| 20 contre 50 candidats du reranker | `topCandidates` par requête | non |
| question seule contre question + 2 reformulations | `multiQuery` par requête | non, si le module est activé |
| regroupement Qdrant par document | `SPECTRA_QDRANT_GROUP_SIZE=2` | API seule |

## Mise en place

Deux piles sur le même index, issues de la baseline, avec leurs propres ports et noms de conteneurs
(deux hôtes Docker ou deux répertoires de projet) :

- **référence** : `--env-file deploy/bench/enterprise-rag-baseline-v1.env --env-file deploy/bench/stage2/reference.env`
  (multi-query activé, regroupement 0) ;
- **groupée** : les deux mêmes fichiers puis `--env-file deploy/bench/stage2/group2.env`
  (`SPECTRA_QDRANT_GROUP_SIZE=2`).

Le profil de `suite` est celui de la baseline, avec un `backend_revision` réel.

```bash
python3 scripts/enterprise_rag_experiment.py suite --retrieval-only \
  --api "$API" --grouped-api "$GROUPED_API" --collection "$COLLECTION" \
  --split-dir "$WORK/split" --corpus-dir "$WORK/documents" \
  --profile deploy/bench/enterprise-rag-baseline-v1.profile.json --out "$WORK/stage2"
python3 scripts/enterprise_rag_stage2_summary.py "$WORK/stage2/report.json"
for d in "$WORK"/stage2/retrieve-*; do python3 scripts/enterprise_rag_experiment.py report --out "$d" >/dev/null; done
```

`suite` classe les variantes ; `enterprise_rag_stage2_summary.py` applique la règle d'adoption à chaque
comparaison, et `report` écrit les métriques absolues (rappel@k, MRR, nDCG, latence) de chaque variante.
Sans `--retrieval-only`, `suite` génère et juge ensuite les deux meilleures variantes.

## Règle d'adoption

Adopter une variante si, sur la comparaison appariée :

- `recall@10` **et** `ndcg@10` progressent, IC95 % strictement positif ;
- `extra@10` n'augmente pas significativement ;
- le p95 client reste inférieur ou égal à 2 × celui de la référence (`--max-p95-ratio`).

`mrr@10` et `all-gold@10` servent au diagnostic. Le bloc `variants` du rapport indique combien de
questions ont réellement utilisé le reranking, l'hybride et les reformulations : un écart n'a de sens
que si ces taux sont proches de 1.

## Hors de `suite`

À mesurer à part, avec les procédures du guide du banc :

- **hybride natif Qdrant** : index clairsemé à réingérer, comparé avec `enterprise_rag_diagnostics.py native` ;
- **autre reranker** (BGE v2-M3) : artefact ONNX et tokenizer compatibles via `SPECTRA_RERANKER_ONNX_PATH`.

## Résultats

> **Statut : à mesurer.** Aucune série n'a été exécutée (pas de Docker ni d'accès à Hugging Face dans
> l'environnement de préparation).

| Variante | Δ recall@10 | Δ ndcg@10 | Δ extra@10 | p95 (ms) | Décision |
|---|---|---|---|---|---|
| 50 contre 20 candidats | — | — | — | — | — |
| multi-query on contre off | — | — | — | — | — |
| regroupement 2 contre 0 | — | — | — | — | — |
