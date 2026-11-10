#!/usr/bin/env python3
"""Confidentialité et exploitation : contrôles automatiques de la checklist (étape 6).

Trois contrôles, exécutables sans pile ni réseau, qui échouent (code 1) au premier écart :

    logs     aucun journal du backend n'écrit une question, une reformulation, un prompt ou une URL
             brute : les valeurs passent par ``LogSafe`` (longueur + empreinte, URL sans paramètres).
             Un appel légitime porte le commentaire ``privacy-ok: <raison>`` sur sa ligne ;
    egress   tout hôte Internet cité dans le code d'exécution, de construction ou de banc figure dans
             ``deploy/airgap/egress-allowlist.txt`` avec son usage. Un nouvel hôte casse le contrôle ;
    airgap   la configuration Compose EFFECTIVE (base + ``docker-compose.airgap.yml``) n'offre aucune
             route sortante : réseau interne, aucun port publié hors frontend, modes hors ligne actifs.

Ils vérifient ce qui est écrit, pas ce qui s'exécute : la validation sur un hôte réel (trace des
connexions sortantes) reste à faire, voir ``docs/process/checklist-confidentialite.fr.md``.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# ── logs ──────────────────────────────────────────────────────────────────────

LOG_CALL = re.compile(r"\blog\.(?:info|warn|error|debug|trace)\(")
SENSITIVE = frozenset({"question", "query", "queries", "prompt", "systemPrompt", "answer", "reformulated",
                       "standalone", "userMessage", "content", "text", "texte", "rewritten", "url",
                       "statement", "chunkText"})
SAFE_WRAPPER = re.compile(r"LogSafe\.(?:text|url)\((?:[^()]|\([^()]*\))*\)")
SIZE_ONLY = re.compile(r"\b\w+(?:\(\))?\.(?:length|size|isBlank|isEmpty)\(\)")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')


def _call_end(source: str, start: int) -> int:
    """Index après la parenthèse fermante de l'appel qui s'ouvre juste avant ``start``."""
    depth, i = 1, start
    while i < len(source) and depth:
        char = source[i]
        if char == '"':
            i += 1
            while source[i] != '"':
                i += 2 if source[i] == "\\" else 1
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        i += 1
    return i


def scan_logs(java_root: Path) -> list[str]:
    violations = []
    for path in sorted(java_root.rglob("*.java")):
        source = path.read_text(encoding="utf-8")
        for match in LOG_CALL.finditer(source):
            call = source[match.start():_call_end(source, match.end())]
            if "privacy-ok" in source[match.start():source.find("\n", match.start() + len(call))]:
                continue
            arguments = STRING_LITERAL.sub('""', call)
            arguments = SIZE_ONLY.sub("", SAFE_WRAPPER.sub("", arguments))
            exposed = sorted({name for name in re.findall(r"(?<![.\w])([A-Za-z_]\w*)", arguments)
                              if name in SENSITIVE})
            if exposed:
                line = source.count("\n", 0, match.start()) + 1
                violations.append(f"{path}:{line} : {', '.join(exposed)} écrit(s) en clair dans "
                                  f"{call.splitlines()[0].strip()}")
    return violations


# ── egress ────────────────────────────────────────────────────────────────────

URL = re.compile(r"https?://([A-Za-z0-9.-]+\.[A-Za-z]{2,})")
IGNORED_HOSTS = re.compile(r"(^|\.)(example\.(com|org)|exemple\.(com|fr)|w3\.org|xml\.org|apache\.org|"
                           r"localhost|evil\.test|spring\.io|xmlsoap\.org|java\.sun\.com|jcp\.org|oracle\.com)$")
COMMENT_PREFIXES = ("//", "*", "/*", "#", "<!--", "--", "REM", "::")
SCOPES = {
    "runtime": ["backend/src/main/**/*", "services/*/*.py", "services/*/*/*.py", "frontend/src/**/*",
                "frontend/index.html", "frontend/nginx.conf", "deploy/docker/docker-compose*.yml"],
    "build": ["deploy/docker/Dockerfile", "services/*/Dockerfile", "frontend/Dockerfile", "scripts/setup.sh",
              "scripts/setup.bat", "scripts/llama_cpp_convert.py", "scripts/export_gguf.py",
              "scripts/nvd_cache.py", "scripts/train_host.py", "scripts/*.sh"],
    "bench": ["scripts/enterprise_rag_*.py", "scripts/frames_bench.py", "scripts/finetune_audit.py"],
}
ALLOWLIST = ROOT / "deploy" / "airgap" / "egress-allowlist.txt"


def scan_egress(root: Path) -> dict[tuple[str, str], list[str]]:
    found: dict[tuple[str, str], set[str]] = defaultdict(set)
    for scope, patterns in SCOPES.items():
        for pattern in patterns:
            for name in glob.glob(str(root / pattern), recursive=True):
                path = Path(name)
                if path.is_dir() or "/tests/" in name or name.endswith((".test.ts", ".test.tsx", ".jar")):
                    continue
                try:
                    lines = path.read_text(encoding="utf-8").splitlines()
                except (OSError, UnicodeDecodeError):
                    continue
                for number, line in enumerate(lines, 1):
                    if line.strip().startswith(COMMENT_PREFIXES):
                        continue
                    for host in URL.findall(line):
                        if not IGNORED_HOSTS.search(host):
                            found[(scope, host)].add(f"{path.relative_to(root)}:{number}")
    return {key: sorted(places) for key, places in found.items()}


def load_allowlist(path: Path) -> dict[tuple[str, str], str]:
    entries = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|")]
        if len(parts) != 3 or not all(parts):
            raise ValueError(f"{path}:{number} : format attendu « portée | hôte | usage et parade »")
        if parts[0] not in SCOPES:
            raise ValueError(f"{path}:{number} : portée inconnue « {parts[0]} » ({', '.join(SCOPES)})")
        entries[(parts[0], parts[1])] = parts[2]
    return entries


def check_egress(root: Path, allowlist: Path) -> tuple[list[str], list[str]]:
    allowed = load_allowlist(allowlist)
    found = scan_egress(root)
    unknown = [f"{scope} : {host} non déclaré ({', '.join(places[:3])})"
               for (scope, host), places in sorted(found.items()) if (scope, host) not in allowed]
    stale = [f"{scope} : {host} déclaré mais introuvable dans le code" for (scope, host) in sorted(allowed)
             if (scope, host) not in found]
    return unknown, stale


# ── airgap ────────────────────────────────────────────────────────────────────

EDGE_SERVICES = {"frontend"}
OFFLINE_HF_SERVICES = ("reranker", "trainer", "docparser")


def check_airgap(config: dict) -> list[str]:
    """Écarts d'une configuration Compose effective (``docker compose config --format json``)."""
    problems = []
    networks = config.get("networks", {})
    services = config.get("services", {})
    if not networks.get("default", {}).get("internal"):
        problems.append("le réseau « default » n'est pas interne : tous les services ont une route sortante")
    for name, service in sorted(services.items()):
        attached = set(service.get("networks", {"default": {}}))
        for network in attached:
            if not networks.get(network, {}).get("internal") and name not in EDGE_SERVICES:
                problems.append(f"{name} : rattaché au réseau « {network} » qui n'est pas interne")
        if name in EDGE_SERVICES and "default" not in attached:
            problems.append(f"{name} : doit rester sur le réseau interne pour joindre spectra-api")
        if service.get("network_mode") in ("host", "bridge"):
            problems.append(f"{name} : network_mode={service['network_mode']} contourne l'isolation")
        for port in service.get("ports", []):
            if name not in EDGE_SERVICES:
                problems.append(f"{name} : publie le port {port.get('published')} (seul le frontend est exposé)")
            elif port.get("host_ip") not in ("127.0.0.1", "::1"):
                problems.append(f"{name} : port {port.get('published')} publié sur {port.get('host_ip') or '0.0.0.0'}"
                                " (boucle locale attendue ; SPECTRA_BIND_ADDR ?)")
    environment = {name: service.get("environment", {}) for name, service in services.items()}
    api = environment.get("spectra-api", {})
    if str(api.get("SPECTRA_OFFLINE")).lower() != "true":
        problems.append("spectra-api : SPECTRA_OFFLINE n'est pas « true »")
    if str(api.get("SPECTRA_STARTUP_AUTO_INSTALL_MODELS")).lower() != "false":
        problems.append("spectra-api : SPECTRA_STARTUP_AUTO_INSTALL_MODELS n'est pas « false »")
    for name in OFFLINE_HF_SERVICES:
        if name in services and str(environment[name].get("HF_HUB_OFFLINE")) != "1":
            problems.append(f"{name} : HF_HUB_OFFLINE n'est pas « 1 »")
    if "qdrant" in services:
        if str(environment["qdrant"].get("QDRANT__TELEMETRY_DISABLED")).lower() != "true":
            problems.append("qdrant : QDRANT__TELEMETRY_DISABLED n'est pas « true »")
    else:
        problems.append("qdrant : service absent de la configuration")
    return problems


def compose_config(*files: Path) -> dict:
    if shutil.which("docker") is None:
        raise FileNotFoundError("docker introuvable : le contrôle airgap lit la sortie de « docker compose config »")
    command = ["docker", "compose", "--project-directory", str(ROOT)]
    for file in files:
        command += ["-f", str(file)]
    command += ["--profile", "*", "config", "--format", "json"]
    done = subprocess.run(command, capture_output=True, text=True, check=False,
                          env={**os.environ, "SPECTRA_BIND_ADDR": os.environ.get("SPECTRA_BIND_ADDR", "127.0.0.1")})
    if done.returncode:
        raise ValueError(f"docker compose config a échoué : {done.stderr.strip()[:300]}")
    return json.loads(done.stdout)


# ── CLI ───────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("logs")
    commands.add_parser("egress")
    commands.add_parser("airgap")
    args = parser.parse_args()
    try:
        if args.command == "logs":
            problems = scan_logs(ROOT / "backend" / "src" / "main" / "java")
        elif args.command == "egress":
            problems, stale = check_egress(ROOT, ALLOWLIST)
            for note in stale:
                print(f"[egress] à nettoyer : {note}", file=sys.stderr)
        else:
            problems = check_airgap(compose_config(ROOT / "deploy/docker/docker-compose.yml",
                                                   ROOT / "deploy/docker/docker-compose.airgap.yml"))
    except (OSError, ValueError) as exc:
        print(f"[{args.command}] {exc}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"[{args.command}] {problem}", file=sys.stderr)
    print(f"[{args.command}] {'ÉCART(S) : ' + str(len(problems)) if problems else 'conforme'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
