#!/usr/bin/env python3
"""Vérifie les liens internes (chemins relatifs) de tous les fichiers Markdown du dépôt.

Seuls les liens vers des fichiers/dossiers locaux sont contrôlés — les URLs http(s) et
mailto sont ignorées (pas de réseau : le contrôle est déterministe et rapide en CI).
Sortie non vide + code retour 1 si au moins un lien est cassé.

Usage : python3 scripts/check-doc-links.py [racine=.]
"""
import re
import sys
from pathlib import Path

LINK_RE = re.compile(r"\[[^\]]*\]\(([^)#\s]+)(?:#[^)]*)?\)")
SKIP_DIRS = {".git", "node_modules", "target", "dist", "build"}


def main(root: str = ".") -> int:
    base = Path(root)
    broken = 0
    for md in sorted(base.rglob("*.md")):
        if any(part in SKIP_DIRS for part in md.parts):
            continue
        text = md.read_text(encoding="utf-8", errors="replace")
        for match in LINK_RE.finditer(text):
            target = match.group(1)
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            if not (md.parent / target).resolve().exists():
                line = text[: match.start()].count("\n") + 1
                print(f"::error file={md},line={line}::lien cassé -> {target}")
                broken += 1
    if broken:
        print(f"\n{broken} lien(s) interne(s) cassé(s).", file=sys.stderr)
        return 1
    print("Tous les liens internes Markdown sont valides.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
