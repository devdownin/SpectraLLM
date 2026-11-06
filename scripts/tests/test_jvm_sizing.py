"""
Invariants du heap JVM de spectra-api (`scripts/lib/jvm-sizing.sh`).

Le heap était calculé sur la RAM de la machine seule : -Xmx3868m sur une machine 16 Go, pour
un conteneur limité à 4 Go. Avec le hors-heap (reranker ONNX, ZGC), le conteneur dépassait sa
limite et le noyau tuait la JVM en pleine requête. Ces tests vérifient la propriété qui compte :
le heap laisse toujours de la marge dans la limite du conteneur.
"""

import re
import subprocess
from pathlib import Path

import pytest

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
LIB = SCRIPTS_DIR / "lib" / "jvm-sizing.sh"
DETECT_ENV_SH = SCRIPTS_DIR / "detect-env.sh"
DETECT_ENV_BAT = SCRIPTS_DIR / "detect-env.bat"


def heap(ram_mb, divisor, limit=None):
    args = ["bash", str(LIB), str(ram_mb), str(divisor)] + ([limit] if limit is not None else [])
    out = subprocess.run(args, capture_output=True, text=True, check=True).stdout
    values = dict(pair.split("=") for pair in out.split())
    return int(values["HEAP"]), int(values["LIMIT"])


@pytest.mark.parametrize("ram_mb", [4096, 8192, 15561, 16384, 32768, 131072])
@pytest.mark.parametrize("divisor", [3, 4])
def test_default_container_limit_always_leaves_off_heap_headroom(ram_mb, divisor):
    value, limit = heap(ram_mb, divisor)

    assert limit == 4096
    assert value <= limit * 0.6


def test_observed_16gb_machine_no_longer_gets_a_heap_near_the_container_limit():
    """Régression relevée : 15 561 Mo disponibles donnaient -Xmx3868m dans un conteneur de 4 Go."""
    value, _ = heap(15561, 4)

    assert value == 2457


@pytest.mark.parametrize("limit, expected_mb", [("4g", 4096), ("8G", 8192), ("6144m", 6144), ("8gb", 8192)])
def test_compose_memory_notations_are_understood(limit, expected_mb):
    _, parsed = heap(32768, 3, limit)

    assert parsed == expected_mb


def test_larger_container_keeps_the_ram_based_heap():
    value, _ = heap(32768, 3, "8g")

    assert value == 4096


def test_cap_wins_over_the_2gb_floor_for_a_small_container():
    value, _ = heap(4096, 4, "2g")

    assert value == 1228


def test_unreadable_limit_fails_loudly():
    result = subprocess.run(["bash", str(LIB), "16384", "4", "beaucoup"], capture_output=True, text=True)

    assert result.returncode != 0
    assert "illisible" in result.stderr


def test_detect_env_delegates_heap_sizing_to_the_library():
    body = DETECT_ENV_SH.read_text(encoding="utf-8")

    assert "lib/jvm-sizing.sh" in body
    assert "jvm_heap " in body
    assert not re.search(r"JVM_HEAP=\$\(\(", body)


def test_windows_script_applies_the_same_container_cap():
    bat = DETECT_ENV_BAT.read_text(encoding="utf-8", errors="replace")

    assert "SPECTRA_API_MEMORY_LIMIT" in bat
    assert re.search(r"JVM_HEAP_CAP=%API_MEM_MB% \* 60 / 100", bat)
