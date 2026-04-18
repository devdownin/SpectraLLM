#!/usr/bin/env bash
# =============================================================================
# llama-autostart.sh — Entrypoint intelligent pour llama-server
#
# Détecte automatiquement les ressources disponibles (CPU, RAM, GPU)
# et calcule les paramètres optimaux pour llama-server.
#
# Variables d'environnement (toutes optionnelles — auto-détectées si absentes) :
#   MODEL_PATH          : chemin vers le fichier GGUF (requis)
#   MODEL_ALIAS         : alias du modèle (flag -a de llama-server)
#   LLAMA_MODE          : "chat" ou "embed" (affecte les défauts)
#   LLAMA_CONTEXT       : taille de la fenêtre de contexte (override)
#   LLAMA_THREADS       : nombre de threads CPU (override)
#   LLAMA_BATCH         : taille du batch (override)
#   LLAMA_NGL           : nombre de couches GPU (-1 = toutes) (override)
#   LLAMA_FLASH_ATTN    : "1" = activé (défaut), "0" = désactivé
#   LLAMA_CACHE_TYPE_K  : type KV cache clé (défaut : q8_0)
#   LLAMA_CACHE_TYPE_V  : type KV cache valeur (défaut : q8_0)
#   LLAMA_PARALLELISM   : slots parallèles -np (défaut : 1)
#   LLAMA_PORT          : port d'écoute (défaut : 8080)
#   LLAMA_EXTRA         : arguments supplémentaires passés tels quels
# =============================================================================

set -euo pipefail

log() { echo "[autostart] $*"; }

# ── Détection CPU ─────────────────────────────────────────────────────────────

CPU_CORES=$(nproc 2>/dev/null || echo 2)

# cgroups v2 : quota CPU container (ex. --cpus=4.0 → 400000/100000)
CPU_QUOTA=$(cat /sys/fs/cgroup/cpu.max 2>/dev/null || echo "max 100000")
CPU_QUOTA_US=$(echo "$CPU_QUOTA" | awk '{print $1}')
CPU_PERIOD_US=$(echo "$CPU_QUOTA" | awk '{print $2}')
if [ "$CPU_QUOTA_US" != "max" ] && [ -n "$CPU_QUOTA_US" ]; then
  CGROUP_CPUS=$(( CPU_QUOTA_US / CPU_PERIOD_US ))
  [ $CGROUP_CPUS -lt $CPU_CORES ] && CPU_CORES=$CGROUP_CPUS
fi

# ── Détection RAM ─────────────────────────────────────────────────────────────

RAM_MB=0
if [ -f /proc/meminfo ]; then
  RAM_KB=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
  RAM_MB=$((RAM_KB / 1024))
fi

# cgroups v2 : limite mémoire container
CGROUP_MEM=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || echo "max")
if [ "$CGROUP_MEM" != "max" ] && [ -n "$CGROUP_MEM" ]; then
  CGROUP_MEM_MB=$((CGROUP_MEM / 1024 / 1024))
  [ $CGROUP_MEM_MB -lt $RAM_MB ] && RAM_MB=$CGROUP_MEM_MB
fi

# ── Détection GPU ─────────────────────────────────────────────────────────────

GPU_TYPE="none"
GPU_VRAM_MB=0
NGL_DEFAULT=0

# NVIDIA CUDA
if command -v nvidia-smi &>/dev/null 2>&1; then
  GPU_NAME=$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -1 || true)
  if [ -n "$GPU_NAME" ]; then
    GPU_TYPE="nvidia"
    GPU_VRAM_MB=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>/dev/null \
      | head -1 | tr -d ' ' || echo 0)
    NGL_DEFAULT=-1   # offload toutes les couches
    log "GPU NVIDIA détecté : $GPU_NAME (VRAM=${GPU_VRAM_MB}MB)"
  fi
fi

# AMD ROCm
if [ "$GPU_TYPE" = "none" ] && [ -e /dev/kfd ] && [ -e /dev/dri ]; then
  GPU_TYPE="amd"
  NGL_DEFAULT=-1
  log "GPU AMD ROCm détecté"
fi

# GPU générique via Vulkan (rendu partiel, conservateur)
if [ "$GPU_TYPE" = "none" ] && [ -e /dev/dri/renderD128 ]; then
  GPU_TYPE="vulkan"
  NGL_DEFAULT=20
  log "GPU Vulkan détecté (/dev/dri/renderD128)"
fi

# ── Calcul des paramètres optimaux ────────────────────────────────────────────

MODE="${LLAMA_MODE:-chat}"

# Threads : laisser de la marge pour l'OS et les autres conteneurs
if [ "$MODE" = "embed" ]; then
  AUTO_THREADS=$((CPU_CORES <= 2 ? 1 : CPU_CORES / 2))
else
  # Chat : tous les cœurs moins 2 pour l'OS (min 1)
  AUTO_THREADS=$((CPU_CORES > 4 ? CPU_CORES - 2 : CPU_CORES))
  [ $AUTO_THREADS -lt 1 ] && AUTO_THREADS=1
fi
THREADS="${LLAMA_THREADS:-$AUTO_THREADS}"

# Contexte : basé sur la VRAM GPU ou la RAM disponible
if [ -n "${LLAMA_CONTEXT:-}" ]; then
  CONTEXT=$LLAMA_CONTEXT
elif [ "$GPU_TYPE" = "nvidia" ] && [ "$GPU_VRAM_MB" -ge 8192 ]; then
  CONTEXT=8192
elif [ "$GPU_TYPE" = "nvidia" ] && [ "$GPU_VRAM_MB" -ge 4096 ]; then
  CONTEXT=4096
elif [ $RAM_MB -ge 32768 ]; then
  CONTEXT=4096
elif [ $RAM_MB -ge 16384 ]; then
  CONTEXT=2048
elif [ $RAM_MB -ge 8192 ]; then
  CONTEXT=1024
else
  CONTEXT=512
fi

# Batch size : influencé par la mémoire disponible
if [ -n "${LLAMA_BATCH:-}" ]; then
  BATCH=$LLAMA_BATCH
elif [ "$GPU_TYPE" != "none" ] || [ $RAM_MB -ge 16384 ]; then
  BATCH=2048
elif [ $RAM_MB -ge 8192 ]; then
  BATCH=1024
else
  BATCH=512
fi

NGL="${LLAMA_NGL:-$NGL_DEFAULT}"
FLASH_ATTN="${LLAMA_FLASH_ATTN:-1}"
CACHE_TYPE_K="${LLAMA_CACHE_TYPE_K:-q8_0}"
CACHE_TYPE_V="${LLAMA_CACHE_TYPE_V:-q8_0}"
PARALLELISM="${LLAMA_PARALLELISM:-1}"

# ── Construction de la commande ───────────────────────────────────────────────

CMD=(llama-server
  -m  "${MODEL_PATH:?MODEL_PATH est requis}"
  -a  "${MODEL_ALIAS:-model}"
  --host "0.0.0.0"
  --port "${LLAMA_PORT:-8080}"
  -c  "$CONTEXT"
  -t  "$THREADS"
  -b  "$BATCH"
  -ub "$BATCH"
  -np "$PARALLELISM"
  --cache-type-k "$CACHE_TYPE_K"
  --cache-type-v "$CACHE_TYPE_V"
)

[ "$FLASH_ATTN" = "1" ] && CMD+=(--flash-attn on)
[ "$FLASH_ATTN" = "0" ] && CMD+=(--flash-attn off)
[ "$MODE" = "embed" ]   && CMD+=(--embeddings)
[ "$NGL" != "0" ]       && CMD+=(--n-gpu-layers "$NGL")

# Arguments supplémentaires (passés tels quels)
if [ -n "${LLAMA_EXTRA:-}" ]; then
  # shellcheck disable=SC2086
  CMD+=($LLAMA_EXTRA)
fi

# ── Résumé ────────────────────────────────────────────────────────────────────

log "══════════════════════════════════════════════"
log "  Ressources détectées"
log "  CPU cœurs   : $CPU_CORES  → threads=$THREADS"
log "  RAM dispo   : ${RAM_MB}MB"
log "  GPU         : $GPU_TYPE (VRAM=${GPU_VRAM_MB}MB) → ngl=$NGL"
log "══════════════════════════════════════════════"
log "  Paramètres llama-server"
log "  mode        : $MODE"
log "  context     : $CONTEXT tokens"
log "  batch       : $BATCH"
log "  parallelism : $PARALLELISM"
log "  flash-attn  : $FLASH_ATTN"
log "  KV cache    : K=$CACHE_TYPE_K  V=$CACHE_TYPE_V"
log "  modèle      : ${MODEL_PATH}"
log "══════════════════════════════════════════════"
log "Commande : ${CMD[*]}"

# Vérification finale : le fichier GGUF doit exister avant de lancer le serveur
if [ ! -f "${MODEL_PATH}" ]; then
  log "ERREUR: Fichier modèle introuvable : ${MODEL_PATH}"
  log "  → Mode chat  : placez votre GGUF dans data/fine-tuning/merged/model.gguf"
  log "  → Mode embed : placez votre GGUF dans data/models/embed.gguf"
  log "  → Ou surchargez MODEL_PATH dans docker-compose.yml"
  exit 1
fi

exec "${CMD[@]}"
