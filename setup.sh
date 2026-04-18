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
cd "$(dirname "$0")"

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

echo "======================================"
echo "  Spectra — Configuration initiale"
echo "======================================"
echo

# ── 1. Docker ─────────────────────────────────────────────────────────────
echo "> [1/5] Vérification de Docker..."
if ! docker info > /dev/null 2>&1; then
  red "  [ERREUR] Docker n'est pas démarré ou n'est pas installé."
  echo "  Installez Docker Desktop : https://www.docker.com/products/docker-desktop"
  ERRORS=$((ERRORS + 1))
else
  green "  [OK] $(docker --version)"
fi

# ── 2. Répertoires de données ──────────────────────────────────────────────
echo
echo "> [2/5] Création des répertoires de données..."
for dir in data/documents data/dataset data/fine-tuning data/fine-tuning/merged data/models data/source; do
  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
    echo "  [CRÉÉ] $dir"
  else
    green "  [OK]   $dir"
  fi
done

# ── 3. Fichier .env ────────────────────────────────────────────────────────
echo
echo "> [3/5] Fichier de configuration .env..."
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

# ── 4. Modèle d'embedding ─────────────────────────────────────────────────
echo
echo "> [4/5] Modèle d'embedding (data/models/embed.gguf)..."
if [ -f "data/models/embed.gguf" ]; then
  SIZE=$(du -sh data/models/embed.gguf | cut -f1)
  green "  [OK] embed.gguf présent — $SIZE"
else
  if [ "$DOWNLOAD_EMBED" -eq 1 ]; then
    echo "  Téléchargement de nomic-embed-text-v1.5.Q4_K_M.gguf (~81 Mo)..."
    curl -L --progress-bar \
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

# ── 5. Modèle de chat ─────────────────────────────────────────────────────
echo
echo "> [5/5] Modèle de chat (data/fine-tuning/merged/model.gguf)..."
if [ -f "data/fine-tuning/merged/model.gguf" ]; then
  SIZE=$(du -sh data/fine-tuning/merged/model.gguf | cut -f1)
  green "  [OK] model.gguf présent — $SIZE"
else
  if [ "$DOWNLOAD_CHAT" -eq 1 ]; then
    echo "  Téléchargement de Phi-3.5-mini-instruct-Q4_K_M.gguf (~2.4 Go)..."
    echo "  (cela peut prendre plusieurs minutes selon votre connexion)"
    curl -L --progress-bar \
      "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf" \
      -o data/fine-tuning/merged/model.gguf
    green "  [OK] model.gguf téléchargé"
  else
    yellow "  [MANQUANT] data/fine-tuning/merged/model.gguf absent"
    echo
    echo "  Option 1 — Téléchargement automatique (Phi-3.5-mini ~2.4 Go) :"
    echo "    ./setup.sh --download-chat"
    echo
    echo "  Option 2 — Tout modèle GGUF instruction-tuned fonctionne :"
    echo "    placez votre fichier dans data/fine-tuning/merged/model.gguf"
    ERRORS=$((ERRORS + 1))
  fi
fi

# ── Résumé ─────────────────────────────────────────────────────────────────
echo
echo "======================================"
if [ "$ERRORS" -eq 0 ]; then
  green "  [OK] Configuration terminée — tout est en place !"
  echo
  echo "  Pour démarrer Spectra :"
  echo "    docker compose up -d"
  echo
  echo "  Pour tester avec des exemples :"
  echo "    bash adddoc.sh examples"
else
  red "  [!] Configuration incomplète — $ERRORS élément(s) à corriger."
  echo "  Relancez ./setup.sh après avoir résolu les problèmes ci-dessus."
fi
echo "======================================"
echo
