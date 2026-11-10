#!/usr/bin/env python3
"""Version and freshness guard for the trusted Dependency-Check database cache."""

import argparse
import json
import os
import time
import xml.etree.ElementTree as ET
from pathlib import Path

MARKER = "spectra-nvd-cache.json"
MAX_AGE_SECONDS = 48 * 60 * 60


def plugin_version(pom: Path) -> str:
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    root = ET.parse(pom).getroot()
    for plugin in root.findall(".//m:plugin", ns):
        if plugin.findtext("m:artifactId", namespaces=ns) == "dependency-check-maven":
            version = plugin.findtext("m:version", namespaces=ns)
            if version and all(part.isdigit() for part in version.split(".")):
                return version
    raise ValueError("Version explicite de dependency-check-maven absente du pom")


def require_database(directory: Path) -> None:
    database = directory / "odc.mv.db"
    if not database.is_file() or database.stat().st_size == 0:
        raise ValueError("Base NVD absente ou vide : relancer le job de mise à jour sur la branche par défaut")


def stamp(directory: Path, version: str, now: int) -> None:
    require_database(directory)
    marker = directory / MARKER
    temporary = marker.with_suffix(".tmp")
    temporary.write_text(json.dumps({"plugin_version": version, "updated_at": now}), encoding="utf-8")
    temporary.replace(marker)


def validate(directory: Path, version: str, now: int) -> None:
    require_database(directory)
    metadata = json.loads((directory / MARKER).read_text(encoding="utf-8"))
    if not isinstance(metadata, dict) or metadata.get("plugin_version") != version:
        raise ValueError("Cache NVD incompatible avec la version du plugin")
    updated = metadata.get("updated_at")
    if type(updated) is not int or updated > now or now - updated > MAX_AGE_SECONDS:
        raise ValueError("Cache NVD périmé (>48 h) ou date invalide : relancer sa mise à jour")


def record_validity(valid: bool) -> None:
    """Publie `valid=true|false` pour les étapes suivantes (sans effet hors GitHub Actions)."""
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as handle:
            handle.write(f"valid={'true' if valid else 'false'}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prefix", "stamp", "validate"))
    parser.add_argument("--pom", type=Path, default=Path("backend/pom.xml"))
    parser.add_argument("--data", type=Path, default=Path.home() / ".dependency-check-data")
    parser.add_argument("--soft", action="store_true",
                        help="validate : ne pas échouer ; écrire valid=true|false dans $GITHUB_OUTPUT "
                             "(le workflow reconstruit alors la base dans le job)")
    args = parser.parse_args()
    try:
        version = plugin_version(args.pom)
        if args.action == "prefix":
            print(f"nvd-ready-v1-dc-{version}-")
        elif args.action == "stamp":
            stamp(args.data, version, int(time.time()))
        else:
            try:
                validate(args.data, version, int(time.time()))
            except (OSError, ValueError) as error:
                if not args.soft:
                    raise
                print(f"::warning::{error}")
                record_validity(False)
                return 0
            print("Cache NVD valide, mis à jour depuis moins de 48 h")
            if args.soft:
                record_validity(True)
        return 0
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"::error::{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
