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

Hors ligne (F12)
----------------
Le téléchargement à l'exécution contredisait la promesse « 100 % local · même air-gapped » :
un export GGUF échouait sans accès sortant, alors que rien dans l'interface ne le laissait
prévoir. Le convertisseur est donc cherché **d'abord sur le disque** :

1. `SPECTRA_LLAMA_CPP_DIR` — répertoire fourni par l'exploitant ;
2. `/opt/llama-cpp-converters` — emplacement rempli **à la construction** de l'image du trainer
   (`services/trainer/Dockerfile`), où le réseau est disponible et le résultat figé ;
3. le paquet `llama_cpp` s'il est installé ;
4. le cache du répertoire de sortie ;
5. téléchargement — **dernier recours**, et le seul chemin qui exige un accès sortant.

Un déploiement air-gapped s'appuie sur (1) ou (2) et ne passe jamais par (5).
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


#: Emplacement rempli à la construction de l'image du trainer. Le nom est stable et versionné
#: par le nom de fichier (`convert_hf_to_gguf-<rev>.py`) : une image plus ancienne ne fournit
#: jamais en silence un convertisseur d'une autre révision que celle demandée.
VENDORED_DIR = "/opt/llama-cpp-converters"


def find_vendored(script_name, revision):
    """
    Convertisseur déjà présent sur le disque, pour la révision demandée.

    Cherche dans `SPECTRA_LLAMA_CPP_DIR` puis dans `VENDORED_DIR`, sous le nom versionné puis
    sous le nom nu. Le nom versionné est essayé d'abord : c'est le seul qui prouve la révision.
    """
    stem = pathlib.Path(script_name).stem
    roots = [os.getenv("SPECTRA_LLAMA_CPP_DIR", "").strip(), VENDORED_DIR]
    for root in roots:
        if not root:
            continue
        for name in (f"{stem}-{revision}.py", script_name):
            candidate = pathlib.Path(root) / name
            if candidate.is_file():
                return str(candidate)
    return None


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
    rev = revision or pinned_revision()

    # Disque d'abord : un déploiement sans accès sortant doit fonctionner, et c'est ce chemin
    # qui le lui permet (F12).
    vendored = find_vendored(script_name, rev)
    if vendored:
        return vendored

    from_package = find_in_package(script_name)
    if from_package:
        return from_package

    cache = pathlib.Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / f"{pathlib.Path(script_name).stem}-{rev}.py"
    if target.exists():
        return str(target)

    # Dernier recours, et le seul chemin qui exige un accès sortant : le dire, pour qu'un export
    # qui échoue en environnement cloisonné soit compris du premier coup.
    print(f"  Téléchargement de {script_name} (llama.cpp {rev}) — aucune copie locale trouvée "
          f"(cf. SPECTRA_LLAMA_CPP_DIR / {VENDORED_DIR})...")
    urllib.request.urlretrieve(script_url(script_name, rev), target)
    print(f"  Convertisseur : {target}")
    return str(target)


def vendor(script_name, target_dir, revision=None):
    """
    Télécharge un convertisseur dans `target_dir`, sous son nom versionné.

    Appelée à la **construction** de l'image du trainer, où le réseau est disponible : c'est ce
    qui permet à l'exécution de s'en passer (F12). Renvoie le chemin écrit.
    """
    rev = revision or pinned_revision()
    target_dir = pathlib.Path(target_dir)
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / f"{pathlib.Path(script_name).stem}-{rev}.py"
    urllib.request.urlretrieve(script_url(script_name, rev), target)
    if target.stat().st_size == 0:
        # Un fichier vide passerait les vérifications d'existence et ferait échouer l'export
        # bien plus tard, avec un message sans rapport.
        raise RuntimeError(f"convertisseur vide téléchargé : {target}")
    return str(target)


if __name__ == "__main__":
    # Point d'entrée de la construction d'image : `python3 llama_cpp_convert.py <répertoire>`.
    import sys as _sys

    _dir = _sys.argv[1] if len(_sys.argv) > 1 else VENDORED_DIR
    _path = vendor("convert_hf_to_gguf.py", _dir)
    print(f"Convertisseur GGUF embarqué : {_path}")
