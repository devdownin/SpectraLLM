#!/bin/sh
# =============================================================================
# llm-chat-entrypoint.sh — Superviseur llama-server piloté par le registre
#
# Résout le problème « modèle actif ≠ modèle servi » du mode conteneurs séparés :
# spectra-api matérialise le modèle de chat ACTIF du registre dans le fichier
# pointeur /models/active-chat-model (écrit par ModelRegistryService à chaque
# changement). Ce superviseur le lit, sert le GGUF correspondant, puis surveille
# le pointeur : quand le modèle actif change (POST /api/config/model, activation
# post-fine-tuning, installation llmfit avec autoActivate), llama-server est
# redémarré avec le nouveau modèle — sans redémarrage manuel du conteneur.
#
# Contrat du pointeur (vue dérivée de registry.json, jamais éditée à la main) :
#   ligne 1 : alias du modèle actif (flag -a de llama-server)
#   ligne 2 : nom du fichier GGUF dans /models (absente si source non servable)
#
# Paramètres de dimensionnement : spectra-api (ResourceAdvisorService) matérialise
# ses recommandations (threads, contexte, batch, KV cache) dans
# /models/active-chat-params — elles servent de VALEURS PAR DÉFAUT ici. Priorité :
#   1. variable LLM_* explicite (env / .env)   — l'utilisateur a toujours raison
#   2. hints de l'API (active-chat-params)     — calculés une seule fois, côté Java
#   3. défauts intégrés ci-dessous             — premier boot, avant l'API
# La détection GPU reste locale (LLM_CHAT_EXTRA_ARGS=--n-gpu-layers …) : l'API ne
# voit pas les GPU attribués à ce conteneur.
#
# Variables d'environnement :
#   LLM_CHAT_MODEL_FILE     : GGUF de repli si le pointeur est absent/invalide
#   LLM_CHAT_MODEL_NAME     : alias de repli
#   LLM_PORT                : port d'écoute (défaut 8081)
#   LLM_CONTEXT             : taille de contexte (sinon hints API, sinon 4096)
#   LLM_THREADS             : threads CPU (sinon hints API, sinon auto llama-server)
#   LLM_BATCH               : taille de batch (sinon hints API, sinon auto)
#   LLM_PARALLEL            : slots parallèles (défaut 2)
#   LLM_CHAT_EXTRA_ARGS     : arguments supplémentaires (ex. --n-gpu-layers 99)
#   LLM_CHAT_WATCH_INTERVAL : période de surveillance du pointeur en s (défaut 10)
#   LLAMA_SERVER_BIN        : chemin du binaire (auto-détecté sinon)
# =============================================================================

set -u

MODELS_DIR="${MODELS_DIR:-/models}"
POINTER="${MODELS_DIR}/active-chat-model"
PARAMS_FILE="${MODELS_DIR}/active-chat-params"
POLL_SECONDS="${LLM_CHAT_WATCH_INTERVAL:-10}"
DEFAULT_FILE="${LLM_CHAT_MODEL_FILE:-Phi-4-mini-reasoning-UD-IQ1_S.gguf}"
DEFAULT_ALIAS="${LLM_CHAT_MODEL_NAME:-phi-4-mini}"

log() { echo "[llm-chat] $*"; }

# ── Hints de dimensionnement écrits par spectra-api (défauts surchargeables) ──
# Lecture ligne à ligne sans `source` : seules les clés RECO_* attendues sont
# prises en compte (un fichier corrompu ne peut pas injecter de commande).
RECO_THREADS="" RECO_CONTEXT="" RECO_BATCH="" RECO_CACHE_TYPE_K="" RECO_CACHE_TYPE_V=""
load_params() {
  RECO_THREADS="" RECO_CONTEXT="" RECO_BATCH="" RECO_CACHE_TYPE_K="" RECO_CACHE_TYPE_V=""
  [ -f "${PARAMS_FILE}" ] || return 0
  while IFS='=' read -r key value; do
    value=$(echo "${value}" | tr -d '\r')
    case "${key}" in
      RECO_THREADS)      RECO_THREADS="${value}" ;;
      RECO_CONTEXT)      RECO_CONTEXT="${value}" ;;
      RECO_BATCH)        RECO_BATCH="${value}" ;;
      RECO_CACHE_TYPE_K) RECO_CACHE_TYPE_K="${value}" ;;
      RECO_CACHE_TYPE_V) RECO_CACHE_TYPE_V="${value}" ;;
    esac
  done < "${PARAMS_FILE}"
}

# ── Localisation du binaire llama-server (varie selon l'image) ────────────────
SERVER_BIN="${LLAMA_SERVER_BIN:-}"
if [ -z "${SERVER_BIN}" ]; then
  for candidate in /app/llama-server /llama-server /usr/local/bin/llama-server llama-server; do
    if command -v "${candidate}" >/dev/null 2>&1; then
      SERVER_BIN="${candidate}"
      break
    fi
  done
fi
if [ -z "${SERVER_BIN}" ]; then
  log "ERREUR: binaire llama-server introuvable (définissez LLAMA_SERVER_BIN)"
  exit 1
fi

# ── Résolution (alias, fichier) : pointeur du registre, sinon défauts env ─────
resolve() {
  RESOLVED_ALIAS="${DEFAULT_ALIAS}"
  RESOLVED_FILE="${DEFAULT_FILE}"
  if [ -f "${POINTER}" ]; then
    P_ALIAS=$(sed -n '1p' "${POINTER}" 2>/dev/null | tr -d '\r')
    P_FILE=$(sed -n '2p' "${POINTER}" 2>/dev/null | tr -d '\r')
    if [ -n "${P_FILE}" ] && [ -f "${MODELS_DIR}/${P_FILE}" ]; then
      RESOLVED_ALIAS="${P_ALIAS:-${DEFAULT_ALIAS}}"
      RESOLVED_FILE="${P_FILE}"
    elif [ -n "${P_FILE}" ]; then
      log "AVERTISSEMENT: le registre pointe '${P_FILE}' mais ${MODELS_DIR}/${P_FILE} est absent — repli sur ${DEFAULT_FILE}"
    fi
    # Pointeur sans ligne 2 : modèle actif sans source GGUF → fichier de repli,
    # mais on conserve l'alias du registre pour que /v1/models reste cohérent.
    if [ -z "${P_FILE}" ] && [ -n "${P_ALIAS}" ]; then
      RESOLVED_ALIAS="${P_ALIAS}"
    fi
  fi
}

start_server() {
  MODEL_PATH="${MODELS_DIR}/${RESOLVED_FILE}"
  if [ ! -f "${MODEL_PATH}" ]; then
    log "EN ATTENTE: modèle introuvable : ${MODEL_PATH} (téléchargement en cours par l'API...)"
    return 1
  fi

  # Recharge les hints à chaque (re)démarrage : un refresh des ressources côté API
  # sera pris en compte au prochain changement de modèle.
  load_params
  CONTEXT="${LLM_CONTEXT:-${RECO_CONTEXT:-4096}}"
  THREADS="${LLM_THREADS:-${RECO_THREADS:-}}"
  BATCH="${LLM_BATCH:-${RECO_BATCH:-}}"

  log "démarrage llama-server : modèle=${RESOLVED_FILE} alias=${RESOLVED_ALIAS} ctx=${CONTEXT}${THREADS:+ threads=${THREADS}}${BATCH:+ batch=${BATCH}}"
  set -- \
    --host 0.0.0.0 \
    --port "${LLM_PORT:-8081}" \
    --model "${MODEL_PATH}" \
    -a "${RESOLVED_ALIAS}" \
    --ctx-size "${CONTEXT}" \
    --parallel "${LLM_PARALLEL:-2}"
  [ -n "${THREADS}" ] && set -- "$@" -t "${THREADS}"
  [ -n "${BATCH}" ] && set -- "$@" -b "${BATCH}" -ub "${BATCH}"
  [ -n "${RECO_CACHE_TYPE_K}" ] && set -- "$@" --cache-type-k "${RECO_CACHE_TYPE_K}"
  [ -n "${RECO_CACHE_TYPE_V}" ] && set -- "$@" --cache-type-v "${RECO_CACHE_TYPE_V}"
  # shellcheck disable=SC2086 — LLM_CHAT_EXTRA_ARGS est volontairement éclaté en mots
  "${SERVER_BIN}" "$@" ${LLM_CHAT_EXTRA_ARGS:-} &
  CHILD=$!
}

stop_server() {
  if [ -n "${CHILD:-}" ] && kill -0 "${CHILD}" 2>/dev/null; then
    kill "${CHILD}" 2>/dev/null
    wait "${CHILD}" 2>/dev/null
  fi
  CHILD=""
}

on_term() {
  log "arrêt demandé"
  stop_server
  exit 0
}
trap on_term TERM INT

# ── Boucle de supervision ─────────────────────────────────────────────────────
resolve
CURRENT_ALIAS="${RESOLVED_ALIAS}"
CURRENT_FILE="${RESOLVED_FILE}"
start_server || true

while :; do
  # sleep en tâche de fond + wait : les signaux TERM/INT restent traités sans délai
  sleep "${POLL_SECONDS}" &
  wait $! 2>/dev/null || true

  if [ -z "${CHILD}" ] || ! kill -0 "${CHILD}" 2>/dev/null; then
    log "llama-server est arrêté ou en attente de modèle — nouvelle tentative dans 5s"
    sleep 5
    resolve
    CURRENT_ALIAS="${RESOLVED_ALIAS}"
    CURRENT_FILE="${RESOLVED_FILE}"
    start_server || true
    continue
  fi

  resolve
  if [ "${RESOLVED_FILE}" != "${CURRENT_FILE}" ] || [ "${RESOLVED_ALIAS}" != "${CURRENT_ALIAS}" ]; then
    if [ ! -f "${MODELS_DIR}/${RESOLVED_FILE}" ]; then
      # Ne jamais sacrifier un serveur sain pour une cible introuvable : on garde
      # le modèle courant et on retentera au prochain tour de surveillance.
      log "AVERTISSEMENT: cible '${RESOLVED_FILE}' introuvable — modèle courant conservé (${CURRENT_FILE})"
      continue
    fi
    log "modèle actif changé : ${CURRENT_ALIAS} (${CURRENT_FILE}) → ${RESOLVED_ALIAS} (${RESOLVED_FILE})"
    stop_server
    CURRENT_ALIAS="${RESOLVED_ALIAS}"
    CURRENT_FILE="${RESOLVED_FILE}"
    start_server || true
  fi
done
