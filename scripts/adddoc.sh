#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────
# Spectra — Ingestion de documents
# Usage  : ./adddoc.sh [repertoire]
# Défaut : data/documents
# Formats: PDF, DOCX, DOC, JSON, XML, TXT, ZIP
#
# POURQUOI CE FICHIER EXISTE
# Il n'existait pas. adddoc.bat, lui, si — et setup.sh comme examples/README.md
# renvoyaient tous deux vers « scripts/adddoc.sh », un fichier absent du dépôt. Le
# chemin de découverte proposé à la fin de l'installation menait donc, sur Linux et
# macOS, à un « No such file or directory ».
# ────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Les chemins de l'utilisateur (data/documents, examples/…) sont relatifs à la RACINE
# du dépôt, pas à scripts/.
cd "$SCRIPT_DIR/.."

API_URL="${SPECTRA_API_URL:-http://localhost:8080}"
INGEST_ENDPOINT="$API_URL/api/ingest"
POLL_INTERVAL=3
MAX_POLL=120

SOURCE_DIR="${1:-data/documents}"

echo "======================================"
echo "  Spectra — Ingestion de documents"
echo "======================================"
echo
echo "  Répertoire : $SOURCE_DIR"
echo "  API        : $INGEST_ENDPOINT"
echo

# ── 1. Vérification du répertoire ─────────────────────────────
if [ ! -d "$SOURCE_DIR" ]; then
  echo "[ERREUR] Répertoire introuvable : $SOURCE_DIR"
  exit 1
fi

# ── 2. Vérification que l'API est accessible ──────────────────
echo "> Vérification de l'API Spectra..."
API_READY=0
for i in $(seq 1 20); do
  if curl -sf --max-time 3 "$API_URL/actuator/health" >/dev/null 2>&1; then
    API_READY=1
    echo "  [OK] API prête"
    break
  fi
  [ "$i" -eq 1 ] && echo "  Attente du démarrage de l'API..."
  sleep 3
done
if [ "$API_READY" -eq 0 ]; then
  echo "  [ERREUR] API inaccessible après 60 s. Vérifiez que ./scripts/start.sh a été lancé."
  exit 1
fi

# ── 3. Collecte des fichiers supportés ────────────────────────
echo
echo "> Recherche de documents dans $SOURCE_DIR..."

FILES=()
while IFS= read -r -d '' f; do
  FILES+=("$f")
done < <(find "$SOURCE_DIR" -maxdepth 1 -type f \
  \( -iname '*.pdf' -o -iname '*.docx' -o -iname '*.doc' -o -iname '*.json' \
     -o -iname '*.xml' -o -iname '*.txt' -o -iname '*.zip' \) -print0 | sort -z)

if [ ${#FILES[@]} -eq 0 ]; then
  echo "  [AVERTISSEMENT] Aucun document trouvé dans $SOURCE_DIR"
  echo "  Formats supportés : PDF, DOCX, DOC, JSON, XML, TXT, ZIP"
  exit 0
fi

CURL_ARGS=()
n=0
for f in "${FILES[@]}"; do
  n=$((n + 1))
  echo "  [$n] $(basename "$f")"
  CURL_ARGS+=(-F "files=@${f}")
done
echo
echo "  Total : $n fichier(s) à ingérer"

# ── 4. Envoi ──────────────────────────────────────────────────
echo
echo "> Envoi vers l'API d'ingestion..."
RESPONSE="$(curl -sf --max-time 120 -X POST "${CURL_ARGS[@]}" "$INGEST_ENDPOINT" || true)"

if [ -z "$RESPONSE" ]; then
  echo "  [ERREUR] Réponse vide de l'API."
  exit 1
fi
echo "  Réponse : $RESPONSE"

# ── 5. Extraction du taskId ───────────────────────────────────
# Extraction textuelle plutôt que jq : l'outil n'est pas garanti présent, et exiger une
# dépendance supplémentaire pour lire un seul champ ferait échouer le premier essai de
# l'utilisateur sur un détail sans rapport avec l'ingestion.
TASK_ID="$(printf '%s' "$RESPONSE" | sed -n 's/.*"taskId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
if [ -z "$TASK_ID" ]; then
  echo "  [ERREUR] Impossible d'extraire le taskId."
  exit 1
fi
echo "  TaskId  : $TASK_ID"

# ── 6. Suivi de la progression ────────────────────────────────
echo
echo "> Suivi de l'ingestion (taskId: $TASK_ID)..."
FINAL_STATUS="UNKNOWN"
CHUNKS=""
for poll in $(seq 1 $MAX_POLL); do
  sleep "$POLL_INTERVAL"
  STATUS_JSON="$(curl -sf --max-time 5 "$INGEST_ENDPOINT/$TASK_ID" || echo '')"
  CURRENT_STATUS="$(printf '%s' "$STATUS_JSON" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  CHUNKS="$(printf '%s' "$STATUS_JSON" | sed -n 's/.*"chunksCreated"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')"

  case "$CURRENT_STATUS" in
    COMPLETED) FINAL_STATUS="COMPLETED"; break ;;
    FAILED)    FINAL_STATUS="FAILED";    break ;;
  esac
  echo "  [$poll/$MAX_POLL] Statut : ${CURRENT_STATUS:-?} — chunks : ${CHUNKS:-0}"
done

if [ "$FINAL_STATUS" = "UNKNOWN" ]; then
  echo "  [TIMEOUT] L'ingestion dépasse le délai d'attente."
  exit 1
fi

# ── 7. Résultat final ─────────────────────────────────────────
echo
echo "======================================"
if [ "$FINAL_STATUS" = "COMPLETED" ]; then
  echo "  [OK] Ingestion terminée avec succès"
  echo "  Chunks vectorisés : ${CHUNKS:-0}"
  echo
  echo "  Étape suivante :"
  echo "    POST $API_URL/api/dataset/generate"
  echo "    puis POST $API_URL/api/fine-tuning"
else
  echo "  [ÉCHEC] Ingestion échouée — vérifiez les logs Docker :"
  echo "    ./scripts/stop.sh ; docker compose --project-directory . -f deploy/docker/docker-compose.yml logs spectra-api"
fi
echo "======================================"
echo
