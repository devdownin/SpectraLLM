# EnterpriseRAG-Bench — évaluer Spectra sur le banc d'Onyx

[EnterpriseRAG-Bench](https://github.com/onyx-dot-app/EnterpriseRAG-Bench) est un banc RAG sur
données d'entreprise synthétiques (≈ 500 000 documents Slack, Gmail, Linear, Drive, Confluence…,
500 questions en 10 catégories). Le classement public est sur
[Hugging Face](https://huggingface.co/spaces/onyx-dot-app/EnterpriseRAG-Bench-Leaderboard).

`scripts/enterprise_rag_bench.py` produit le fichier de réponses de l'évaluateur officiel ; le
score (correctness, completeness, recall, invalid extra docs) est ensuite calculé par le script
d'Onyx, avec un LLM juge.

## Comparaison reproductible sur 120 questions

`scripts/enterprise_rag_experiment.py` utilise Python 3.11 ou supérieur et le banc existant.
Il sépare **120 questions de réglage** et **380 questions de validation**, avec les dix catégories
dans chaque lot. Les mêmes questions, dans le même ordre, sont utilisées pour chaque variante.
Figer le réglage gagnant avant de passer aux 380 questions. Pour le classement, utiliser les 500
questions et le corpus complet.

### Préparer la pile et les données

Exécuter depuis la racine de SpectraLLM. Installer `pyarrow` dans l'environnement Python pour
la préparation du corpus. Utiliser une pile de test dédiée avec un index vide au départ.
Voici les révisions du premier lot de comparaison.

```bash
ONYX_COMMIT=d36685e273713975ee20299bbf1ab64165575b3c
CORPUS_REV=69916e31c68aa5963c00248fd7f0bc12d04fd235
WORK=data/onyx-quality-v1

git clone https://github.com/onyx-dot-app/EnterpriseRAG-Bench ../EnterpriseRAG-Bench
git -C ../EnterpriseRAG-Bench checkout --detach "$ONYX_COMMIT"
python3 scripts/enterprise_rag_bench.py --work-dir "$WORK" prepare --subset 5000 --seed 42 \
  --questions-url "https://raw.githubusercontent.com/onyx-dot-app/EnterpriseRAG-Bench/$ONYX_COMMIT/questions.jsonl" \
  --documents-url "https://huggingface.co/datasets/onyx-dot-app/EnterpriseRAG-Bench/resolve/$CORPUS_REV/data/documents/test.parquet"
python3 scripts/enterprise_rag_experiment.py split --questions "$WORK/questions.jsonl" --out "$WORK/split"
python3 scripts/enterprise_rag_bench.py --work-dir "$WORK" ingest
```

La préparation télécharge environ 1,4 Go de Parquet, même avec `--subset`. Réserver un répertoire
neuf pour chaque révision du corpus. L'ingestion utilise la collection par défaut de la pile.
Vérifier l'absence d'erreurs dans `ingest_errors.log` et utiliser cette même collection dans
`--collection`. Garder le corpus et l'index stables pendant les essais. Une autre pile ou un autre
index doit recevoir exactement les mêmes fichiers. Le lanceur enregistre l'empreinte des fichiers
locaux et s'appuie sur l'opérateur pour confirmer leur correspondance avec l'index distant.
Si l'API exige une clé, fournir `SPECTRA_API_KEY` dans l'environnement.

Pour la référence de génération avec llama.cpp, configurer **8 192 tokens par requête**
(`LLM_CONTEXT=8192` et `LLM_PARALLEL=1`, ou 16 384 et 2). Cette fenêtre inclut le prompt et la
sortie. Créer un profil JSON par configuration et remplacer les valeurs d'exemple par les
identifiants ou empreintes réels. Inclure les autres paramètres serveur qui influencent le RAG
et conserver les clés dans l'environnement.

```json
{
  "backend_revision": "commit-ou-digest-image",
  "index": "moteur, version, configuration lexicale/vectorielle et chunking",
  "embedding": "modèle et révision",
  "chat": "modèle et révision",
  "reranker": "moteur, modèle et révision ou empreinte de l'artefact",
  "context_tokens_per_request": 8192
}
```

### Lancer le comparatif automatisé sur dev-120

Préparer deux instances de test avec la même révision SpectraLLM, le même modèle et
la même collection indexée depuis le même corpus. L'instance de référence utilise
`SPECTRA_QDRANT_GROUP_SIZE=0`, l'autre `SPECTRA_QDRANT_GROUP_SIZE=2`. Sur les deux,
laisser `SPECTRA_QDRANT_NATIVE_HYBRID=false` pour isoler le regroupement. Sur la
référence, définir `SPECTRA_MULTI_QUERY_ENABLED=true` et `SPECTRA_MULTI_QUERY_COUNT=2`.
Utiliser deux hôtes Docker ou deux configurations isolées si les noms de conteneurs
et les ports Compose entrent en conflit.

```bash
python3 scripts/enterprise_rag_experiment.py suite \
  --api "$API" --grouped-api "$GROUPED_API" --collection "$COLLECTION" \
  --split-dir "$WORK/split" --corpus-dir "$WORK/documents" \
  --profile "$WORK/profile-reference.json" --out "$WORK/dev-suite" \
  --evaluator-dir ../EnterpriseRAG-Bench --python ../EnterpriseRAG-Bench/.venv/bin/python
```

`suite` contrôle la disponibilité de Qdrant et du reranker, l'état de l'index BM25
de la collection, l'égalité du nombre de passages et l'activité de l'endpoint
de regroupement sur une requête de contrôle. Le nombre de passages ne prouve pas
que les index contiennent les mêmes textes. Consigner la provenance des deux index
dans le profil et les laisser stables pendant toute la campagne.

Le lanceur compare 20 et 50 candidats avec un budget fixe de 10 passages. Il reprend
le meilleur budget pour tester séparément les reformulations et le regroupement.
Il choisit deux variantes par ordre décroissant de couverture complète des preuves,
puis de rappel. En cas d'égalité, il privilégie moins de documents hors référence,
puis une latence p95 plus basse. Il génère les réponses des deux variantes et les
soumet au même juge. Le rapport apparié `report.json` contient les écarts par
catégorie, le taux d'activation des chemins et la comparaison des scores jugés.

Avec `--retrieval-only`, le lanceur s'arrête après le classement documentaire et
publie le rapport sans appeler le juge. Relancer sans cette option dans le même
répertoire pour réutiliser les captures, avec les mêmes paramètres et index.
Ne pas utiliser les 380 questions de validation pour ce réglage.

### Comparer 20 et 50 candidats du reranker

Garder dix passages de contexte et le même profil pour isoler le nombre de candidats.
Lancer les deux variantes dans des répertoires distincts et conserver le rapport apparié.

```bash
API=http://localhost:8089
COLLECTION=nom-de-la-collection-indexee

(
  for CANDIDATES in 20 50; do
    python3 scripts/enterprise_rag_experiment.py run --api "$API" --mode retrieve \
      --collection "$COLLECTION" --questions "$WORK/split/dev-120.jsonl" \
      --corpus-dir "$WORK/documents" --profile "$WORK/profile-reference.json" \
      --hybrid on --max-context-chunks 10 --top-candidates "$CANDIDATES" \
      --out "$WORK/retrieve-top$CANDIDATES" || exit 1
  done

  python3 scripts/enterprise_rag_experiment.py compare \
    "$WORK/retrieve-top20" "$WORK/retrieve-top50"
)
```

Dans les deux fichiers `records.jsonl`, vérifier que `rerankApplied` et
`hybridSearchApplied` sont vrais pour les questions comparées. Si le reranker est
indisponible, Spectra réduit la recherche au nombre de passages demandé pour le
contexte. Un essai de ce type ne mesure alors pas la différence entre 20 et 50 candidats.

Le rapport `all-gold@10` mesure la proportion des questions annotées avec au moins deux
documents de référence distincts pour lesquelles tous ces documents sont retrouvés.
Le `recall@10` compte aussi les preuves partielles. Le `extra@10` donne le nombre moyen
de documents hors référence parmi les dix premiers. Examiner ces trois métriques
ensemble avant de passer les variantes retenues en mode `answer`.

### Comparer les reformulations et le regroupement Qdrant

Garder le nombre de candidats retenu à l'étape précédente dans `CANDIDATES`, ainsi que
le même modèle, la même collection et les mêmes dix passages de contexte. Pour isoler
les deux changements, comparer chacun à une référence sans reformulation ni regroupement.
Laisser `SPECTRA_QDRANT_NATIVE_HYBRID=false` pour cette série. Le chemin hybride natif
utilise un autre point d'entrée Qdrant.

Pour la référence, configurer la pile avec `SPECTRA_MULTI_QUERY_ENABLED=true`,
`SPECTRA_MULTI_QUERY_COUNT=2` et `SPECTRA_QDRANT_GROUP_SIZE=0`. Activer le module dès la
référence permet de comparer `off` et `on` par requête sans redémarrage. Les deux
reformulations s'ajoutent à la question originale.

```bash
retrieve_variant() {
  python3 scripts/enterprise_rag_experiment.py run --api "$API" --mode retrieve \
    --collection "$COLLECTION" --questions "$WORK/split/dev-120.jsonl" \
    --corpus-dir "$WORK/documents" --hybrid on --max-context-chunks 10 \
    --top-candidates "$CANDIDATES" "$@"
}

retrieve_variant --multi-query off --profile "$WORK/profile-group0.json" \
  --out "$WORK/retrieve-reference" &&
retrieve_variant --multi-query on --profile "$WORK/profile-group0.json" \
  --out "$WORK/retrieve-reformulations" &&
python3 scripts/enterprise_rag_experiment.py compare \
  "$WORK/retrieve-reference" "$WORK/retrieve-reformulations"
```

Pour le regroupement, recréer uniquement le service API avec
`SPECTRA_QDRANT_GROUP_SIZE=2` dans la configuration Compose et attendre que l'API
soit prête. Garder l'index et ses volumes. Copier le profil de référence et y
consigner le nouveau réglage dans `profile-group2.json`.

```bash
retrieve_variant --multi-query off --profile "$WORK/profile-group2.json" \
  --out "$WORK/retrieve-group2" &&
python3 scripts/enterprise_rag_experiment.py compare \
  "$WORK/retrieve-reference" "$WORK/retrieve-group2"
```

Vérifier `multi-query-applied` pour savoir quelle part des requêtes a réellement
utilisé les reformulations. En cas de repli, garder la requête dans le calcul du
score pour mesurer le comportement réel de la variante. Pour Qdrant, vérifier
que le compteur `spectra.qdrant.http` pour `operation=groups` augmente pendant
l'essai. Le regroupement peut revenir à une recherche simple si les groupes
fournissent trop peu de candidats.

Comparer `all-gold@10`, `recall@10`, `extra@10` et les champs `p95_a` et `p95_b`
de `client-ms`. Le p95 couvre le temps vu par le client, y compris la reformulation
et les éventuelles relances HTTP. Pour une comparaison de latence, réchauffer
les deux configurations de la même façon avant les captures.

### Comparer la fusion Java et l’hybride natif Qdrant

Utiliser une instance de test dédiée avec ses propres répertoires de données et un
`WORK` neuf. La stack Compose fixe les noms de conteneurs et monte des répertoires
locaux. Pour l'isoler, utiliser un autre hôte Docker ou une instance locale de test
démarrée seule depuis un checkout dédié.

Préparer le palier de 5 000 distracteurs avec les révisions épinglées ci-dessus.
Avant la première ingestion, démarrer l’instance de test avec
`SPECTRA_QDRANT_NATIVE_HYBRID=true`. Elle construit une collection à vecteurs
denses et clairsemés dès l’indexation. Utiliser `spectra_documents` comme collection
pour une installation vierge. Relever la durée de l’ingestion et vérifier
`ingest_errors.log` avant les captures.

Comparer les moteurs sur **la même collection clairsemée**. Basculer le chemin de
recherche en recréant l’API seule. Garder le poids BM25 à 1, le regroupement à 0,
le multi-query désactivé et les autres réglages identiques. Cela isole la fusion
et le classement lexical. Le choix du mode ne nécessite aucune réindexation entre
les deux mesures.

```bash
# Depuis la racine du checkout de test.
compose=(docker compose --project-directory . -f deploy/docker/docker-compose.yml)

start_hybrid() {
  SPECTRA_QDRANT_NATIVE_HYBRID="$1" SPECTRA_QDRANT_GROUP_SIZE=0 \
    SPECTRA_HYBRID_BM25_WEIGHT=1 "${compose[@]}" up -d --no-deps --wait spectra-api
}

capture_hybrid() {
  python3 scripts/enterprise_rag_experiment.py run --api "$API" --mode retrieve \
    --collection "$COLLECTION" --questions "$WORK/split/dev-120.jsonl" \
    --corpus-dir "$WORK/documents" --profile "$1" --out "$2" \
    --hybrid on --multi-query off --top-candidates "$CANDIDATES" --max-context-chunks 10
}
```

Créer `profile-java.json` et `profile-native.json` pour le même corpus et renseigner
`qdrant_native_hybrid` à `false` et `true` respectivement. Pour chaque mode, démarrer
l’API, réchauffer la pile de la même façon, relever les compteurs et lancer la
capture dans un répertoire neuf. Relever à nouveau les compteurs à la fin. Pour la variante native,
`spectra.retrieval.qdrant.hybrid` avec `outcome=used` compte les résultats Qdrant
effectivement retenus. `outcome=fallback` compte les tentatives qui reviennent
au chemin Java. Un compteur absent vaut zéro. Un taux d’activation faible
doit être expliqué avant d’attribuer l’écart à Qdrant. Pour la référence, aucun
de ces compteurs ne doit augmenter.

```bash
# Première capture.
start_hybrid false
# Réchauffement et relevé initial.
capture_hybrid "$WORK/profile-java.json" "$WORK/retrieve-java"

# Deuxième capture.
start_hybrid true
# Même réchauffement et relevé initial.
capture_hybrid "$WORK/profile-native.json" "$WORK/retrieve-native"

# Relevés avant et après chaque série.
curl -fsS "$API/actuator/prometheus" | grep '^spectra_retrieval_qdrant_hybrid_total'
"${compose[@]}" stats --no-stream spectra-api qdrant
```

Comparer les deux sorties avec `enterprise_rag_experiment.py compare`. Relever le
rappel@10, la couverture complète des preuves, les sources hors référence et le p95.
En mode `answer`, faire juger les citations par Onyx pour mesurer le bruit réel.
Échantillonner la mémoire de l’API et de Qdrant pendant les mesures. L’index BM25 Java
reste chargé pour le repli. Cette comparaison ne mesure donc pas le gain potentiel
d’une architecture sans BM25 Java.

Monter ensuite à **20 000**, puis **100 000 distracteurs** avec `prepare --extend`
et `ingest` dans le même `WORK`. Réactiver l’hybride natif pour les nouvelles ingestions,
garder le fichier d’état, utiliser deux nouveaux répertoires de résultats par palier
et relancer la paire sur le même index figé. Consigner la durée d’ingestion
incrémentale et la mémoire à chaque palier avant d’envisager le corpus complet.

### Exécuter les variantes

Choisir `retrieve` pour le rappel documentaire sans génération ni juge. Choisir `answer`
pour le score de réponse. Enregistrer les profils et les deux modes dans des répertoires distincts.

```bash
COLLECTION=nom-de-la-collection-indexee
QUESTIONS="$WORK/split/dev-120.jsonl"

python3 scripts/enterprise_rag_experiment.py run --mode retrieve --collection "$COLLECTION" \
  --questions "$QUESTIONS" --corpus-dir "$WORK/documents" \
  --profile "$WORK/profile-reference.json" --out "$WORK/retrieve-reference"

# Redémarrer SpectraLLM avec BGE v2-M3. Utiliser un artefact ONNX et un tokenizer compatibles.
python3 scripts/enterprise_rag_experiment.py run --mode retrieve --collection "$COLLECTION" \
  --questions "$QUESTIONS" --corpus-dir "$WORK/documents" \
  --profile "$WORK/profile-bge.json" --out "$WORK/retrieve-bge"
python3 scripts/enterprise_rag_experiment.py compare "$WORK/retrieve-reference" "$WORK/retrieve-bge"
```

Rejouer avec mMARCO et un profil distinct si la référence utilise un autre modèle.
En mode ONNX, sélectionner le modèle via `SPECTRA_RERANKER_ONNX_PATH`. En mode HTTP, vérifier
le modèle effectivement chargé dans le service de reranking. Après chaque redémarrage, vérifier
`/api/status` et les indicateurs `rerankApplied` et `hybridSearchApplied` dans `records.jsonl`.
Une variante sans reranking effectif doit être examinée avant d'interpréter le gain.
Le profil hybride Qdrant natif, sur les versions qui le proposent, se compare de la même façon avec
un index et un profil dédiés.

Pour mesurer la génération, rejouer les variantes retenues avec `--mode answer`.
Avec `SPECTRA_SELF_RAG_ENABLED=true` côté serveur, comparer `--self-rag off` à `--self-rag on`
sur le même index, le même modèle et les mêmes réglages. Utiliser le pipeline RAG standard
(`SPECTRA_AGENTIC_RAG_ENABLED=false` et `SPECTRA_ADAPTIVE_RAG_ENABLED=false`).
Le Self-RAG actuel vérifie un extrait
des trois premiers passages. `selfRagApplied` indique qu'un raffinement a eu lieu.
Cet essai mesure la réflexion existante. Une vérification exhaustive des faits requiert sa propre variante.

### Automatiser les diagnostics après le comparatif dev-120

Pour le diagnostic de génération, fournir le répertoire `answer-…` de la variante
de référence déjà jugée. Le lanceur sélectionne jusqu’à 20 questions annotées
au score le plus faible, fige cette sélection et réutilise les notes RAG existantes.
Il soumet les versions oracle concise et détaillée au même juge et écrit les écarts
appariés dans `report.json`. Garder le même modèle de chat et le même corpus.

```bash
python3 scripts/enterprise_rag_diagnostics.py oracle \
  --reference "$RAG_REFERENCE" --split-dir "$WORK/split" --corpus-dir "$WORK/documents" \
  --api http://localhost:8091 --model "$CHAT_MODEL" --profile "$WORK/profile-reference.json" \
  --evaluator-dir ../EnterpriseRAG-Bench --python ../EnterpriseRAG-Bench/.venv/bin/python \
  --out "$WORK/diagnostic-oracle"
```

Pour l'hybride natif, préparer deux API de test dédiées avec la même collection
clairsemée et le même corpus. Utiliser un serveur construit avec les compteurs
`spectra.retrieval.qdrant.hybrid` de la PR #482. Sur la référence, régler
`SPECTRA_QDRANT_NATIVE_HYBRID=false`, puis `true` sur la variante. Garder
`SPECTRA_QDRANT_GROUP_SIZE=0` et le poids BM25 à 1 des deux côtés. Réchauffer
les deux piles de la même façon et les réserver au benchmark pendant les relevés.

```bash
DISTRACTORS=5000
python3 scripts/enterprise_rag_diagnostics.py native \
  --work-dir "$WORK" --split-dir "$WORK/split" --profile "$WORK/profile-reference.json" \
  --api "$API" --native-api "$NATIVE_API" --collection "$COLLECTION" \
  --distractors "$DISTRACTORS" --top-candidates "$CANDIDATES" \
  --evaluator-dir ../EnterpriseRAG-Bench --python ../EnterpriseRAG-Bench/.venv/bin/python \
  --out "$WORK/diagnostic-native-$DISTRACTORS"
```

Après le palier de 5 000, passer à 20 000 avec `prepare --subset 20000 --extend`,
indexer les nouveaux documents dans les deux piles, puis relancer avec
`DISTRACTORS=20000`. Garder un état d’ingestion distinct par instance. Pour un
essai documentaire sans génération ni juge, utiliser `--retrieval-only`.

Le rapport inclut les scores appariés, les appels natifs retenus, les replis et
deux relevés du tas JVM par capture. Ces relevés ne donnent pas le pic mémoire.
Pour la RAM de Qdrant, relever `docker compose stats --no-stream qdrant spectra-api`
sur chaque pile pendant l’essai. Le BM25 Java reste chargé pour le repli, donc
la mesure inclut son coût. Si une capture native est interrompue avant son relevé
final, reprendre dans un répertoire neuf pour établir des compteurs comparables.

### Diagnostiquer la génération avec les documents de référence

Sur `dev-120`, choisir une vingtaine de questions mal répondues ou incomplètes dans
le résultat jugé de la référence. Retenir celles qui ont des documents annotés et
garder le même lot pour tous les essais. Ce lot sert au diagnostic de développement.

```bash
jq -c --slurpfile judged "$WORK/answer-reference/results.json" '
  ($judged[0].questions
    | map(select(.answer_correct == false or .completeness_pct < 100) | .question_id)
    | INDEX(.)) as $misses
  | select(.question_type != "high_level" and .question_type != "info_not_found")
  | select((.expected_doc_ids // [] | length) > 0 and $misses[.question_id])
' "$WORK/split/dev-120.jsonl" | sed -n '1,20p' > "$WORK/oracle-dev.jsonl"
```

Le mode `oracle` envoie les documents annotés en entier au serveur llama.cpp,
avec des références numérotées. La fenêtre par slot est lue dans `/props`. Le
serveur applique son gabarit et compte les tokens avant de générer. Une question
qui dépasse le budget prompt + sortie arrête le run avec son identifiant. Pour
l’inclure, augmenter le contexte du serveur et du profil pour **toutes** les
variantes, dans de nouveaux répertoires. Les réponses qui atteignent la limite de
sortie restent repérables via `oracle.finishReason`.

```bash
CHAT_API=http://localhost:8091
CHAT_MODEL=qwen2.5-7b-instruct

(
  for STYLE in concise detailed; do
    python3 scripts/enterprise_rag_experiment.py oracle --api "$CHAT_API" \
      --model "$CHAT_MODEL" --style "$STYLE" --max-tokens 512 \
      --questions "$WORK/oracle-dev.jsonl" --corpus-dir "$WORK/documents" \
      --profile "$WORK/profile-reference.json" --out "$WORK/oracle-$STYLE" || exit 1
  done
)
```

Si le serveur est protégé, fournir `LLAMA_CPP_API_KEY` dans l’environnement. Rejouer
aussi la variante RAG de référence en mode `answer` sur `oracle-dev.jsonl`, avec
`--parallelism 1` et un nouveau répertoire. Appliquer le même juge aux trois
sorties, puis comparer RAG ↔ oracle et concis ↔ détaillé. Pour tester un autre
GGUF, garder le style gagnant et le budget de sortie, consigner le modèle dans un
nouveau profil et utiliser un nouveau répertoire.

`compare` limite ces comparaisons au score, à l’exactitude et à la complétude.
Les `document_ids` de l’oracle indiquent les preuves fournies au modèle. Une
réponse oracle encore incomplète invite à examiner le modèle, les consignes ou
le budget de sortie. Un gain oracle indique une marge côté preuves ou contexte.
L’oracle utilise ses propres consignes de génération et ne produit pas un score
de classement.

### Noter et comparer

Installer les dépendances d'Onyx dans un environnement Python séparé. Configurer `LLM_API_KEY` pour le
juge et utiliser le même `LLM_MODEL_NAME` dans toutes les variantes (`gpt-5.4` dans cet exemple).
`OPENAI_BASE_URL` permet d'utiliser une API compatible OpenAI. L'évaluateur et le corpus de référence
restent figés pour la comparaison.

```bash
python3 -m venv ../EnterpriseRAG-Bench/.venv
../EnterpriseRAG-Bench/.venv/bin/python -m pip install -r ../EnterpriseRAG-Bench/requirements.txt
export LLM_PROVIDER=openai LLM_MODEL_NAME=gpt-5.4

python3 scripts/enterprise_rag_experiment.py judge --out "$WORK/answer-reference" \
  --evaluator-dir ../EnterpriseRAG-Bench --python ../EnterpriseRAG-Bench/.venv/bin/python &&
python3 scripts/enterprise_rag_experiment.py judge --out "$WORK/answer-bge" \
  --evaluator-dir ../EnterpriseRAG-Bench --python ../EnterpriseRAG-Bench/.venv/bin/python &&
python3 scripts/enterprise_rag_experiment.py compare "$WORK/answer-reference" "$WORK/answer-bge"
```

- `run.json` fixe le profil, les questions, le corpus local, les options et la version des scripts.
  Relancer la même commande pour compléter `records.jsonl`. Un changement impose un nouveau
  répertoire. Utiliser un seul processus par répertoire. Après un arrêt forcé, retirer le dossier vide
  `.running` seulement après avoir vérifié que le processus est arrêté.
- `judge.json` fixe la révision et la configuration du juge. `results.json` est publié seulement
  si toutes les questions sont notées et que le contrôle des appels au juge passe. Un jugement
  interrompu est relancé intégralement.
- `compare` refuse les paires incomplètes et les écarts de corpus, de questions, de mode ou de juge.
  Le JSON contient l'écart par question agrégé, l'IC95 %, les gains et pertes et le détail par catégorie.
  En mode `retrieve`, il compare le rappel, la couverture complète des preuves et les documents hors référence à 10. En mode `answer`, le score principal correspond
  à la complétude pour une réponse correcte, zéro sinon. Le rappel et les sources hors référence
  servent au diagnostic.

## 1. Préparer le corpus

```bash
pip install pyarrow
# Essai rapide : documents de référence (~800) + 5 000 distracteurs
python3 scripts/enterprise_rag_bench.py prepare --subset 5000
# Run complet (leaderboard) : 1,4 Go de parquet, ~500 000 fichiers
python3 scripts/enterprise_rag_bench.py prepare
```

Pour passer à un sous-corpus plus grand sans tout ré-indexer, `--extend` conserve les documents déjà préparés et
ajoute des distracteurs jusqu'à N ; `ingest` ne renvoie alors que les nouveaux fichiers (son état note les fichiers
indexés) :

```bash
python3 scripts/enterprise_rag_bench.py prepare --subset 20000 --extend
python3 scripts/enterprise_rag_bench.py ingest
```

Sans accès à Hugging Face (proxy, réseau restreint), le même corpus se lit depuis un clone du dépôt
GitHub, sans pyarrow (~4 Go de JSON bruts) :

```bash
git clone --depth 1 https://github.com/onyx-dot-app/EnterpriseRAG-Bench /tmp/erb
python3 scripts/enterprise_rag_bench.py prepare --subset 5000 --source-dir /tmp/erb/generated_data
```

Les fichiers sont écrits dans `data/enterprise-rag-bench/documents/<doc_id>.txt`. Le doc_id porté
par le nom de fichier revient dans `sources[].sourceFile` de `/api/query`.

> Un sous-ensemble facilite artificiellement la recherche : son score **n'est pas comparable** au
> classement. Seul le corpus complet l'est.

## 2. Configurer et indexer

Spectra doit tourner, réponses agentiques en anglais (le juge compare à des références en
anglais) :

```bash
SPECTRA_AGENTIC_LANGUAGE=en ./scripts/start.sh
```

```bash
python3 scripts/enterprise_rag_bench.py ingest   # lots de 40 : Tomcat plafonne à 50 fichiers par requête
```

Reprenable : les lots terminés sont notés dans `ingest_state.json`, les échecs par fichier dans
`ingest_errors.log`. L'embedding de 500 000 documents sur CPU se compte en jours : prévoir un GPU
pour le run complet.

## 3. Répondre aux questions

```bash
python3 scripts/enterprise_rag_bench.py answer --max-context-chunks 10 --top-candidates 50
# Vérifier la chaîne sur un échantillon (5 questions × 10 catégories) :
python3 scripts/enterprise_rag_bench.py answer --per-type 5 --output data/enterprise-rag-bench/answers-sample.jsonl
# Itérer sur une catégorie :
python3 scripts/enterprise_rag_bench.py answer --question-type basic --limit 20 \
  --output data/enterprise-rag-bench/answers-basic.jsonl
```

Reprenable également : les questions déjà présentes dans `answers.jsonl` sont sautées.
`--max-docs` (défaut 10) borne les `document_ids` rendus, car chaque document hors-sujet est
compté en *invalid extra docs*. `--question-suffix` ajoute « Answer in English. » à chaque
question : la persona et les prompts internes de Spectra sont en français.

### Comparer deux réglages sur la même pile

Plusieurs réglages se basculent **par requête** (surcharges `RagOverrides`), sans redémarrer Spectra :

| Option | Effet |
|---|---|
| `--hybrid on\|off` | recherche hybride BM25 + vecteurs |
| `--neighbor-window N` | N chunks voisins (± N, 0-5) ajoutés à chaque passage |
| `--self-rag on\|off` | réflexion et raffinement en mode `answer` (serveur : `SPECTRA_SELF_RAG_ENABLED=true`) |
| `--multi-query on\|off` | reformulations de la question (serveur : `SPECTRA_MULTI_QUERY_ENABLED=true`) |
| `--dedup none\|jaccard\|mmr` | déduplication des passages (serveur : `SPECTRA_SEMANTIC_DEDUP_ENABLED=true`) |
| `--rerank-min-score S` | écarte les passages dont le score du reranker (0-1) est sous S |
| `--sources cited` | `document_ids` limités aux passages cités `[n]` |

Un module désactivé côté serveur ne peut pas être activé par la requête : activez-le au démarrage, puis
fixez-le explicitement (`--multi-query off`, `--dedup none`) dans les exécutions de référence, faute de quoi
le défaut du serveur s'applique. Chaque réponse conserve `spectra.contextChars` (taille du contexte servi),
`spectra.sourceScores` (scores du reranker) et, en mode agentique, le nombre d'itérations.

Le score de classement ne se mesure qu'avec le juge (partie suivante). Sans clé, le rappel documentaire, le
nombre de documents hors-sujet, la taille du contexte et la latence se comparent déjà, mais **ne disent pas
si la réponse est juste** : n'en tirez pas de changement de défaut.

### Comparer le retrieval sans générer

`retrieve` interroge `POST /api/query/retrieve` : mêmes surcharges, même pipeline que `/api/query`, mais **sans
génération**. Quelques secondes par question au lieu de plusieurs minutes sur CPU, donc les 470 questions qui ont
des documents de référence en une fois. Il rend rappel@k, hit@k, MRR et le nombre de documents hors référence
(approximation des « invalid extra docs » du banc).

```bash
python3 scripts/enterprise_rag_bench.py retrieve --hybrid on --rerank-min-score 0.2 \
  --output data/enterprise-rag-bench/retrieval-seuil-0.2.jsonl
```

Les étapes qui supposent le modèle de chat (RAG corrective, compression, boucle agentique) ne s'exécutent pas.

### Sur un environnement qui redémarre

Un conteneur éphémère ou une machine partagée peut redémarrer en cours de run. Les trois commandes qui appellent
l'API reprennent là où elles s'étaient arrêtées (`ingest` note les fichiers indexés, `answer` et `retrieve` sautent
les questions déjà écrites), donc relancer la même commande suffit. Deux garde-fous évitent de mesurer à vide :

- `--wait-ready SECONDES` (600 par défaut, 0 pour désactiver) : avant de commencer, et entre deux essais après
  une coupure, le harnais attend `/actuator/health/readiness`. L'API le garde fermé tant que l'index BM25 n'est pas
  rechargé ; sans cela, une requête « hybride » envoyée juste après un redémarrage est vectorielle seule.
- `scripts/bench-resume.sh <commande…>` : relance le démon Docker s'il est arrêté (en supprimant d'abord les
  fichiers pid périmés, qui font échouer `dockerd` quand un numéro de processus est réutilisé), relance la pile,
  puis exécute la commande du harnais.

```bash
scripts/bench-resume.sh retrieve --hybrid on --output data/enterprise-rag-bench/retrieval.jsonl
```

## 4. Mesurer

Rappel documentaire immédiat, sans LLM juge :

```bash
python3 scripts/enterprise_rag_bench.py recall
```

Score complet avec l'évaluateur officiel (clé OpenAI ou Anthropic requise ; le classement utilise
GPT-5.4 en raisonnement medium) :

```bash
git clone https://github.com/onyx-dot-app/EnterpriseRAG-Bench && cd EnterpriseRAG-Bench
pip install -r requirements.txt
export LLM_PROVIDER=openai LLM_API_KEY=... LLM_MODEL_NAME=gpt-5.4
python -m src.scripts.answer_evaluation.metrics_based_eval \
  --answers-file ../SpectraLLM/data/enterprise-rag-bench/answers.jsonl --parallelism 8
```

`answer_evaluation/results.json` contient les agrégats et le détail par question.

### Juger sans zéros silencieux, comparer sans bruit

`scripts/enterprise_rag_eval.py` règle deux pièges rencontrés pendant les mesures :

```bash
# sonde l'API du juge, lance l'évaluateur, écarte un résultat dont des appels ont échoué
python3 scripts/enterprise_rag_eval.py judge --evaluator-dir ../EnterpriseRAG-Bench \
  --answers-file answer_evaluation/spectra/answers.jsonl --questions-file answer_evaluation/spectra/questions.jsonl \
  --results-file answer_evaluation/spectra/results.json --python ../venv/bin/python

# différence appariée par question, intervalle de confiance bootstrap à 95 %
python3 scripts/enterprise_rag_eval.py compare results-a.json results-b.json --metric score
```

Crédit épuisé ou quota atteint : l'évaluateur d'Onyx ne s'arrête pas, il note chaque question 0 % et termine
« avec succès ». `judge` sonde d'abord l'API (code de sortie 3 si le juge est indisponible) puis refuse un
résultat où des lignes `[WARN] … failed` apparaissent (code 4, fichier renommé `….INVALID-judge-failures.json`).

`compare` dit si un écart se distingue du bruit. Sur 50 questions, +9 points de score peuvent encore être
compatibles avec zéro : le nombre de victoires et de défaites par question et l'intervalle comptent plus que la
moyenne. Métriques : `score` (classement), `correct`, `completeness`, `recall`, `extra`.

## Résultats mesurés (sous-corpus de 5 722 documents)

Échantillon de 50 questions (5 par catégorie), juge GPT-5.4, `--no-correction`. Le sous-corpus facilite la
recherche : ces nombres servent à **comparer des réglages entre eux**, pas à se situer dans le classement.

| Réglage | Score | Écart | Lecture |
|---|---|---|---|
| 2 048 tokens par requête | 32,0 | — | référence |
| 8 192 tokens par requête | 51,6 | +19,5 (IC95 % [+9,1 ; +30,8]) | distinct du bruit |
| + recherche hybride | 60,8 | +9,2 (IC95 % [−2,3 ; +20,4]) | **indiscernable du bruit** |

- Recherche hybride : le rappel documentaire passe de 83,1 % à 94,8 % (écart distinct du bruit) ; le gain de
  score, lui, n'est pas établi à n = 50. Le défaut `true` repose sur le rappel.
- Fenêtre de voisins (`--neighbor-window 2`) : 65,1 → 63,6, bruit.
- Sources citées seulement (`--sources cited`) : documents hors référence 6,0 → 1,3 par question, mais rappel
  94 % → 70 % : à réserver à un classement qui pénalise les documents en trop.
- Graphe d'entités : aucun changement des documents retrouvés sur les 16 questions concernées.
- Outils de l'agent : 0 appel d'outil sur 12 questions avec un 7B ; sans outils, 8 réponses directes sur 12.

### Retrieval seul (`retrieve`), corpus de 7 402 documents indexés

Échantillon de 15 questions par catégorie (n = 120, dont 60 pour les 4 catégories multi-documents), recherche
hybride, reranker ms-marco L6, 10 passages :

| Réglage | n | recall@10 | MRR | documents hors référence @10 |
|---|---|---|---|---|
| référence, toutes catégories | 120 | 0,902 | 0,904 | 5,94 |
| référence, catégories multi-documents | 60 | 0,838 | 0,944 | 4,57 |
| + MMR (`--dedup mmr`), multi-documents | 60 | 0,815 | 0,944 | 4,53 |

MMR ne se distingue pas de la référence à cette taille d'échantillon (rappel −2 points, MRR et bruit inchangés).

**Non mesuré :** seuil du reranker, multi-query, rerankers `mmarco` et `bge-reranker-v2-m3` (modèles prêts dans
`data/models/reranker-mmarco` et `reranker-bge`, voir `SPECTRA_RERANKER_ONNX_PATH`). Une première série sur 5 722
documents, publiée dans une version précédente de ce guide (rappel 0,905 sans seuil ; 0,812 avec un seuil de 0,1),
a été **retirée** : elle a été mesurée pendant que l'index BM25 se rechargeait après un redémarrage, donc en
recherche vectorielle seule.

> **Piège de mesure.** Après un redémarrage, l'index BM25 se recharge de façon asynchrone (log
> `chroma=…, fts=0`, puis `loaded from disk` ou `rebuilt`). Une requête envoyée entre les deux est hybride de nom
> seulement : `both=0`, vecteurs seuls. Depuis cette version, `/actuator/health/readiness` reste fermé tant que
> l'index n'est pas prêt ; sur une version plus ancienne, attendre ce message avant de mesurer. Ne jamais mélanger
> dans une même série des lignes obtenues avant et après.

**Empreinte mémoire de l'index BM25 (mesurée).** Un vrai index de 26 572 passages (7 402 documents, 127 Mo
sérialisés) occupe **655 Mo de tas, soit ≈ 26 Ko par passage**. Ce corpus donne 3,6 passages par document ; à
500 000 documents, cela fait ≈ 1,8 million de passages et **≈ 47 Go de tas**, donc hors de portée d'un profil de
16 Go. Une version précédente de ce guide annonçait ≈ 5 Ko par passage (≈ 9 Go) : c'était une extrapolation sans
mesure, fausse d'un facteur 5. Un essai sur le corpus complet suppose un index lexical sur disque (Lucene,
SQLite FTS5) ou une compression de l'index en mémoire.

## 5. Soumettre au classement

Le run doit couvrir les 500 questions sur le corpus complet. La soumission se fait par e-mail à
l'équipe Onyx (adresse dans la carte du dataset) avec un guide de reproduction — ce document et
la configuration Spectra utilisée (modèle chat, modèle d'embedding, modules RAG actifs).
