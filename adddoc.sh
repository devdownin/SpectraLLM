#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Spectra — Ingestion de documents (Linux / macOS)
# Usage  : ./adddoc.sh [repertoire]
# Defaut : data/documents
# Formats: PDF, DOCX, DOC, JSON, XML, TXT, ZIP
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

API_URL="http://localhost:8080"
INGEST_ENDPOINT="$API_URL/api/ingest"
POLL_INTERVAL=3
MAX_POLL=120

SOURCE_DIR="${1:-data/documents}"

green()  { echo -e "\033[32m$*\033[0m"; }
yellow() { echo -e "\033[33m$*\033[0m"; }
red()    { echo -e "\033[31m$*\033[0m"; }

echo "======================================"
echo "  Spectra — Ingestion de documents"
echo "======================================"
echo "  Répertoire : $SOURCE_DIR"
echo "  API        : $INGEST_ENDPOINT"
echo

# ── 1. Vérification du répertoire ───────────────────────────────────────────
if [ ! -d "$SOURCE_DIR" ]; then
    red "[ERREUR] Répertoire introuvable : $SOURCE_DIR"
    exit 1
fi

# ── 2. Vérification que l'API est accessible ───────────────────────────────
echo "> Vérification de l'API Spectra..."
if ! curl -sf "$API_URL/actuator/health" > /dev/null; then
    yellow "  Attente du démarrage de l'API..."
    MAX_WAIT=20
    COUNT=0
    until curl -sf "$API_URL/actuator/health" > /dev/null || [ $COUNT -eq $MAX_WAIT ]; do
        sleep 3
        COUNT=$((COUNT + 1))
    done
fi

if ! curl -sf "$API_URL/actuator/health" > /dev/null; then
    red "  [ERREUR] API inaccessible. Vérifiez que ./start.sh a été lancé."
    exit 1
else
    green "  [OK] API prête"
fi

# ── 3. Collecte des fichiers supportés ──────────────────────────────────────
echo
echo "> Recherche de documents dans $SOURCE_DIR..."

FILES=()
while IFS= read -r -d '' file; do
    FILES+=("$file")
done < <(find "$SOURCE_DIR" -maxdepth 1 -type f \( -name "*.pdf" -o -name "*.docx" -o -name "*.doc" -o -name "*.json" -o -name "*.xml" -o -name "*.txt" -o -name "*.zip" \) -print0)

FILE_COUNT=${#FILES[@]}

if [ "$FILE_COUNT" -eq 0 ]; then
    yellow "  [AVERTISSEMENT] Aucun document trouvé dans $SOURCE_DIR"
    echo "  Formats supportés : PDF, DOCX, DOC, JSON, XML, TXT, ZIP"
    exit 0
fi

echo "  Total : $FILE_COUNT fichier(s) à ingérer"
for f in "${FILES[@]}"; do
    echo "    - $(basename "$f")"
done

# ── 4. Envoi vers l'API ─────────────────────────────────────────────────────
echo
echo "> Envoi vers l'API d'ingestion..."

# Construction de la commande curl avec tous les fichiers
CURL_ARGS=()
for f in "${FILES[@]}"; do
    CURL_ARGS+=("-F" "files=@$f")
done

RESPONSE=$(curl -s "${CURL_ARGS[@]}" "$INGEST_ENDPOINT")

if [ -z "$RESPONSE" ]; then
    red "  [ERREUR] Réponse vide de l'API."
    exit 1
fi

TASK_ID=$(echo "$RESPONSE" | grep -oP '(?<="taskId":")[^"]*' || echo "")

if [ -z "$TASK_ID" ]; then
    red "  [ERREUR] Impossible d'extraire le taskId."
    echo "  Réponse API : $RESPONSE"
    exit 1
fi

green "  [OK] Requête soumise (taskId: $TASK_ID)"

# ── 5. Suivi de la progression ──────────────────────────────────────────────
echo
echo "> Suivi de l'ingestion..."

POLL=0
while [ $POLL -lt $MAX_POLL ]; do
    sleep "$POLL_INTERVAL"
    POLL=$((POLL + 1))

    STATUS_JSON=$(curl -s "$INGEST_ENDPOINT/$TASK_ID")
    STATUS=$(echo "$STATUS_JSON" | grep -oP '(?<="status":")[^"]*' || echo "UNKNOWN")
    CHUNKS=$(echo "$STATUS_JSON" | grep -oP '(?<="chunksCreated":)[0-9]*' || echo "0")

    echo "  [$POLL/$MAX_POLL] Statut : $STATUS — chunks : $CHUNKS"

    if [ "$STATUS" == "COMPLETED" ]; then
        echo
        green "======================================"
        green "  [OK] Ingestion terminée avec succès"
        green "  Chunks vectorisés : $CHUNKS"
        echo
        echo "  Étape suivante :"
        echo "    Accédez à l'interface : http://localhost:80"
        echo "    Ou lancez le dataset : curl -X POST $API_URL/api/dataset/generate"
        green "======================================"
        exit 0
    fi

    if [ "$STATUS" == "FAILED" ]; then
        ERROR=$(echo "$STATUS_JSON" | grep -oP '(?<="error":")[^"]*' || echo "Erreur inconnue")
        echo
        red "======================================"
        red "  [ÉCHEC] Ingestion échouée"
        red "  Détail : $ERROR"
        echo "  Vérifiez les logs Docker : docker compose logs spectra-api"
        red "======================================"
        exit 1
    fi
done

red "  [TIMEOUT] L'ingestion dépasse le délai d'attente."
exit 1
