#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Spectra — Script de configuration initiale (Linux / macOS)
#
# Usage : ./setup.sh [--download-embed] [--download-chat]
#
#   --download-embed   Télécharge nomic-embed-text (~81 Mo) si absent
#   --download-chat    Télécharge Phi-3.5-mini Q4_K_M (~2.4 Go) si absent
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail
# Les scripts vivent dans scripts/ mais opèrent sur la racine du dépôt
# (data/, .env, .env.example, docker-compose via --project-directory .).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

DOWNLOAD_EMBED=0
DOWNLOAD_CHAT=0
ERRORS=0

for arg in "$@"; do
  case "$arg" in
    --download-embed) DOWNLOAD_EMBED=1 ;;
    --download-chat)  DOWNLOAD_CHAT=1  ;;
  esac
done

green()  { echo -e "\033[32m$*\033[0m"; }
yellow() { echo -e "\033[33m$*\033[0m"; }
red()    { echo -e "\033[31m$*\033[0m"; }

# Lit une variable depuis .env (sans sourcer le fichier), guillemets retirés.
read_env_var() {
  local key="$1"
  [ -f ".env" ] || return 0
  grep -E "^${key}=" .env | tail -n1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'
}

# Insère ou met à jour une variable dans .env.
set_env_var() {
  local key="$1" val="$2" tmp
  [ -f ".env" ] || touch ".env"
  if grep -qE "^${key}=" ".env"; then
    tmp="$(mktemp)"
    grep -vE "^${key}=" ".env" > "$tmp"
    printf '%s=%s\n' "$key" "$val" >> "$tmp"
    mv "$tmp" ".env"
  else
    printf '%s=%s\n' "$key" "$val" >> ".env"
  fi
}

echo "======================================"
echo "  Spectra — Configuration initiale"
echo "======================================"
echo

# ── 1. Java ───────────────────────────────────────────────────────────────
echo "> [1/6] Vérification de Java 25..."
if [ -f "$SCRIPT_DIR/setup-java.sh" ]; then
  if ! "$SCRIPT_DIR/setup-java.sh"; then
    ERRORS=$((ERRORS + 1))
  fi
fi

# ── 2. Docker ─────────────────────────────────────────────────────────────
echo
echo "> [2/6] Vérification de Docker..."
if ! docker info > /dev/null 2>&1; then
  red "  [ERREUR] Docker n'est pas démarré ou n'est pas installé."
  echo "  Installez Docker Desktop : https://www.docker.com/products/docker-desktop"
  ERRORS=$((ERRORS + 1))
else
  green "  [OK] $(docker --version)"
fi

# ── 3. Répertoires de données ──────────────────────────────────────────────
echo
echo "> [3/6] Création des répertoires de données..."
for dir in data/documents data/dataset data/fine-tuning data/fine-tuning/merged data/models data/source; do
  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
    echo "  [CRÉÉ] $dir"
  else
    green "  [OK]   $dir"
  fi
done

# ── 4. Fichier .env ────────────────────────────────────────────────────────
echo
echo "> [4/6] Fichier de configuration .env..."
if [ ! -f ".env" ]; then
  if [ -f ".env.example" ]; then
    cp .env.example .env
    green "  [CRÉÉ] .env copié depuis .env.example"
    echo "  Éditez .env pour personnaliser la configuration."
  else
    yellow "  [AVERT] .env.example introuvable — ignoré"
  fi
else
  green "  [OK] .env existe déjà"
fi

# ── 5. Modèle d'embedding ─────────────────────────────────────────────────
echo
echo "> [5/6] Modèle d'embedding (data/models/embed.gguf)..."
if [ -f "data/models/embed.gguf" ]; then
  SIZE=$(du -sh data/models/embed.gguf | cut -f1)
  green "  [OK] embed.gguf présent — $SIZE"
else
  if [ "$DOWNLOAD_EMBED" -eq 1 ]; then
    echo "  Téléchargement de nomic-embed-text-v1.5.Q4_K_M.gguf (~81 Mo)..."
    # --fail : sortir en erreur sur 404/5xx au lieu d'enregistrer une page HTML d'erreur.
    curl -L --fail --progress-bar \
      "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf" \
      -o data/models/embed.gguf
    green "  [OK] embed.gguf téléchargé"
  else
    yellow "  [MANQUANT] data/models/embed.gguf absent"
    echo
    echo "  Téléchargement automatique :"
    echo "    ./setup.sh --download-embed"
    echo "  Ou manuellement :"
    echo "    curl -L https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf \\"
    echo "         -o data/models/embed.gguf"
    ERRORS=$((ERRORS + 1))
  fi
fi

# ── 6. Modèle de chat ─────────────────────────────────────────────────────
# Le modèle doit résider dans data/models/ sous le nom que la stack Docker lit
# (data/models/${LLM_CHAT_MODEL_FILE}), sinon model-init / llm-chat ne le trouvent pas.
CHAT_DOWNLOAD_NAME="Phi-3.5-mini-instruct-Q4_K_M.gguf"
CHAT_MODEL_FILE="$(read_env_var LLM_CHAT_MODEL_FILE)"
CHAT_MODEL_FILE="${CHAT_MODEL_FILE:-$CHAT_DOWNLOAD_NAME}"
CHAT_MODEL_PATH="data/models/$CHAT_MODEL_FILE"
echo
echo "> [6/6] Modèle de chat ($CHAT_MODEL_PATH)..."
if [ -f "$CHAT_MODEL_PATH" ]; then
  SIZE=$(du -sh "$CHAT_MODEL_PATH" | cut -f1)
  green "  [OK] $CHAT_MODEL_FILE présent — $SIZE"
  set_env_var LLM_CHAT_MODEL_FILE "$CHAT_MODEL_FILE"
else
  if [ "$DOWNLOAD_CHAT" -eq 1 ]; then
    # Téléchargement dans data/models/ sous le nom attendu par la stack.
    CHAT_MODEL_FILE="$CHAT_DOWNLOAD_NAME"
    CHAT_MODEL_PATH="data/models/$CHAT_MODEL_FILE"
    echo "  Téléchargement de $CHAT_DOWNLOAD_NAME (~2.4 Go)..."
    echo "  (cela peut prendre plusieurs minutes selon votre connexion)"
    curl -L --fail --progress-bar \
      "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf" \
      -o "$CHAT_MODEL_PATH"
    green "  [OK] $CHAT_MODEL_FILE téléchargé"
    # Aligner .env pour que la stack Docker charge bien ce fichier.
    set_env_var LLM_CHAT_MODEL_FILE "$CHAT_MODEL_FILE"
    echo "  LLM_CHAT_MODEL_FILE=$CHAT_MODEL_FILE écrit dans .env"
  else
    yellow "  [MANQUANT] $CHAT_MODEL_PATH absent"
    echo
    echo "  Option 1 — Téléchargement automatique (Phi-3.5-mini ~2.4 Go) :"
    echo "    ./setup.sh --download-chat"
    echo
    echo "  Option 2 — Tout modèle GGUF instruction-tuned fonctionne :"
    echo "    placez votre fichier dans data/models/ et renseignez"
    echo "    LLM_CHAT_MODEL_FILE=<nom-du-fichier.gguf> dans .env"
    ERRORS=$((ERRORS + 1))
  fi
fi

# ── Résumé ─────────────────────────────────────────────────────────────────
echo
echo "======================================"
if [ "$ERRORS" -eq 0 ]; then
  green "  [OK] Configuration terminée — tout est en place !"
  echo
  echo "  Pour compiler (nécessite Java 25 local) :"
  echo "    ./scripts/build.sh"
  echo
  echo "  Pour démarrer Spectra :"
  echo "    ./scripts/start.sh"
  echo
  echo "  Pour tester avec des exemples :"
  echo "    bash scripts/adddoc.sh examples"
else
  red "  [!] Configuration incomplète — $ERRORS élément(s) à corriger."
  echo "  Relancez ./setup.sh après avoir résolu les problèmes ci-dessus."
fi
echo "======================================"
echo
