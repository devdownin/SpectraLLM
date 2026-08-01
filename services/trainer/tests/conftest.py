"""
Fixtures du service trainer.

Contrairement aux deux autres services Python, celui-ci n'importe **aucune** bibliothèque
lourde : il ne fait que lancer des sous-processus. Il n'y a donc rien à neutraliser à
l'import — mais tout à contrôler à l'exécution.

Les tests substituent aux scripts réels de petits scripts shell écrits dans un répertoire
temporaire. C'est ce qui permet de vérifier ce qui compte vraiment — l'ordre des arguments
transmis, la diffusion ligne à ligne, la sentinelle de sortie, l'annulation — sans installer
torch ni entraîner quoi que ce soit.
"""
import os
import sys

import pytest

# app.py vit à la racine du service, un niveau au-dessus de ce répertoire tests/.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


@pytest.fixture
def echo_script(tmp_path):
    """Script qui répète ses arguments, un par ligne, puis sort en 0."""
    script = tmp_path / "train.sh"
    script.write_text('#!/bin/sh\nfor a in "$@"; do echo "ARG:$a"; done\nexit 0\n')
    script.chmod(0o755)
    return script


@pytest.fixture
def echo_py_script(tmp_path):
    """Équivalent Python de `echo_script` : l'export est invoqué par PYTHON_BIN, pas par sh."""
    script = tmp_path / "export_gguf.py"
    script.write_text("import sys\n[print('ARG:' + a) for a in sys.argv[1:]]\n")
    return script


@pytest.fixture
def failing_script(tmp_path):
    """Script qui écrit sur stderr puis sort en 42 — vérifie la fusion des flux."""
    script = tmp_path / "train.sh"
    script.write_text('#!/bin/sh\necho "torch introuvable" >&2\nexit 42\n')
    script.chmod(0o755)
    return script


@pytest.fixture
def slow_script(tmp_path):
    """Script long, pour éprouver l'annulation."""
    script = tmp_path / "train.sh"
    script.write_text(
        '#!/bin/sh\ni=0\nwhile [ $i -lt 200 ]; do echo "step $i"; i=$((i+1)); sleep 0.1; done\n'
    )
    script.chmod(0o755)
    return script


@pytest.fixture
def client(tmp_path, monkeypatch):
    """
    Client de test configuré sur un répertoire de travail temporaire.

    Une fabrique est renvoyée plutôt qu'un client : chaque test choisit le script à faire
    exécuter, et l'application lit ses chemins au moment de l'import.
    """
    from fastapi.testclient import TestClient

    def _build(train_script=None, export_script=None):
        import app as app_module

        monkeypatch.setattr(app_module, "WORK_DIR", tmp_path)
        if train_script is not None:
            monkeypatch.setattr(app_module, "TRAIN_SCRIPT", train_script)
        if export_script is not None:
            monkeypatch.setattr(app_module, "EXPORT_SCRIPT", export_script)
        return TestClient(app_module.app)

    return _build
