#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# Spectra — Pipeline complet : Ingest → Dataset → Fine-tuning
#
# Usage  : ./pipeline.sh [repertoire] [modele-base] [nom-modele] [--reset] [--packing] [--dpo|--orpo]
# Exemples :
#   ./pipeline.sh data/documents phi3
#   ./pipeline.sh data/documents phi3 spectra-autoroute
#   ./pipeline.sh data/documents mistral spectra-mistral-autoroute
#   ./pipeline.sh data/documents phi3 phi3-autoroute --reset      (repart de zero)
#   ./pipeline.sh data/documents phi3 phi3-dpo --dpo              (alignement DPO)
#   ./pipeline.sh data/documents phi3 phi3-fast --packing         (multipacking)
#
# Arguments :
#   [repertoire]   Dossier source des documents (defaut: data/documents)
#   [modele-base]  Modele a enrichir : phi3 | mistral | llama3 (defaut: phi3)
#   [nom-modele]   Nom du modele enrichi (defaut: {modele-base}-autoroute)
#   --reset        Supprime l'adaptateur existant et repart d'un entrainement initial
#   --packing      Active le multipacking (concatenation des sequences courtes)
#   --dpo          Active l'alignement DPO (requiert une generation DPO prealable)
#   --orpo         Active l'alignement ORPO (SFT+preference en une passe, sans ref ; meme prerequis)
# ────────────────────────────────────────────────────────────────────────────

set -euo pipefail
# Les chemins de documents (data/documents par défaut) sont relatifs à la
# racine du dépôt, pas au dossier scripts/ où vit ce fichier.
cd "$(dirname "$0")/.."

# ── Couleurs ─────────────────────────────────────────────────────────────────
green()  { echo -e "\033[32m  [OK] $*\033[0m"; }
red()    { echo -e "\033[31m  [ERREUR] $*\033[0m"; }
yellow() { echo -e "\033[33m  [INFO] $*\033[0m"; }
die()    { red "$*"; exit 1; }

# ── Paramètres ────────────────────────────────────────────────────────────────
API_URL="http://localhost:8080"
POLL_INTERVAL=5
MAX_POLL_INGEST=120
MAX_POLL_DATASET=240

RESET_ADAPTER=0
PACKING_FLAG=""
DPO_FLAG=""
ORPO_FLAG=""

# Séparer les options (--xxx) des arguments positionnels, quel que soit l'ordre.
# Sinon « ./pipeline.sh data/docs phi3 --dpo » prendrait « --dpo » comme nom de modèle.
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --reset)   RESET_ADAPTER=1      ;;
    --packing) PACKING_FLAG="--packing" ;;
    --dpo)     DPO_FLAG="--dpo"     ;;
    --orpo)    ORPO_FLAG="--orpo"   ;;
    --*)       die "Option inconnue : $arg" ;;
    *)         POSITIONAL+=("$arg") ;;
  esac
done

SOURCE_DIR="${POSITIONAL[0]:-data/documents}"
BASE_MODEL="${POSITIONAL[1]:-phi3}"
MODEL_NAME="${POSITIONAL[2]:-${BASE_MODEL}-autoroute}"

DATASET_FILE="data/fine-tuning/pipeline-export.jsonl"
DPO_DATASET_FILE="data/fine-tuning/pipeline-dpo.jsonl"
ADAPTER_DIR="data/fine-tuning/pipeline-adapter"
MERGED_DIR="data/fine-tuning/pipeline-merged"

# Hyperparamètres surchargeables par variable d'environnement (sinon valeurs CPU rapides).
# Permet d'appliquer une recette (ex: EPOCHS=3 LORA_RANK=64 LORA_ALPHA=128 ./pipeline.sh ...).
EPOCHS="${EPOCHS:-1}"
LORA_RANK="${LORA_RANK:-8}"
LORA_ALPHA="${LORA_ALPHA:-16}"
LR="${LR:-2e-4}"
VAL_SPLIT="${VAL_SPLIT:-0}"
LORA_TARGET="${LORA_TARGET:-attention}"   # attention | all (+ MLP)
NEFTUNE_ALPHA="${NEFTUNE_ALPHA:-0}"        # 0 = off ; 5 = valeur courante
WARMUP_RATIO="${WARMUP_RATIO:-0.03}"

echo "======================================"
echo "  Spectra — Pipeline complet"
echo "======================================"
echo
echo "  Repertoire   : $SOURCE_DIR"
echo "  Modele base  : $BASE_MODEL"
echo "  Modele final : $MODEL_NAME"
echo "  Multipacking : ${PACKING_FLAG:-(desactive)}"
echo "  DPO          : ${DPO_FLAG:-(desactive)}"
echo "  ORPO         : ${ORPO_FLAG:-(desactive)}"
echo "  API          : $API_URL"
echo

# ══════════════════════════════════════════════════════════════════════════════
# ETAPE 0 — PREREQUIS
# ══════════════════════════════════════════════════════════════════════════════
echo "> [0/5] Verification des prerequis..."

# Fichiers GGUF — la stack Docker charge data/models/${LLM_CHAT_MODEL_FILE}.
CHAT_MODEL_FILE="${LLM_CHAT_MODEL_FILE:-}"
if [ -z "$CHAT_MODEL_FILE" ] && [ -f ".env" ]; then
  CHAT_MODEL_FILE="$(grep -E '^LLM_CHAT_MODEL_FILE=' .env | tail -n1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//')"
fi
CHAT_MODEL_FILE="${CHAT_MODEL_FILE:-Phi-4-mini-reasoning-UD-IQ1_S.gguf}"
GGUF_CHAT="data/models/$CHAT_MODEL_FILE"
GGUF_EMBED="data/models/embed.gguf"

if [ ! -f "$GGUF_CHAT" ]; then
  die "Modele de chat introuvable : $GGUF_CHAT
  Placez un GGUF instruction-tuned dans data/models/ (et renseignez
  LLM_CHAT_MODEL_FILE dans .env), ou lancez :
    ./setup.sh --download-chat"
fi
green "Modele chat : $GGUF_CHAT"

if [ ! -f "$GGUF_EMBED" ]; then
  die "Modele d'embedding introuvable : $GGUF_EMBED
  Lancez : ./setup.sh --download-embed"
fi
green "Modele embed : $GGUF_EMBED"

# Python
if ! command -v python3 &>/dev/null; then
  die "Python3 introuvable. Installez Python 3.10+."
fi
if ! python3 -c "import peft, transformers, trl" &>/dev/null; then
  die "Dependances Python manquantes.
  Executez : pip install peft transformers trl accelerate bitsandbytes"
fi
green "Python $(python3 --version) et dependances OK"

# API
echo "  Attente de l'API Spectra..."
API_READY=0
for _ in $(seq 1 20); do
  if curl -sf "$API_URL/actuator/health" -o /dev/null 2>/dev/null; then
    API_READY=1
    break
  fi
  sleep 3
done
[ "$API_READY" -eq 1 ] || die "API inaccessible apres 60s. Lancez docker compose up -d d'abord."
green "API prete"

# ── Clé API (si l'authentification est activée) ───────────────────────────────
# Si SPECTRA_API_KEY est définie (env ou .env), toutes les requêtes /api/** doivent
# porter l'en-tête X-API-Key — sinon l'API répond 401. L'ingestion, la génération,
# l'export et la bascule de modèle passent tous par /api/** (non exemptés par
# ApiKeyFilter ; seul /actuator l'est, d'où le health check ci-dessus sans clé).
API_KEY="${SPECTRA_API_KEY:-}"
if [ -z "$API_KEY" ] && [ -f ".env" ]; then
  API_KEY="$(grep -E '^SPECTRA_API_KEY=' .env | tail -n1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//')"
fi
AUTH=()
if [ -n "$API_KEY" ]; then
  AUTH=(-H "X-API-Key: $API_KEY")
  green "Authentification API activee (X-API-Key)"
fi

# ══════════════════════════════════════════════════════════════════════════════
# ETAPE 1 — INGESTION
# ══════════════════════════════════════════════════════════════════════════════
echo
echo "> [1/5] Ingestion des documents depuis $SOURCE_DIR..."

[ -d "$SOURCE_DIR" ] || die "Repertoire introuvable : $SOURCE_DIR"

# Collecte des fichiers supportes
FILES=()
for ext in pdf docx doc json xml txt; do
  while IFS= read -r -d '' f; do
    FILES+=("$f")
  done < <(find "$SOURCE_DIR" -maxdepth 1 -iname "*.${ext}" -print0 2>/dev/null)
done

FILE_COUNT="${#FILES[@]}"
[ "$FILE_COUNT" -gt 0 ] || die "Aucun document trouve dans $SOURCE_DIR (formats: pdf docx doc json xml txt)"
echo "  $FILE_COUNT fichier(s) detecte(s)"

# Construction de la requete multipart avec curl
CURL_ARGS=()
for f in "${FILES[@]}"; do
  CURL_ARGS+=(-F "files=@${f}")
done

# Soumission avec gestion de la contre-pression (HTTP 429). Le serveur refuse une
# soumission quand trop de taches sont actives (spectra.pipeline.max-active-ingestions) ;
# on respecte alors l'en-tete Retry-After et on reessaie au lieu d'abandonner.
INGEST_MAX_RETRIES="${INGEST_MAX_RETRIES:-5}"
_HDR="$(mktemp)"; _BODY="$(mktemp)"
trap 'rm -f "$_HDR" "$_BODY"' EXIT
INGEST_RESP=""
attempt=0
while true; do
  attempt=$((attempt + 1))
  HTTP_CODE=$(curl -s --max-time 120 -o "$_BODY" -D "$_HDR" -w '%{http_code}' \
    ${AUTH[@]+"${AUTH[@]}"} \
    "${CURL_ARGS[@]}" \
    "$API_URL/api/ingest" || echo 000)
  case "$HTTP_CODE" in
    200|201)
      INGEST_RESP="$(cat "$_BODY")"; break ;;
    429)
      [ "$attempt" -lt "$INGEST_MAX_RETRIES" ] \
        || die "Ingestion refusee (429) apres $attempt tentatives — trop d'ingestions actives cote serveur. Reessayez plus tard."
      RETRY_AFTER=$(grep -i '^retry-after:' "$_HDR" | tail -n1 | tr -d '\r' | awk '{print $2}')
      case "$RETRY_AFTER" in ''|*[!0-9]*) RETRY_AFTER=5 ;; esac
      yellow "Serveur occupe (429) — nouvelle tentative dans ${RETRY_AFTER}s (tentative $attempt/$INGEST_MAX_RETRIES)"
      sleep "$RETRY_AFTER" ;;
    *)
      die "Appel API /api/ingest echoue (HTTP $HTTP_CODE) : $(cat "$_BODY")" ;;
  esac
done

INGEST_TASK=$(echo "$INGEST_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['taskId'])" 2>/dev/null) \
  || die "Impossible d'extraire le taskId de : $INGEST_RESP"
echo "  TaskId : $INGEST_TASK"

# Polling ingestion
POLL=0
while true; do
  [ "$POLL" -lt "$MAX_POLL_INGEST" ] || die "[TIMEOUT] Ingestion trop longue (>${MAX_POLL_INGEST} polls)."
  sleep "$POLL_INTERVAL"
  POLL=$((POLL + 1))
  INGEST_JSON=$(curl -sf --max-time 5 ${AUTH[@]+"${AUTH[@]}"} "$API_URL/api/ingest/$INGEST_TASK" 2>/dev/null || echo '{"status":"ERROR","chunksCreated":0}')
  INGEST_STATUS=$(echo "$INGEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
  INGEST_CHUNKS=$(echo "$INGEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('chunksCreated',0))" 2>/dev/null)
  case "$INGEST_STATUS" in
    COMPLETED) break ;;
    FAILED)    die "Ingestion echouee — verifiez : docker compose logs spectra-api" ;;
    CANCELLED) die "Ingestion annulee." ;;
    *)         echo "  Ingestion en cours... chunks: $INGEST_CHUNKS" ;;
  esac
done

# Succes partiel : une tache COMPLETED peut porter des echecs par fichier (fileErrors).
# On continue (les fichiers OK sont vectorises) mais on ne masque pas les echecs.
FILE_ERRORS=$(echo "$INGEST_JSON" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('fileErrors') or []))" 2>/dev/null || echo 0)
if [ "$FILE_ERRORS" -gt 0 ]; then
  yellow "Ingestion partielle : $FILE_ERRORS fichier(s) en echec (les autres sont vectorises) :"
  echo "$INGEST_JSON" | python3 -c "import sys,json; [print('    - '+str(x)) for x in (json.load(sys.stdin).get('fileErrors') or [])]" 2>/dev/null || true
fi
[ "$INGEST_CHUNKS" -gt 0 ] || die "Ingestion terminee mais 0 chunk vectorise — aucun document exploitable."
green "Ingestion terminee — $INGEST_CHUNKS chunks vectorises"

# ══════════════════════════════════════════════════════════════════════════════
# ETAPE 2 — GENERATION DU DATASET
# ══════════════════════════════════════════════════════════════════════════════
echo
echo "> [2/5] Generation du dataset d'entrainement (modele: $BASE_MODEL)..."

# S'assurer que le modele de generation est bien le bon
curl -sf -X POST "$API_URL/api/config/model" \
  ${AUTH[@]+"${AUTH[@]}"} \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$BASE_MODEL\"}" -o /dev/null 2>/dev/null || true

# maxChunks=0 (defaut) = tout le corpus ; surchargeable via MAX_CHUNKS pour un essai rapide.
MAX_CHUNKS="${MAX_CHUNKS:-0}"
DATASET_RESP=$(curl -sf --max-time 30 ${AUTH[@]+"${AUTH[@]}"} -X POST "$API_URL/api/dataset/generate?maxChunks=$MAX_CHUNKS") \
  || die "Echec du lancement de la generation"
DATASET_TASK=$(echo "$DATASET_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['taskId'])" 2>/dev/null) \
  || die "Impossible d'extraire le taskId de : $DATASET_RESP"
echo "  TaskId : $DATASET_TASK"

# Polling dataset
POLL=0
while true; do
  [ "$POLL" -lt "$MAX_POLL_DATASET" ] || die "[TIMEOUT] Generation trop longue (>${MAX_POLL_DATASET} polls)."
  sleep "$POLL_INTERVAL"
  POLL=$((POLL + 1))
  DS_JSON=$(curl -sf --max-time 5 ${AUTH[@]+"${AUTH[@]}"} "$API_URL/api/dataset/generate/$DATASET_TASK" 2>/dev/null || echo '{"status":"ERROR","pairsGenerated":0}')
  DS_STATUS=$(echo "$DS_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
  DS_PAIRS=$(echo "$DS_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('pairsGenerated',0))" 2>/dev/null)
  case "$DS_STATUS" in
    COMPLETED) break ;;
    FAILED)    die "Generation echouee — verifiez : docker compose logs spectra-api" ;;
    *)         echo "  Generation en cours... paires: $DS_PAIRS" ;;
  esac
done

[ "$DS_PAIRS" -gt 0 ] || die "Generation terminee mais 0 paires produites.
  Verifiez que le modele de chat repond :
    curl -X POST $API_URL/api/query -H 'Content-Type: application/json' -d '{\"question\":\"test\"}'"
green "Dataset genere — $DS_PAIRS paires d'entrainement"

# ══════════════════════════════════════════════════════════════════════════════
# ETAPE 3 — EXPORT DU DATASET
# ══════════════════════════════════════════════════════════════════════════════
echo
echo "> [3/5] Export du dataset..."

mkdir -p "data/fine-tuning"
curl -sf --max-time 30 ${AUTH[@]+"${AUTH[@]}"} -X POST "$API_URL/api/dataset/export" -o "$DATASET_FILE" \
  || die "Export du dataset echoue"
[ -s "$DATASET_FILE" ] || die "Fichier dataset vide apres export : $DATASET_FILE"

DS_LINES=$(wc -l < "$DATASET_FILE")
green "Dataset exporte : $DATASET_FILE ($DS_LINES lignes)"

# ══════════════════════════════════════════════════════════════════════════════
# ETAPE 4 — FINE-TUNING SUR L'HOTE
# ══════════════════════════════════════════════════════════════════════════════
echo
echo "> [4/5] Fine-tuning du modele $BASE_MODEL sur l'hote..."
echo "  Cette etape peut durer plusieurs minutes (CPU) ou quelques minutes (GPU)."

# Verification preference (DPO/ORPO) + export du BON fichier (format {prompt, chosen, rejected})
TRAIN_DATASET="$DATASET_FILE"
if [ -n "$DPO_FLAG" ] || [ -n "$ORPO_FLAG" ]; then
  PREF_LABEL="DPO"; [ -n "$ORPO_FLAG" ] && PREF_LABEL="ORPO"
  DPO_JSON=$(curl -sf --max-time 5 ${AUTH[@]+"${AUTH[@]}"} "$API_URL/api/dataset/dpo/stats" 2>/dev/null || echo '{"totalPairs":0}')
  DPO_PAIRS=$(echo "$DPO_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('totalPairs',0))" 2>/dev/null || echo 0)
  [ "$DPO_PAIRS" -gt 0 ] || die "--${PREF_LABEL,,} demande mais aucune paire de preference trouvee.
  Lancez d'abord la generation :
    curl -X POST $API_URL/api/dataset/dpo/generate"
  green "$DPO_PAIRS paires de preference disponibles"

  # DPO et ORPO consomment le format {prompt, chosen, rejected}, PAS l'export SFT.
  curl -sf --max-time 30 ${AUTH[@]+"${AUTH[@]}"} -X POST "$API_URL/api/dataset/dpo/export" -o "$DPO_DATASET_FILE" \
    || die "Export des paires de preference echoue"
  [ -s "$DPO_DATASET_FILE" ] || die "Fichier de preference vide apres export : $DPO_DATASET_FILE"
  TRAIN_DATASET="$DPO_DATASET_FILE"
  green "Dataset de preference exporte : $DPO_DATASET_FILE"
fi

# Adaptateur incremental ou reset
RESUME_FLAG=""
if [ "$RESET_ADAPTER" -eq 1 ]; then
  yellow "--reset : suppression de l'adaptateur existant"
  rm -rf "$ADAPTER_DIR"
  yellow "Entrainement initial"
elif [ -f "$ADAPTER_DIR/adapter_config.json" ]; then
  yellow "Adaptateur existant detecte : entrainement incremental"
  RESUME_FLAG="--resume-adapter $ADAPTER_DIR"
else
  yellow "Aucun adaptateur existant : entrainement initial"
fi

# shellcheck disable=SC2086
python3 scripts/train_host.py \
  --dataset "$TRAIN_DATASET" \
  --output  "$ADAPTER_DIR" \
  --base-model "$BASE_MODEL" \
  $RESUME_FLAG \
  --epochs "$EPOCHS" \
  --lora-rank "$LORA_RANK" \
  --lora-alpha "$LORA_ALPHA" \
  --lr "$LR" \
  --val-split "$VAL_SPLIT" \
  --lora-target "$LORA_TARGET" \
  --neftune-alpha "$NEFTUNE_ALPHA" \
  --warmup-ratio "$WARMUP_RATIO" \
  $PACKING_FLAG \
  $DPO_FLAG \
  $ORPO_FLAG \
  || die "Fine-tuning echoue"

green "Fine-tuning termine — adaptateur : $ADAPTER_DIR"

# ══════════════════════════════════════════════════════════════════════════════
# ETAPE 5 — EXPORT GGUF + ENREGISTREMENT
# ══════════════════════════════════════════════════════════════════════════════
echo
echo "> [5/5] Export GGUF et enregistrement ($MODEL_NAME)..."

python3 scripts/export_gguf.py \
  --adapter    "$ADAPTER_DIR" \
  --output     "$MERGED_DIR" \
  --base-model "$BASE_MODEL" \
  --model-name "$MODEL_NAME" \
  || die "Export GGUF echoue"
green "Modele $MODEL_NAME enregistre"

# Basculer le modele actif de l'API
curl -sf -X POST "$API_URL/api/config/model" \
  ${AUTH[@]+"${AUTH[@]}"} \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL_NAME\"}" -o /dev/null 2>/dev/null || true
green "Modele actif de l'API bascule vers $MODEL_NAME"

# ══════════════════════════════════════════════════════════════════════════════
# RESUME
# ══════════════════════════════════════════════════════════════════════════════
echo
echo "======================================"
echo "  [OK] Pipeline termine avec succes !"
echo
echo "  Modele de base enrichi : $BASE_MODEL"
echo "  Modele final           : $MODEL_NAME"
echo "  Modele actif de l'API  : $MODEL_NAME"
echo
echo "  Tester :"
echo "    curl -X POST $API_URL/api/query \\"
echo "      -H 'Content-Type: application/json' \\"
echo "      -d '{\"question\": \"Votre question...\"}'"
echo
echo "  Swagger : $API_URL/swagger-ui.html"
echo "======================================"
