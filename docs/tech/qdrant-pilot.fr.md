# Qdrant et comparaison des moteurs dans SpectraLLM

Qdrant est le moteur vectoriel par défaut. La pile Compose standard écrit dans `data/qdrant` et accède à Qdrant via le réseau Compose. Pour démarrer avec un index neuf, utiliser un répertoire de données neuf pour l’index vectoriel, le catalogue SQL et BM25, puis ingérer les documents. Les collections sont exposées sous des alias stables. Une réindexation avec un nouveau modèle reconstruit les vecteurs dans une collection séparée avant de publier son alias.

## Pile de comparaison

L’overlay ci-dessous utilise ChromaDB comme référence et permet de rejouer l’ancien pilote de lecture Qdrant pour les mesures A/B.

Le pilote remplace la recherche dense pour une seule collection Chroma identifiée par UUID. Le RAG standard, hybride et agentique utilisent ce chemin commun. BM25, le reranker, les écritures et les opérations de gestion gardent leur configuration habituelle. Le pilote est désactivé par défaut.

## Préparer et comparer

Utiliser une **instance de test isolée** avec une copie cohérente des données Chroma, du catalogue SQL, du registre des modèles et de l’index lexical. Garder le même modèle d’embedding pour les deux mesures. Suspendre toute mutation pendant l’export et les mesures, y compris la synchronisation des sources, les suppressions, la réindexation et les changements de modèle. Un changement de contenu qui conserve le même nombre de chunks n’est pas détectable par les contrôles de comptage.

Depuis la racine du dépôt, avec la pile de test disponible sur le port Chroma local 8000 :

```bash
export SPECTRA_QDRANT_API_KEY="$(openssl rand -hex 32)"
compose=(docker compose --project-directory . -f deploy/docker/docker-compose.yml \
  -f deploy/docker/docker-compose.qdrant-pilot.yml)

"${compose[@]}" up -d chromadb qdrant-pilot

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

## Comparer sur EnterpriseRAG-Bench d’Onyx

[EnterpriseRAG-Bench](https://onyx.app/enterpriserag-bench) fournit les questions et les documents de référence. Préparer puis ingérer le corpus avec [le lanceur EnterpriseRAG-Bench de SpectraLLM](enterprise-rag-bench.fr.md) sur une instance de test dédiée. Pour un premier essai, utiliser les documents de référence et 5 000 distracteurs, avec la graine par défaut. Utiliser un répertoire de travail neuf pour garantir le périmètre du sous-corpus.

```bash
work=data/onyx-qdrant-bench
python3 scripts/enterprise_rag_bench.py --work-dir "$work" prepare --subset 5000
python3 scripts/enterprise_rag_bench.py --work-dir "$work" ingest
```

Vérifier les erreurs d’ingestion, puis figer les écritures et importer la collection dans Qdrant selon la procédure ci-dessus. Les deux captures lisent directement le fichier Onyx, sans conversion.

```bash
# Désactiver le pilote pour la référence Chroma, puis l’activer pour Qdrant.
# Exécuter la ligne suivante une fois par moteur avec engine=chroma ou engine=qdrant.
python3 scripts/compare_qdrant_pilot.py capture --engine "$engine" \
  --collection "$collection" --questions "$work/questions.jsonl" --suite-format onyx \
  --per-type 5 --hybrid --concurrency 4 --output "$work/$engine-hybrid.json"
python3 scripts/compare_qdrant_pilot.py compare "$work/chroma-hybrid.json" "$work/qdrant-hybrid.json"
```

Pour la passe dense, retirer `--hybrid`. Pour une passe à faible concurrence, utiliser `--concurrency 1`. Omettre `--per-type` pour rejouer toutes les questions. Utiliser des noms de capture distincts pour chaque paire.

Le rapport Onyx s’appuie sur les identifiants `dsid_…` des documents et calcule le rappel, le taux de succès et le rang réciproque moyen, globalement et par catégorie. Les questions sans référence restent dans les mesures de latence. Le comptage des documents hors référence sert au diagnostic local. Le classement public repose sur le corpus complet et l’évaluateur officiel Onyx, décrit dans le guide dédié.

## Mesurer les appels Qdrant natifs

Les métriques Actuator `spectra.qdrant.http`, `spectra.qdrant.engine` et
`spectra.qdrant.response.bytes` mesurent respectivement le temps HTTP côté
SpectraLLM, le temps déclaré par Qdrant et la taille du corps reçu. Filtrer par
`operation=query` ou `operation=scroll` pour isoler la recherche et les lectures
paginées. Les autres opérations portent le tag `operation=other`.

La recherche d’identifiants utilise `match.text` sur le champ `document`
sans index texte. Le test d’intégration vérifie la sous-chaîne et la casse sur
la version Qdrant épinglée. Conserver ce test lors des mises à niveau.

## Diversité, recherche approchée et reformulations

| Variable Compose | Défaut | Usage |
| --- | --- | --- |
| `SPECTRA_QDRANT_GROUP_SIZE` | `0` | Activer le regroupement des candidats denses par SHA documentaire, par exemple avec `2` ou `3`. Une recherche complémentaire conserve les passages historiques sans SHA. Si les groupes fournissent trop peu de candidats, la recherche standard complète le parcours. |
| `SPECTRA_QDRANT_HNSW_EF` | `0` | Laisser Qdrant choisir, ou fixer la largeur de recherche HNSW pour un essai. |
| `SPECTRA_QDRANT_EXACT` | `false` | Forcer la recherche exacte pour établir la référence du rappel ANN. |
| `SPECTRA_QDRANT_ACORN` | `false` | Tester ACORN en recherche approchée avec des filtres sélectifs. |
| `SPECTRA_QDRANT_ACORN_MAX_SELECTIVITY` | `0.4` | Seuil de sélectivité transmis à Qdrant quand ACORN est activé. |

Le regroupement intervient sur les candidats denses, avant la fusion hybride et le
reranking éventuel. Il s’agit d’une option à évaluer sur les questions dont la
réponse exige plusieurs passages du même document. La branche BM25 conserve ses
propres candidats.

En mode multi-query, les variantes indépendantes utilisent un lot d’embeddings et
un appel `query/batch` sur le chemin Qdrant natif. Le classement RRF, BM25 et les
contrôles de visibilité restent appliqués par variante. Les requêtes individuelles
prennent le relais si le lot échoue ou si le regroupement est activé. Le tag
`operation` distingue `batch`, `groups` et `query` dans les métriques Qdrant.

Sur un essai isolé de 50 000 vecteurs synthétiques de dimension 64, tous indexés
par HNSW dans Qdrant 1.16.3, un filtre combiné conservait 1 % des points. Sur
60 requêtes filtrées par réglage, le rappel@10 mesuré par recouvrement avec la
recherche exacte était de 96,5 % pour `ef=128`, 99,5 % pour `ef=256` et 100 %
pour `ef=128` avec ACORN. Le p95 moteur correspondant était de 1,09, 1,39 et
5,45 ms. Refaire cette comparaison sur le corpus cible avant de choisir un réglage.

## Quantification INT8 et hybride natif

| Variable Compose | Défaut | Effet |
| --- | --- | --- |
| `SPECTRA_QDRANT_INT8` | `false` | Vecteurs float32 sur disque et copie scalaire INT8 en RAM pour les collections créées ou réindexées avec cette option. |
| `SPECTRA_QDRANT_OVERSAMPLING` | `2` | Nombre relatif de candidats quantifiés à reclasser sur les vecteurs originaux. Valeur comprise entre 1 et 10. |
| `SPECTRA_QDRANT_NATIVE_HYBRID` | `false` | Index lexical clairsemé dans Qdrant et fusion RRF des candidats denses et lexicaux. |
| `SPECTRA_QDRANT_HYBRID_STRATEGY` | `rrf` | Fusion `rrf` ou `dbsf`. Les contrôles `dense` et `sparse` interrogent une seule branche Qdrant. |
| `SPECTRA_QDRANT_RRF_K` | `60` | Constante RRF du chemin natif. |
| `SPECTRA_QDRANT_LEXICAL_CANDIDATES` | `0` | Profondeur du préchargement lexical dans une fusion native. Zéro reprend le nombre de candidats demandé. |

Renseigner les options dans `.env`, puis recréer le service `spectra-api`.
Pour une collection déjà remplie, lancer sa réindexation pour construire le nouveau
schéma. La réindexation publie la nouvelle collection par alias. Refaire la même
opération avec les options désactivées pour revenir au schéma précédent.
Le mode exact ignore la quantification pour la mesure de référence.

L’hybride natif réutilise la tokenisation de BM25 Java. Il stocke des poids de
termes avec une longueur de référence fixe, applique l’IDF dans Qdrant et fusionne
les rangs avec RRF (`k=60`). La réponse expose l’ordre fusionné, avec distance
cosinus inconnue et sans score BM25 brut. Le BM25 Java reste la référence et le
repli si la collection est encore dense seule, si Qdrant échoue ou si le contrôle
de visibilité écarte un résultat. Un poids BM25 personnalisé ou le regroupement
par document utilise aussi la fusion Java. Les variantes multi-query hybrides
natives sont exécutées séparément.

### Comparer les stratégies sur dev-120

Utiliser le même index clairsemé, sans regroupement ni reformulation. Fixer
`SPECTRA_QDRANT_LEXICAL_CANDIDATES=20`, `SPECTRA_HYBRID_BM25_TOP=20` pour la
référence Java et `--top-candidates 20` pour les captures. Comparer d'abord
les deux contrôles `dense` et `sparse` à RRF `k=60`. Comparer ensuite
RRF `k=2` et DBSF à cette référence. Conserver un profil et un répertoire de
capture distincts pour chaque stratégie et recréer l’API après changement de
configuration. La collection reste la même entre les variantes.

```bash
python3 scripts/enterprise_rag_experiment.py run --mode retrieve --api "$API" \
  --collection "$COLLECTION" --questions "$WORK/split/dev-120.jsonl" \
  --corpus-dir "$WORK/documents" --profile "$PROFILE" --out "$CAPTURE" \
  --hybrid on --multi-query off --rerank off --max-context-chunks 20 --top-candidates 20
```

Comparer les captures avec `enterprise_rag_experiment.py compare`. Avec ce budget,
`all-gold@20` mesure la couverture complète des preuves avant reranking. Pour la
mesure finale, refaire la paire avec `--rerank on --max-context-chunks 10` et
examiner le rappel@10, `all-gold@10` et la latence p95. Vérifier les compteurs
`spectra.retrieval.qdrant.hybrid` pour s’assurer que les contrôles mono-branche
et les fusions ont réellement été servis par Qdrant. L’indicateur d’hybride de
l’API reste actif dans ces contrôles car ils passent par le même chemin natif.

Figer le profil gagnant avant de le comparer à la référence sur
`holdout-380.jsonl`. Si la recherche dense manque encore des documents,
comparer d’abord la recherche approximative à `SPECTRA_QDRANT_EXACT=true`
sur le même index avant d’ajuster `SPECTRA_QDRANT_HNSW_EF`.

Sur 50 000 vecteurs synthétiques de dimension 64 indexés, à `ef=256`, le rappel@10
par rapport au calcul exact est resté identique entre float32 et INT8 avec
reclassement et suréchantillonnage ×2. Le p95 moteur est passé de 1,10 à 1,01 ms
sans filtre et de 1,40 à 1,19 ms avec le filtre à 1 %. Les originaux étaient sur
disque dans les deux passes. Mesurer la RAM, les lectures SSD et la qualité sur
le corpus cible avant de choisir ce profil.
