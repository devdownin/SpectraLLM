# EnterpriseRAG-Bench — évaluer Spectra sur le banc d'Onyx

[EnterpriseRAG-Bench](https://github.com/onyx-dot-app/EnterpriseRAG-Bench) est un banc RAG sur
données d'entreprise synthétiques (≈ 500 000 documents Slack, Gmail, Linear, Drive, Confluence…,
500 questions en 10 catégories). Le classement public est sur
[Hugging Face](https://huggingface.co/spaces/onyx-dot-app/EnterpriseRAG-Bench-Leaderboard).

`scripts/enterprise_rag_bench.py` produit le fichier de réponses de l'évaluateur officiel ; le
score (correctness, completeness, recall, invalid extra docs) est ensuite calculé par le script
d'Onyx, avec un LLM juge.

## 1. Préparer le corpus

```bash
pip install pyarrow
# Essai rapide : documents de référence (~800) + 5 000 distracteurs
python3 scripts/enterprise_rag_bench.py prepare --subset 5000
# Run complet (leaderboard) : 1,4 Go de parquet, ~500 000 fichiers
python3 scripts/enterprise_rag_bench.py prepare
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

## 5. Soumettre au classement

Le run doit couvrir les 500 questions sur le corpus complet. La soumission se fait par e-mail à
l'équipe Onyx (adresse dans la carte du dataset) avec un guide de reproduction — ce document et
la configuration Spectra utilisée (modèle chat, modèle d'embedding, modules RAG actifs).
