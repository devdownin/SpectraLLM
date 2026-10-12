"""
Le rattrapage d'après publication : l'overlay Docker Hub, CITATION.cff et les tags d'exemple
des pages Docker Hub suivent la version publiée, d'un geste et sans rien casser d'autre.
La v0.11.0 a montré ce qui arrive sans lui : trois fichiers en retard, découverts par la PR
suivante, qui n'y touchait pas.
"""

import importlib.util
import re
import shutil
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent.parent
spec = importlib.util.spec_from_file_location("release_followup", ROOT / "scripts/release_followup.py")
followup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(followup)


@pytest.fixture
def tree(tmp_path):
    for rel in (followup.OVERLAY, followup.CITATION, *followup.HUB_PAGES):
        target = tmp_path / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(ROOT / rel, target)
    return tmp_path


def test_a_release_moves_the_pin_the_citation_and_the_hub_examples(tree):
    changed = followup.apply(tree, "9.8.7", "2030-01-02")
    assert set(changed) == {followup.OVERLAY, followup.CITATION, *followup.HUB_PAGES}
    overlay = (tree / followup.OVERLAY).read_text()
    assert overlay.count("${SPECTRA_IMAGE_TAG:-9.8.7}") == 2
    citation = (tree / followup.CITATION).read_text()
    assert "version: 9.8.7\n" in citation and 'date-released: "2030-01-02"' in citation
    for page in followup.HUB_PAGES:
        text = (tree / page).read_text()
        assert "| `9.8` | The 9.8 series" in text and "| `9.8.7` | An exact version" in text


def test_a_second_run_changes_nothing(tree):
    followup.apply(tree, "9.8.7", "2030-01-02")
    assert followup.apply(tree, "9.8.7", "2030-01-02") == []


def test_a_prerelease_moves_nothing(tree):
    with pytest.raises(ValueError, match="X.Y.Z"):
        followup.apply(tree, "1.0.0-rc1", "2030-01-02")


def test_the_live_tree_already_names_its_own_version(tree):
    # La forme des fichiers réels : si l'un change sans que le script suive, ce test le dit
    # avant qu'une publication ne le découvre. Rejoué sur une copie, jamais sur l'arbre réel.
    citation = (ROOT / followup.CITATION).read_text()
    version = re.search(r"^version: (.+)$", citation, re.M).group(1)
    date = re.search(r'^date-released: "(.+)"$', citation, re.M).group(1)
    assert followup.apply(tree, version, date) == []
