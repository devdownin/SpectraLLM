"""
La section « Écosystème » des README et de la page Docker Hub est générée par
scripts/check-ecosystem.py, le même fichier dans les trois dépôts (Kafka SQL Explorer,
SpectraLLM, Kex-anHarness). Ce test échoue dès qu'une copie a été retouchée à la main :
c'est la dérive que la section a été introduite pour arrêter — un nom d'image, un port ou
une licence écrits différemment d'un dépôt à l'autre.
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def test_ecosystem_sections_match_the_shared_definition():
    proc = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "check-ecosystem.py")],
        capture_output=True, text=True, check=False,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr
