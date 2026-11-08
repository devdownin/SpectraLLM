# Pilote Qdrant pour SpectraLLM

Le pilote remplace la recherche dense pour une seule collection Chroma identifiée par UUID. Le RAG standard, hybride et agentique utilisent ce chemin commun. BM25, le reranker, les écritures et les opérations de gestion gardent leur configuration habituelle. Le pilote est désactivé par défaut.

## Préparer et comparer

Utiliser une **instance de test isolée** avec une copie cohérente des données Chroma, du catalogue SQL, du registre des modèles et de l’index lexical. Garder le même modèle d’embedding pour les deux mesures. Suspendre toute mutation pendant l’export et les mesures, y compris la synchronisation des sources, les suppressions, la réindexation et les changements de modèle. Un changement de contenu qui conserve le même nombre de chunks n’est pas détectable par les contrôles de comptage.

Depuis la racine du dépôt, avec la pile de test disponible sur le port Chroma local 8000 :

```bash
export SPECTRA_QDRANT_API_KEY="$(openssl rand -hex 32)"
compose=(docker compose --project-directory . -f deploy/docker/docker-compose.yml \
  -f deploy/docker/docker-compose.qdrant-pilot.yml)

"${compose[@]}" up -d qdrant-pilot

python3 scripts/import_qdrant_pilot.py \
  --chroma http://127.0.0.1:8000 \
  --source NOM_COLLECTION \
  --target spectra_pilot_essai1
```

L’import pagine les chunks, conserve les vecteurs, textes, métadonnées et identifiants Chroma, puis vérifie les comptages. Il publie l’alias après validation du chargement. Utiliser un alias neuf à chaque import et sérialiser les imports. En cas d’échec, le script peut laisser une collection de travail isolée dont le nom figure dans la sortie. Une collection sans distance cosinus explicite ou sans chunk est refusée.

Mesurer d’abord la pile de test avec le pilote désactivé. Pour la seconde passe, utiliser les valeurs affichées par le script :

```bash
export SPECTRA_QDRANT_SOURCE_ID=UUID_AFFICHE
export SPECTRA_QDRANT_TARGET=spectra_pilot_essai1
export SPECTRA_QDRANT_PILOT_ENABLED=true
"${compose[@]}" up -d --build --no-deps spectra-api
```

## Mesurer les deux moteurs

Le corpus `examples/highway/` et ses questions annotées permettent une première mesure reproductible sur une collection de test. Pour un corpus métier, fournir un fichier JSONL au même format. Le lanceur ne modifie pas la collection. Exécuter la première capture avant activation du pilote et la seconde après activation, quand l’API est prête et que l’optimisation Qdrant est terminée.

```bash
suite=backend/src/main/resources/benchmarks/highway_benchmark.jsonl
# Remplacer NOM_COLLECTION par le nom de la même collection dans les deux passes.
python3 scripts/compare_qdrant_pilot.py capture --engine chroma \
  --collection NOM_COLLECTION --questions "$suite" --output chroma.json
# Activer le pilote comme indiqué plus haut.
python3 scripts/compare_qdrant_pilot.py capture --engine qdrant \
  --collection NOM_COLLECTION --questions "$suite" --output qdrant.json
python3 scripts/compare_qdrant_pilot.py compare chroma.json qdrant.json
```

Exporter `SPECTRA_API_KEY` si l’API est protégée. Le rapport compare les latences serveur et client p50/p95, le rappel des sources annotées et les changements de classement des sources et des passages. L’option `--hybrid` active BM25 dans une deuxième comparaison. L’option `--generate` ajoute la génération et les compteurs du vérificateur de citations lorsqu’il est actif. Utiliser les mêmes options dans chaque paire et des noms de fichiers distincts. `--engine` étiquette la capture. Vérifier que l’UUID du pilote correspond à la collection et examiner `pipelineFlagsChanged` avant d’interpréter les mesures. Pour évaluer des écarts de latence faibles, augmenter `--repeat` et inverser l’ordre des moteurs lors d’une seconde paire.

Pour le corpus métier, rejouer les paires avec `--concurrency 1`, puis avec le nombre de requêtes simultanées habituel. Faire cette mesure en dense et en hybride, puis une passe `--generate` à part pour les citations. Le rapport donne aussi le débit observé, le gain de p95 client et l’écart de rappel en points. Comparer uniquement des captures de même mode et de même concurrence. Une capture ancienne sans champ de concurrence doit être comparée à une capture de la même version du lanceur.

Une ligne peut inclure `filter` au format de l’API, `expectedSources` (noms exacts ou noms de fichiers sans extension) et `expectEmpty` pour un cas sans résultat attendu. La comparaison sort en erreur si une source renvoyée échappe à un filtre explicite `sources` ou si un cas déclaré vide renvoie des sources. Pour les catégories, les homonymes et les révisions de connecteurs, construire des cas annotés sur le corpus cible et inspecter les passages et leur provenance. Valider séparément les appels MCP et leurs droits.

Un incident Qdrant suit la gestion d’erreur existante du pipeline. En mode hybride, celle-ci peut laisser fonctionner la branche lexicale. Le pilote ne relance pas discrètement la même recherche dense dans Chroma, afin que les mesures et les incidents restent identifiables.

## Confirmer le moteur utilisé

Sur la pile de test isolée, relever les compteurs avant et après chaque capture. Les compteurs repartent à zéro au redémarrage. Le compteur dense compte une recherche logique, y compris lorsqu’un moteur retourne zéro passage. Une recherche qui n’interroge aucun moteur à cause d’un filtre vide ne l’incrémente pas. Le compteur BM25 s’incrémente seulement lorsque des passages lexicaux sont disponibles et qu’aucun passage dense ne participe à la fusion.

| Compteur Micrometer | Tags | Interprétation |
| --- | --- | --- |
| `spectra.retrieval.dense` | `engine=chroma\|qdrant`, `outcome=success\|error` | Recherches exécutées par moteur |
| `spectra.retrieval.bm25.only` | `reason=no_embedding\|dense_error\|dense_empty` | Résultats servis par BM25 seul, selon la cause |

Pour une passe Qdrant, contrôler que `success` augmente pour Qdrant et examiner les erreurs et les réponses BM25 seules. Les compteurs sont globaux à l’instance et comptent aussi l’échauffement. En mode agentique, une requête utilisateur peut déclencher plusieurs recherches denses.

```bash
curl -fsS -G http://127.0.0.1:8080/actuator/metrics/spectra.retrieval.dense \
  --data-urlencode tag=engine:qdrant --data-urlencode tag=outcome:success
curl -fsS http://127.0.0.1:8080/actuator/metrics/spectra.retrieval.bm25.only
```

Fournir le header `X-API-Key` si l’accès à Actuator est protégé. Un compteur sans observation peut être absent. La CI exécute l’import et les recherches sur les deux moteurs, puis vérifie aussi le refus d’une recherche MCP hors périmètre via le transport HTTP.

## Revenir à Chroma

```bash
export SPECTRA_QDRANT_PILOT_ENABLED=false
"${compose[@]}" up -d --no-deps spectra-api
```

Réactiver les écritures après ce retour. Les opérations de production nécessiteront une stratégie de synchronisation et une validation de cohérence sur le corpus cible avant toute bascule.
