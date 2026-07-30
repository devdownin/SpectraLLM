#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Détection automatique de la configuration serveur
# Génère un fichier .env adapté aux ressources disponibles.
# Usage: ./detect-env.sh [--gpu]
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Les scripts vivent dans scripts/ ; le .env (et docker-compose via
# --project-directory .) est ancré à la racine du dépôt, à côté de .env.example.
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
GPU_TYPE="none"   # none | nvidia | amd | vulkan
FORCE=0

for arg in "$@"; do
    case "$arg" in
        --gpu)   GPU_TYPE="nvidia" ;;  # force NVIDIA pour tests sans carte physique
        --force) FORCE=1 ;;            # régénère .env même s'il existe déjà
    esac
done

# Ne jamais écraser un .env existant sans --force : sinon chaque ./start.sh
# détruirait la configuration éditée par l'utilisateur (modèle de chat, etc.).
if [[ -f "$ENV_FILE" && "$FORCE" -eq 0 ]]; then
    echo "  ✓ .env existant conservé (utilisez ./detect-env.sh --force pour régénérer)"
    exit 0
fi

# ── 1. Détection RAM disponible (en Mo) ──
# MemAvailable = RAM libre + reclaimable ; plus représentative de la marge réelle.
if [[ "$(uname)" == "Darwin" ]]; then
    TOTAL_RAM_MB=$(( $(sysctl -n hw.memsize) / 1024 / 1024 ))
else
    TOTAL_RAM_MB=$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo)
fi

# ── 2. Détection CPU ──
if [[ "$(uname)" == "Darwin" ]]; then
    CPU_CORES=$(sysctl -n hw.ncpu)
else
    CPU_CORES=$(nproc)
fi

# ── 3. Détection GPU (même ordre de priorité que llama-autostart.sh) ──
if [[ "$GPU_TYPE" == "none" ]]; then
    # NVIDIA : nvidia-smi doit répondre ET lister au moins un GPU
    if command -v nvidia-smi &>/dev/null && nvidia-smi --query-gpu=name --format=csv,noheader &>/dev/null 2>&1; then
        GPU_NAME=$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -1 || true)
        if [[ -n "$GPU_NAME" ]]; then
            GPU_TYPE="nvidia"
            GPU_VRAM_MB=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>/dev/null | head -1 | tr -d ' ' || echo 0)
        fi
    fi
fi

if [[ "$GPU_TYPE" == "none" ]]; then
    # AMD ROCm : /dev/kfd (driver KFD) + /dev/dri présent
    if [[ -e /dev/kfd && -d /dev/dri ]]; then
        GPU_TYPE="amd"
    fi
fi

if [[ "$GPU_TYPE" == "none" ]]; then
    # GPU générique via Vulkan (rendu partiel)
    if [[ -e /dev/dri/renderD128 ]]; then
        GPU_TYPE="vulkan"
    fi
fi

GPU_VRAM_MB="${GPU_VRAM_MB:-0}"
GPU_DETECTED=$([[ "$GPU_TYPE" != "none" ]] && echo true || echo false)

# ── 4. Calcul du profil ──
if (( TOTAL_RAM_MB < 8192 )); then
    PROFILE="small"
elif (( TOTAL_RAM_MB < 24576 )); then
    PROFILE="medium"
else
    PROFILE="large"
fi

echo "► Détection du serveur :"
echo "  RAM dispo  : ${TOTAL_RAM_MB} Mo"
echo "  CPU cores  : ${CPU_CORES}"
echo "  GPU        : ${GPU_TYPE} (VRAM: ${GPU_VRAM_MB} Mo)"
echo "  Profil     : ${PROFILE}"

# ── 5. Calcul des paramètres pipeline ──
case "$PROFILE" in
    small)
        CHUNK_MAX_TOKENS=256
        CHUNK_OVERLAP_TOKENS=32
        EMBEDDING_BATCH_SIZE=5
        EMBEDDING_TIMEOUT=60
        GENERATION_TIMEOUT=180
        CONCURRENT_INGESTIONS=1
        JVM_HEAP=$((TOTAL_RAM_MB / 4))
        ;;
    medium)
        CHUNK_MAX_TOKENS=512
        CHUNK_OVERLAP_TOKENS=64
        EMBEDDING_BATCH_SIZE=5
        EMBEDDING_TIMEOUT=30
        GENERATION_TIMEOUT=120
        CONCURRENT_INGESTIONS=1
        JVM_HEAP=$((TOTAL_RAM_MB / 4))
        ;;
    large)
        CHUNK_MAX_TOKENS=512
        CHUNK_OVERLAP_TOKENS=64
        EMBEDDING_BATCH_SIZE=5
        EMBEDDING_TIMEOUT=30
        GENERATION_TIMEOUT=120
        CONCURRENT_INGESTIONS=1
        JVM_HEAP=$((TOTAL_RAM_MB / 3))
        ;;
esac

# Plafonner le heap JVM à 4 Go et garantir un minimum de 2 Go
if (( JVM_HEAP < 2048 )); then
    JVM_HEAP=2048
fi
if (( JVM_HEAP > 4096 )); then
    JVM_HEAP=4096
fi

# Serveur LLM : nombre de requêtes parallèles (min 1, max 8)
LLM_PARALLEL=$(( CPU_CORES / 2 ))
if (( LLM_PARALLEL < 1 )); then LLM_PARALLEL=1; fi
if (( LLM_PARALLEL > 8 )); then LLM_PARALLEL=8; fi

# Taille de contexte LLM.
#
# LLM_CONTEXT est le contexte TOTAL du serveur, réparti entre les slots parallèles :
# une requête ne voit que LLM_CONTEXT / LLM_PARALLEL tokens. L'ancien calcul fixait le
# total selon la RAM tout en faisant croître le parallélisme avec les cœurs — donc plus
# la machine avait de cœurs, plus la fenêtre par requête RÉTRÉCISSAIT : 512 tokens sur
# une machine 32 Go / 16 cœurs, alors que le RAG agentique en budgète 3000 à lui seul.
#
# On dimensionne désormais la fenêtre PAR REQUÊTE (la seule qui compte fonctionnellement),
# puis on en déduit le total. Le parallélisme est plafonné pour que le total reste dans
# l'enveloppe mémoire : sur du CPU, deux conversations à 4096 tokens valent mieux que
# huit à 512.
if (( TOTAL_RAM_MB >= 32768 )); then
    SLOT_CONTEXT=8192; MAX_TOTAL_CONTEXT=32768
elif (( TOTAL_RAM_MB >= 16384 )); then
    SLOT_CONTEXT=4096; MAX_TOTAL_CONTEXT=16384
elif (( TOTAL_RAM_MB >= 8192 )); then
    SLOT_CONTEXT=2048; MAX_TOTAL_CONTEXT=4096
else
    SLOT_CONTEXT=1024; MAX_TOTAL_CONTEXT=2048
fi

# Plafonne le parallélisme pour tenir l'enveloppe totale sans rogner la fenêtre par slot.
MAX_SLOTS=$(( MAX_TOTAL_CONTEXT / SLOT_CONTEXT ))
if (( MAX_SLOTS < 1 )); then MAX_SLOTS=1; fi
if (( LLM_PARALLEL > MAX_SLOTS )); then LLM_PARALLEL=$MAX_SLOTS; fi
LLM_CONTEXT=$(( SLOT_CONTEXT * LLM_PARALLEL ))

# ── 6. Écriture du .env ──
cat > "$ENV_FILE" <<EOF
# ── Spectra — Configuration auto-détectée ──
# Profil: ${PROFILE} | RAM: ${TOTAL_RAM_MB} Mo | CPU: ${CPU_CORES} cores | GPU: ${GPU_TYPE} (VRAM: ${GPU_VRAM_MB} Mo)
# Généré le $(date -Iseconds 2>/dev/null || date)

# ── Pipeline ──
SPECTRA_CHUNK_MAX_TOKENS=${CHUNK_MAX_TOKENS}
SPECTRA_CHUNK_OVERLAP_TOKENS=${CHUNK_OVERLAP_TOKENS}
SPECTRA_EMBEDDING_BATCH_SIZE=${EMBEDDING_BATCH_SIZE}
SPECTRA_EMBEDDING_TIMEOUT=${EMBEDDING_TIMEOUT}
SPECTRA_GENERATION_TIMEOUT=${GENERATION_TIMEOUT}
SPECTRA_CONCURRENT_INGESTIONS=${CONCURRENT_INGESTIONS}

# ── JVM ──
JAVA_OPTS="-Xms256m -Xmx${JVM_HEAP}m -XX:+UseZGC"

# ── Serveur LLM — Chat ──
LLM_CHAT_MODEL_FILE=Qwen2.5-7B-Instruct-Q4_K_M.gguf
LLM_CHAT_MODEL_NAME=qwen2.5-7b-instruct
LLM_PARALLEL=${LLM_PARALLEL}
LLM_CONTEXT=${LLM_CONTEXT}

# ── Serveur LLM — Embedding ──
LLM_EMBED_MODEL_FILE=embed.gguf
LLM_EMBED_MODEL_NAME=nomic-embed-text
LLM_EMBED_PARALLEL=${LLM_PARALLEL}

# ── Provider LLM ──
SPECTRA_LLM_PROVIDER=llama-cpp

# ── GPU ──
SPECTRA_GPU_ENABLED=${GPU_DETECTED}
SPECTRA_GPU_TYPE=${GPU_TYPE}
SPECTRA_GPU_VRAM_MB=${GPU_VRAM_MB}
EOF

echo ""
echo "  ✓ Fichier .env généré : ${ENV_FILE}"
