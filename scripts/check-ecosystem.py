#!/usr/bin/env python3
"""The « Ecosystem » section three repositories share, checked against one definition.

Kafka SQL Explorer, SpectraLLM and Kex-anHarness each present the other two — in their README
(EN and FR) and on their Docker Hub page — with the same table, the same ports and the same
command to run the three together. Written by hand three times, those copies drift on the first
edit made in one repository only: a renamed image, a moved port, a licence written two ways.
That is the state this section was introduced to end, so it is generated, not maintained.

THIS FILE IS IDENTICAL IN THE THREE REPOSITORIES (docs/ in Kafka SQL Explorer, scripts/ in the
other two). Change it in one, copy it to the other two in the same round of pull requests, and
run it with --write in each: the projects, ports and command below are the single definition.

    python3 check-ecosystem.py          # exit 1 and a diff when a section has drifted
    python3 check-ecosystem.py --write  # regenerate every section in place

A section lives between `<!-- ecosystem:start -->` and `<!-- ecosystem:end -->`. Which files
carry one depends on the repository this copy sits in, recognised by a file only it has.
"""
from __future__ import annotations

import difflib
import sys
from pathlib import Path

START, END = "<!-- ecosystem:start -->", "<!-- ecosystem:end -->"

# (key, name, repository, image, licence, what it does in English, in French)
PROJECTS = [
    ("kafka", "Kafka SQL Explorer", "https://github.com/devdownin/Kafkaexplorer", "kafkaexplorer",
     "AGPL-3.0-or-later",
     "Explore Kafka, query it in Flink SQL, audit it with AI — and serve it to agents as a read-only MCP server",
     "Explorer Kafka, l'interroger en Flink SQL, l'auditer avec l'IA — et le servir aux agents en serveur MCP en lecture seule"),
    ("spectra", "SpectraLLM", "https://github.com/devdownin/SpectraLLM", "spectrallm",
     "AGPL-3.0-or-later",
     "Turn your documents into a private, fine-tuned AI — local RAG, fine-tuning, and an MCP server over the corpus",
     "Transformer vos documents en IA privée et fine-tunée — RAG local, fine-tuning, et un serveur MCP sur le corpus"),
    ("kex", "Kex-anHarness", "https://github.com/devdownin/Kex-anHarness", "kex-agent-ai",
     "GPL-3.0-or-later",
     "A governed AI agent that works through MCP tools — supervision, human approvals, audit trail",
     "Un agent IA gouverné qui agit par des outils MCP — supervision, validations humaines, piste d'audit"),
]

COMMAND = """```bash
git clone https://github.com/devdownin/Kafkaexplorer.git && cd Kafkaexplorer
docker compose -f compose/spectra-hub.yml -f compose/spectra-hub.mcp.yml \\
  -f compose/ecosystem.yml up -d
```"""

TEXT = {
    "en": {
        "heading": "## 🧩 Ecosystem",
        "intro": "Three open-source projects, published under the same Docker Hub namespace, each usable on its own and wired to the others through MCP:",
        "columns": "| Project | What it does | Image | License |",
        "self": " *(this project)*",
        "fit": "How they fit: Kex-anHarness reads Kafka through Kafka SQL Explorer's MCP server and searches documents through SpectraLLM's; Kafka SQL Explorer can use SpectraLLM as its private LLM provider.",
        "together": "**All three together**, from published images — nothing is built:",
        "ports": "Ports, one plan for the three: **8080** Kafka SQL Explorer · **8081** Kex-anHarness · **8088** / **8089** SpectraLLM UI / API · **9092** Kafka. SpectraLLM's own stack keeps 80 / 8080 when it runs alone; `SPECTRA_UI_PORT=8088 SPECTRA_API_PORT=8089` puts it beside the other two.",
    },
    "fr": {
        "heading": "## 🧩 Écosystème",
        "intro": "Trois projets open source, publiés sous le même espace Docker Hub, utilisables seuls et branchés entre eux par MCP :",
        "columns": "| Projet | Ce qu'il fait | Image | Licence |",
        "self": " *(ce projet)*",
        "fit": "Comment ils s'emboîtent : Kex-anHarness lit Kafka par le serveur MCP de Kafka SQL Explorer et cherche dans les documents par celui de SpectraLLM ; Kafka SQL Explorer peut prendre SpectraLLM comme fournisseur de LLM privé.",
        "together": "**Les trois ensemble**, depuis les images publiées — rien n'est construit :",
        "ports": "Les ports, un seul plan pour les trois : **8080** Kafka SQL Explorer · **8081** Kex-anHarness · **8088** / **8089** interface / API SpectraLLM · **9092** Kafka. La stack propre de SpectraLLM garde 80 / 8080 quand elle tourne seule ; `SPECTRA_UI_PORT=8088 SPECTRA_API_PORT=8089` la place à côté des deux autres.",
    },
}

# Per repository: a file only it has, which project it is, and its (path, language) targets.
# Every link the section carries is absolute, because one of the targets is a Docker Hub page,
# which resolves no relative link.
REPOSITORIES = [
    ("docs/check-ecosystem.py", "kafka", [
        ("README.md", "en"),
        ("README.fr.md", "fr"),
        ("docs/DOCKERHUB.md", "en"),
    ]),
    ("deploy/docker/hub/spectrallm.md", "spectra", [
        ("README.md", "en"),
        ("README.fr.md", "fr"),
        ("deploy/docker/hub/spectrallm.md", "en"),
    ]),
    ("DOCKERHUB.md", "kex", [
        ("README.md", "en"),
        ("README.fr.md", "fr"),
        ("DOCKERHUB.md", "en"),
    ]),
]


def render(lang: str, current: str) -> str:
    t = TEXT[lang]
    rows = []
    for key, name, url, image, licence, en, fr in PROJECTS:
        label = f"**{name}**{t['self']}" if key == current else f"[{name}]({url})"
        rows.append(f"| {label} | {en if lang == 'en' else fr} | "
                    f"[`compagnonsdudev/{image}`](https://hub.docker.com/r/compagnonsdudev/{image}) | {licence} |")
    return "\n".join([
        START, t["heading"], "", t["intro"], "", t["columns"], "|---|---|---|---|", *rows, "",
        t["fit"], "", t["together"], "", COMMAND, "", t["ports"], END,
    ])


def repository(root: Path):
    for marker, current, targets in REPOSITORIES:
        if (root / marker).is_file():
            return current, targets
    return None


def main(argv: list[str]) -> int:
    root = Path(__file__).resolve().parent.parent
    found = repository(root)
    if found is None:
        print("check-ecosystem: this repository is none of the three this file knows")
        return 1
    current, targets = found
    write = "--write" in argv
    problems = 0
    for rel, lang in targets:
        path = root / rel
        text = path.read_text(encoding="utf-8")
        if text.count(START) != 1 or text.count(END) != 1:
            print(f"  ✗ {rel}: expected one {START} … {END} block, found {text.count(START)}")
            problems += 1
            continue
        head, rest = text.split(START, 1)
        body, tail = rest.split(END, 1)
        actual = START + body + END
        expected = render(lang, current)
        if actual == expected:
            continue
        if write:
            path.write_text(head + expected + tail, encoding="utf-8")
            print(f"  ↻ {rel}: regenerated")
            continue
        problems += 1
        print(f"  ✗ {rel}: the ecosystem section differs from its definition")
        sys.stdout.writelines(difflib.unified_diff(
            actual.splitlines(True), expected.splitlines(True), "in the file", "expected", n=1))
        print()
    if problems:
        print(f"{problems} ecosystem section(s) out of step — run with --write, or change this file in the three repositories.")
        return 1
    print(f"{len(targets)} ecosystem section(s) match the shared definition.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
