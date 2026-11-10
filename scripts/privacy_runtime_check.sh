#!/usr/bin/env bash
# Contrôle d'exécution de la confidentialité, sur une pile qui tourne (non exécutable en CI) :
#   1. une question portant un marqueur unique ne doit apparaître dans AUCUN journal ;
#   2. un document portant un autre marqueur est ingéré, interrogé, puis supprimé : son texte ne doit plus
#      figurer dans le stockage Qdrant, ni dans les journaux, ni dans la base GED.
# À lancer sur une pile de TEST (il ingère puis supprime un document « zz-privacy-check-*.txt »).
#
# Usage : scripts/privacy_runtime_check.sh [--api URL] [--data-dir DIR] [--compose-logs]
# Variables : SPECTRA_API_KEY (en-tête X-API-Key), SPECTRA_COLLECTION (collection de test, facultatif).
# Codes de sortie : 0 conforme, 1 résidu trouvé, 2 pile injoignable ou erreur d'ingestion.
set -euo pipefail

API=${SPECTRA_API_URL:-http://localhost:8080}
DATA_DIR=./data
COMPOSE_LOGS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --api) API=$2; shift 2 ;;
    --data-dir) DATA_DIR=$2; shift 2 ;;
    --compose-logs) COMPOSE_LOGS=1; shift ;;
    *) echo "option inconnue : $1" >&2; exit 2 ;;
  esac
done

AUTH=()
[ -n "${SPECTRA_API_KEY:-}" ] && AUTH=(-H "X-API-Key: ${SPECTRA_API_KEY}")
SUFFIX=$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')
DOC_MARK="zzdocmark${SUFFIX}"
QUESTION_MARK="zzquestionmark${SUFFIX}"
FILE_NAME="zz-privacy-check-${SUFFIX}.txt"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
fail=0

api() { curl -fsS --max-time 120 "${AUTH[@]}" "$@"; }
json() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

echo "Pile : $API  |  données : $DATA_DIR  |  marqueurs : $DOC_MARK, $QUESTION_MARK"
api "$API/actuator/health" >/dev/null 2>&1 || { echo "API injoignable : $API" >&2; exit 2; }

printf 'Le code de la procédure de contrôle de confidentialité est %s. Ce document sert uniquement au contrôle.\n' \
  "$DOC_MARK" > "$WORK/$FILE_NAME"
COLLECTION_ARGS=()
[ -n "${SPECTRA_COLLECTION:-}" ] && COLLECTION_ARGS=(-F "collection=${SPECTRA_COLLECTION}")
TASK=$(api -F "files=@${WORK}/${FILE_NAME}" "${COLLECTION_ARGS[@]}" "$API/api/ingest" | json "['taskId']") \
  || { echo "ingestion refusée" >&2; exit 2; }
for _ in $(seq 1 60); do
  STATUS=$(api "$API/api/ingest/$TASK" | json "['status']")
  case "$STATUS" in COMPLETED) break ;; FAILED) echo "ingestion échouée" >&2; exit 2 ;; esac
  sleep 2
done
[ "$STATUS" = COMPLETED ] || { echo "ingestion non terminée après 120 s" >&2; exit 2; }

# 1. Question à marqueur : aucune trace attendue dans les journaux (LogSafe).
api -X POST -H 'Content-Type: application/json' \
  -d "{\"question\":\"Quel est le code de la procédure ${QUESTION_MARK} ?\"}" "$API/api/query" >/dev/null || true
sleep "${PRIVACY_CHECK_SETTLE:-2}"

scan() {  # scan <marqueur> <libellé> <chemin>... : renvoie 1 si le marqueur est présent
  local mark=$1 label=$2; shift 2
  local hits
  hits=$(grep -rla -- "$mark" "$@" 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "RÉSIDU  $label : $mark trouvé dans :"
    while IFS= read -r hit; do echo "          $hit"; done <<< "$hits"
    return 1
  fi
  echo "ok      $label : $mark absent"
}
scan "$QUESTION_MARK" "question / journaux fichiers" "$DATA_DIR/logs" || fail=1
if [ "$COMPOSE_LOGS" = 1 ]; then
  if docker compose logs --no-color spectra-api 2>/dev/null | grep -aq -- "$QUESTION_MARK"; then
    echo "RÉSIDU  question / journaux conteneur : $QUESTION_MARK présent"; fail=1
  else
    echo "ok      question / journaux conteneur : $QUESTION_MARK absent"
  fi
fi

# 2. Suppression : le texte du document doit disparaître du stockage. Sans trace AVANT la suppression,
#    l'absence APRÈS ne prouve rien (mauvais --data-dir, stockage ailleurs) : le contrôle est alors non probant.
if ! grep -rqa -- "$DOC_MARK" "$DATA_DIR/qdrant" 2>/dev/null; then
  echo "Contrôle non probant : $DOC_MARK introuvable dans $DATA_DIR/qdrant avant suppression (--data-dir ?)." >&2
  exit 2
fi
echo "ok      document / stockage Qdrant avant suppression : $DOC_MARK présent (le contrôle est probant)"
api -X DELETE "$API/api/documents/${FILE_NAME}${SPECTRA_COLLECTION:+?collection=${SPECTRA_COLLECTION}}" >/dev/null \
  || { echo "suppression refusée" >&2; exit 2; }
sleep "${PRIVACY_CHECK_DELETE_SETTLE:-5}"
scan "$DOC_MARK" "document / stockage Qdrant après suppression" "$DATA_DIR/qdrant" || fail=1
scan "$DOC_MARK" "document / journaux fichiers" "$DATA_DIR/logs" || fail=1
scan "$DOC_MARK" "document / base GED et archives" "$DATA_DIR/spectra-db.mv.db" "$DATA_DIR/archive" || fail=1
if [ "$fail" -ne 0 ]; then
  cat <<'MSG' >&2

Un résidu n'est pas toujours une fuite applicative : Qdrant retire physiquement un point supprimé lors de
l'optimisation des segments, pas à la suppression. Remède garanti : arrêter la pile et supprimer
data/qdrant (puis ré-ingérer), ou régler QDRANT__STORAGE__OPTIMIZERS__* (voir la checklist).
MSG
  exit 1
fi
echo "Contrôle conforme."
