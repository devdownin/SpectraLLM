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

if [[ "${1:-}" == "--clean" ]]; then
    docker compose down -v
    echo "  ✓ Services arrêtés et volumes supprimés"
else
    docker compose down
    echo "  ✓ Services arrêtés (volumes conservés)"
fi
