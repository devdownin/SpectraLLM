#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Script de lancement
# Usage: ./start.sh [--gpu] [--detach]
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

DETACH=""
GPU_FLAG=""

# Parse des arguments
for arg in "$@"; do
    case "$arg" in
        --detach|-d)   DETACH="-d" ;;
        --gpu)         GPU_FLAG="--gpu" ;;
    esac
done

echo "╔══════════════════════════════════════╗"
echo "║        Spectra — Démarrage           ║"
echo "╚══════════════════════════════════════╝"

# 1. Créer les répertoires de données
echo ""
echo "► Création des répertoires de données..."
mkdir -p data/documents data/dataset data/fine-tuning data/models
echo "  ✓ data/documents, data/dataset, data/fine-tuning, data/models"

# 2. Détection automatique de la configuration serveur
echo ""
echo "► Détection de la configuration serveur..."
bash "$SCRIPT_DIR/detect-env.sh" $GPU_FLAG

# Lire le .env pour déterminer si le GPU est activé
COMPOSE_FILES="-f docker-compose.yml"
if grep -q 'SPECTRA_GPU_ENABLED=true' "$SCRIPT_DIR/.env" 2>/dev/null; then
    COMPOSE_FILES="-f docker-compose.yml -f docker-compose.gpu.yml"
    echo "  ✓ GPU activé → docker-compose.gpu.yml inclus"
fi

# 3. Build si l'image n'existe pas
if ! docker image inspect spectra-spectra-api &>/dev/null 2>&1; then
    echo ""
    echo "► Image spectra-api non trouvée, build en cours..."
    docker compose $COMPOSE_FILES build
fi

# 4. Démarrage des services
echo ""
echo "► Démarrage des services Docker..."
docker compose $COMPOSE_FILES up $DETACH

# Si mode détaché, on continue avec le post-setup
if [[ -n "$DETACH" ]]; then
    echo "  ✓ Services démarrés en arrière-plan"

    # 5. Attente que les services soient prêts
    echo ""
    echo "► Attente des services..."

    # Serveur LLM
    echo -n "  LLM server: "
    for i in $(seq 1 30); do
        if curl -s http://localhost:8081/health &>/dev/null; then
            echo "✓ prêt"
            break
        fi
        if [[ $i -eq 30 ]]; then echo "✗ timeout"; fi
        sleep 2
    done

    # ChromaDB
    echo -n "  ChromaDB:   "
    for i in $(seq 1 30); do
        if curl -s http://localhost:8000/api/v1/heartbeat &>/dev/null; then
            echo "✓ prêt"
            break
        fi
        if [[ $i -eq 30 ]]; then echo "✗ timeout"; fi
        sleep 2
    done

    # Spectra API
    echo -n "  Spectra API:"
    for i in $(seq 1 30); do
        if curl -s http://localhost:8080/actuator/health &>/dev/null; then
            echo " ✓ prêt"
            break
        fi
        if [[ $i -eq 30 ]]; then echo " ✗ timeout"; fi
        sleep 2
    done

    # 6. Résumé
    echo ""
    echo "══════════════════════════════════════"
    echo " Spectra est prêt !"
    echo ""
    echo " API REST    :  http://localhost:8080/api/status"
    echo " Swagger     :  http://localhost:8080/swagger-ui.html"
    echo " LLM server  :  http://localhost:8081"
    echo " ChromaDB    :  http://localhost:8000"
    echo ""
    echo " Arrêt       :  ./stop.sh"
    echo " Logs        :  docker compose logs -f"
    echo "══════════════════════════════════════"
fi
