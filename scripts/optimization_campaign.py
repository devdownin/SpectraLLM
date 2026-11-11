#!/usr/bin/env python3
"""Campagne de mesures (étapes 1 à 7) enchaînée sur UN hôte : une commande, reprenable.

    python3 scripts/optimization_campaign.py plan                 # affiche les étapes, rien n'est lancé
    python3 scripts/optimization_campaign.py run                  # exécute ce qui n'est pas déjà fait
    python3 scripts/optimization_campaign.py run --only baseline  # une étape (ou --from, --until)

Ce que le script AUTOMATISE : tout ce qui se mesure sur la pile en cours, sans la reconstruire — baseline,
variantes de recherche par requête (candidats, multi-query, dense, hybride, reranker, filtres), audit de
génération, audit du fine-tuning, contrôles de confidentialité, synthèse.

Ce qu'il NE FAIT PAS, et affiche comme étape « manuelle » : tout changement d'index (autre embedding,
int8, HNSW, chunks, locale) et tout changement de réglage serveur (regroupement Qdrant, hybride natif).
Ils demandent de réingérer le corpus ou de recréer des conteneurs, avec les noms de conteneurs fixes de
Compose : à faire avec les commandes affichées, puis `run --from <étape suivante>`.

Chaque étape déclare le fichier qu'elle produit : une étape dont le fichier existe est sautée (reprise après
interruption). Un échec arrête la campagne ; la sortie complète est dans `<work>/campaign.log`.

Variables d'environnement : SPECTRA_API_KEY (si l'API est protégée), LLM_API_KEY (clé du juge), API,
COLLECTION, EVALUATOR (clone d'EnterpriseRAG-Bench), WORK.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Step:
    id: str
    title: str
    command: tuple[str, ...] = ()
    produces: str | None = None          # chemin relatif à `work` : l'étape est faite si ce fichier existe
    manual: str | None = None            # instructions : l'étape n'est jamais exécutée par le script
    needs: tuple[str, ...] = ()          # variables d'environnement requises
    needs_stack: bool = True             # exige l'API Spectra joignable
    stage: int = 0
    notes: list[str] = field(default_factory=list)


def settings(env: dict[str, str]) -> dict:
    work = Path(env.get("WORK", "data/onyx-baseline-v1"))
    return {
        "work": work, "api": env.get("API", "http://localhost:8080"),
        "collection": env.get("COLLECTION", "spectra_documents"),
        "evaluator": Path(env.get("EVALUATOR", "../EnterpriseRAG-Bench")),
        "profile": "deploy/bench/enterprise-rag-baseline-v1.profile.json",
    }


def build_steps(cfg: dict, finetune: dict[str, str] | None = None, candidates: int = 20) -> list[Step]:
    work, api, collection = cfg["work"], cfg["api"], cfg["collection"]
    evaluator, profile = cfg["evaluator"], cfg["profile"]
    py = sys.executable
    experiment = [py, "scripts/enterprise_rag_experiment.py"]
    split = f"{work}/split/dev-120.jsonl"

    def variant(name: str, extra: list[str], top: int = candidates, label: str = "stage2") -> Step:
        out = f"{work}/{label}/{name}"
        return Step(
            id=f"{label}-{name}", stage=2, title=f"retrieve {name}", produces=f"{label}/{name}/report.json",
            command=("bash", "-c",
                     " ".join([*experiment, "run", "--api", api, "--mode", "retrieve", "--collection", collection,
                               "--questions", split, "--corpus-dir", f"{work}/documents", "--profile", profile,
                               "--max-context-chunks", "10", "--top-candidates", str(top), "--parallelism", "1",
                               *extra, "--out", out]) + f" && {py} scripts/enterprise_rag_experiment.py report --out {out}"))

    steps = [
        Step("baseline", "Baseline : corpus, lot dev-120, ingestion, retrieve + answer, juge, rapports",
             stage=1, command=("bash", "scripts/enterprise_rag_baseline.sh"), produces="baseline-answer/report.json",
             needs=("LLM_API_KEY",)),
        # Étape 2 : séries sans redémarrage (la pile doit avoir été démarrée avec stage2/reference.env).
        variant("top20", ["--multi-query", "off"], top=20),
        variant("top50", ["--multi-query", "off"], top=50),
        variant("multi-query-off", ["--multi-query", "off"]),
        variant("multi-query-on", ["--multi-query", "on"]),
        Step("stage2-compare", "Comparaisons appariées ; rapport au format de `suite` (verdicts d'adoption)", stage=2,
             needs_stack=False, produces="stage2/report.json",
             command=("bash", "-c",
                      f"{py} scripts/enterprise_rag_experiment.py compare {work}/stage2/top20 {work}/stage2/top50 "
                      f"> {work}/stage2/compare-candidates.json && "
                      f"{py} scripts/enterprise_rag_experiment.py compare {work}/stage2/multi-query-off "
                      f"{work}/stage2/multi-query-on > {work}/stage2/compare-multiquery.json && "
                      f"{py} -c \"import json,sys; d='{work}/stage2/'; "
                      "json.dump({'candidate_budget': 'à choisir', 'shortlist': [], 'retrieval': {"
                      "'top20_vs_top50': json.load(open(d+'compare-candidates.json')), "
                      "'multi_query_off_vs_on': json.load(open(d+'compare-multiquery.json'))}}, "
                      "open(d+'report.json', 'w'), indent=2)\" && "
                      f"{py} scripts/enterprise_rag_stage2_summary.py {work}/stage2/report.json")),
        Step("stage2-group", "MANUEL — regroupement Qdrant à 2", stage=2, produces="stage2/group2/report.json",
             manual=("docker compose --project-directory . -f deploy/docker/docker-compose.yml "
                     "--env-file deploy/bench/enterprise-rag-baseline-v1.env --env-file deploy/bench/stage2/reference.env "
                     "--env-file deploy/bench/stage2/group2.env up -d --no-deps --wait spectra-api\n"
                     f"puis : variante `group2` (retrieve, 20 candidats, --multi-query off) dans {work}/stage2/group2,\n"
                     "puis remettre la pile de référence (même commande sans group2.env).")),
        # Étape 3 : audit par index. Seul l'index courant est automatisé.
        Step("audit-modes", "Audit du retrieval : dense, hybride, reranker, filtres (index courant)", stage=3,
             command=("bash", "scripts/enterprise_rag_audit.sh", os.environ.get("AUDIT_INDEX", "nomic"), "modes"),
             produces=f"audit/{os.environ.get('AUDIT_INDEX', 'nomic')}/hybrid-rerank/report.json"),
        Step("audit-indexes", "MANUEL — autres index (BGE-M3, Qwen3, int8, HNSW, chunks 512, locale en, natif)",
             stage=3, produces="audit/.indexes-done",
             manual=("Pour chaque overlay de deploy/bench/stage3/index-*.env et embed-*.env : pile dédiée et "
                     "stockage Qdrant dédié, ingestion (scripts/enterprise_rag_bench.py ingest), puis\n"
                     "  AUDIT_ENV_FILES=\"…\" scripts/enterprise_rag_audit.sh <index> modes ann\n"
                     "Terminer par : touch " + f"{work}/audit/.indexes-done")),
        Step("audit-matrix", "Tableau comparatif rappel/latence", stage=3, needs_stack=False,
             produces="audit/matrix.md",
             command=(py, "scripts/enterprise_rag_matrix.py", "--audit-dir", f"{work}/audit", "--reference",
                      f"{os.environ.get('AUDIT_INDEX', 'nomic')}/hybrid-rerank")),
        # Étape 4 : audit de génération sur la baseline, juge Opus.
        Step("generation", "Audit de la génération (fidélité, citations, refus) par catégorie", stage=4,
             needs=("LLM_API_KEY",), needs_stack=False, produces="baseline-answer/generation-audit.md",
             command=("bash", "-c",
                      f"{py} scripts/enterprise_rag_generation_audit.py ground --out {work}/baseline-answer "
                      f"--corpus-dir {work}/documents --evaluator-dir {evaluator} && "
                      f"{py} scripts/enterprise_rag_generation_audit.py report --out {work}/baseline-answer")),
    ]
    if finetune:
        base_dir = f"{work}/finetune-audit"
        collects = " && ".join(
            f"{py} scripts/finetune_audit.py collect --api {api} --out {base_dir} --label {label} --model {model}"
            for label, model in finetune.items())
        candidates_args = " ".join(f"--candidate {label}" for label in finetune if label not in ("base",)
                                   and not label.endswith("-f16"))
        parity = " ".join(f"--parity {label[:-4]}={label}" for label in finetune if label.endswith("-f16"))
        steps.append(Step("finetune", "Audit du fine-tuning : collecte et décision go/no-go", stage=5,
                          produces="finetune-audit/decision.json",
                          command=("bash", "-c", f"{collects} && {py} scripts/finetune_audit.py decide --out {base_dir} "
                                                  f"--base base {candidates_args} {parity}")))
    else:
        steps.append(Step("finetune", "Audit du fine-tuning (non demandé : --finetune base=…,qlora=…,qlora-f16=…)",
                          stage=5, manual="Enregistrer les modèles dans Spectra, puis relancer avec "
                                          "--finetune base=<nom>,qlora=<nom>,qlora-f16=<nom>[,dpo=…,dpo-f16=…]",
                          produces="finetune-audit/decision.json"))
    steps += [
        Step("privacy-static", "Confidentialité : journaux, sorties réseau, configuration air-gap", stage=6,
             needs_stack=False, command=("bash", "-c",
                                         f"{py} scripts/privacy_audit.py logs && {py} scripts/privacy_audit.py egress && "
                                         f"{py} scripts/privacy_audit.py airgap")),
        Step("privacy-runtime", "Confidentialité : résidus dans les journaux et le stockage Qdrant", stage=6,
             command=("bash", "scripts/privacy_runtime_check.sh", "--api", api, "--compose-logs"),
             produces=".privacy-runtime-ok"),
        Step("summary", "Synthèse et décision", stage=7, needs_stack=False,
             command=("bash", "-c", f"{py} scripts/optimization_summary.py --work {work} "
                                    + (f"--finetune {work}/finetune-audit " if finetune else "")
                                    + f"--out {work}/synthese.md || [ $? -eq 2 ]")),
    ]
    return steps


def is_done(step: Step, work: Path) -> bool:
    return step.produces is not None and (work / step.produces).exists()


def select(steps: list[Step], only: str | None, start: str | None, until: str | None) -> list[Step]:
    ids = [s.id for s in steps]
    for name in (only, start, until):
        if name and name not in ids:
            raise ValueError(f"étape inconnue : {name} (valides : {', '.join(ids)})")
    if only:
        return [s for s in steps if s.id == only]
    first = ids.index(start) if start else 0
    last = ids.index(until) if until else len(steps) - 1
    return steps[first:last + 1]


def preflight(steps: list[Step], cfg: dict, env: dict[str, str]) -> list[str]:
    problems = []
    runnable = [s for s in steps if s.command and not s.manual]
    for step in runnable:
        for variable in step.needs:
            if not env.get(variable):
                problems.append(f"{step.id} : variable {variable} absente")
    if any(s.id == "baseline" for s in runnable):
        for tool in ("docker",):
            if shutil.which(tool) is None:
                problems.append(f"baseline : {tool} introuvable")
        if not (cfg["evaluator"] / ".git").exists():
            problems.append(f"baseline : clone d'EnterpriseRAG-Bench absent ({cfg['evaluator']})")
    return problems


def render_plan(steps: list[Step], work: Path) -> str:
    lines = ["| Étape | Phase | Statut | Description |", "|---|---|---|---|"]
    for step in steps:
        status = "manuelle" if step.manual else ("faite" if is_done(step, work) else "à faire")
        lines.append(f"| {step.id} | {step.stage} | {status} | {step.title} |")
    return "\n".join(lines)


def api_reachable(api: str) -> bool:
    import urllib.request
    try:
        with urllib.request.urlopen(api.rstrip("/") + "/actuator/health", timeout=10):
            return True
    except Exception:  # noqa: BLE001 — toute erreur réseau signifie « injoignable »
        return False


def run(steps: list[Step], cfg: dict, env: dict[str, str], dry_run: bool = False) -> int:
    work: Path = cfg["work"]
    log = work / "campaign.log"
    work.mkdir(parents=True, exist_ok=True)
    for step in steps:
        if step.manual:
            if is_done(step, work):
                print(f"[{step.id}] fait (manuel)")
                continue
            print(f"\n[{step.id}] ÉTAPE MANUELLE — {step.title}\n{step.manual}\n"
                  "Relancer ensuite la campagne ; les étapes suivantes qui n'en dépendent pas continuent.\n")
            continue
        if is_done(step, work):
            print(f"[{step.id}] déjà fait ({step.produces})")
            continue
        if step.needs_stack and not dry_run and not api_reachable(cfg["api"]):
            print(f"[{step.id}] API injoignable : {cfg['api']}", file=sys.stderr)
            return 2
        print(f"[{step.id}] {step.title}", flush=True)
        if dry_run:
            print("    $ " + " ".join(step.command))
            continue
        with log.open("a", encoding="utf-8") as handle:
            handle.write(f"\n=== {step.id} : {' '.join(step.command)}\n")
            handle.flush()
            done = subprocess.run(step.command, cwd=ROOT, env=env, stdout=handle, stderr=subprocess.STDOUT, check=False)
        if done.returncode:
            print(f"[{step.id}] ÉCHEC (code {done.returncode}) — voir {log}", file=sys.stderr)
            return 1
        if step.id == "privacy-runtime":
            (work / ".privacy-runtime-ok").write_text("ok\n")
    return 0


def parse_finetune(value: str | None) -> dict[str, str] | None:
    if not value:
        return None
    pairs = dict(item.split("=", 1) for item in value.split(","))
    if "base" not in pairs:
        raise ValueError("--finetune doit contenir base=<modèle>")
    return pairs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=("plan", "run"))
    parser.add_argument("--only")
    parser.add_argument("--from", dest="start")
    parser.add_argument("--until")
    parser.add_argument("--finetune", help="base=<modèle>,qlora=<modèle>,qlora-f16=<modèle>[,dpo=…]")
    parser.add_argument("--dry-run", action="store_true", help="avec run : affiche les commandes sans les lancer")
    args = parser.parse_args(argv)
    env = dict(os.environ)
    cfg = settings(env)
    try:
        steps = select(build_steps(cfg, parse_finetune(args.finetune)), args.only, args.start, args.until)
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 2
    if args.action == "plan":
        print(render_plan(steps, cfg["work"]))
        return 0
    problems = [] if args.dry_run else preflight(steps, cfg, env)
    if problems:
        print("Prérequis manquants :\n  " + "\n  ".join(problems), file=sys.stderr)
        return 2
    return run(steps, cfg, env, args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
