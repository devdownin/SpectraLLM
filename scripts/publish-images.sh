#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Spectra — Publication des images vers le registre Docker Hub
# Usage: ./publish-images.sh [--tag VERSION] [--namespace NS] [--platforms LIST]
#                            [--include-profiled] [--latest] [--dry-run]
#
#   --tag VERSION       Version publiée (ex. v0.7.1 ou 0.7.1). Défaut : le tag git
#                       pointant sur HEAD, sinon « edge-<sha> ».
#   --namespace NS      Espace de noms Docker Hub. Défaut : $SPECTRA_IMAGE_NAMESPACE,
#                       sinon la valeur par défaut du dépôt (devdownin).
#   --platforms LIST    Architectures visées. Défaut : linux/amd64,linux/arm64
#                       (les services profilés restent en linux/amd64 seul).
#   --include-profiled  Publie aussi docparser, reranker et trainer. Plusieurs Go :
#                       comptez une heure et de l'espace disque.
#   --latest            Marque aussi les images « latest » et « <major>.<minor> ».
#                       Implicite quand --tag est une version (vX.Y[.Z]).
#   --dry-run           Construit tout, ne pousse rien. N'exige aucun identifiant.
#   --print-plan        N'affiche que le plan (images, tags, plateformes) et s'arrête.
#                       Ne touche ni à Docker ni au réseau : c'est ce qui rend les règles
#                       de nommage vérifiables par un test.
#
# PRÉREQUIS : `docker login` déjà effectué (jeton d'accès Docker Hub, portée Read & Write),
# sauf en --dry-run.
#
# POURQUOI CE SCRIPT EXISTE, PUISQUE LA CI PUBLIE DÉJÀ
# ----------------------------------------------------
# .github/workflows/docker-publish.yml est le chemin normal : il publie sur tag de version,
# de façon reproductible et traçable. Ce script sert aux deux cas qu'un workflow ne couvre
# pas — republier depuis un poste quand GitHub Actions est indisponible ou pas encore
# configuré, et VÉRIFIER avant de tagger que les images se construisent et se poussent
# vraiment, sans engager un tag de version pour l'apprendre.
#
# Les deux chemins produisent les mêmes noms d'image et les mêmes tags. C'est une
# duplication, et scripts/tests/test_docker_hub_images.py en surveille la dérive :
# un nom d'image qui ne serait plus le même des deux côtés y ferait échouer la CI.
# ────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Les contextes de construction sont relatifs à la racine du dépôt (le trainer embarque
# scripts/, hors de son propre répertoire) : on s'y ancre une fois pour toutes.
cd "$SCRIPT_DIR/.."

bold()  { echo -e "\033[1m$*\033[0m"; }
green() { echo -e "\033[1;32m$*\033[0m"; }
warn()  { echo -e "\033[33m$*\033[0m"; }
die()   { echo -e "\033[31mErreur : $*\033[0m" >&2; exit 1; }

# Valeur par défaut de l'espace de noms. DOIT rester alignée sur celle de
# deploy/docker/docker-compose.hub.yml : l'overlay tire ce que ce script pousse.
DEFAULT_NAMESPACE="devdownin"
DEFAULT_PLATFORMS="linux/amd64,linux/arm64"
# Les images profilées embarquent torch. Sous émulation QEMU, leur construction arm64
# prendrait des heures pour les rares utilisateurs concernés — qui ont tout intérêt à
# construire nativement chez eux.
PROFILED_PLATFORMS="linux/amd64"
# Nom du constructeur buildx dédié. Le pilote « docker » par défaut ne sait pas produire un
# manifeste multi-architecture : sans ce constructeur, le --push échoue tard, après la
# construction, sur un message qui n'oriente pas vers la cause.
BUILDER="spectra-publisher"

NAMESPACE="${SPECTRA_IMAGE_NAMESPACE:-$DEFAULT_NAMESPACE}"
PLATFORMS="$DEFAULT_PLATFORMS"
VERSION=""
INCLUDE_PROFILED=0
FORCE_LATEST=0
DRY_RUN=0
PRINT_PLAN=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)              VERSION="${2:-}"; shift 2 ;;
        --namespace)        NAMESPACE="${2:-}"; shift 2 ;;
        --platforms)        PLATFORMS="${2:-}"; shift 2 ;;
        --include-profiled) INCLUDE_PROFILED=1; shift ;;
        --latest)           FORCE_LATEST=1; shift ;;
        --dry-run)          DRY_RUN=1; shift ;;
        --print-plan)       PRINT_PLAN=1; shift ;;
        # L'entête d'usage EST l'aide : deux textes séparés divergent, et c'est toujours
        # celui que l'utilisateur lit qui devient faux. On s'arrête au premier bloc
        # d'explication interne, qui ne le concerne pas.
        -h|--help)          awk '/^# POURQUOI/{exit} NR>1{sub(/^# ?/,""); print}' "$0"; exit 0 ;;
        *)                  die "option inconnue : $1 (voir --help)" ;;
    esac
done

echo "╔══════════════════════════════════════╗"
echo "║   Spectra — Publication des images   ║"
echo "╚══════════════════════════════════════╝"

# ── Version et tags ───────────────────────────────────────────────────────────
if [[ -z "$VERSION" ]]; then
    # Le tag git qui pointe sur HEAD, s'il existe : publier « la version qu'on a sous la
    # main » est le cas courant, et le demander deux fois (git tag puis --tag) invite à
    # l'écart entre les deux.
    VERSION="$(git describe --exact-match --tags HEAD 2>/dev/null || true)"
    [[ -z "$VERSION" ]] && VERSION="edge-$(git rev-parse --short HEAD)"
fi
VERSION="${VERSION#v}"

# Mêmes règles que .github/workflows/docker-publish.yml, et
# scripts/tests/test_docker_hub_images.py vérifie qu'elles le restent :
#   0.7.1 → 0.7.1, 0.7, latest    (la série donne les correctifs sans la montée de version)
#   0.7   → 0.7, latest           (« 0.7 » ne se redonne pas lui-même)
#   1.0.0-rc1 / edge-<sha> → tel quel, JAMAIS latest : une pré-version ne doit pas devenir
#                            le défaut de ceux qui n'épinglent rien.
TAGS=("$VERSION")
if [[ "$VERSION" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
    # `cut -d. -f1,2` et non `${VERSION%.*}` : ce dernier retire le DERNIER segment, donc
    # transforme « 0.7 » en « 0 » — un tag de série majeure que personne n'a demandé.
    SERIES="$(printf '%s' "$VERSION" | cut -d. -f1,2)"
    [[ "$SERIES" != "$VERSION" ]] && TAGS+=("$SERIES")
    TAGS+=("latest")
elif [[ "$FORCE_LATEST" -eq 1 ]]; then
    TAGS+=("latest")
fi

if ! [[ "$NAMESPACE" =~ ^[a-z0-9][a-z0-9_-]{1,254}$ ]]; then
    die "espace de noms Docker Hub invalide : « $NAMESPACE » (minuscules, chiffres, « - » et « _ »)."
fi

# ── Images ────────────────────────────────────────────────────────────────────
# Format : nom|contexte|dockerfile|plateformes|build-args
IMAGES=(
    "spectra-api|.|deploy/docker/Dockerfile|$PLATFORMS|"
    "spectra-frontend|frontend|frontend/Dockerfile|$PLATFORMS|"
)
if [[ "$INCLUDE_PROFILED" -eq 1 ]]; then
    IMAGES+=(
        "spectra-docparser|services/docparser|services/docparser/Dockerfile|$PROFILED_PLATFORMS|USE_DOCLING=false"
        "spectra-reranker|services/reranker|services/reranker/Dockerfile|$PROFILED_PLATFORMS|RERANKER_MODEL=cross-encoder/mmarco-mMiniLMv2-L12-H384-v1"
        "spectra-trainer|.|services/trainer/Dockerfile|$PROFILED_PLATFORMS|"
    )
fi

echo ""
bold "► Plan de publication"
echo "  espace de noms : $NAMESPACE"
echo "  tags           : ${TAGS[*]}"
echo "  plateformes    : $PLATFORMS"
for entry in "${IMAGES[@]}"; do
    echo "  image          : $NAMESPACE/${entry%%|*}"
done
[[ "$DRY_RUN" -eq 1 ]] && warn "  MODE dry-run — rien ne sera poussé."

if [[ "$PRINT_PLAN" -eq 1 ]]; then
    echo ""
    echo "(--print-plan : rien d'autre ne sera fait)"
    exit 0
fi

# ── Prérequis ─────────────────────────────────────────────────────────────────
command -v docker >/dev/null 2>&1 || die "docker est introuvable."
docker buildx version >/dev/null 2>&1 || die "docker buildx est introuvable (Docker Engine 23+)."

if [[ "$DRY_RUN" -eq 0 ]]; then
    # Vérifié AVANT de construire : découvrir l'absence d'identifiants après quarante
    # minutes de build est la seule chose plus coûteuse que de la découvrir tout de suite.
    if ! grep -q 'index.docker.io\|"auths"' "${DOCKER_CONFIG:-$HOME/.docker}/config.json" 2>/dev/null; then
        warn ""
        warn "  Aucun identifiant Docker Hub détecté dans ${DOCKER_CONFIG:-$HOME/.docker}/config.json."
        warn "  Lancez « docker login -u <compte> » (jeton d'accès, portée Read & Write) avant de publier."
        die "authentification requise (ou utilisez --dry-run)."
    fi
fi

echo ""
bold "► Constructeur buildx ($BUILDER)"
if docker buildx inspect "$BUILDER" >/dev/null 2>&1; then
    docker buildx use "$BUILDER"
    echo "  ✓ réutilisé"
else
    docker buildx create --name "$BUILDER" --driver docker-container --use >/dev/null
    echo "  ✓ créé (pilote docker-container — requis par le multi-architecture)"
fi
docker buildx inspect --bootstrap >/dev/null

# ── Construction et poussée ───────────────────────────────────────────────────
FAILED=()
for entry in "${IMAGES[@]}"; do
    IFS='|' read -r name context dockerfile platforms build_args <<< "$entry"

    args=(buildx build "$context" --file "$dockerfile" --platform "$platforms")
    for tag in "${TAGS[@]}"; do
        args+=(--tag "$NAMESPACE/$name:$tag")
    done
    [[ -n "$build_args" ]] && args+=(--build-arg "$build_args")
    args+=(--label "org.opencontainers.image.version=$VERSION")
    args+=(--label "org.opencontainers.image.source=https://github.com/devdownin/SpectraLLM")
    args+=(--label "org.opencontainers.image.licenses=AGPL-3.0-or-later")
    args+=(--label "org.opencontainers.image.revision=$(git rev-parse HEAD)")

    if [[ "$DRY_RUN" -eq 1 ]]; then
        # Ni --push ni --load : une image multi-architecture ne peut pas être chargée dans
        # le magasin local (il n'accepte qu'un manifeste mono-arch). Le résultat reste dans
        # le cache du constructeur, ce qui suffit : on vérifie ici qu'elle SE CONSTRUIT.
        echo ""
        bold "► [dry-run] $NAMESPACE/$name"
    else
        echo ""
        bold "► $NAMESPACE/$name"
        args+=(--push)
    fi

    if docker "${args[@]}"; then
        green "  ✓ $name"
    else
        warn "  ✗ $name"
        # On continue : savoir QUELLES images échouent vaut mieux que s'arrêter à la
        # première — et une image publiée pendant qu'une autre casse est un état à
        # connaître, pas à masquer.
        FAILED+=("$name")
    fi
done

echo ""
if [[ ${#FAILED[@]} -gt 0 ]]; then
    die "image(s) en échec : ${FAILED[*]}"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
    green "✓ Toutes les images se construisent (rien n'a été poussé)."
else
    green "✓ Publication terminée."
    echo ""
    echo "  Démarrer depuis ces images, sans rien construire :"
    echo "    SPECTRA_IMAGE_NAMESPACE=$NAMESPACE SPECTRA_IMAGE_TAG=$VERSION \\"
    echo "      docker compose --project-directory . \\"
    echo "        -f deploy/docker/docker-compose.yml -f deploy/docker/docker-compose.hub.yml \\"
    echo "        up -d --wait --no-build"
fi
