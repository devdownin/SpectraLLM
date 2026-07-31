#!/bin/bash
# =============================================================================
# Préparation d'une session Claude Code sur le web.
#
# POURQUOI CE FICHIER EXISTE
# Le conteneur distant fournit Maven, Node et Python, mais un JDK 21 — alors que
# le projet cible Java 25 (backend/pom.xml, CI, images Docker). Sans ce hook, la
# compilation échoue sur « release version 25 not supported », et le contournement
# (-Djava.version=21) produit un binaire qui n'est pas celui que la CI valide :
# on teste alors autre chose que ce qu'on livre.
#
# Adoptium/Temurin — recommandé par CONTRIBUTING.md et épinglé par .sdkmanrc — est
# injoignable derrière le proxy de l'environnement. L'archive Ubuntu, elle, sert le
# même OpenJDK 25 ; c'est donc la source retenue ici.
#
# Le script est idempotent, non interactif, et ne fait rien hors environnement
# distant : en local, votre JDK est déjà le bon.
# =============================================================================
set -euo pipefail

# En local, on ne touche à rien : cette préparation ne concerne que le conteneur
# éphémère des sessions web.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
log() { echo "[session-start] $*"; }

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

# ── 1. JDK à la version déclarée par le projet ───────────────────────────────
# La version est lue dans le pom plutôt que recopiée ici : une montée de version
# du projet ne doit pas laisser ce hook installer silencieusement l'ancienne.
JAVA_MAJOR="$(sed -n 's:.*<java\.version>\([0-9]\+\)</java\.version>.*:\1:p' \
  "$PROJECT_DIR/backend/pom.xml" | head -1)"
JAVA_MAJOR="${JAVA_MAJOR:-25}"
JDK_HOME="/usr/lib/jvm/java-${JAVA_MAJOR}-openjdk-amd64"

if [ ! -x "$JDK_HOME/bin/javac" ]; then
  log "JDK ${JAVA_MAJOR} absent — installation depuis l'archive Ubuntu."
  # Index souvent périmé dans l'image de base : sans rafraîchissement, apt tente
  # de télécharger une révision retirée du miroir et échoue en 404. Les dépôts
  # tiers (PPA) sont bloqués par le proxy ; leurs avertissements sont sans effet
  # sur les paquets de l'archive principale, d'où le `|| true`.
  $SUDO apt-get update -qq || true
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq \
    "openjdk-${JAVA_MAJOR}-jdk-headless"
else
  log "JDK ${JAVA_MAJOR} déjà présent."
fi

if [ ! -x "$JDK_HOME/bin/javac" ]; then
  log "ÉCHEC : JDK ${JAVA_MAJOR} introuvable après installation ($JDK_HOME)."
  exit 1
fi

# Maven ne lit pas /usr/bin/java mais JAVA_HOME, que l'image fixe sur le JDK 21.
# La corriger dans /etc/profile.d ne suffit pas : la variable est héritée du
# processus parent. CLAUDE_ENV_FILE est le seul point d'entrée qui vaille ici.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=${JDK_HOME}"
    echo "export PATH=${JDK_HOME}/bin:\$PATH"
  } >> "$CLAUDE_ENV_FILE"
fi
export JAVA_HOME="$JDK_HOME"
export PATH="${JDK_HOME}/bin:$PATH"
log "JAVA_HOME=${JAVA_HOME} ($("$JDK_HOME/bin/java" -version 2>&1 | head -1))"

# ── 2. pytest, pour les tests des scripts d'entraînement ─────────────────────
# La CI installe pytest explicitement (job « training-scripts ») ; l'image ne
# l'embarque pas. Sans lui, `python -m pytest scripts/tests` échoue sur un
# « No module named pytest » qui ressemble à un problème de code.
if ! python3 -m pytest --version >/dev/null 2>&1; then
  log "Installation de pytest."
  python3 -m pip install --quiet --disable-pip-version-check pytest || \
    log "AVERTISSEMENT : pytest non installé ; les tests de scripts/ seront indisponibles."
fi

# ── 3. Dépendances du frontend ───────────────────────────────────────────────
# `npm install` plutôt que `npm ci` : l'état du conteneur est mis en cache après
# ce hook, et install réutilise ce qui est déjà là au lieu de tout réinstaller.
if [ -d "$PROJECT_DIR/frontend" ] && [ ! -d "$PROJECT_DIR/frontend/node_modules" ]; then
  log "Installation des dépendances du frontend."
  (cd "$PROJECT_DIR/frontend" && npm install --no-audit --no-fund --silent) || \
    log "AVERTISSEMENT : npm install a échoué ; les tests du frontend seront indisponibles."
fi

log "Préparation terminée."
