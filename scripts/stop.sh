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

# shellcheck source=lib/compose.sh
source "$SCRIPT_DIR/lib/compose.sh"

# Même invocation que start.sh, overlay GPU compris. Ce point comptait : « down » construit
# la liste des conteneurs à supprimer à partir des fichiers qu'on lui donne, et stop.sh
# n'incluait pas docker-compose.gpu.yml — il ne voyait donc pas exactement la stack que
# start.sh avait démarrée.
spectra_compose_init

echo "► Arrêt des services Spectra..."

# Activer TOUS les profils pour que « down » stoppe aussi les services optionnels
# (layout-parser, reranker, kafka, trainer) démarrés via --profile ; --remove-orphans nettoie
# les conteneurs restants du projet.
#
# La liste est DEMANDÉE à Compose au lieu d'être recopiée ici. La copie codée en dur
# fonctionnait tant que personne n'ajoutait de profil : le jour où c'est arrivé, le service
# correspondant survivait à « stop » — port et mémoire toujours pris, sans rien pour le
# signaler puisque la commande sortait en succès.
PROFILES="$(spectra_compose_all_profiles)"
export COMPOSE_PROFILES="$PROFILES"
echo "  profils couverts : ${PROFILES:-aucun}"

if [[ "${1:-}" == "--clean" ]]; then
    "${COMPOSE[@]}" down -v --remove-orphans
    echo "  ✓ Services arrêtés et volumes supprimés"
else
    "${COMPOSE[@]}" down --remove-orphans
    echo "  ✓ Services arrêtés (volumes conservés)"
fi
