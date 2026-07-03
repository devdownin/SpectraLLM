#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Script d'arrêt
# Usage: ./stop.sh [--clean]
#   --clean : supprime aussi les volumes Docker (données)
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "► Arrêt des services Spectra..."

# Activer TOUS les profils pour que « down » stoppe aussi les services optionnels
# (layout-parser, reranker, kafka) démarrés via --profile ; --remove-orphans nettoie
# les conteneurs restants du projet.
export COMPOSE_PROFILES="layout-parser,reranker,kafka"

if [[ "${1:-}" == "--clean" ]]; then
    docker compose down -v --remove-orphans
    echo "  ✓ Services arrêtés et volumes supprimés"
else
    docker compose down --remove-orphans
    echo "  ✓ Services arrêtés (volumes conservés)"
fi
