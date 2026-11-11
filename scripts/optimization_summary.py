#!/usr/bin/env python3
"""Synthèse et décision (étape 7) : assemble les résultats des étapes 1 à 6 en un seul rapport.

Lit ce que les étapes ont produit, sans rien relancer, et dit pour chacune : mesurée ou non, ce qu'elle
conclut, et la commande qui manque. Sans mesure, la synthèse l'écrit : elle ne remplit jamais un chiffre
par défaut. Une décision n'est rendue que sur des résultats présents.

Entrées (toutes facultatives, chemins par défaut sous ``--work``) :

    étape 1  <work>/baseline-retrieve/report.json, <work>/baseline-answer/report.json
    étape 2  <work>/stage2/report.json                 (commande ``suite``)
    étape 3  <work>/audit/matrix.json                  (``enterprise_rag_matrix.py``)
    étape 4  <work>/baseline-answer/generation-audit.json (ou baseline-answer-opus/, copie jugée à part)
    étape 5  --finetune <répertoire>/decision.json     (``finetune_audit.py decide``)
    étape 6  contrôles statiques exécutés ici (``privacy_audit.py logs`` et ``egress``) ;
             les contrôles d'exécution (§ 5 de la checklist) ne laissent pas de fichier : à déclarer
             avec ``--runtime-checked`` une fois faits.

Sortie : un tableau Markdown par étape et un verdict global, sur la sortie standard et dans ``--out``.
Code de sortie : 0 si toutes les étapes sont mesurées et sans refus, 1 si un refus (NO-GO, écart de
confidentialité) est établi, 2 si des mesures manquent.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

import privacy_audit
from enterprise_rag_stage2_summary import verdicts

COMMANDS = {
    1: "scripts/enterprise_rag_baseline.sh",
    2: "scripts/enterprise_rag_experiment.py suite --retrieval-only … (docs/tech/enterprise-rag-stage2.fr.md)",
    3: "scripts/enterprise_rag_audit.sh <index> modes ann, puis scripts/enterprise_rag_matrix.py (stage3)",
    4: "scripts/enterprise_rag_generation_audit.py ground puis report (docs/tech/enterprise-rag-stage4.fr.md)",
    5: "scripts/finetune_audit.py collect puis decide (docs/tech/finetune-audit.fr.md)",
    6: "scripts/privacy_runtime_check.sh et § 5 de docs/process/checklist-confidentialite.fr.md",
}
TITLES = {1: "Baseline", 2: "Piste recherche", 3: "Audit du retrieval", 4: "Audit de la génération",
          5: "Audit du fine-tuning", 6: "Confidentialité"}


@dataclass
class Stage:
    number: int
    measured: bool = False
    refused: bool = False
    lines: list[str] = field(default_factory=list)   # constats, une ligne chacun
    decisions: list[str] = field(default_factory=list)

    @property
    def status(self) -> str:
        if self.refused:
            return "REFUS"
        if not self.measured:
            return "PARTIELLE" if self.lines else "NON MESURÉE"
        return "MESURÉE"


def read(path: Path) -> dict | list | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def pct(value, digits=1) -> str:
    return "—" if value is None else f"{value * 100:.{digits}f} %"


def num(value, digits=3) -> str:
    return "—" if value is None else f"{value:.{digits}f}"


def interval(row: dict | None, key: str = "mean", digits=3) -> str:
    if not row or row.get(key) is None:
        return "—"
    low, high = row.get("ci95", [None, None])
    ci = "" if low is None else f" [{low:.{digits}f} ; {high:.{digits}f}]"
    return f"{row[key]:.{digits}f}{ci}"


def stage1(work: Path) -> Stage:
    stage = Stage(1)
    retrieve = read(work / "baseline-retrieve" / "report.json")
    answer = read(work / "baseline-answer" / "report.json")
    if retrieve and "retrieval" in retrieve:
        r, latency = retrieve["retrieval"], retrieve["latency_ms"]["client"]
        stage.measured = True
        stage.lines.append(f"retrieve : recall@10 {interval(r.get('recall@10'))}, nDCG@10 "
                           f"{interval(r.get('ndcg@10'))}, MRR@10 {num(r.get('mrr@10', {}).get('mean'))}, "
                           f"extra@10 {num(r.get('extra@10', {}).get('mean'), 2)}, p95 client {num(latency.get('p95'), 0)} ms")
        applied = retrieve.get("applied", {})
        for flag in ("rerankApplied", "hybridSearchApplied"):
            if applied.get(flag, 0) < 0.99:
                stage.lines.append(f"⚠ {flag} = {applied.get(flag)} : la baseline n'a pas mesuré ce qu'elle annonce")
                stage.refused = True
    generation = (answer or {}).get("generation")
    if generation:
        stage.lines.append(f"answer : score {interval(generation.get('score'), digits=1)}, exactitude "
                           f"{interval(generation.get('correct'), digits=1)}, complétude "
                           f"{interval(generation.get('completeness'), digits=1)} (juge {generation['judge'].get('model')})")
    elif answer:
        stage.lines.append("answer : run présent mais non jugé (`enterprise_rag_experiment.py judge`)")
    return stage


def stage2(work: Path) -> Stage:
    stage = Stage(2)
    report = read(work / "stage2" / "report.json")
    if not report or "retrieval" not in report:
        return stage
    stage.measured = True
    stage.lines.append(f"budget de candidats retenu par `suite` : {report.get('candidate_budget')} ; "
                       f"meilleures variantes : {', '.join(report.get('shortlist', [])) or '—'}")
    for name, result in verdicts(report).items():
        diffs = result["mean_diff"]
        label = "ADOPTER" if result["adopt"] else "rejeter"
        failed = [check for check, ok in result["checks"].items() if not ok]
        stage.decisions.append(f"{name} : **{label}** (Δ recall@10 {num(diffs.get('recall@10'))}, Δ nDCG@10 "
                               f"{num(diffs.get('ndcg@10'))}" + (f" ; échecs : {', '.join(failed)}" if failed else "") + ")")
    return stage


def stage3(work: Path) -> Stage:
    stage = Stage(3)
    rows = read(work / "audit" / "matrix.json")
    if not rows:
        return stage
    stage.measured = True
    best = max((r for r in rows if r.get("recall@10") is not None), key=lambda r: r["recall@10"], default=None)
    if best:
        stage.lines.append(f"meilleur rappel : {best['index']}/{best['variant']} recall@10 {num(best['recall@10'])}, "
                           f"nDCG@10 {num(best['ndcg@10'])}, p95 {num(best.get('p95_ms'), 0)} ms")
    stage.lines.append(f"{len(rows)} configurations mesurées")
    for row in rows:
        if row.get("pareto"):
            stage.decisions.append(f"non dominée : {row['index']}/{row['variant']} (recall@10 {num(row['recall@10'])}, "
                                   f"p95 {num(row.get('p95_ms'), 0)} ms)")
    return stage


def stage4(work: Path) -> Stage:
    stage = Stage(4)
    # Le juge par défaut de la baseline est Opus : l'audit se fait sur le run lui-même ; une copie
    # jugée à part (`baseline-answer-opus`) reste lue si elle existe.
    audit = read(work / "baseline-answer" / "generation-audit.json") or read(
        work / "baseline-answer-opus" / "generation-audit.json")
    if not audit:
        return stage
    stage.measured = True
    overall = audit["overall"]
    stage.lines.append(f"juge {audit.get('grounding', {}).get('judge', {}).get('model', '?')} : exactitude "
                       f"{num(overall.get('correct'), 1)}, complétude {num(overall.get('completeness'), 1)}, fidélité "
                       f"{pct(overall.get('grounded'))}, précision des citations {pct(overall.get('citation_precision'))}")
    stage.lines.append(f"refus correct (info_not_found) {pct(overall.get('correct_refusal'))}, "
                       f"refus abusif {pct(overall.get('false_refusal'))}")
    weakest = min(((t, row) for t, row in audit["by_type"].items() if row.get("correct") is not None),
                  key=lambda item: item[1]["correct"], default=None)
    if weakest:
        stage.decisions.append(f"catégorie la plus faible : {weakest[0]} (exactitude {num(weakest[1]['correct'], 1)} %, "
                               f"n={weakest[1]['n']})")
    return stage


def stage5(finetune: Path | None) -> Stage:
    stage = Stage(5)
    decision = read(finetune / "decision.json") if finetune else None
    if not decision:
        return stage
    stage.measured = True
    for label, version in decision["versions"].items():
        go = version["decision"] == "GO"
        stage.refused = stage.refused or not go
        stage.decisions.append(f"{label} : **{version['decision']}**"
                               + ("" if go else " — " + " ; ".join(version["reasons"])))
    stage.lines.append(f"base : {decision['base']['model']}, juge : {decision.get('judge')}")
    return stage


def stage6(runtime_checked: bool) -> Stage:
    stage = Stage(6, measured=runtime_checked)
    java_logs = privacy_audit.scan_logs(privacy_audit.ROOT / "backend" / "src" / "main" / "java")
    unknown, stale = privacy_audit.check_egress(privacy_audit.ROOT, privacy_audit.ALLOWLIST)
    stage.lines.append(f"journaux : {'conformes' if not java_logs else f'{len(java_logs)} écart(s)'} ; "
                       f"sorties réseau : {'toutes déclarées' if not unknown else f'{len(unknown)} hôte(s) non déclaré(s)'}"
                       f"{f' ({len(stale)} entrée(s) périmée(s))' if stale else ''}")
    stage.refused = bool(java_logs or unknown)
    stage.lines.append("contrôles d'exécution (capture réseau, résidus Qdrant) : "
                       + ("déclarés effectués" if runtime_checked else "**non effectués** (§ 5 de la checklist)"))
    if not runtime_checked:
        stage.decisions.append("la checklist reste « validée sur plan » tant que le § 5 n'a pas été exécuté sur un hôte")
    return stage


def render(stages: list[Stage]) -> str:
    out = ["# Synthèse et décision — optimisation SpectraLLM", "",
           "| Étape | État | Constat |", "|---|---|---|"]
    for stage in stages:
        summary = stage.lines[0] if stage.lines else f"à produire : `{COMMANDS[stage.number]}`"
        out.append(f"| {stage.number}. {TITLES[stage.number]} | {stage.status} | {summary} |")
    for stage in stages:
        out += ["", f"## Étape {stage.number} — {TITLES[stage.number]} ({stage.status})"]
        out += [f"- {line}" for line in stage.lines] + [f"- Décision : {line}" for line in stage.decisions]
        if not stage.measured:
            out.append(f"{'Mesure à compléter' if stage.lines else 'Aucun résultat trouvé'}. À produire avec : "
                       f"`{COMMANDS[stage.number]}`.")
    missing = [s.number for s in stages if not s.measured]
    refused = [s.number for s in stages if s.refused]
    out += ["", "## Verdict global"]
    if refused:
        out.append(f"**REFUS établi** aux étapes {', '.join(map(str, refused))} : corriger avant de retenir une configuration.")
    if missing:
        out.append(f"**INCOMPLET** : étapes sans mesure : {', '.join(map(str, missing))}. Aucune configuration n'est recommandée "
                   "sur des chiffres absents ; la baseline v1 reste la référence.")
    if not refused and not missing:
        adopted = [d for s in stages if s.number == 2 for d in s.decisions if "**ADOPTER**" in d]
        out.append("Toutes les étapes sont mesurées et sans refus. Réglages à adopter (étape 2) : "
                   + ("; ".join(adopted) if adopted else "aucun, la baseline v1 est conservée") + ".")
        out.append("Les réglages adoptés se valident ENSEMBLE : appliquer la combinaison, rejouer la baseline "
                   "(étape 1) puis confirmer sur les 380 questions de validation avant de la figer.")
    return "\n".join(out) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--work", type=Path, default=Path("data/onyx-baseline-v1"))
    parser.add_argument("--finetune", type=Path, help="répertoire de l'audit du fine-tuning (decision.json)")
    parser.add_argument("--runtime-checked", action="store_true",
                        help="déclare effectués les contrôles d'exécution de l'étape 6 (§ 5 de la checklist)")
    parser.add_argument("--out", type=Path, help="écrit aussi le rapport Markdown dans ce fichier")
    args = parser.parse_args()
    stages = [stage1(args.work), stage2(args.work), stage3(args.work), stage4(args.work),
              stage5(args.finetune), stage6(args.runtime_checked)]
    report = render(stages)
    if args.out:
        args.out.write_text(report, encoding="utf-8")
    print(report, end="")
    if any(stage.refused for stage in stages):
        return 1
    return 2 if any(not stage.measured for stage in stages) else 0


if __name__ == "__main__":
    sys.exit(main())
