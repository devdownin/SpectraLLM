#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Spectra — Script de vérification et configuration Java 25
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

green()  { echo -e "\033[32m$*\033[0m"; }
yellow() { echo -e "\033[33m$*\033[0m"; }
red()    { echo -e "\033[31m$*\033[0m"; }

echo "======================================"
echo "  Spectra — Configuration Java 25"
echo "======================================"
echo

# 1. Vérification de Java
echo "> [1/2] Vérification de la version de Java..."
if command -v java > /dev/null 2>&1; then
    # Extraire la version majeure de manière robuste
    JAVA_FULL_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    JAVA_MAJOR_VERSION=$(echo "$JAVA_FULL_VERSION" | cut -d'.' -f1)

    # Gérer le format 1.8.x
    if [ "$JAVA_MAJOR_VERSION" = "1" ]; then
        JAVA_MAJOR_VERSION=$(echo "$JAVA_FULL_VERSION" | cut -d'.' -f2)
    fi

    if [[ "$JAVA_MAJOR_VERSION" == "25" ]]; then
        green "  [OK] Java $JAVA_FULL_VERSION détecté."
        ERRORS=0
    else
        red "  [ERREUR] Java $JAVA_FULL_VERSION détecté, mais Java 25 est requis."
        ERRORS=1
    fi
else
    red "  [ERREUR] Java n'est pas installé."
    ERRORS=1
fi

# 2. Aide à l'installation
echo
echo "> [2/2] Guide d'installation..."

if [[ "$ERRORS" -eq 1 ]]; then
    yellow "  Vous devez installer le JDK 25 pour compiler Spectra."
    echo
    echo "  Option 1 : Utiliser SDKMAN! (recommandé)"
    echo "    1. Installez SDKMAN! : https://sdkman.io/"
    echo "    2. Dans le dossier du projet, lancez :"
    echo "       sdk env install"
    echo "       sdk env use"
    echo
    echo "  Option 2 : Utiliser un DevContainer (VS Code)"
    echo "    Ouvrez le projet dans VS Code et acceptez la suggestion d'ouvrir dans un conteneur."
    echo
    echo "  Option 3 : Téléchargement manuel"
    echo "    Téléchargez Eclipse Temurin 25 (LTS) : https://adoptium.net/temurin/releases/?version=25"
    echo
    exit 1
else
    green "  [OK] Votre environnement Java est prêt !"
    echo "  Vous pouvez maintenant compiler le projet avec : ./build.sh"
fi

echo "======================================"
