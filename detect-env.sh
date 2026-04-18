#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Détection automatique de la configuration serveur
# Génère un fichier .env adapté aux ressources disponibles.
# Usage: ./detect-env.sh [--gpu]
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
FORCE_GPU=false

for arg in "$@"; do
    case "$arg" in
        --gpu) FORCE_GPU=true ;;
    esac
done

# ── 1. Détection RAM (en Mo) ──
if [[ "$(uname)" == "Darwin" ]]; then
    TOTAL_RAM_MB=$(( $(sysctl -n hw.memsize) / 1024 / 1024 ))
else
    TOTAL_RAM_MB=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)
fi

# ── 2. Détection CPU ──
if [[ "$(uname)" == "Darwin" ]]; then
    CPU_CORES=$(sysctl -n hw.ncpu)
else
    CPU_CORES=$(nproc)
fi

# ── 3. Détection GPU NVIDIA ──
GPU_DETECTED=false
if command -v nvidia-smi &>/dev/null; then
    if nvidia-smi &>/dev/null; then
        GPU_DETECTED=true
    fi
fi

if [[ "$FORCE_GPU" == true ]]; then
    GPU_DETECTED=true
fi

# ── 4. Calcul du profil ──
if (( TOTAL_RAM_MB < 8192 )); then
    PROFILE="small"
elif (( TOTAL_RAM_MB < 24576 )); then
    PROFILE="medium"
else
    PROFILE="large"
fi

echo "► Détection du serveur :"
echo "  RAM        : ${TOTAL_RAM_MB} Mo"
echo "  CPU cores  : ${CPU_CORES}"
echo "  GPU NVIDIA : ${GPU_DETECTED}"
echo "  Profil     : ${PROFILE}"

# ── 5. Calcul des paramètres pipeline ──
case "$PROFILE" in
    small)
        CHUNK_MAX_TOKENS=256
        CHUNK_OVERLAP_TOKENS=32
        EMBEDDING_BATCH_SIZE=5
        EMBEDDING_TIMEOUT=60
        GENERATION_TIMEOUT=180
        CONCURRENT_INGESTIONS=2
        JVM_HEAP=$((TOTAL_RAM_MB / 4))
        ;;
    medium)
        CHUNK_MAX_TOKENS=512
        CHUNK_OVERLAP_TOKENS=64
        EMBEDDING_BATCH_SIZE=10
        EMBEDDING_TIMEOUT=30
        GENERATION_TIMEOUT=120
        CONCURRENT_INGESTIONS=4
        JVM_HEAP=$((TOTAL_RAM_MB / 4))
        ;;
    large)
        CHUNK_MAX_TOKENS=512
        CHUNK_OVERLAP_TOKENS=64
        EMBEDDING_BATCH_SIZE=20
        EMBEDDING_TIMEOUT=30
        GENERATION_TIMEOUT=120
        CONCURRENT_INGESTIONS=8
        JVM_HEAP=$((TOTAL_RAM_MB / 3))
        ;;
esac

# Plafonner le heap JVM à 4 Go
if (( JVM_HEAP > 4096 )); then
    JVM_HEAP=4096
fi

# Serveur LLM : nombre de requêtes parallèles (min 1, max 8)
LLM_PARALLEL=$(( CPU_CORES / 2 ))
if (( LLM_PARALLEL < 1 )); then LLM_PARALLEL=1; fi
if (( LLM_PARALLEL > 8 )); then LLM_PARALLEL=8; fi

# ── 6. Écriture du .env ──
cat > "$ENV_FILE" <<EOF
# ── Spectra — Configuration auto-détectée ──
# Profil: ${PROFILE} | RAM: ${TOTAL_RAM_MB} Mo | CPU: ${CPU_CORES} cores | GPU: ${GPU_DETECTED}
# Généré le $(date -Iseconds 2>/dev/null || date)

# ── Pipeline ──
SPECTRA_CHUNK_MAX_TOKENS=${CHUNK_MAX_TOKENS}
SPECTRA_CHUNK_OVERLAP_TOKENS=${CHUNK_OVERLAP_TOKENS}
SPECTRA_EMBEDDING_BATCH_SIZE=${EMBEDDING_BATCH_SIZE}
SPECTRA_EMBEDDING_TIMEOUT=${EMBEDDING_TIMEOUT}
SPECTRA_GENERATION_TIMEOUT=${GENERATION_TIMEOUT}
SPECTRA_CONCURRENT_INGESTIONS=${CONCURRENT_INGESTIONS}

# ── JVM ──
JAVA_OPTS=-Xms256m -Xmx${JVM_HEAP}m -XX:+UseZGC

# ── Serveur LLM — Chat ──
LLM_CHAT_MODEL_FILE=Phi-4-mini-reasoning-UD-IQ1_S.gguf
LLM_CHAT_MODEL_NAME=phi-4-mini
LLM_PARALLEL=${LLM_PARALLEL}

# ── Serveur LLM — Embedding ──
LLM_EMBED_MODEL_FILE=embed.gguf
LLM_EMBED_MODEL_NAME=nomic-embed-text
LLM_EMBED_PARALLEL=${LLM_PARALLEL}

# ── Provider LLM ──
SPECTRA_LLM_PROVIDER=llama-cpp

# ── GPU ──
SPECTRA_GPU_ENABLED=${GPU_DETECTED}
EOF

echo ""
echo "  ✓ Fichier .env généré : ${ENV_FILE}"
