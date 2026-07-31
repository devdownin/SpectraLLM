"""
Invariants du dimensionnement du serveur d'inférence (`scripts/lib/llm-sizing.sh`).

Ce calcul est de l'arithmétique pure, et il a été faux pendant longtemps sans que rien ne
puisse le révéler : `LLM_CONTEXT` est le contexte TOTAL du serveur, réparti entre les slots
parallèles, si bien qu'une requête ne voit que `LLM_CONTEXT / LLM_PARALLEL` tokens. L'ancienne
formule fixait le total selon la RAM tout en faisant croître le parallélisme avec les cœurs :
plus la machine était grosse, plus la fenêtre par requête rétrécissait — 512 tokens sur une
machine 32 Go / 16 cœurs, quand le RAG agentique en budgète 3000 à lui seul. Le symptôme, en
production, était un prompt système tronqué et un modèle répondant hors format ; jamais une
erreur nommant la cause.

D'où ces tests : ils vérifient la propriété qui compte (la fenêtre par requête), pas la valeur
d'une variable intermédiaire.
"""

import re
import subprocess
from pathlib import Path

import pytest

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
SIZING_LIB = SCRIPTS_DIR / "lib" / "llm-sizing.sh"
DETECT_ENV_SH = SCRIPTS_DIR / "detect-env.sh"
DETECT_ENV_BAT = SCRIPTS_DIR / "detect-env.bat"
AUTOSTART_SH = SCRIPTS_DIR / "llama-autostart.sh"

# Fenêtre par requête attendue à chaque palier de RAM (Mo → tokens). C'est le contrat
# fonctionnel : au-dessous de 2048 tokens, la classification de documents ne tient plus.
TIERS = [(32768, 8192), (16384, 4096), (8192, 2048), (0, 1024)]

CORE_COUNTS = [1, 2, 4, 8, 16, 32, 64]
RAM_SIZES = [1024, 2048, 4096, 8191, 8192, 12288, 16384, 24576, 32768, 65536, 131072]


def sizing(ram_mb, cores):
    """Invoque la bibliothèque et renvoie (parallel, total_context, slot_context)."""
    result = subprocess.run(
        ["bash", str(SIZING_LIB), str(ram_mb), str(cores)],
        capture_output=True, text=True, check=True,
    )
    values = dict(pair.split("=") for pair in result.stdout.split())
    return int(values["PARALLEL"]), int(values["CONTEXT"]), int(values["SLOT"])


def expected_slot(ram_mb):
    return next(slot for threshold, slot in TIERS if ram_mb >= threshold)


@pytest.mark.parametrize("ram_mb", RAM_SIZES)
@pytest.mark.parametrize("cores", CORE_COUNTS)
def test_window_per_request_matches_the_ram_tier(ram_mb, cores):
    """
    La fenêtre par requête ne dépend que de la RAM — jamais du nombre de cœurs.

    C'est exactement la régression d'origine : le parallélisme suivait les cœurs et rognait
    silencieusement la fenêtre. Ici, ajouter des cœurs peut ajouter des conversations
    simultanées, mais jamais réduire ce que chacune voit.
    """
    parallel, total, slot = sizing(ram_mb, cores)

    assert slot == expected_slot(ram_mb)
    assert total // parallel == slot


@pytest.mark.parametrize("ram_mb", RAM_SIZES)
@pytest.mark.parametrize("cores", CORE_COUNTS)
def test_total_context_is_the_product_of_slot_and_parallelism(ram_mb, cores):
    """LLM_CONTEXT doit rester exactement slot × parallélisme : c'est ce que llama.cpp répartit."""
    parallel, total, slot = sizing(ram_mb, cores)

    assert total == slot * parallel
    assert parallel >= 1


@pytest.mark.parametrize("cores", CORE_COUNTS)
def test_window_never_shrinks_when_ram_grows(cores):
    """Monotonie : plus de RAM ne doit jamais donner une fenêtre plus petite."""
    previous = 0
    for ram_mb in sorted(RAM_SIZES):
        _, _, slot = sizing(ram_mb, cores)
        assert slot >= previous, f"régression de fenêtre à {ram_mb} Mo"
        previous = slot


@pytest.mark.parametrize("ram_mb", RAM_SIZES)
def test_total_context_stays_within_the_memory_envelope(ram_mb):
    """
    Le total reste borné même sur une machine à 64 cœurs : le cache KV est proportionnel au
    contexte total, et le dépasser fait échouer le démarrage de llama-server (OOM), pas
    seulement ralentir.
    """
    envelope = {8192: 32768, 4096: 16384, 2048: 4096, 1024: 2048}[expected_slot(ram_mb)]

    for cores in CORE_COUNTS:
        _, total, _ = sizing(ram_mb, cores)
        assert total <= envelope


@pytest.mark.parametrize("ram_mb", RAM_SIZES)
@pytest.mark.parametrize("cores", CORE_COUNTS)
def test_parallelism_never_exceeds_half_the_cores_nor_eight(ram_mb, cores):
    """Le parallélisme reste plausible : pas plus d'un slot pour deux cœurs, et 8 au maximum."""
    parallel, _, _ = sizing(ram_mb, cores)

    assert 1 <= parallel <= 8
    assert parallel == 1 or parallel <= cores // 2


def test_a_documented_16gb_machine_can_hold_the_agentic_budget():
    """
    Cas concret, et raison d'être du correctif : sur la machine minimale documentée (16 Go),
    une requête doit pouvoir porter le budget du RAG agentique (3000 tokens) et l'extrait de
    classification (~2200 tokens). Avec l'ancien calcul et 16 cœurs, elle en avait 512.
    """
    _, _, slot = sizing(16384, 16)

    assert slot >= 3000


def test_library_rejects_a_malformed_invocation():
    """Un appel sans les deux arguments doit échouer bruyamment, pas produire un total vide."""
    result = subprocess.run(
        ["bash", str(SIZING_LIB), "16384"], capture_output=True, text=True,
    )

    assert result.returncode != 0
    assert "usage" in result.stderr.lower()


def test_detect_env_delegates_to_the_library_instead_of_recomputing():
    """
    detect-env.sh ne doit pas refaire le calcul dans son coin : une seule formule, testée une
    fois. Ce test tombe si quelqu'un ré-inline les paliers.
    """
    body = DETECT_ENV_SH.read_text(encoding="utf-8")

    assert "lib/llm-sizing.sh" in body
    assert "llm_sizing " in body
    assert "MAX_TOTAL_CONTEXT=" not in body


def test_windows_script_uses_the_same_tiers_as_the_library():
    """
    detect-env.bat ne peut pas sourcer du bash : ses paliers sont forcément dupliqués. Ce test
    est le garde-fou contre la dérive — corriger l'un sans l'autre donnerait deux
    dimensionnements pour une même machine, selon le système.
    """
    bat = DETECT_ENV_BAT.read_text(encoding="utf-8", errors="replace")

    # Palier par défaut (branche « else » du .sh), puis les seuils GEQ.
    default_slot = int(re.search(r"^set SLOT_CONTEXT=(\d+)", bat, re.M).group(1))
    default_total = int(re.search(r"^set MAX_TOTAL_CONTEXT=(\d+)", bat, re.M).group(1))
    thresholds = [
        (int(ram), int(slot), int(total))
        for ram, slot, total in re.findall(
            r"GEQ (\d+) \(\s*set SLOT_CONTEXT=(\d+)&\s*set MAX_TOTAL_CONTEXT=(\d+)", bat
        )
    ]
    bat_tiers = {ram: (slot, total) for ram, slot, total in thresholds}
    bat_tiers[0] = (default_slot, default_total)

    envelopes = {8192: 32768, 4096: 16384, 2048: 4096, 1024: 2048}
    expected = {ram: (slot, envelopes[slot]) for ram, slot in TIERS}

    assert bat_tiers == expected


def test_kubernetes_entrypoint_uses_the_same_tiers_as_the_library():
    """
    `llama-autostart.sh` est la QUATRIÈME implémentation de ce calcul — après la
    bibliothèque, `detect-env.bat` et `ResourceAdvisorService`. Il est l'entrypoint des
    images llama.cpp autonomes (`Dockerfile.llama`, `Dockerfile.llama.cuda`) et ne peut pas
    sourcer la bibliothèque : il doit fonctionner dans une image nue, sans le dépôt. Ses
    paliers du mode chat sont donc dupliqués ; ce test empêche qu'ils dérivent, comme pour
    le script Windows.
    """
    body = AUTOSTART_SH.read_text(encoding="utf-8")

    # Paliers du mode chat : la branche `embed` a son propre palier fixe, hors comparaison.
    tiers = {
        int(ram): int(slot)
        for ram, slot in re.findall(
            r"RAM_MB -ge (\d+) \];? then\s*\n\s*SLOT_CONTEXT=(\d+)", body
        )
    }
    # Le `else` final est le palier des petites machines.
    tiers[0] = int(re.search(r"else\s*\n\s*SLOT_CONTEXT=(\d+)\s*\nfi", body).group(1))

    assert tiers == dict(TIERS)


def test_kubernetes_entrypoint_derives_the_total_from_the_parallelism():
    """
    Le point qui rendait le calcul faux partout : `-c` est un TOTAL. Le script doit le
    déduire du parallélisme, sinon ajouter un slot rétrécit toutes les fenêtres.
    """
    body = AUTOSTART_SH.read_text(encoding="utf-8")

    assert "CONTEXT=$((SLOT_CONTEXT * PARALLELISM))" in body
