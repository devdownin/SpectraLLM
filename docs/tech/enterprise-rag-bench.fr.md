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

## 5. Soumettre au classement

Le run doit couvrir les 500 questions sur le corpus complet. La soumission se fait par e-mail à
l'équipe Onyx (adresse dans la carte du dataset) avec un guide de reproduction — ce document et
la configuration Spectra utilisée (modèle chat, modèle d'embedding, modules RAG actifs).
