#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Script de lancement
# Usage: ./start.sh [--first-run] [--gpu] [--detach] [--trainer]
#
#   --first-run    Premier lancement tout-en-un : configuration initiale,
#                  téléchargement des modèles (embedding + chat), démarrage
#                  en arrière-plan puis ouverture du navigateur sur l'UI.
#   --gpu          Force la détection GPU (transmis à detect-env.sh).
#   --detach, -d   Démarre en arrière-plan et attend que les services
#                  soient prêts avant d'afficher le récapitulatif.
#   --trainer      Rend le fine-tuning exécutable : démarre le service
#                  spectra-trainer et branche l'API dessus. Sans cela, une
#                  soumission est refusée — l'image spectra-api est une JRE
#                  sans Python, elle ne peut pas lancer scripts/train.sh.
#                  L'image du trainer pèse plusieurs Go (torch) : le premier
#                  lancement est long.
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
TRAINER=""

# Parse des arguments
for arg in "$@"; do
    case "$arg" in
        --detach|-d)   DETACH="-d" ;;
        --gpu)         GPU_FLAG="--gpu" ;;
        --first-run)   FIRST_RUN=1; DETACH="-d" ;;
        --trainer)     TRAINER=1 ;;
    esac
done

# Lit la dernière affectation d'une clé dans .env. Compose fait de même pour interpoler
# ses variables ; le script doit lire le même fichier pour décider des mêmes choses.
env_value() {
    [[ -f .env ]] || return 0
    sed -n "s/^[[:space:]]*$1=[[:space:]]*//p" .env | tail -1 | tr -d '\r'
}

echo "╔══════════════════════════════════════╗"
echo "║        Spectra — Démarrage           ║"
echo "╚══════════════════════════════════════╝"

# 0. Premier lancement : setup complet (Java, répertoires, .env, modèles)
if [[ -n "$FIRST_RUN" ]]; then
    echo ""
    echo "► Premier lancement : configuration initiale + téléchargement des modèles..."
    # Pas de --download-reranker : le reranking étant actif par défaut, setup.sh récupère
    # son artefact de lui-même s'il manque, et traite l'échec comme une information et non
    # comme une erreur. Le passer ici en ferait une demande explicite, donc un échec
    # bloquant — et tout premier lancement passerait pour incomplet tant que l'artefact
    # n'est pas publié, alors que son absence n'a aucune conséquence fonctionnelle.
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

# 2 bis. Fine-tuning en conteneur.
#
# Deux réglages n'ont de sens qu'ENSEMBLE, et c'est exactement là que ça cassait quand on les
# posait à la main : le profil compose « trainer » démarre le service qui sait exécuter
# scripts/train.sh, et SPECTRA_FINE_TUNING_RUNNER=http dit à l'API de s'adresser à lui.
# N'en poser qu'un donne les deux pannes symétriques, toutes deux constatées à la soumission :
#   — profil sans runner : « script d'entraînement introuvable : /app/scripts/train.sh »,
#     l'API cherchant un script que son image (une JRE nue) ne contient pas ;
#   — runner sans profil : « service d'entraînement injoignable ».
# Les poser ici, au même endroit, supprime la classe d'erreur entière.
#
# Le shell l'emporte sur .env pour l'interpolation Compose : c'est la même règle de priorité
# qui est appliquée ici, sinon le script déciderait sur une valeur que Compose n'utilisera pas.
ENV_RUNNER="$(env_value SPECTRA_FINE_TUNING_RUNNER)"
if [[ -n "$TRAINER" || "${SPECTRA_FINE_TUNING_RUNNER:-$ENV_RUNNER}" == "http" ]]; then
    export SPECTRA_FINE_TUNING_RUNNER=http

    # Fusion plutôt qu'affectation : un COMPOSE_PROFILES déjà renseigné (reranker, kafka…)
    # serait sinon écrasé, et demander le trainer désactiverait silencieusement le reste.
    PROFILES="${COMPOSE_PROFILES:-$(env_value COMPOSE_PROFILES)}"
    case ",$PROFILES," in
        *,trainer,*) ;;
        *) PROFILES="${PROFILES:+$PROFILES,}trainer" ;;
    esac
    export COMPOSE_PROFILES="$PROFILES"

    echo ""
    echo "► Fine-tuning en conteneur activé"
    echo "  ✓ service spectra-trainer + SPECTRA_FINE_TUNING_RUNNER=http"
    echo "    Première fois : l'image embarque torch, comptez plusieurs Go et un long build."
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

    # Service d'entraînement (seulement s'il a été demandé). Le vérifier ici plutôt que de
    # laisser l'utilisateur le découvrir en soumettant un job : un trainer qui n'a pas
    # démarré rend le fine-tuning indisponible, et c'est une information qui a sa place
    # dans le récapitulatif de démarrage, pas dans un refus une heure plus tard.
    if [[ "${SPECTRA_FINE_TUNING_RUNNER:-}" == "http" ]]; then
        echo -n "  Trainer:      "
        for i in $(seq 1 30); do
            if curl -sf http://localhost:8004/health &>/dev/null; then
                echo "✓ prêt"
                break
            fi
            if [[ $i -eq 30 ]]; then echo "✗ timeout"; fi
            sleep 2
        done
    fi

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
    if [[ "${SPECTRA_FINE_TUNING_RUNNER:-}" == "http" ]]; then
        echo " Trainer     :  http://localhost:8004/health"
    else
        echo ""
        echo " Fine-tuning :  indisponible — relancez avec ./scripts/start.sh --trainer"
    fi
    echo ""
    echo " Arrêt       :  ./scripts/stop.sh"
    echo " Logs        :  ${COMPOSE[*]} logs -f"
    echo "══════════════════════════════════════"

    # 7. Premier lancement : ouvrir le navigateur sur l'UI (best effort)
    if [[ -n "$FIRST_RUN" ]]; then
        (xdg-open "http://localhost" 2>/dev/null || open "http://localhost" 2>/dev/null || true) &
    fi
fi
