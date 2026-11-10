#!/usr/bin/env bash
# Audit du retrieval (étape 3) pour UN index déjà ingéré : dense seul, hybride, reranker, filtres de
# payload et, sur demande, paramètres de recherche Qdrant et hybride natif.
# Usage : enterprise_rag_audit.sh <index> [groupes…]    (groupes : modes ann native ; "modes" par défaut)
#   modes   dense | hybride Java | hybride + reranker | idem sous filtre de payload (sans redémarrage)
#   ann     hnsw_ef 64/128/256, recherche exacte, ACORN sous filtre (recrée l'API : AUDIT_ENV_FILES requis)
#   native  stratégies dense/sparse/rrf/dbsf de l'hybride natif (index-native.env requis, idem)
# Variables : AUDIT_DIR QUESTIONS CORPUS_DIR COLLECTION API PROFILE AUDIT_ENV_FILES (liste d'--env-file Compose
# de la pile de cet index, séparés par des espaces). Reprenable : relancer la commande.
set -euo pipefail

INDEX=${1:?usage: $0 <index> [modes|ann|native]...}
shift
GROUPS_TO_RUN=("${@:-modes}")
WORK=${WORK:-data/onyx-baseline-v1}
AUDIT_DIR=${AUDIT_DIR:-$WORK/audit}
QUESTIONS=${QUESTIONS:-$WORK/split/dev-120.jsonl}
CORPUS_DIR=${CORPUS_DIR:-$WORK/documents}
API=${API:-http://localhost:8080}
COLLECTION=${COLLECTION:-spectra_documents}
PROFILE=${PROFILE:-deploy/bench/enterprise-rag-baseline-v1.profile.json}
OVERLAYS=deploy/bench/stage3
CANDIDATES=${CANDIDATES:-20}
mkdir -p "$AUDIT_DIR/$INDEX"

# profile <variante> <clé=JSON>... : profil de l'index + réglages de la variante (consignés dans run.json).
profile() {
  local name=$1; shift
  python3 - "$PROFILE" "$AUDIT_DIR/$INDEX/profile-$name.json" "$INDEX" "$@" <<'PY'
import json, sys
src, dst, index, *pairs = sys.argv[1:]
data = json.load(open(src))
data["name"] = f"retrieval-audit-{index}"
variant = data.setdefault("variant", {})
variant["embedding"] = index
for pair in pairs:
    key, value = pair.split("=", 1)
    variant[key] = json.loads(value)
json.dump(data, open(dst, "w"), indent=2, ensure_ascii=False)
PY
  echo "$AUDIT_DIR/$INDEX/profile-$name.json"
}

# variant <nom> <profil> <options de run…> : retrieve sur dev-120 (10 passages), puis report.json.
variant() {
  local name=$1 profile_path=$2; shift 2
  python3 scripts/enterprise_rag_experiment.py run --api "$API" --mode retrieve --collection "$COLLECTION" \
    --questions "$QUESTIONS" --corpus-dir "$CORPUS_DIR" --profile "$profile_path" \
    --max-context-chunks 10 --top-candidates "$CANDIDATES" --parallelism 1 "$@" --out "$AUDIT_DIR/$INDEX/$name"
  python3 scripts/enterprise_rag_experiment.py report --out "$AUDIT_DIR/$INDEX/$name" >/dev/null
}

# restart <overlay…> : recrée l'API seule avec les surcharges (index et volumes conservés).
restart() {
  local files=() f
  for f in ${AUDIT_ENV_FILES:?AUDIT_ENV_FILES est requis pour recréer l\'API}; do files+=(--env-file "$f"); done
  for f in "$@"; do files+=(--env-file "$OVERLAYS/$f"); done
  docker compose --project-directory . -f deploy/docker/docker-compose.yml "${files[@]}" up -d --no-deps --wait spectra-api
}

run_modes() {
  variant dense "$(profile dense 'search="dense"')" --hybrid off --rerank off
  variant hybrid "$(profile hybrid 'search="hybrid-java"')" --hybrid on --rerank off
  variant hybrid-rerank "$(profile hybrid-rerank 'search="hybrid-java+rerank"')" --hybrid on --rerank on
  for ratio in 0.1 0.01; do
    variant "dense-filter$ratio" "$(profile "dense-filter$ratio" 'search="dense"' "filter_ratio=$ratio")" \
      --hybrid off --rerank off --filter-ratio "$ratio"
    variant "hybrid-rerank-filter$ratio" "$(profile "hybrid-rerank-filter$ratio" 'search="hybrid-java+rerank"' "filter_ratio=$ratio")" \
      --hybrid on --rerank on --filter-ratio "$ratio"
  done
}

run_ann() {  # ANN isolé : dense seul, sans reranker ; ACORN éprouvé sous filtre sélectif
  for setting in ef64 ef128 ef256 exact; do
    restart "search-$setting.env"
    variant "dense-$setting" "$(profile "dense-$setting" 'search="dense"' "qdrant=\"$setting\"")" --hybrid off --rerank off
  done
  restart search-acorn.env
  variant dense-acorn-filter0.01 "$(profile dense-acorn-filter0.01 'search="dense"' 'qdrant="acorn"' 'filter_ratio=0.01')" \
    --hybrid off --rerank off --filter-ratio 0.01
  restart  # retour à la pile de l'index
}

run_native() {
  for strategy in dense sparse rrf dbsf; do
    restart "native-$strategy.env"
    variant "native-$strategy" "$(profile "native-$strategy" "search=\"native-$strategy\"")" --hybrid on --rerank off
  done
  restart native-rrf.env
  variant native-rrf-rerank "$(profile native-rrf-rerank 'search="native-rrf+rerank"')" --hybrid on --rerank on
  restart
}

for group in "${GROUPS_TO_RUN[@]}"; do
  case "$group" in
    modes) run_modes ;; ann) run_ann ;; native) run_native ;;
    *) echo "groupe inconnu : $group" >&2; exit 2 ;;
  esac
done
python3 scripts/enterprise_rag_matrix.py --audit-dir "$AUDIT_DIR" ${REFERENCE:+--reference "$REFERENCE"}
