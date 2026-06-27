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
cd "$(dirname "$0")"

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

SOURCE_DIR="${1:-data/documents}"
BASE_MODEL="${2:-phi3}"
MODEL_NAME="${3:-${BASE_MODEL}-autoroute}"

RESET_ADAPTER=0
PACKING_FLAG=""
DPO_FLAG=""
ORPO_FLAG=""

for arg in "$@"; do
  case "$arg" in
    --reset)   RESET_ADAPTER=1      ;;
    --packing) PACKING_FLAG="--packing" ;;
    --dpo)     DPO_FLAG="--dpo"     ;;
    --orpo)    ORPO_FLAG="--orpo"   ;;
  esac
done

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

# Fichiers GGUF
GGUF_CHAT="data/fine-tuning/merged/model.gguf"
GGUF_EMBED="data/models/embed.gguf"

if [ ! -f "$GGUF_CHAT" ]; then
  die "Modele de chat introuvable : $GGUF_CHAT
  Placez un GGUF instruction-tuned dans ce chemin, ou lancez :
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
for i in $(seq 1 20); do
  if curl -sf "$API_URL/actuator/health" -o /dev/null 2>/dev/null; then
    API_READY=1
    break
  fi
  sleep 3
done
[ "$API_READY" -eq 1 ] || die "API inaccessible apres 60s. Lancez docker compose up -d d'abord."
green "API prete"

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

INGEST_RESP=$(curl -sf --max-time 120 \
  "${CURL_ARGS[@]}" \
  "$API_URL/api/ingest") || die "Appel API /api/ingest echoue"

INGEST_TASK=$(echo "$INGEST_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['taskId'])" 2>/dev/null) \
  || die "Impossible d'extraire le taskId de : $INGEST_RESP"
echo "  TaskId : $INGEST_TASK"

# Polling ingestion
POLL=0
while true; do
  [ "$POLL" -lt "$MAX_POLL_INGEST" ] || die "[TIMEOUT] Ingestion trop longue (>${MAX_POLL_INGEST} polls)."
  sleep "$POLL_INTERVAL"
  POLL=$((POLL + 1))
  INGEST_JSON=$(curl -sf --max-time 5 "$API_URL/api/ingest/$INGEST_TASK" 2>/dev/null || echo '{"status":"ERROR","chunksCreated":0}')
  INGEST_STATUS=$(echo "$INGEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
  INGEST_CHUNKS=$(echo "$INGEST_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('chunksCreated',0))" 2>/dev/null)
  case "$INGEST_STATUS" in
    COMPLETED) break ;;
    FAILED)    die "Ingestion echouee — verifiez : docker compose logs spectra-api" ;;
    *)         echo "  Ingestion en cours... chunks: $INGEST_CHUNKS" ;;
  esac
done
green "Ingestion terminee — $INGEST_CHUNKS chunks vectorises"

# ══════════════════════════════════════════════════════════════════════════════
# ETAPE 2 — GENERATION DU DATASET
# ══════════════════════════════════════════════════════════════════════════════
echo
echo "> [2/5] Generation du dataset d'entrainement (modele: $BASE_MODEL)..."

# S'assurer que le modele de generation est bien le bon
curl -sf -X POST "$API_URL/api/config/model" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$BASE_MODEL\"}" -o /dev/null 2>/dev/null || true

DATASET_RESP=$(curl -sf --max-time 30 -X POST "$API_URL/api/dataset/generate") \
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
  DS_JSON=$(curl -sf --max-time 5 "$API_URL/api/dataset/generate/$DATASET_TASK" 2>/dev/null || echo '{"status":"ERROR","pairsGenerated":0}')
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
curl -sf --max-time 30 -X POST "$API_URL/api/dataset/export" -o "$DATASET_FILE" \
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
  DPO_JSON=$(curl -sf --max-time 5 "$API_URL/api/dataset/dpo/stats" 2>/dev/null || echo '{"totalPairs":0}')
  DPO_PAIRS=$(echo "$DPO_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('totalPairs',0))" 2>/dev/null || echo 0)
  [ "$DPO_PAIRS" -gt 0 ] || die "--${PREF_LABEL,,} demande mais aucune paire de preference trouvee.
  Lancez d'abord la generation :
    curl -X POST $API_URL/api/dataset/dpo/generate"
  green "$DPO_PAIRS paires de preference disponibles"

  # DPO et ORPO consomment le format {prompt, chosen, rejected}, PAS l'export SFT.
  curl -sf --max-time 30 -X POST "$API_URL/api/dataset/dpo/export" -o "$DPO_DATASET_FILE" \
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
