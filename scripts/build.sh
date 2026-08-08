#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Script de build
# Usage: ./build.sh [--skip-tests] [--no-cache]
#
#   --skip-tests  Ne joue pas les tests Maven.
#   --no-cache    Reconstruit les images en ignorant le cache de couches.
#                 À réserver au diagnostic : c'est plusieurs minutes de plus,
#                 le temps de retélécharger les dépendances Maven et npm.
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Opère sur la racine du dépôt : pom sous backend/, docker-compose sous
# deploy/docker/ (contexte projet = racine via --project-directory .).
cd "$SCRIPT_DIR/.."

# shellcheck source=lib/compose.sh
source "$SCRIPT_DIR/lib/compose.sh"
spectra_compose_init

SKIP_TESTS=""
NO_CACHE=""
for arg in "$@"; do
    case "$arg" in
        --skip-tests) SKIP_TESTS="-DskipTests" ;;
        --no-cache)   NO_CACHE="--no-cache" ;;
    esac
done

echo "╔══════════════════════════════════════╗"
echo "║        Spectra — Build               ║"
echo "╚══════════════════════════════════════╝"

# 1. Build Maven (si mvn est disponible localement)
if command -v mvn &>/dev/null; then
    echo ""
    echo "► Maven build..."
    mvn clean package $SKIP_TESTS -B -q -f backend/pom.xml
    # Le nom du JAR était annoncé en dur (« spectra-api-1.1.0-SNAPSHOT.jar ») : à la première
    # montée de version, le script désignait un fichier inexistant. On lit ce qui a été produit.
    JAR="$(ls -1 backend/target/*.jar 2>/dev/null | head -1 || true)"
    echo "  ✓ JAR construit: ${JAR:-backend/target/ (introuvable)}"
else
    echo ""
    echo "► Maven non trouvé localement, le build sera fait dans Docker."
fi

# 2. Build Docker
#
# Le cache de couches n'est plus contourné par défaut. `--no-cache` systématique
# retéléchargeait l'intégralité des dépendances Maven et npm à chaque appel — plusieurs
# minutes pour une virgule changée — et annulait au passage le cache de dépôt Maven que le
# Dockerfile monte précisément pour éviter cela. Docker sait déjà invalider ce qui a changé ;
# l'option reste disponible pour les cas où on soupçonne le cache lui-même.
echo ""
echo "► Docker build..."
"${COMPOSE[@]}" build $NO_CACHE
echo "  ✓ Images Docker construites"

echo ""
echo "══════════════════════════════════════"
echo " Build terminé."
echo " Lancez: ./scripts/start.sh"
echo "══════════════════════════════════════"
