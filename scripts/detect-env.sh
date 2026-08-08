#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Détection automatique de la configuration serveur
# Écrit dans .env un bloc de réglages adaptés aux ressources disponibles.
# Usage: ./detect-env.sh [--gpu] [--force]
#
#   --gpu    Force la détection NVIDIA (tests sans carte physique).
#   --force  Repart d'un .env vierge : la zone utilisateur est sauvegardée
#            dans .env.bak puis effacée.
#
# COMMENT CE SCRIPT COHABITE AVEC VOS RÉGLAGES
# Le fichier .env est coupé en deux : un BLOC AUTO en tête, régénéré à chaque
# exécution, puis tout le reste — votre zone, jamais touchée. Compose retenant la
# DERNIÈRE affectation d'une clé, ce que vous écrivez sous le bloc l'emporte
# toujours sur ce qui est détecté.
#
# La version précédente sortait sans rien faire dès que .env existait. Ce n'était
# pas une précaution : c'était l'annulation pure et simple de la détection sur le
# chemin le plus fréquenté — `start.sh --first-run` fait tourner setup.sh d'abord,
# qui créait .env, et detect-env.sh n'écrivait donc JAMAIS rien. Ni profil matériel,
# ni SPECTRA_GPU_ENABLED — au point que le bloc GPU de start.sh, qui cherche cette
# clé dans .env, ne pouvait pas se déclencher et que --gpu restait sans effet.
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
        --force) FORCE=1 ;;            # repart d'une zone utilisateur vierge
    esac
done

# Délimiteurs du bloc régénérable. Toute ligne hors de ces bornes appartient à
# l'utilisateur et survit à chaque exécution.
#
# Bornes en ASCII pur, et à l'octet près identiques à celles de detect-env.bat : un même
# dépôt est régulièrement piloté depuis les deux (WSL le matin, PowerShell l'après-midi),
# et une borne accentuée n'aurait pas la même représentation d'un côté et de l'autre — le
# script Windows ne reconnaîtrait pas le bloc écrit par le script shell et empilerait un
# second bloc à chaque exécution.
AUTO_BEGIN="# === SPECTRA:AUTO:BEGIN - bloc regenere par detect-env, ne pas editer ==="
AUTO_END="# === SPECTRA:AUTO:END - ecrivez vos reglages SOUS cette ligne ==="
# Entête écrit par les versions antérieures, qui produisaient un fichier ENTIÈREMENT
# généré. Aucun humain ne l'écrit : le reconnaître permet de reprendre la main sur ces
# fichiers au lieu d'empiler un bloc sous des valeurs figées qui l'emporteraient.
# Motif ASCII commun aux deux entêtes historiques (« auto-détectée » côté shell,
# « auto-detectee » côté batch).
LEGACY_HEADER="Configuration auto"

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

# ── 3. Détection GPU (même ordre de priorité que ResourceAdvisorService) ──
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

# ── Contexte LLM et parallélisme ──
# Arithmétique volontairement isolée dans une bibliothèque : elle avait été fausse sans que
# rien ne puisse le révéler (voir l'entête de llm-sizing.sh), et elle est désormais couverte
# par scripts/tests/test_llm_sizing.py. Renseigne LLM_PARALLEL, LLM_CONTEXT, LLM_SLOT_CONTEXT.
# shellcheck source=lib/llm-sizing.sh
source "$SCRIPT_DIR/lib/llm-sizing.sh"
llm_sizing "$TOTAL_RAM_MB" "$CPU_CORES"

echo "  Contexte   : ${LLM_SLOT_CONTEXT} tokens/requête × ${LLM_PARALLEL} slots = ${LLM_CONTEXT} au total"

# ── 6. Écriture du .env ──
#
# Zone utilisateur : tout ce qui, dans le .env actuel, n'a pas été écrit par ce script.
# Elle est relue AVANT réécriture puis réémise telle quelle sous le bloc auto.
USER_ZONE="$(mktemp)"
trap 'rm -f "$USER_ZONE" "$USER_ZONE.new"' EXIT
MIGRATED=""

if [[ -f "$ENV_FILE" ]]; then
    if [[ "$FORCE" -eq 1 ]]; then
        cp "$ENV_FILE" "$ENV_FILE.bak"
        MIGRATED="--force : ancien contenu sauvegardé dans .env.bak"
    elif grep -qF "$AUTO_END" "$ENV_FILE"; then
        # Cas courant : on ne garde que ce qui suit la borne de fin. Comparaison de
        # chaînes brutes (awk index) et non expression régulière : la borne contient des
        # caractères qu'une regex interpréterait.
        awk -v marker="$AUTO_END" '
            keep { print }
            index($0, marker) == 1 { keep = 1 }
        ' "$ENV_FILE" > "$USER_ZONE"
    elif head -1 "$ENV_FILE" | grep -qF "$LEGACY_HEADER"; then
        # Fichier entièrement généré par une version antérieure. Le conserver en zone
        # utilisateur figerait une détection périmée — et masquerait toute nouvelle
        # détection, puisque la dernière affectation gagne. On le remplace, avec copie.
        cp "$ENV_FILE" "$ENV_FILE.bak"
        MIGRATED="ancien .env auto-généré remplacé (copie dans .env.bak)"
    else
        # .env écrit à la main, ou copié depuis .env.example : intégralement à l'utilisateur.
        cp "$ENV_FILE" "$USER_ZONE"
    fi
fi

# Un .env neuf reçoit une zone utilisateur vide mais annoncée, pour que l'endroit où
# écrire ses propres réglages soit visible sans lire ce script.
if [[ ! -s "$USER_ZONE" ]]; then
    cat > "$USER_ZONE" <<'EOF'

# ── Vos réglages ──────────────────────────────────────────────────────────────
# Ajoutez ici vos surcharges : elles l'emportent sur le bloc auto ci-dessus.
# Le catalogue complet des variables, commenté, est dans .env.example — recopiez-en
# les lignes qui vous intéressent plutôt que le fichier entier, dont les valeurs
# figeraient à la fois la détection matérielle et les défauts de docker-compose.yml.
EOF
fi

cat > "$ENV_FILE.new" <<EOF
$AUTO_BEGIN
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
$AUTO_END
EOF

cat "$USER_ZONE" >> "$ENV_FILE.new"
mv "$ENV_FILE.new" "$ENV_FILE"

echo ""
echo "  ✓ Bloc auto-détecté écrit dans ${ENV_FILE}"
[[ -n "$MIGRATED" ]] && echo "  ✓ $MIGRATED"
if grep -qE '^[A-Z_]+=' "$USER_ZONE"; then
    KEPT=$(grep -cE '^[A-Z_]+=' "$USER_ZONE" || true)
    echo "  ✓ ${KEPT} réglage(s) personnel(s) conservé(s) — ils l'emportent sur le bloc auto"
fi
