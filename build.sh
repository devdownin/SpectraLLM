#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Script de build
# Usage: ./build.sh [--skip-tests]
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

SKIP_TESTS=""
if [[ "${1:-}" == "--skip-tests" ]]; then
    SKIP_TESTS="-DskipTests"
fi

echo "╔══════════════════════════════════════╗"
echo "║        Spectra — Build               ║"
echo "╚══════════════════════════════════════╝"

# 1. Build Maven (si mvn est disponible localement)
if command -v mvn &>/dev/null; then
    echo ""
    echo "► Maven build..."
    mvn clean package $SKIP_TESTS -B -q
    echo "  ✓ JAR construit: target/spectra-api-0.1.0-SNAPSHOT.jar"
else
    echo ""
    echo "► Maven non trouvé localement, le build sera fait dans Docker."
fi

# 2. Build Docker
echo ""
echo "► Docker build..."
docker compose build --no-cache
echo "  ✓ Images Docker construites"

echo ""
echo "══════════════════════════════════════"
echo " Build terminé."
echo " Lancez: ./start.sh"
echo "══════════════════════════════════════"
