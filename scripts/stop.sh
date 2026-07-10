#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Script d'arrêt
# Usage: ./stop.sh [--clean]
#   --clean : supprime aussi les volumes Docker (données)
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Opère sur la racine du dépôt (docker-compose via --project-directory .).
cd "$SCRIPT_DIR/.."

COMPOSE=(docker compose --project-directory . -f deploy/docker/docker-compose.yml)

echo "► Arrêt des services Spectra..."

# Activer TOUS les profils pour que « down » stoppe aussi les services optionnels
# (layout-parser, reranker, kafka) démarrés via --profile ; --remove-orphans nettoie
# les conteneurs restants du projet.
export COMPOSE_PROFILES="layout-parser,reranker,kafka"

if [[ "${1:-}" == "--clean" ]]; then
    "${COMPOSE[@]}" down -v --remove-orphans
    echo "  ✓ Services arrêtés et volumes supprimés"
else
    "${COMPOSE[@]}" down --remove-orphans
    echo "  ✓ Services arrêtés (volumes conservés)"
fi
