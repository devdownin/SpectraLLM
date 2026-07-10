#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Script de lancement
# Usage: ./start.sh [--first-run] [--gpu] [--detach]
#
#   --first-run    Premier lancement tout-en-un : configuration initiale,
#                  téléchargement des modèles (embedding + chat), démarrage
#                  en arrière-plan puis ouverture du navigateur sur l'UI.
#   --gpu          Force la détection GPU (transmis à detect-env.sh).
#   --detach, -d   Démarre en arrière-plan et attend que les services
#                  soient prêts avant d'afficher le récapitulatif.
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Les scripts vivent dans scripts/ mais la stack (docker-compose via
# --project-directory ., data/, .env) est ancrée à la racine du dépôt.
cd "$SCRIPT_DIR/.."

# Invocation Compose : fichier sous deploy/docker/, contexte projet = racine.
COMPOSE=(docker compose --project-directory . -f deploy/docker/docker-compose.yml)

bold()  { echo -e "\033[1m$*\033[0m"; }
green() { echo -e "\033[1;32m$*\033[0m"; }

DETACH=""
GPU_FLAG=""
FIRST_RUN=""

# Parse des arguments
for arg in "$@"; do
    case "$arg" in
        --detach|-d)   DETACH="-d" ;;
        --gpu)         GPU_FLAG="--gpu" ;;
        --first-run)   FIRST_RUN=1; DETACH="-d" ;;
    esac
done

echo "╔══════════════════════════════════════╗"
echo "║        Spectra — Démarrage           ║"
echo "╚══════════════════════════════════════╝"

# 0. Premier lancement : setup complet (Java, répertoires, .env, modèles)
if [[ -n "$FIRST_RUN" ]]; then
    echo ""
    echo "► Premier lancement : configuration initiale + téléchargement des modèles..."
    bash "$SCRIPT_DIR/setup.sh" --download-embed --download-chat
fi

# 1. Créer les répertoires de données
echo ""
echo "► Création des répertoires de données..."
mkdir -p data/documents data/dataset data/fine-tuning data/models
echo "  ✓ data/documents, data/dataset, data/fine-tuning, data/models"

# 2. Détection automatique de la configuration serveur
echo ""
echo "► Détection de la configuration serveur..."
bash "$SCRIPT_DIR/detect-env.sh" $GPU_FLAG

# Lire le .env (racine du dépôt) pour déterminer si le GPU est activé
if grep -q 'SPECTRA_GPU_ENABLED=true' .env 2>/dev/null; then
    COMPOSE+=(-f deploy/docker/docker-compose.gpu.yml)
    echo "  ✓ GPU activé → docker-compose.gpu.yml inclus"
fi

# 3. Build si l'image n'existe pas
# On interroge Compose lui-même plutôt qu'un nom d'image codé en dur (qui dépend du nom
# de projet dérivé du répertoire, p. ex. « spectrallm-spectra-api »).
if ! "${COMPOSE[@]}" images -q spectra-api 2>/dev/null | grep -q .; then
    echo ""
    echo "► Image spectra-api non trouvée, build en cours..."
    "${COMPOSE[@]}" build
fi

# En mode premier plan, docker compose bloque le terminal : afficher les URLs
# d'accès AVANT le démarrage, sinon l'utilisateur ne les voit jamais.
if [[ -z "$DETACH" ]]; then
    echo ""
    bold "► URLs d'accès (une fois les services prêts, ~1-2 min) :"
    green "   Interface Web :  http://localhost"
    echo "   API REST      :  http://localhost:8080/api/status"
    echo "   Ctrl+C pour arrêter — ou relancez avec --detach pour libérer le terminal."
fi

# 4. Démarrage des services
echo ""
echo "► Démarrage des services Docker..."
"${COMPOSE[@]}" up $DETACH

# Si mode détaché, on continue avec le post-setup
if [[ -n "$DETACH" ]]; then
    echo "  ✓ Services démarrés en arrière-plan"

    # 5. Attente que les services soient prêts
    echo ""
    echo "► Attente des services..."

    # Serveur LLM
    echo -n "  LLM server:   "
    for i in $(seq 1 30); do
        if curl -sf http://localhost:8081/health &>/dev/null; then
            echo "✓ prêt"
            break
        fi
        if [[ $i -eq 30 ]]; then echo "✗ timeout"; fi
        sleep 2
    done

    # ChromaDB
    echo -n "  ChromaDB:     "
    for i in $(seq 1 30); do
        if curl -sf http://localhost:8000/api/v1/heartbeat &>/dev/null; then
            echo "✓ prêt"
            break
        fi
        if [[ $i -eq 30 ]]; then echo "✗ timeout"; fi
        sleep 2
    done

    # Spectra API
    echo -n "  Spectra API:  "
    for i in $(seq 1 30); do
        if curl -sf http://localhost:8080/actuator/health &>/dev/null; then
            echo "✓ prêt"
            break
        fi
        if [[ $i -eq 30 ]]; then echo "✗ timeout"; fi
        sleep 2
    done

    # Interface Web (nginx + React)
    echo -n "  Interface Web:"
    for i in $(seq 1 30); do
        if curl -sf http://localhost/ &>/dev/null; then
            echo " ✓ prêt"
            break
        fi
        if [[ $i -eq 30 ]]; then echo " ✗ timeout"; fi
        sleep 2
    done

    # 6. Résumé
    echo ""
    echo "══════════════════════════════════════"
    green " Spectra est prêt !"
    echo ""
    green " Interface Web :  http://localhost"
    echo ""
    echo " API REST    :  http://localhost:8080/api/status"
    echo " Swagger     :  http://localhost:8080/swagger-ui.html"
    echo " LLM server  :  http://localhost:8081"
    echo " ChromaDB    :  http://localhost:8000"
    echo ""
    echo " Arrêt       :  ./scripts/stop.sh"
    echo " Logs        :  ${COMPOSE[*]} logs -f"
    echo "══════════════════════════════════════"

    # 7. Premier lancement : ouvrir le navigateur sur l'UI (best effort)
    if [[ -n "$FIRST_RUN" ]]; then
        (xdg-open "http://localhost" 2>/dev/null || open "http://localhost" 2>/dev/null || true) &
    fi
fi
