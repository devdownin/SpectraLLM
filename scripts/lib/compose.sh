#!/usr/bin/env bash
# =============================================================================
# compose.sh — invocation Docker Compose partagée par les scripts de déploiement.
#
# POURQUOI CE FICHIER EXISTE
# La même commande — `docker compose --project-directory . -f deploy/docker/docker-compose.yml`
# — était réécrite dans start.sh, stop.sh, build.sh et verify.sh. Quatre copies, et elles
# avaient déjà divergé : start.sh ajoutait l'overlay GPU quand .env l'annonçait, stop.sh non.
# Un `down` ne voyait donc pas la même stack que le `up` correspondant.
#
# La liste des profils avait le même défaut sous une autre forme : stop.sh la codait en dur
# (« layout-parser,reranker,kafka,trainer »). Ajouter un profil au fichier Compose sans
# penser à cette ligne produisait un service que « stop » laissait tourner — panne
# silencieuse, l'utilisateur croyant tout avoir arrêté. La liste est désormais DEMANDÉE à
# Compose, qui est seul à en faire autorité.
#
# Usage : depuis un script ayant déjà fait `cd` vers la racine du dépôt,
#   source "$SCRIPT_DIR/lib/compose.sh"
#   spectra_compose_init          # remplit le tableau COMPOSE
#   "${COMPOSE[@]}" up -d
# =============================================================================

SPECTRA_COMPOSE_FILE="deploy/docker/docker-compose.yml"
SPECTRA_COMPOSE_GPU_FILE="deploy/docker/docker-compose.gpu.yml"
# Overlay réservé au job E2E (noms d'images attendus par le cache de couches). Aucun script
# ne le charge — il est déclaré ici pour que verify.sh puisse le valider comme les autres.
SPECTRA_COMPOSE_CI_FILE="deploy/docker/docker-compose.ci.yml"
# Overlay « images publiées sur Docker Hub » : démarre la pile sans rien construire. Chargé
# à la main (cf. docs/tech/docker-hub.fr.md), donc jamais par la CI ni par start.sh — d'où
# la même déclaration ici, pour que verify.sh en valide la syntaxe comme celle des autres.
SPECTRA_COMPOSE_HUB_FILE="deploy/docker/docker-compose.hub.yml"

# Lit la DERNIÈRE affectation d'une clé dans .env, sans sourcer le fichier.
#
# « Dernière » n'est pas un détail : c'est la règle qu'applique Compose lui-même en cas de
# clé répétée, et c'est elle qui permet au bloc auto-détecté de detect-env.sh de vivre en
# tête du fichier tout en laissant gagner ce que l'utilisateur écrit en dessous. Un script
# qui lirait la première affectation déciderait sur une valeur que Compose n'utilisera pas.
spectra_env_value() {
    [ -f .env ] || return 0
    sed -n "s/^[[:space:]]*$1=[[:space:]]*//p" .env | tail -1 | tr -d '\r'
}

# Remplit le tableau global COMPOSE. À appeler après le `cd` vers la racine.
# L'overlay GPU est ajouté si .env l'annonce — pour TOUS les appelants, `down` compris.
spectra_compose_init() {
    COMPOSE=(docker compose --project-directory . -f "$SPECTRA_COMPOSE_FILE")
    if [ "$(spectra_env_value SPECTRA_GPU_ENABLED)" = "true" ]; then
        COMPOSE+=(-f "$SPECTRA_COMPOSE_GPU_FILE")
        SPECTRA_GPU_OVERLAY=1
    else
        SPECTRA_GPU_OVERLAY=0
    fi
}

# Tous les profils déclarés dans le fichier Compose, séparés par des virgules.
# Sert à `down` : sans profil actif, Compose ignore les services optionnels.
spectra_compose_all_profiles() {
    docker compose --project-directory . -f "$SPECTRA_COMPOSE_FILE" config --profiles 2>/dev/null \
        | paste -sd, -
}

# Ajoute un profil à COMPOSE_PROFILES sans écraser ceux déjà demandés.
# L'affectation directe était le défaut : demander le trainer désactivait silencieusement
# les profils que l'utilisateur avait posés dans son environnement ou dans .env.
spectra_add_profile() {
    local profile="$1"
    local profiles="${COMPOSE_PROFILES:-$(spectra_env_value COMPOSE_PROFILES)}"
    case ",$profiles," in
        *",$profile,"*) ;;
        *) profiles="${profiles:+$profiles,}$profile" ;;
    esac
    export COMPOSE_PROFILES="$profiles"
}
