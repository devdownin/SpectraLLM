"""
Une extraction par `grep` ne doit jamais pouvoir interrompre un script en silence.

Sous `set -euo pipefail`, un `grep` sans correspondance fait échouer tout le pipeline. Quand ce
pipeline est le membre droit d'une affectation — `VAR="$(grep … | cut … )"` — `set -e`
**termine le script**, sans message, sans code d'erreur lisible, et sans exécuter le résumé
final ni le repli écrit juste en dessous.

Le défaut est traître pour deux raisons :

1. **Il est latent.** Tant que la clé cherchée figure dans `.env.example`, tout `.env` en
   contient une copie et le `grep` trouve. Le script casse uniquement chez qui a édité son
   `.env` à la main — ou dès qu'on lit une clé *optionnelle*.
2. **Il annule les gardes qui l'entourent.** `pipeline.sh` testait `[ -f ".env" ]` avant de
   lire (l'existence du fichier, pas celle de la clé), et fixait `RETRY_AFTER=5` à la ligne
   suivante quand l'en-tête manquait. Les deux protections étaient inatteignables : le script
   mourait avant.

Trois occurrences réelles au moment d'écrire ce test — `pipeline.sh` lignes 101, 151 et 205,
dont une sur le chemin par défaut (`SPECTRA_API_KEY` n'est pas dans `.env.example`) — plus une
quatrième déjà corrigée dans `setup.sh` (`read_env_var`).

**Ce que ce test ne couvre pas.** Il inspecte les affectations sur une ligne. Une substitution
de commande étalée sur plusieurs lignes lui échappe, comme lui échappe un `grep` appelé
indirectement. Il réduit la surface, il ne l'annule pas.
"""

import re
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent.parent

# Affectation dont le membre droit est une substitution de commande : VAR="$(…)" ou VAR=$(…).
ASSIGNMENT = re.compile(r'^\s*(?:local\s+|export\s+)?([A-Za-z_][A-Za-z0-9_]*)=("?)\$\((.*)\)\2\s*$')

# Commandes qui échouent légitimement quand elles ne trouvent rien, et dont l'échec doit donc
# être neutralisé plutôt que propagé.
FAILING_ON_NO_MATCH = ("grep", "rg", "find -name")

# Neutralisations acceptées : le pipeline ne peut plus faire échouer l'affectation.
TOLERATED = ("|| true", "|| echo", "||true", "|| :")


def shell_scripts():
    """Scripts dont `pipefail` rend un grep infructueux fatal."""
    for path in sorted(SCRIPTS.rglob("*.sh")):
        body = path.read_text(encoding="utf-8")
        # Sans pipefail, le pipeline renvoie le statut de sa DERNIÈRE commande (sort, awk, sed…),
        # qui réussit : le défaut n'existe pas. Ces scripts sont volontairement hors périmètre
        # plutôt que « corrigés » sans raison.
        if re.search(r"^set\s+-[a-z]*e[a-z]*\b", body, re.M) and "pipefail" in body:
            yield path, body


def risky_lines(path, body):
    """Affectations qui peuvent tuer le script si le motif n'est pas trouvé."""
    found = []
    for number, line in enumerate(body.splitlines(), 1):
        match = ASSIGNMENT.match(line)
        if not match:
            continue
        command = match.group(3)
        if not any(c in command for c in FAILING_ON_NO_MATCH):
            continue
        if any(t in command for t in TOLERATED):
            continue
        found.append((number, match.group(1), line.strip()))
    return found


def test_at_least_one_script_is_inspected():
    """Un filtre trop strict rendrait ce test vert sans rien avoir regardé."""
    inspected = list(shell_scripts())

    assert inspected, (
        "aucun script avec « set -e » et « pipefail » trouvé — le filtre de "
        "shell_scripts() ne correspond plus à la réalité du dépôt"
    )


@pytest.mark.parametrize("path,body", list(shell_scripts()), ids=lambda p: getattr(p, "name", ""))
def test_grep_extractions_cannot_abort_the_script(path, body):
    risky = risky_lines(path, body)

    assert not risky, (
        f"{path.relative_to(SCRIPTS.parent)} : extraction(s) pouvant interrompre le script "
        f"sans message si le motif est absent — "
        + "; ".join(f"ligne {n} ({var})" for n, var, _ in risky)
        + ". Terminez le pipeline par « || true » : la valeur vide est gérée par le code "
        "qui suit, l'arrêt brutal ne l'est jamais."
    )


def test_the_detector_actually_detects():
    """
    Sans cette vérification, une expression rationnelle devenue inopérante rendrait le test
    ci-dessus vert par construction — exactement la fausse assurance qu'il doit empêcher.
    """
    fake = Path("fictif.sh")
    vulnerable = 'API_KEY="$(grep -E \'^SPECTRA_API_KEY=\' .env | tail -n1 | cut -d= -f2-)"'

    assert risky_lines(fake, vulnerable), "le détecteur laisse passer le motif vulnérable"
    assert not risky_lines(fake, 'API_KEY="$(grep foo .env | cut -d= -f2- || true)"'), \
        "le détecteur signale une extraction pourtant neutralisée"
    assert not risky_lines(fake, 'SIZE=$(du -sh data | cut -f1)'), \
        "le détecteur signale une commande qui ne dépend d'aucune correspondance"
