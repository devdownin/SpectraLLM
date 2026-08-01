#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Spectra — Script de configuration initiale (Linux / macOS)
#
# Usage : ./setup.sh [--download-embed] [--download-chat] [--download-reranker]
#
#   --download-embed     Télécharge nomic-embed-text (~81 Mo) si absent
#   --download-chat      Télécharge Qwen2.5-7B-Instruct Q4_K_M (~4.7 Go) si absent
#   --download-reranker  Télécharge l'artefact ONNX du reranker (~0,5 Go) si absent.
#                        Optionnel : le reranking est désactivé par défaut.
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail
# Les scripts vivent dans scripts/ mais opèrent sur la racine du dépôt
# (data/, .env, .env.example, docker-compose via --project-directory .).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

DOWNLOAD_EMBED=0
DOWNLOAD_CHAT=0
DOWNLOAD_RERANKER=0
ERRORS=0

for arg in "$@"; do
  case "$arg" in
    --download-embed)    DOWNLOAD_EMBED=1    ;;
    --download-chat)     DOWNLOAD_CHAT=1     ;;
    --download-reranker) DOWNLOAD_RERANKER=1 ;;
  esac
done

green()  { echo -e "\033[32m$*\033[0m"; }
yellow() { echo -e "\033[33m$*\033[0m"; }
red()    { echo -e "\033[31m$*\033[0m"; }

# Lit une variable depuis .env (sans sourcer le fichier), guillemets retirés.
# Renvoie une chaîne vide — et un succès — si la clé est absente.
#
# Le « || true » n'est pas cosmétique : sous « set -euo pipefail », un grep sans
# correspondance fait échouer tout le pipeline, et une affectation
# « VAR="$(read_env_var ABSENTE)" » interrompait alors setup.sh sans le moindre message,
# résumé final compris. Le défaut était latent tant que la seule clé lue
# (LLM_CHAT_MODEL_FILE) figurait dans .env.example ; il devient systématique dès qu'on lit
# une clé optionnelle.
read_env_var() {
  local key="$1"
  [ -f ".env" ] || return 0
  grep -E "^${key}=" .env | tail -n1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' || true
}

# Insère ou met à jour une variable dans .env.
set_env_var() {
  local key="$1" val="$2" tmp
  [ -f ".env" ] || touch ".env"
  if grep -qE "^${key}=" ".env"; then
    tmp="$(mktemp)"
    grep -vE "^${key}=" ".env" > "$tmp"
    printf '%s=%s\n' "$key" "$val" >> "$tmp"
    mv "$tmp" ".env"
  else
    printf '%s=%s\n' "$key" "$val" >> ".env"
  fi
}

echo "======================================"
echo "  Spectra — Configuration initiale"
echo "======================================"
echo

# ── 1. Java ───────────────────────────────────────────────────────────────
echo "> [1/7] Vérification de Java 25..."
if [ -f "$SCRIPT_DIR/setup-java.sh" ]; then
  if ! "$SCRIPT_DIR/setup-java.sh"; then
    ERRORS=$((ERRORS + 1))
  fi
fi

# ── 2. Docker ─────────────────────────────────────────────────────────────
echo
echo "> [2/7] Vérification de Docker..."
if ! docker info > /dev/null 2>&1; then
  red "  [ERREUR] Docker n'est pas démarré ou n'est pas installé."
  echo "  Installez Docker Desktop : https://www.docker.com/products/docker-desktop"
  ERRORS=$((ERRORS + 1))
else
  green "  [OK] $(docker --version)"
fi

# ── 3. Répertoires de données ──────────────────────────────────────────────
echo
echo "> [3/7] Création des répertoires de données..."
for dir in data/documents data/dataset data/fine-tuning data/fine-tuning/merged data/models data/source; do
  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
    echo "  [CRÉÉ] $dir"
  else
    green "  [OK]   $dir"
  fi
done

# ── 4. Fichier .env ────────────────────────────────────────────────────────
echo
echo "> [4/7] Fichier de configuration .env..."
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

# ── 5. Modèle d'embedding ─────────────────────────────────────────────────
echo
echo "> [5/7] Modèle d'embedding (data/models/embed.gguf)..."
if [ -f "data/models/embed.gguf" ]; then
  SIZE=$(du -sh data/models/embed.gguf | cut -f1)
  green "  [OK] embed.gguf présent — $SIZE"
else
  if [ "$DOWNLOAD_EMBED" -eq 1 ]; then
    echo "  Téléchargement de nomic-embed-text-v1.5.Q4_K_M.gguf (~81 Mo)..."
    # --fail : sortir en erreur sur 404/5xx au lieu d'enregistrer une page HTML d'erreur.
    curl -L --fail --progress-bar \
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

# ── 6. Modèle de chat ─────────────────────────────────────────────────────
# Le modèle doit résider dans data/models/ sous le nom que la stack Docker lit
# (data/models/${LLM_CHAT_MODEL_FILE}), sinon model-init / llm-chat ne le trouvent pas.
CHAT_DOWNLOAD_NAME="Qwen2.5-7B-Instruct-Q4_K_M.gguf"
CHAT_MODEL_FILE="$(read_env_var LLM_CHAT_MODEL_FILE)"
CHAT_MODEL_FILE="${CHAT_MODEL_FILE:-$CHAT_DOWNLOAD_NAME}"
CHAT_MODEL_PATH="data/models/$CHAT_MODEL_FILE"
echo
echo "> [6/7] Modèle de chat ($CHAT_MODEL_PATH)..."
if [ -f "$CHAT_MODEL_PATH" ]; then
  SIZE=$(du -sh "$CHAT_MODEL_PATH" | cut -f1)
  green "  [OK] $CHAT_MODEL_FILE présent — $SIZE"
  set_env_var LLM_CHAT_MODEL_FILE "$CHAT_MODEL_FILE"
else
  if [ "$DOWNLOAD_CHAT" -eq 1 ]; then
    # Téléchargement dans data/models/ sous le nom attendu par la stack.
    CHAT_MODEL_FILE="$CHAT_DOWNLOAD_NAME"
    CHAT_MODEL_PATH="data/models/$CHAT_MODEL_FILE"
    echo "  Téléchargement de $CHAT_DOWNLOAD_NAME (~4.7 Go)..."
    echo "  (cela peut prendre plusieurs minutes selon votre connexion)"
    curl -L --fail --progress-bar \
      "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf" \
      -o "$CHAT_MODEL_PATH"
    green "  [OK] $CHAT_MODEL_FILE téléchargé"
    # Aligner .env pour que la stack Docker charge bien ce fichier.
    set_env_var LLM_CHAT_MODEL_FILE "$CHAT_MODEL_FILE"
    echo "  LLM_CHAT_MODEL_FILE=$CHAT_MODEL_FILE écrit dans .env"
    # Aucune réécriture d'alias : ce script télécharge désormais le modèle PAR DÉFAUT
    # du projet, dont l'alias par défaut décrit déjà correctement le fichier obtenu.
    # (Auparavant il fournissait un Phi-3.5 et devait corriger l'étiquette.)
  else
    yellow "  [MANQUANT] $CHAT_MODEL_PATH absent"
    echo
    echo "  Option 1 — Téléchargement automatique (Qwen2.5-7B-Instruct ~4.7 Go) :"
    echo "    ./setup.sh --download-chat"
    echo
    echo "  Option 2 — Tout modèle GGUF instruction-tuned fonctionne :"
    echo "    placez votre fichier dans data/models/ et renseignez"
    echo "    LLM_CHAT_MODEL_FILE=<nom-du-fichier.gguf> dans .env"
    ERRORS=$((ERRORS + 1))
  fi
fi

# ── 7. Artefact ONNX du reranker ──────────────────────────────────────────
# Le reranking est ACTIF par défaut (spectra.reranker.enabled=true, moteur onnx) : son
# artefact fait donc partie de la pile par défaut, et il est récupéré s'il manque — sans
# qu'aucune option ne soit nécessaire. --download-reranker ne sert plus qu'à le forcer
# quand le reranking a été désactivé.
#
# Défaut d'URL : un asset de release DU PROJET, et non un dépôt tiers. Le modèle
# multilingue par défaut (mmarco-mMiniLMv2) n'étant pas publié au format ONNX en amont,
# l'artefact est produit une fois, hors ligne, et publié ici (cf.
# docs/process/plan-migration-java.fr.md, décision D1). Un tag dédié — et non la version
# applicative — parce que l'artefact ne change que si le MODÈLE change.
RERANKER_ONNX_URL_DEFAULT="https://github.com/devdownin/SpectraLLM/releases/download/reranker-onnx-v1"
RERANKER_DIR="data/models/reranker"
RERANKER_ONNX_URL="${SPECTRA_RERANKER_ONNX_URL:-$(read_env_var SPECTRA_RERANKER_ONNX_URL)}"
RERANKER_ONNX_URL="${RERANKER_ONNX_URL:-$RERANKER_ONNX_URL_DEFAULT}"

# Le reranking est-il attendu ? Absent de .env = défaut applicatif, donc actif.
RERANKER_ENABLED="$(read_env_var SPECTRA_RERANKER_ENABLED)"
RERANKER_ENABLED="${RERANKER_ENABLED:-true}"

echo
echo "> [7/7] Artefact ONNX du reranker ($RERANKER_DIR)..."
if [ -f "$RERANKER_DIR/model.onnx" ] && [ -f "$RERANKER_DIR/tokenizer.json" ]; then
  SIZE=$(du -sh "$RERANKER_DIR" | cut -f1)
  green "  [OK] artefact présent — $SIZE"
elif [ "$DOWNLOAD_RERANKER" -eq 1 ] || [ "$RERANKER_ENABLED" = "true" ]; then
    mkdir -p "$RERANKER_DIR"
    echo "  Récupération de l'artefact (~0,5 Go) depuis :"
    echo "    $RERANKER_ONNX_URL"
    RERANKER_OK=1
    for f in model.onnx tokenizer.json; do
      # --fail : une 404 doit échouer, pas laisser une page HTML nommée model.onnx —
      # ONNX Runtime ne la rejetterait qu'au premier rerank, longtemps après le setup.
      if ! curl -L --fail --progress-bar "${RERANKER_ONNX_URL%/}/$f" -o "$RERANKER_DIR/$f"; then
        rm -f "$RERANKER_DIR/$f"
        RERANKER_OK=0
      fi
    done
    if [ "$RERANKER_OK" -eq 1 ]; then
      green "  [OK] artefact téléchargé"
    elif [ "$DOWNLOAD_RERANKER" -eq 1 ]; then
      # Demande EXPLICITE : l'échec doit être une erreur, l'utilisateur l'a réclamé.
      red "  [ERREUR] Échec du téléchargement de l'artefact."
      echo "  L'URL doit désigner le RÉPERTOIRE contenant model.onnx et tokenizer.json,"
      echo "  pas l'un des deux fichiers. Vérifiez SPECTRA_RERANKER_ONNX_URL."
      ERRORS=$((ERRORS + 1))
    else
      # Récupération AUTOMATIQUE : son échec ne doit pas faire échouer l'installation.
      # Le reranking est un raffinement du RAG, pas une dépendance : sans artefact, le
      # backend le déclare indisponible et sert les requêtes à l'identique. Compter une
      # erreur ici afficherait « Configuration incomplète » alors que rien ne l'est.
      yellow "  [INFO] artefact indisponible — le RAG fonctionnera sans reranking."
      echo "  Sans conséquence : /api/status en donnera la cause, et les réponses sont"
      echo "  servies dans l'ordre vectoriel. Pour réessayer ou pointer une autre source :"
      echo "    SPECTRA_RERANKER_ONNX_URL=<url> ./scripts/setup.sh --download-reranker"
      echo "  Pour produire l'artefact hors ligne : docs/process/audit-python-java.fr.md (§6.3 bis)."
    fi
else
  # Seul cas restant : artefact absent, reranking explicitement désactivé, pas de demande
  # explicite. Ne rien télécharger — l'utilisateur a coupé la fonctionnalité.
  green "  [OK] non requis — SPECTRA_RERANKER_ENABLED=$RERANKER_ENABLED"
  echo "  Pour récupérer l'artefact malgré tout : ./scripts/setup.sh --download-reranker"
fi

# ── Résumé ─────────────────────────────────────────────────────────────────
echo
echo "======================================"
if [ "$ERRORS" -eq 0 ]; then
  green "  [OK] Configuration terminée — tout est en place !"
  echo
  echo "  Pour compiler (nécessite Java 25 local) :"
  echo "    ./scripts/build.sh"
  echo
  echo "  Pour démarrer Spectra :"
  echo "    ./scripts/start.sh"
  echo
  echo "  Pour tester avec des exemples :"
  echo "    bash scripts/adddoc.sh examples"
else
  red "  [!] Configuration incomplète — $ERRORS élément(s) à corriger."
  echo "  Relancez ./setup.sh après avoir résolu les problèmes ci-dessus."
fi
echo "======================================"
echo
