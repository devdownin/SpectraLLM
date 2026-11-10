#!/usr/bin/env bash
# Baseline EnterpriseRAG-Bench v1 : corpus, lots, ingestion, retrieve, answer, juge et rapports.
# Prérequis : pile démarrée avec deploy/bench/enterprise-rag-baseline-v1.env et un index vide,
# évaluateur Onyx cloné dans $EVALUATOR à la révision ci-dessous, LLM_API_KEY pour le juge.
# Reprise : relancer le script ; chaque étape reprend là où elle s'est arrêtée.
set -euo pipefail

ONYX_COMMIT=d36685e273713975ee20299bbf1ab64165575b3c
CORPUS_REV=69916e31c68aa5963c00248fd7f0bc12d04fd235
WORK=${WORK:-data/onyx-baseline-v1}
API=${API:-http://localhost:8080}
COLLECTION=${COLLECTION:-spectra_documents}
EVALUATOR=${EVALUATOR:-../EnterpriseRAG-Bench}
JUDGE_PYTHON=${JUDGE_PYTHON:-$EVALUATOR/.venv/bin/python}
PROFILE=deploy/bench/enterprise-rag-baseline-v1.profile.json
export LLM_PROVIDER=${LLM_PROVIDER:-openai} LLM_MODEL_NAME=${LLM_MODEL_NAME:-gpt-5.4}

bench() { python3 scripts/enterprise_rag_bench.py --api "$API" --work-dir "$WORK" "$@"; }
experiment() { python3 scripts/enterprise_rag_experiment.py "$@"; }

if [ ! -f "$WORK/questions.jsonl" ]; then
  bench prepare --subset 5000 --seed 42 \
    --questions-url "https://raw.githubusercontent.com/onyx-dot-app/EnterpriseRAG-Bench/$ONYX_COMMIT/questions.jsonl" \
    --documents-url "https://huggingface.co/datasets/onyx-dot-app/EnterpriseRAG-Bench/resolve/$CORPUS_REV/data/documents/test.parquet"
fi
[ -f "$WORK/split/dev-120.jsonl" ] || experiment split --questions "$WORK/questions.jsonl" --out "$WORK/split"
bench ingest

for MODE in retrieve answer; do
  experiment run --api "$API" --mode "$MODE" --collection "$COLLECTION" \
    --questions "$WORK/split/dev-120.jsonl" --corpus-dir "$WORK/documents" --profile "$PROFILE" \
    --hybrid on --multi-query off --max-context-chunks 10 --top-candidates 20 --parallelism 1 \
    --out "$WORK/baseline-$MODE"
done
experiment judge --out "$WORK/baseline-answer" --evaluator-dir "$EVALUATOR" --python "$JUDGE_PYTHON"
experiment report --out "$WORK/baseline-retrieve"
experiment report --out "$WORK/baseline-answer"
