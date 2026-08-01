"""
Résolution du manifeste des modèles de base (`scripts/base_models.py`).

Le manifeste est la **source de vérité unique** du mapping alias → repo HuggingFace, partagée
avec le backend Java. Un adaptateur LoRA n'étant valide que sur le modèle de base exact qui l'a
entraîné, une divergence entre les deux lecteurs ne se voit qu'au moment où la fusion échoue —
donc après l'entraînement. Ces tests figent l'emplacement canonique et le contenu, en miroir de
`BaseModelCatalogTest` côté Java.
"""

import json
import os
import pathlib
import sys

SCRIPTS_DIR = pathlib.Path(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, str(SCRIPTS_DIR))

import base_models  # noqa: E402

BACKEND_RESOURCE = (SCRIPTS_DIR.parent / "backend" / "src" / "main" / "resources"
                    / "base_models.json")


def test_manifeste_resolu_dans_les_ressources_du_backend():
    """
    L'emplacement canonique est la ressource du backend, pas un fichier voisin des scripts.

    C'était l'inverse : le manifeste vivait dans scripts/ et backend/pom.xml déclarait une
    <resource> pointant sur ../scripts.
    """
    assert BACKEND_RESOURCE.is_file(), f"manifeste absent : {BACKEND_RESOURCE}"
    assert base_models.manifest_path() == BACKEND_RESOURCE


def test_surcharge_explicite_par_environnement(monkeypatch, tmp_path):
    ailleurs = tmp_path / "autre.json"
    ailleurs.write_text("{}", encoding="utf-8")
    monkeypatch.setenv("SPECTRA_BASE_MODELS_MANIFEST", str(ailleurs))
    assert base_models.manifest_path() == ailleurs


def test_alias_historiques_et_repos_inchanges():
    """Verrou anti-régression : changer un repo casserait les adaptateurs déjà entraînés."""
    assert base_models.load_base_models() == {
        "tinyllama": "TinyLlama/TinyLlama-1.1B-Chat-v1.0",
        "phi3": "microsoft/Phi-3-mini-4k-instruct",
        "mistral": "mistralai/Mistral-7B-Instruct-v0.3",
        "llama3": "meta-llama/Meta-Llama-3-8B-Instruct",
    }


def test_le_java_et_le_python_lisent_le_meme_fichier():
    """
    Le backend charge `base_models.json` au classpath depuis src/main/resources ; le script doit
    résoudre exactement ce fichier — sinon les deux catalogues peuvent diverger en silence.
    """
    resolved = base_models.manifest_path()
    assert resolved.parent == BACKEND_RESOURCE.parent
    manifest = json.loads(resolved.read_text(encoding="utf-8"))
    assert set(manifest["models"]) == set(base_models.load_base_models())


def test_manifeste_absent_nempeche_pas_lusage_autonome(tmp_path, capsys):
    """Repli documenté : mapping vide + avertissement, l'appelant utilise le repo HF brut."""
    assert base_models.load_base_models(tmp_path / "inexistant.json") == {}
    assert "AVERTISSEMENT" in capsys.readouterr().out


def test_entrees_sans_hfrepo_ignorees(tmp_path):
    bancal = tmp_path / "bancal.json"
    bancal.write_text(json.dumps({"models": {
        "bon": {"hfRepo": "org/bon"},
        "vide": {"hfRepo": ""},
        "absent": {"contextLength": 2048},
        "pas_un_objet": "org/xxx",
    }}), encoding="utf-8")
    assert base_models.load_base_models(bancal) == {"bon": "org/bon"}
