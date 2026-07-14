#!/bin/sh
set -u

MODELS_DIR="${MODELS_DIR:-/models}"
MODEL_FILE="${LLM_EMBED_MODEL_FILE:-embed.gguf}"
MODEL_PATH="${MODELS_DIR}/${MODEL_FILE}"

log() { echo "[llm-embed] $*"; }

while [ ! -f "${MODEL_PATH}" ]; do
    log "EN ATTENTE: modèle introuvable : ${MODEL_PATH} (téléchargement en cours par l'API...)"
    sleep 5
done

log "modèle trouvé : ${MODEL_PATH}, démarrage de llama-server..."

# Launch the original command
exec /app/llama-server \
  --host 0.0.0.0 \
  --port 8082 \
  --model "${MODEL_PATH}" \
  --embedding \
  -c 8192 \
  -b 2048 \
  -ub 2048 \
  --parallel "${LLM_EMBED_PARALLEL:-4}" \
  ${LLM_EMBED_EXTRA_ARGS:-}
