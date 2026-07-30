"""
Invariants de l'approvisionnement des convertisseurs GGUF (`scripts/llama_cpp_convert.py`).

Aucun accès réseau : ce qui compte ici est vérifiable hors ligne — que la révision soit
**épinglée** et jamais `master`, qu'elle reste alignée sur l'image llama.cpp servie, et que le
cache ne réutilise pas une copie tirée d'une autre révision. C'est précisément ce que l'ancien
code ne garantissait pas, et son échec était silencieux : l'export réussissait, avec un
convertisseur différent d'une semaine à l'autre.
"""

import os
import pathlib
import re
import sys

import pytest

SCRIPTS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_DIR)

import llama_cpp_convert as lcc  # noqa: E402


def test_revision_par_defaut_est_epinglee():
    """Un tag de release (bNNNN) ou un SHA — jamais une branche mobile."""
    assert lcc.DEFAULT_REVISION not in ("master", "main", "HEAD")
    assert re.fullmatch(r"b\d+|[0-9a-f]{7,40}", lcc.DEFAULT_REVISION), lcc.DEFAULT_REVISION


def test_revision_alignee_sur_image_llama_cpp_du_compose():
    """
    La révision doit suivre le tag de l'image qui SERT les GGUF produits.

    Sans ce test, les deux valeurs dérivent : on convertirait avec un llama.cpp et on servirait
    avec un autre, ce qui est exactement le mode de défaillance que l'épinglage vise à éviter.
    """
    compose = pathlib.Path(SCRIPTS_DIR).parent / "deploy" / "docker" / "docker-compose.yml"
    text = compose.read_text(encoding="utf-8")
    tags = set(re.findall(r"LLAMA_CPP_IMAGE_TAG:-server-(\S+?)\}", text))
    assert tags, "tag d'image llama.cpp introuvable dans docker-compose.yml"
    assert tags == {lcc.DEFAULT_REVISION}, (
        f"docker-compose épingle {tags}, llama_cpp_convert épingle "
        f"{lcc.DEFAULT_REVISION} — les deux doivent évoluer ensemble")


def test_url_construite_sur_le_depot_canonique_et_la_revision():
    url = lcc.script_url("convert_hf_to_gguf.py", "b1234")
    assert url == ("https://raw.githubusercontent.com/ggml-org/llama.cpp/"
                   "b1234/convert_hf_to_gguf.py")


def test_revision_surchargeable_par_environnement(monkeypatch):
    monkeypatch.setenv("LLAMA_CPP_REVISION", "deadbeef")
    assert lcc.pinned_revision() == "deadbeef"


@pytest.mark.parametrize("valeur", ["", "   "])
def test_surcharge_vide_retombe_sur_le_defaut(monkeypatch, valeur):
    monkeypatch.setenv("LLAMA_CPP_REVISION", valeur)
    assert lcc.pinned_revision() == lcc.DEFAULT_REVISION


def test_cache_reutilise_la_meme_revision_sans_telecharger(tmp_path, monkeypatch):
    def interdit(*_args, **_kwargs):
        pytest.fail("aucun téléchargement ne doit avoir lieu quand le cache est valide")

    monkeypatch.setattr(lcc.urllib.request, "urlretrieve", interdit)
    monkeypatch.setattr(lcc, "find_in_package", lambda _name: None)
    (tmp_path / "convert_hf_to_gguf-b9999.py").write_text("# cache", encoding="utf-8")

    resolved = lcc.resolve("convert_hf_to_gguf.py", tmp_path, revision="b9999")
    assert resolved == str(tmp_path / "convert_hf_to_gguf-b9999.py")


def test_cache_dune_autre_revision_nest_pas_reutilise(tmp_path, monkeypatch):
    """
    Le nom de cache porte la révision : une copie de `master` laissée par l'ancienne version du
    script (nom fixe `convert_hf_to_gguf.py`) ne doit plus geler la conversion.
    """
    telecharges = []
    monkeypatch.setattr(lcc.urllib.request, "urlretrieve",
                        lambda url, dest: telecharges.append((url, str(dest))))
    monkeypatch.setattr(lcc, "find_in_package", lambda _name: None)
    (tmp_path / "convert_hf_to_gguf.py").write_text("# ancienne copie master", encoding="utf-8")

    lcc.resolve("convert_hf_to_gguf.py", tmp_path, revision="b9999")

    assert len(telecharges) == 1
    url, dest = telecharges[0]
    assert "b9999" in url and "master" not in url
    assert dest.endswith("convert_hf_to_gguf-b9999.py")


def test_paquet_installe_a_la_priorite(tmp_path, monkeypatch):
    monkeypatch.setattr(lcc, "find_in_package", lambda _name: "/opt/llama_cpp/convert_x.py")
    monkeypatch.setattr(lcc.urllib.request, "urlretrieve",
                        lambda *_a, **_k: pytest.fail("le paquet local devait suffire"))
    assert lcc.resolve("convert_x.py", tmp_path) == "/opt/llama_cpp/convert_x.py"
