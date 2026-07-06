"""
Spectra — Chargeur du manifeste UNIQUE des modèles de base (base_models.json).

Le mapping alias → repo HuggingFace vivait en triple exemplaire dans train_host.py,
export_gguf.py et export_lora_gguf.py, avec obligation de les garder identiques (un
adaptateur LoRA n'est valide que sur le modèle de base exact qui l'a entraîné). Il vit
désormais dans base_models.json — également embarqué au classpath du backend Java
(BaseModelCatalog) — et les trois scripts l'importent d'ici.
"""

import json
import pathlib

MANIFEST_PATH = pathlib.Path(__file__).resolve().parent / "base_models.json"


def load_base_models(path=MANIFEST_PATH):
    """Renvoie le mapping {alias: repo_hf} du manifeste (vide si illisible).

    Un manifeste absent ne doit pas empêcher un usage autonome des scripts :
    l'appelant retombe alors sur l'identifiant brut (repo HuggingFace complet).
    """
    try:
        with open(path, encoding="utf-8") as f:
            manifest = json.load(f)
        return {
            alias: entry["hfRepo"]
            for alias, entry in manifest.get("models", {}).items()
            if isinstance(entry, dict) and entry.get("hfRepo")
        }
    except (OSError, ValueError) as e:
        print(f"AVERTISSEMENT : manifeste {path} illisible ({e}) — "
              "seuls les repos HuggingFace complets seront résolus.")
        return {}
