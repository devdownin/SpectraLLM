#!/usr/bin/env bash
# =============================================================================
# llama-autostart.sh — Entrypoint AUTONOME pour llama-server (images
# Dockerfile.llama / Dockerfile.llama.cuda, déploiements k8s/GKE)
#
# Détecte automatiquement les ressources disponibles (CPU, RAM, GPU)
# et calcule les paramètres optimaux pour llama-server.
#
# NB : en mode deploy/docker/docker-compose, le dimensionnement CPU/RAM appartient à
# spectra-api (ResourceAdvisorService), qui matérialise ses recommandations
# dans data/models/active-chat-params, consommées par llm-chat-entrypoint.sh.
# Ce script reste l'implémentation de repli pour les environnements où l'API
# n'est pas joignable (images llama.cpp autonomes) — la détection GPU, elle,
# doit dans tous les cas rester locale au conteneur qui sert le modèle.
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

# ── Détection CPU (cgroup v2, puis v1, sinon nœud) ────────────────────────────

CPU_CORES=$(nproc 2>/dev/null || echo 2)

# Plafonne au quota CPU du conteneur (Kubernetes limits.cpu / docker --cpus).
CGROUP_CPUS=""
if [ -r /sys/fs/cgroup/cpu.max ]; then
  # cgroup v2 : "<quota> <période>" (quota = "max" si illimité)
  read -r Q P < /sys/fs/cgroup/cpu.max 2>/dev/null || true
  if [ "${Q:-max}" != "max" ] && [ "${P:-0}" -gt 0 ] 2>/dev/null; then
    CGROUP_CPUS=$(( Q / P ))
  fi
elif [ -r /sys/fs/cgroup/cpu/cpu.cfs_quota_us ]; then
  # cgroup v1 : quota = -1 si illimité ; période par défaut 100000 µs
  Q=$(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null || echo -1)
  P=$(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us 2>/dev/null || echo 100000)
  if [ "${Q:--1}" -gt 0 ] 2>/dev/null && [ "${P:-0}" -gt 0 ] 2>/dev/null; then
    CGROUP_CPUS=$(( Q / P ))
  fi
fi
if [ -n "$CGROUP_CPUS" ]; then
  [ "$CGROUP_CPUS" -lt 1 ] 2>/dev/null && CGROUP_CPUS=1
  [ "$CGROUP_CPUS" -lt "$CPU_CORES" ] && CPU_CORES=$CGROUP_CPUS
fi

# ── Détection RAM (cgroup v2, puis v1, sinon nœud) ────────────────────────────

RAM_MB=0
if [ -f /proc/meminfo ]; then
  RAM_KB=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
  RAM_MB=$((RAM_KB / 1024))
fi

# Plafonne à la limite mémoire du conteneur (Kubernetes limits.memory).
CGROUP_MEM_BYTES=""
if [ -r /sys/fs/cgroup/memory.max ]; then
  M=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || echo max)                  # v2
  [ "$M" != "max" ] && CGROUP_MEM_BYTES=$M
elif [ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
  M=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || echo 0)  # v1
  # v1 "illimité" = valeur proche de LLONG_MAX → on l'ignore
  if [ "${M:-0}" -gt 0 ] 2>/dev/null && [ "$M" -lt 9223372036854000000 ] 2>/dev/null; then
    CGROUP_MEM_BYTES=$M
  fi
fi
if [ -n "$CGROUP_MEM_BYTES" ]; then
  CGROUP_MEM_MB=$(( CGROUP_MEM_BYTES / 1024 / 1024 ))
  [ "$CGROUP_MEM_MB" -gt 0 ] && [ "$CGROUP_MEM_MB" -lt "$RAM_MB" ] && RAM_MB=$CGROUP_MEM_MB
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

# Adaptateur LoRA chargé À CHAUD par-dessus le modèle de base (sans fusion).
# LLAMA_LORA = chemin d'un GGUF d'adaptateur (cf. scripts/export_lora_gguf.py).
# LLAMA_LORA_SCALE = poids d'application (défaut 1.0 ; 0 = neutre). Permet de servir
# le modèle fine-tuné sans dupliquer ni re-quantifier le modèle de base.
if [ -n "${LLAMA_LORA:-}" ] && [ "$MODE" != "embed" ]; then
  CMD+=(--lora-scaled "$LLAMA_LORA" "${LLAMA_LORA_SCALE:-1.0}")
fi

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
[ -n "${LLAMA_LORA:-}" ] && log "  LoRA        : ${LLAMA_LORA} (scale=${LLAMA_LORA_SCALE:-1.0})"
log "══════════════════════════════════════════════"
log "Commande : ${CMD[*]}"

# Vérification finale : le fichier GGUF doit exister avant de lancer le serveur
if [ ! -f "${MODEL_PATH}" ]; then
  log "ERREUR: Fichier modèle introuvable : ${MODEL_PATH}"
  log "  → Mode chat  : placez votre GGUF dans data/fine-tuning/merged/model.gguf"
  log "  → Mode embed : placez votre GGUF dans data/models/embed.gguf"
  log "  → Ou surchargez MODEL_PATH dans deploy/docker/docker-compose.yml"
  exit 1
fi

exec "${CMD[@]}"
