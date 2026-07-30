"""
Spectra — Localisation des convertisseurs GGUF de llama.cpp, à révision **épinglée**.

`export_gguf.py` et `export_lora_gguf.py` téléchargeaient chacun leur convertisseur depuis la
branche `master` de llama.cpp, au moment de l'exécution. Deux conséquences, invisibles depuis
l'interface :

* **Non reproductible.** Deux exports faits à quelques semaines d'intervalle n'utilisaient pas
  le même code de conversion, et rien ne le signalait — alors que le convertisseur décide de la
  quantification, du nommage des tenseurs et des métadonnées (dont le gabarit de conversation)
  écrits dans le GGUF.
* **Divergence avec le runtime.** Le GGUF produit doit être lisible par le `llama-server` qui le
  sert, dont l'image est elle-même épinglée (`LLAMA_CPP_IMAGE_TAG`, défaut `server-b9828`).
  Convertir avec `master` et servir avec un build figé, c'est faire dépendre le résultat de la
  date de l'export.

La révision par défaut est donc **alignée sur le tag de l'image servie**. Un tag de release
llama.cpp (`bNNNN`) désigne un état figé, contrairement à `master`.

Surcharge : `LLAMA_CPP_REVISION` (tag, branche ou SHA de commit).
"""

import os
import pathlib
import urllib.request

# Aligné sur LLAMA_CPP_IMAGE_TAG de deploy/docker/docker-compose.yml (server-b9828).
# À faire évoluer EN MÊME TEMPS que l'image, pas indépendamment.
DEFAULT_REVISION = "b9828"

# Dépôt canonique : « ggerganov/llama.cpp » n'est plus qu'une redirection.
RAW_BASE = "https://raw.githubusercontent.com/ggml-org/llama.cpp"


def pinned_revision():
    """Révision de llama.cpp à utiliser (`LLAMA_CPP_REVISION` sinon défaut épinglé)."""
    return os.getenv("LLAMA_CPP_REVISION", "").strip() or DEFAULT_REVISION


def script_url(script_name, revision=None):
    """URL brute du convertisseur pour la révision retenue."""
    return f"{RAW_BASE}/{revision or pinned_revision()}/{script_name}"


def find_in_package(script_name):
    """Chemin du convertisseur fourni par le paquet `llama_cpp` installé, ou None."""
    try:
        import llama_cpp
    except ImportError:
        return None
    candidate = os.path.join(os.path.dirname(llama_cpp.__file__), script_name)
    return candidate if os.path.exists(candidate) else None


def resolve(script_name, cache_dir, revision=None):
    """
    Renvoie le chemin d'un convertisseur utilisable, téléchargé si nécessaire.

    Le fichier mis en cache porte la révision dans son nom : une copie tirée d'une révision
    antérieure (ou de l'ancien `master`) n'est jamais réutilisée en silence — c'était le cas
    avec un nom fixe, où le premier téléchargement gelait la version pour tous les suivants.

    Toute erreur de téléchargement est propagée : l'appelant décide du message et du code retour.
    """
    from_package = find_in_package(script_name)
    if from_package:
        return from_package

    rev = revision or pinned_revision()
    cache = pathlib.Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / f"{pathlib.Path(script_name).stem}-{rev}.py"
    if target.exists():
        return str(target)

    print(f"  Téléchargement de {script_name} (llama.cpp {rev})...")
    urllib.request.urlretrieve(script_url(script_name, rev), target)
    print(f"  Convertisseur : {target}")
    return str(target)
