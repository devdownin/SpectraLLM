#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Script de lancement
# Usage: ./start.sh [--first-run] [--gpu] [--detach] [--trainer] [--build] [--hub]
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
#   --build        Reconstruit les images avant de démarrer. Sans cette option,
#                  une image déjà construite est réutilisée telle quelle : après
#                  une modification du code, c'est l'ANCIENNE qui redémarre.
#   --hub          Démarre depuis les images PUBLIÉES sur Docker Hub au lieu de les
#                  construire : ni « mvn package », ni build Vite sur votre machine.
#                  Le premier démarrage passe de plusieurs minutes à un téléchargement,
#                  et cesse d'échouer derrière un réseau qui filtre Maven Central ou le
#                  registre npm. Incompatible avec --build, qui demande l'inverse.
#                  Version tirée : cf. SPECTRA_IMAGE_TAG (défaut épinglé dans l'overlay).
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Les scripts vivent dans scripts/ mais la stack (docker-compose via
# --project-directory ., data/, .env) est ancrée à la racine du dépôt.
cd "$SCRIPT_DIR/.."

# Invocation Compose : une seule définition pour start/stop/build/verify.
# shellcheck source=lib/compose.sh
source "$SCRIPT_DIR/lib/compose.sh"

bold()  { echo -e "\033[1m$*\033[0m"; }
green() { echo -e "\033[1;32m$*\033[0m"; }

DETACH=""
GPU_FLAG=""
FIRST_RUN=""
TRAINER=""
HUB=""
# Option de construction passée à `docker compose up` : « --build » pour reconstruire,
# « --no-build » en mode --hub (garde-fou : une image manquante doit échouer franchement
# au lieu d'être reconstruite en douce, ce qui annulerait tout l'intérêt du tirage).
BUILD_OPT=""

# Parse des arguments
for arg in "$@"; do
    case "$arg" in
        --detach|-d)   DETACH="-d" ;;
        --gpu)         GPU_FLAG="--gpu" ;;
        --first-run)   FIRST_RUN=1; DETACH="-d" ;;
        --trainer)     TRAINER=1 ;;
        --build)       BUILD_OPT="--build" ;;
        --hub)         HUB=1 ;;
    esac
done

# Les deux options demandent l'inverse l'une de l'autre. Les accepter ensemble ferait
# gagner la dernière lue — donc un comportement décidé par l'ordre des arguments, que
# rien n'annonce.
if [[ -n "$HUB" && "$BUILD_OPT" == "--build" ]]; then
    echo "Erreur : --hub tire les images publiées, --build les reconstruit. Choisissez." >&2
    exit 1
fi

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

# 2. Détection automatique de la configuration serveur.
# Rejoue la détection à CHAQUE démarrage : le matériel change (GPU branché, RAM libérée,
# machine différente pour un même dépôt) et le bloc auto de .env doit le suivre. Les
# réglages personnels, écrits sous ce bloc, sont préservés — cf. detect-env.sh.
echo ""
echo "► Détection de la configuration serveur..."
bash "$SCRIPT_DIR/detect-env.sh" $GPU_FLAG

# Construit le tableau COMPOSE, overlay GPU compris s'il y a lieu, à partir du .env
# que detect-env.sh vient d'écrire.
spectra_compose_init
[[ "$SPECTRA_GPU_OVERLAY" -eq 1 ]] && echo "  ✓ GPU activé → docker-compose.gpu.yml inclus"

# Overlay des images publiées, ajouté EN DERNIER : c'est lui qui doit l'emporter sur les
# précédents pour le champ `image:`.
if [[ -n "$HUB" ]]; then
    COMPOSE+=(-f "$SPECTRA_COMPOSE_HUB_FILE")
    echo "  ✓ Images publiées → docker-compose.hub.yml inclus (rien ne sera construit)"
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
ENV_RUNNER="$(spectra_env_value SPECTRA_FINE_TUNING_RUNNER)"
if [[ -n "$TRAINER" || "${SPECTRA_FINE_TUNING_RUNNER:-$ENV_RUNNER}" == "http" ]]; then
    export SPECTRA_FINE_TUNING_RUNNER=http
    spectra_add_profile trainer

    echo ""
    echo "► Fine-tuning en conteneur activé"
    echo "  ✓ service spectra-trainer + SPECTRA_FINE_TUNING_RUNNER=http"
    echo "    Première fois : l'image embarque torch, comptez plusieurs Go et un long build."
fi

# 3. Se procurer les images : les tirer (--hub) ou les construire.
if [[ -n "$HUB" ]]; then
    echo ""
    echo "► Tirage des images publiées..."
    # Les deux services que l'overlay couvre, nommément. Un `pull` sans argument
    # tenterait aussi les services profilés, qui n'ont pas d'image publiée : le message
    # d'erreur parlerait d'un service que l'utilisateur n'a pas demandé.
    "${COMPOSE[@]}" pull spectra-api frontend
    BUILD_OPT="--no-build"
elif [[ -z "$BUILD_OPT" ]] && ! "${COMPOSE[@]}" images -q spectra-api 2>/dev/null | grep -q .; then
    # On interroge Compose lui-même plutôt qu'un nom d'image codé en dur (qui dépend du
    # nom de projet dérivé du répertoire, p. ex. « spectrallm-spectra-api »).
    BUILD_OPT="--build"
    echo ""
    echo "► Image spectra-api non trouvée, elle sera construite au démarrage."
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
#
# `--wait` remplace les cinq boucles curl qui vivaient ici. Elles interrogeaient des URLs
# codées en dur alors que CHAQUE service déclare déjà son healthcheck dans le fichier
# Compose : deux définitions du « prêt » pour un même service, dont une seule faisait
# autorité. Elles ne couvraient d'ailleurs que quatre services sur neuf — un reranker ou
# un docparser en échec passait inaperçu jusqu'à la première requête — et un dépassement
# de délai n'affichait qu'un « ✗ timeout » cosmétique : le récapitulatif « Spectra est
# prêt ! » s'affichait quand même, en vert, sur une stack en panne.
echo ""
echo "► Démarrage des services Docker..."
STARTED=1
if [[ -n "$DETACH" ]]; then
    # --wait sort en erreur si un service n'atteint jamais l'état sain ; on ne veut pas
    # pour autant interrompre le script sous `set -e`, mais afficher l'état et la marche
    # à suivre. Le délai couvre le start_period le plus long de la stack (llm-chat, 120 s)
    # augmenté du chargement effectif d'un modèle 7B.
    "${COMPOSE[@]}" up -d --wait --wait-timeout 300 $BUILD_OPT || STARTED=0
else
    exec "${COMPOSE[@]}" up $BUILD_OPT
fi

if [[ "$STARTED" -eq 1 ]]; then
    echo "  ✓ Tous les services sont sains"
else
    echo ""
    bold "  ⚠ Un ou plusieurs services ne sont pas devenus sains dans le délai imparti."
    "${COMPOSE[@]}" ps
    echo ""
    echo "  Diagnostic : ${COMPOSE[*]} logs --tail=50"
    echo "  Un modèle GGUF absent de data/models/ est la cause la plus fréquente :"
    echo "  llm-chat et llm-embed attendent alors le fichier au lieu de servir."
fi

# 5. Résumé
echo ""
echo "══════════════════════════════════════"
[[ "$STARTED" -eq 1 ]] && green " Spectra est prêt !" || bold " Spectra a démarré partiellement."
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
[[ -n "$HUB" ]] && echo " Images      :  publiées (Docker Hub) — aucune construction locale"
echo " Arrêt       :  ./scripts/stop.sh"
echo " Logs        :  ${COMPOSE[*]} logs -f"
echo "══════════════════════════════════════"

# 6. Premier lancement : ouvrir le navigateur sur l'UI (best effort)
if [[ -n "$FIRST_RUN" && "$STARTED" -eq 1 ]]; then
    (xdg-open "http://localhost" 2>/dev/null || open "http://localhost" 2>/dev/null || true) &
fi

[[ "$STARTED" -eq 1 ]] || exit 1
