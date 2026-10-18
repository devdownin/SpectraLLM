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

# Pooling : les modèles d'embedding issus d'un LLM décodeur (Qwen3-Embedding) s'évaluent sur le
# DERNIER token. Servi avec le pooling par défaut, le modèle répond, mais ses vecteurs ne valent
# rien — panne silencieuse. LLM_EMBED_POOLING prime ; sinon déduit du nom logique du modèle.
POOLING="${LLM_EMBED_POOLING:-}"
if [ -z "${POOLING}" ]; then
    case "$(printf '%s %s' "${LLM_EMBED_MODEL_NAME:-}" "${MODEL_FILE}" | tr '[:upper:]' '[:lower:]')" in
        *qwen3-embedding*) POOLING="last" ;;
    esac
fi
POOLING_ARGS=""
if [ -n "${POOLING}" ]; then
    POOLING_ARGS="--pooling ${POOLING}"
    log "pooling : ${POOLING}"
fi

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
  ${POOLING_ARGS} \
  ${LLM_EMBED_EXTRA_ARGS:-}
