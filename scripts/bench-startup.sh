#!/usr/bin/env bash
# =============================================================================
# bench-startup.sh — Mesure le temps de démarrage de la stack, images déjà construites.
#
# POURQUOI CE FICHIER EXISTE
# L'audit de déploiement (constat D11) a ajouté `start_interval: 3s` aux healthchecks, en
# calculant qu'il fait gagner jusqu'à 70 s : sans lui, Docker ne sonde que toutes les
# `interval` secondes même pendant le `start_period`, et `depends_on: service_healthy`
# sérialise la chaîne, donc les granularités s'additionnent.
#
# CE CHIFFRE N'A JAMAIS ÉTÉ MESURÉ. Trois tentatives via la CI ont échoué à l'isoler, et pour
# une raison structurelle : la durée du job E2E est dominée par la construction des images,
# qui variait de plusieurs minutes d'un run à l'autre. Quelques dizaines de secondes s'y
# noient. Les mesures observées — 3 min 34, 3 min 40, 4 min 12 — sont du bruit, dans les deux
# sens.
#
# Ce script isole ce qu'il faut isoler : il construit les images UNE fois, hors chronomètre,
# puis chronomètre uniquement `up -d --wait`, plusieurs fois, en repartant d'une stack arrêtée.
# C'est la seule façon honnête d'obtenir le chiffre.
#
# Usage :
#   ./scripts/bench-startup.sh              3 itérations (défaut)
#   ./scripts/bench-startup.sh 5            5 itérations
#
# Pour comparer AVEC et SANS start_interval, lancez-le une fois sur la branche courante, puis
# une fois sur un checkout où les lignes `start_interval:` sont retirées de
# deploy/docker/docker-compose.yml. Le script ne fait pas cette bascule lui-même : modifier le
# fichier Compose sous les pieds de l'utilisateur serait une mauvaise idée.
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

# shellcheck source=lib/compose.sh
source scripts/lib/compose.sh

ITERATIONS="${1:-3}"

if ! docker compose version >/dev/null 2>&1; then
    echo "docker compose est indisponible — rien à mesurer." >&2
    exit 1
fi

# `docker compose version` répond SANS démon : il n'interroge que la CLI. S'arrêter là
# laisserait le script afficher son en-tête, lancer la construction, et n'échouer qu'ensuite
# sur un message de bas niveau. C'est le démon qu'il faut interroger.
if ! docker info >/dev/null 2>&1; then
    echo "Aucun démon Docker joignable — cette mesure demande une machine qui exécute la stack." >&2
    exit 1
fi

COMPOSE=(docker compose --project-directory . -f "$SPECTRA_COMPOSE_FILE")

echo "── Configuration mesurée ──"
printf 'start_interval déclarés : %s\n' \
    "$(grep -cE '^\s+start_interval:' "$SPECTRA_COMPOSE_FILE")"
printf 'itérations              : %s\n\n' "$ITERATIONS"

echo "── Construction des images (hors chronomètre) ──"
if ! "${COMPOSE[@]}" build; then
    echo "Échec de la construction — mesure abandonnée." >&2
    exit 1
fi

# Un premier démarrage à blanc : il télécharge les images tierces et remplit le cache de
# pages. L'inclure dans la moyenne mesurerait le réseau, pas les healthchecks.
echo
echo "── Démarrage à blanc (non compté) ──"
"${COMPOSE[@]}" up -d --wait --wait-timeout 600 >/dev/null 2>&1
"${COMPOSE[@]}" down >/dev/null 2>&1

TIMES=()
for i in $(seq 1 "$ITERATIONS"); do
    echo
    echo "── Itération $i/$ITERATIONS ──"
    start=$(date +%s.%N)
    if ! "${COMPOSE[@]}" up -d --wait --wait-timeout 600 >/dev/null 2>&1; then
        echo "  démarrage en échec — itération ignorée"
        "${COMPOSE[@]}" down >/dev/null 2>&1
        continue
    fi
    end=$(date +%s.%N)
    elapsed=$(echo "$end - $start" | bc)
    TIMES+=("$elapsed")
    printf '  %.1f s\n' "$elapsed"
    "${COMPOSE[@]}" down >/dev/null 2>&1
done

echo
echo "══ Résultat ══"
if [ ${#TIMES[@]} -eq 0 ]; then
    echo "Aucune itération exploitable."
    exit 1
fi

printf '%s\n' "${TIMES[@]}" | awk '
    { t[NR] = $1; sum += $1 }
    END {
        n = NR
        asort(t)
        printf "itérations retenues : %d\n", n
        printf "moyenne             : %.1f s\n", sum / n
        printf "médiane             : %.1f s\n", (n % 2 ? t[(n+1)/2] : (t[n/2] + t[n/2+1]) / 2)
        printf "min / max           : %.1f s / %.1f s\n", t[1], t[n]
    }
' 2>/dev/null || {
    # awk sans asort (mawk) : moyenne seule, plutôt qu'un résultat faux.
    printf '%s\n' "${TIMES[@]}" | awk '{ s += $1 } END { printf "moyenne : %.1f s (sur %d)\n", s/NR, NR }'
}

echo
echo "Comparez cette valeur à celle obtenue sans start_interval pour trancher D11."
