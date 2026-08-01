"""
Les scripts Windows doivent offrir les mêmes options que leurs jumeaux shell.

Six scripts `.bat` (~1 000 lignes) doublent leurs équivalents `.sh`. La duplication est
irréductible — batch ne source pas du bash — mais sa dérive ne l'est pas. Au moment d'écrire
ce test, `pipeline.bat` acceptait `--orpo` sans le déclarer dans son usage : un utilisateur
Windows lisant l'entête ignorait qu'une option existait.

**Ce que ce test couvre, et ce qu'il ne couvre pas.** Il compare les options déclarées dans
les lignes « Usage » — le contrat annoncé à l'utilisateur. Il ne compare pas les
comportements : deux scripts peuvent offrir les mêmes options et faire des choses
différentes. C'est précisément ce qui est arrivé à `stop.bat`, qui n'activait pas les profils
Compose et laissait tourner les services optionnels ; aucune comparaison d'interface ne
l'aurait révélé, il a fallu lire les deux scripts. Ce test réduit la surface de dérive, il ne
l'annule pas.
"""

import re
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent.parent

# Paires shell / batch censées offrir la même interface.
PAIRS = ["pipeline", "setup", "start", "build", "stop", "detect-env"]

# Options légitimement absentes d'un côté, avec leur raison.
KNOWN_ASYMMETRIES: dict[str, str] = {}


def usage_options(path: Path) -> set[str]:
    """Options `--x` déclarées dans la ligne « Usage » d'un script."""
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines()[:40]:
        stripped = line.lstrip("#:REM ").strip()
        if stripped.lower().startswith("usage"):
            return set(re.findall(r"--[a-z][a-z0-9-]*", stripped))
    return set()


@pytest.mark.parametrize("name", PAIRS)
def test_both_twins_declare_a_usage_line(name):
    """Sans ligne « Usage », la comparaison ci-dessous passerait à vide et ne prouverait rien."""
    for suffix in (".sh", ".bat"):
        script = SCRIPTS / f"{name}{suffix}"
        assert script.exists(), f"{script.name} manquant"
        assert usage_options(script) or name in KNOWN_ASYMMETRIES, (
            f"{script.name} ne déclare aucune option dans sa ligne « Usage »"
        )


@pytest.mark.parametrize("name", PAIRS)
def test_windows_and_shell_twins_offer_the_same_options(name):
    sh = usage_options(SCRIPTS / f"{name}.sh")
    bat = usage_options(SCRIPTS / f"{name}.bat")

    only_sh = sorted(sh - bat)
    only_bat = sorted(bat - sh)

    assert not (only_sh or only_bat), (
        f"{name}.sh et {name}.bat n'annoncent pas les mêmes options — "
        f"absentes du .bat : {only_sh or '—'} ; absentes du .sh : {only_bat or '—'}. "
        "Alignez les deux, ou documentez l'écart dans KNOWN_ASYMMETRIES."
    )


def test_declared_options_are_actually_parsed():
    """
    Une option annoncée mais non lue est pire qu'une option absente : l'utilisateur croit
    l'avoir activée. On vérifie que chaque option de la ligne « Usage » apparaît ailleurs
    dans le script, donc qu'elle y est au moins mentionnée.
    """
    unparsed = []
    for name in PAIRS:
        for suffix in (".sh", ".bat"):
            script = SCRIPTS / f"{name}{suffix}"
            body = script.read_text(encoding="utf-8", errors="replace")
            # Corps du script, entête d'usage exclu.
            lines = body.splitlines()
            head = next((i for i, l in enumerate(lines)
                         if l.lstrip("#:REM ").strip().lower().startswith("usage")), 0)
            rest = "\n".join(lines[head + 1:])
            for opt in usage_options(script):
                if opt not in rest:
                    unparsed.append(f"{script.name} annonce {opt} sans jamais le lire")

    assert not unparsed, "\n".join(unparsed)
