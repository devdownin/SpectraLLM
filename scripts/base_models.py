"""
Spectra — Chargeur du manifeste UNIQUE des modèles de base (base_models.json).

Le mapping alias → repo HuggingFace vivait en triple exemplaire dans train_host.py,
export_gguf.py et export_lora_gguf.py, avec obligation de les garder identiques (un
adaptateur LoRA n'est valide que sur le modèle de base exact qui l'a entraîné). Il vit
désormais en **un seul endroit**.

**Où, et pourquoi là.** Le manifeste est une ressource du backend
(`backend/src/main/resources/base_models.json`) : c'est le composant qui l'expose par API
(`BaseModelCatalog`, `GET /api/fine-tuning/base-models`) et qui doit l'embarquer dans son jar.
Auparavant le fichier vivait dans `scripts/` et `backend/pom.xml` déclarait une `<resource>`
pointant sur `../scripts` — un module Maven qui lit hors de son propre répertoire. Le couplage
est inversé : ce sont ces scripts, périphériques et optionnels, qui vont chercher le manifeste
dans le backend.

Les scripts restant utilisables hors du dépôt (copiés seuls sur une machine d'entraînement),
plusieurs emplacements sont essayés, et un manifeste absent n'est pas une erreur fatale :
l'appelant retombe sur l'identifiant brut (repo HuggingFace complet).
"""

import json
import os
import pathlib

_SCRIPTS_DIR = pathlib.Path(__file__).resolve().parent

# Par ordre de priorité :
#   1. SPECTRA_BASE_MODELS_MANIFEST  — chemin explicite (machine d'entraînement isolée)
#   2. ressource du backend          — l'emplacement canonique dans le dépôt
#   3. fichier voisin                — copie autonome à côté des scripts
CANDIDATE_PATHS = [
    _SCRIPTS_DIR.parent / "backend" / "src" / "main" / "resources" / "base_models.json",
    _SCRIPTS_DIR / "base_models.json",
]


def manifest_path():
    """Premier emplacement de manifeste existant, ou le chemin canonique si aucun n'existe."""
    override = os.getenv("SPECTRA_BASE_MODELS_MANIFEST", "").strip()
    if override:
        return pathlib.Path(override)
    for candidate in CANDIDATE_PATHS:
        if candidate.is_file():
            return candidate
    return CANDIDATE_PATHS[0]


# Conservé pour compatibilité : des scripts tiers importaient MANIFEST_PATH.
MANIFEST_PATH = manifest_path()


def load_base_models(path=None):
    """Renvoie le mapping {alias: repo_hf} du manifeste (vide si illisible).

    Un manifeste absent ne doit pas empêcher un usage autonome des scripts :
    l'appelant retombe alors sur l'identifiant brut (repo HuggingFace complet).
    """
    resolved = pathlib.Path(path) if path is not None else manifest_path()
    try:
        with open(resolved, encoding="utf-8") as f:
            manifest = json.load(f)
        return {
            alias: entry["hfRepo"]
            for alias, entry in manifest.get("models", {}).items()
            if isinstance(entry, dict) and entry.get("hfRepo")
        }
    except (OSError, ValueError) as e:
        print(f"AVERTISSEMENT : manifeste {resolved} illisible ({e}) — "
              "seuls les repos HuggingFace complets seront résolus.")
        return {}
