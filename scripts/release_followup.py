#!/usr/bin/env python3
"""Rattrape le dépôt sur une version publiée : ce que rien d'autre ne fait bouger.

Après la v0.11.0, trois endroits décrivaient encore la version d'avant, et seul l'un d'eux
avait un garde-fou : l'overlay Docker Hub tirait 0.10.0 (test_docker_hub_images l'a vu, mais
sur la PR suivante, qui n'y touchait pas), CITATION.cff annonçait une autre version, et les
pages Docker Hub montraient des tags d'exemple périmés. Ce script les aligne d'un geste ;
le workflow release-followup.yml l'appelle une fois les images publiées et ouvre la PR.

    python3 scripts/release_followup.py 0.12.0 [--date 2026-10-20]

Idempotent : relancé sur une version déjà appliquée, il ne change rien et le dit. Une version
qui n'est pas X.Y.Z (préversion, tag daté) est refusée : elle ne doit déplacer ni l'épinglage
par défaut, ni les exemples des pages.
"""

from __future__ import annotations

import argparse
import datetime
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OVERLAY = "deploy/docker/docker-compose.hub.yml"
CITATION = "CITATION.cff"
HUB_PAGES = ("deploy/docker/hub/spectrallm.md", "deploy/docker/hub/spectrallm-frontend.md")


def substitute(path: Path, pattern: str, replacement, expected: int | None = None) -> bool:
    text = path.read_text(encoding="utf-8")
    new, count = re.subn(pattern, replacement, text, flags=re.M)
    if count == 0 or (expected is not None and count != expected):
        raise ValueError(f"{path.name} : motif attendu {expected or '≥1'} fois, trouvé {count} "
                         "— le fichier a changé de forme")
    if new != text:
        path.write_text(new, encoding="utf-8")
        return True
    return False


def apply(root: Path, version: str, date: str) -> list[str]:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError(f"« {version} » n'est pas une version X.Y.Z : rien à rattraper")
    series = version.rsplit(".", 1)[0]
    changed = []
    if substitute(root / OVERLAY, r"(\$\{SPECTRA_IMAGE_TAG:-)[^}]+(\})",
                  lambda m: m.group(1) + version + m.group(2), expected=2):
        changed.append(OVERLAY)
    citation = root / CITATION
    if substitute(citation, r"^version: .*$", f"version: {version}", expected=1) | \
            substitute(citation, r'^date-released: .*$', f'date-released: "{date}"', expected=1):
        changed.append(CITATION)
    for page in HUB_PAGES:
        path = root / page
        touched = substitute(path, r"^\| `\d+\.\d+` \| The \d+\.\d+ series",
                             lambda m: f"| `{series}` | The {series} series", expected=1)
        touched |= substitute(path, r"^\| `\d+\.\d+\.\d+` \| An exact version",
                              f"| `{version}` | An exact version", expected=1)
        if touched:
            changed.append(page)
    return changed


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("version")
    parser.add_argument("--date", default=datetime.date.today().isoformat())
    args = parser.parse_args(argv)
    try:
        changed = apply(ROOT, args.version.removeprefix("v"), args.date)
    except (OSError, ValueError) as error:
        print(f"::error::{error}")
        return 1
    print("modifié : " + ", ".join(changed) if changed else f"rien à faire : tout nomme déjà {args.version}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
