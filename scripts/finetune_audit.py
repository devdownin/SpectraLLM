#!/usr/bin/env python3
"""Audit du fine-tuning (étape 5) : décision go/no-go pour chaque version fine-tunée.

Compare le modèle de base et chaque version (QLoRA, DPO…) sur le **même** benchmark qualité tenu à
l'écart (``POST /api/quality-benchmark``) : score d'exactitude (questions answerable, 1-10) et
hallucination sur les questions **hors corpus** (non-answerable). Vérifie aussi que la quantification
GGUF ne change pas le comportement : la version servie (q8_0) est comparée à sa référence non
quantifiée (f16, ``export_gguf.py --outtype f16``) enregistrée sous un autre nom.

Sous-commandes :

    collect  lance le benchmark pour un modèle enregistré dans Spectra et fige son rapport
             (``<out>/<label>/report.json``) ; une étiquette déjà collectée n'est pas relancée ;
    decide   compare chaque version à la base, applique les règles et écrit ``decision.json`` et
             ``decision.md``.

Le benchmark génère en décodage glouton (température 0) : deux passages d'un même modèle donnent les
mêmes réponses, et un écart entre versions n'est pas un effet de tirage. Les notes viennent du juge
configuré côté serveur (``spectra.evaluation.judge-model``) ; un rapport auto-jugé (juge = modèle évalué)
est refusé, car chaque version se noterait elle-même.

Bibliothèque standard uniquement.
"""
from __future__ import annotations

import argparse
import difflib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from enterprise_rag_eval import paired_report

# Règles par défaut (toutes réglables en ligne de commande, et consignées dans decision.json).
DEFAULTS = {
    "score_margin": 0.5,          # perte de score moyenne tolérée (sur 10), borne basse de l'IC95 %
    "min_gain": 0.0,              # gain moyen à dépasser strictement pour justifier la version
    "category_margin": 1.0,       # perte tolérée dans une catégorie d'au moins min_category_items
    "min_category_items": 3,
    "max_new_hallucinations": 0,  # questions hors corpus refusées par la base, inventées par la version
    "max_missing_verdicts": 0.10, # part des items sans verdict du juge
    "regression_drop": 3.0,       # baisse de score d'un item comptée comme régression (liste détaillée)
    "parity_margin": 0.5,         # écart de score toléré entre GGUF quantifié et référence f16
    "min_refusal_agreement": 0.95,  # accord de refus entre GGUF quantifié et référence (hors corpus)
}


# ── Collecte ──────────────────────────────────────────────────────────────────


def http_json(method: str, url: str, timeout: float) -> dict:
    request = urllib.request.Request(url, method=method, data=b"" if method == "POST" else None,
                                     headers={"Accept": "application/json"})
    if os.environ.get("SPECTRA_API_KEY"):
        request.add_header("X-API-Key", os.environ["SPECTRA_API_KEY"])
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def collect(args: argparse.Namespace) -> None:
    directory = args.out / args.label
    report_path = directory / "report.json"
    if report_path.exists():
        print(f"[collect] {args.label} déjà collecté ({report_path})", file=sys.stderr)
        return
    api = args.api.rstrip("/")
    benchmark = http_json("GET", f"{api}/api/quality-benchmark/benchmark", 60)
    print(f"[collect] {args.label} : benchmark {benchmark.get('total')} questions, modèle « {args.model} »…",
          file=sys.stderr)
    report = http_json("POST", f"{api}/api/quality-benchmark?" + urllib.parse.urlencode({"model": args.model}),
                       args.timeout)
    if report.get("model") != args.model:
        raise ValueError(f"le serveur a évalué « {report.get('model')} » au lieu de « {args.model} »")
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "manifest.json").write_text(json.dumps(
        {"label": args.label, "model": args.model, "benchmark": benchmark}, indent=2, ensure_ascii=False) + "\n")
    temporary = report_path.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(report_path)


# ── Comparaison ───────────────────────────────────────────────────────────────


def load(out: Path, label: str) -> dict:
    path = out / label / "report.json"
    if not path.exists():
        raise ValueError(f"{label} : rapport absent ({path}), lancer collect")
    report = json.loads(path.read_text(encoding="utf-8"))
    items = {}
    for item in report["items"]:
        if item["question"] in items:
            raise ValueError(f"{label} : question en double dans le benchmark : {item['question'][:60]}")
        items[item["question"]] = item
    return {"label": label, "model": report["model"], "judge": report["judgeModel"], "items": items}


def check_reports(reports: list[dict], allow_self_judge: bool) -> list[str]:
    """Conditions de validité communes : même benchmark, même juge, juge distinct du modèle évalué."""
    problems = []
    questions = set(reports[0]["items"])
    for report in reports[1:]:
        if set(report["items"]) != questions:
            problems.append(f"{report['label']} : benchmark différent de {reports[0]['label']}")
    if len({report["judge"] for report in reports}) > 1:
        problems.append("juges différents : " + ", ".join(f"{r['label']}={r['judge']}" for r in reports))
    if not allow_self_judge:
        problems += [f"{r['label']} : auto-jugé par {r['judge']} (configurer spectra.evaluation.judge-model)"
                     for r in reports if r["judge"] == r["model"]]
    return problems


def missing_verdicts(report: dict) -> float:
    items = list(report["items"].values())
    missing = [item for item in items
               if (item["score"] is None if item["answerable"] else item["hallucinated"] is None)]
    return len(missing) / len(items) if items else 0.0


def scores(report: dict) -> tuple[dict[str, float], dict[str, str]]:
    values = {q: float(i["score"]) for q, i in report["items"].items() if i["answerable"] and i["score"] is not None}
    return values, {q: i["category"] for q, i in report["items"].items()}


def hallucination(report: dict) -> dict[str, bool]:
    return {q: i["hallucinated"] for q, i in report["items"].items()
            if not i["answerable"] and i["hallucinated"] is not None}


def compare_versions(base: dict, candidate: dict, rules: dict) -> dict:
    a, types = scores(base)
    b, _ = scores(candidate)
    score = paired_report(a, b, types)
    ha, hb = hallucination(base), hallucination(candidate)
    common = sorted(set(ha) & set(hb))
    regressions = sorted(({"question": q, "category": types[q], "base": a[q], "candidate": b[q]}
                          for q in set(a) & set(b) if a[q] - b[q] >= rules["regression_drop"]),
                         key=lambda row: row["candidate"] - row["base"])
    return {
        "score": score,
        "hallucination_rate": {"base": sum(ha[q] for q in common) / len(common) if common else None,
                               "candidate": sum(hb[q] for q in common) / len(common) if common else None,
                               "n": len(common)},
        "new_hallucinations": [q for q in common if hb[q] and not ha[q]],
        "fixed_hallucinations": [q for q in common if ha[q] and not hb[q]],
        "regressions": regressions,
        "missing_verdicts": missing_verdicts(candidate),
    }


def gguf_parity(reference: dict, quantized: dict) -> dict:
    """Écarts entre la référence non quantifiée et le GGUF servi, sur les mêmes questions."""
    a, types = scores(reference)
    b, _ = scores(quantized)
    ha, hb = hallucination(reference), hallucination(quantized)
    common = sorted(set(ha) & set(hb))
    answers = [(reference["items"][q]["modelAnswer"] or "", quantized["items"][q]["modelAnswer"] or "")
               for q in reference["items"]]
    return {
        "score": paired_report(a, b, types),
        "refusal_agreement": sum(ha[q] == hb[q] for q in common) / len(common) if common else None,
        "exact_match": sum(x == y for x, y in answers) / len(answers),
        "answer_similarity": sum(difflib.SequenceMatcher(None, x, y).ratio() for x, y in answers) / len(answers),
    }


def decide_version(comparison: dict, parity: dict | None, rules: dict, invalid: list[str],
                   parity_required: bool = True) -> list[str]:
    """Motifs de refus ; aucune entrée = GO."""
    reasons = list(invalid)
    score = comparison["score"]
    if comparison["missing_verdicts"] > rules["max_missing_verdicts"]:
        reasons.append(f"{comparison['missing_verdicts']:.0%} d'items sans verdict du juge")
    if len(comparison["new_hallucinations"]) > rules["max_new_hallucinations"]:
        reasons.append(f"{len(comparison['new_hallucinations'])} nouvelle(s) hallucination(s) hors corpus")
    if score["ci95"][0] < -rules["score_margin"]:
        reasons.append(f"score non garanti : IC95 % bas {score['ci95'][0]:+.2f} < -{rules['score_margin']}")
    if score["mean_diff"] <= rules["min_gain"]:
        reasons.append(f"aucun gain mesuré (Δ {score['mean_diff']:+.2f})")
    for category, row in score.get("by_type", {}).items():
        if row["n"] >= rules["min_category_items"] and row["mean_diff"] < -rules["category_margin"]:
            reasons.append(f"régression en « {category} » (Δ {row['mean_diff']:+.2f}, n={row['n']})")
    if parity is None:
        if parity_required:
            reasons.append("parité GGUF non vérifiée (aucune référence f16 fournie)")
    else:
        if abs(parity["score"]["mean_diff"]) > rules["parity_margin"]:
            reasons.append(f"quantification : Δ score {parity['score']['mean_diff']:+.2f} vs référence")
        agreement = parity["refusal_agreement"]
        if agreement is not None and agreement < rules["min_refusal_agreement"]:
            reasons.append(f"quantification : accord de refus {agreement:.0%}")
    return reasons


def render(decision: dict) -> str:
    lines = ["| Version | Δ score [IC95 %] | Hallucination base → version | Nouvelles halluc. | Régressions | "
             "Parité GGUF (Δ score, accord refus, similarité) | Décision | Motifs |", "|---" * 8 + "|"]
    for label, row in decision["versions"].items():
        score, rate = row["comparison"]["score"], row["comparison"]["hallucination_rate"]
        parity = row["parity"]
        parity_cell = "—" if parity is None else (
            f"{parity['score']['mean_diff']:+.2f}, "
            f"{'—' if parity['refusal_agreement'] is None else format(parity['refusal_agreement'], '.0%')}, "
            f"{parity['answer_similarity']:.2f}")
        halluc = "—" if rate["base"] is None else f"{rate['base']:.0%} → {rate['candidate']:.0%} (n={rate['n']})"
        lines.append(f"| {label} | {score['mean_diff']:+.2f} [{score['ci95'][0]:+.2f} ; {score['ci95'][1]:+.2f}] | "
                     f"{halluc} | {len(row['comparison']['new_hallucinations'])} | "
                     f"{len(row['comparison']['regressions'])} | {parity_cell} | **{row['decision']}** | "
                     f"{'; '.join(row['reasons']) or '—'} |")
    return "\n".join(lines) + "\n"


def decide(args: argparse.Namespace) -> None:
    rules = {key: getattr(args, key) for key in DEFAULTS}
    parity_refs = dict(pair.split("=", 1) for pair in args.parity)
    unknown = set(parity_refs) - set(args.candidate)
    if unknown:
        raise ValueError(f"--parity pour une version non comparée : {', '.join(sorted(unknown))}")
    labels = [args.base, *args.candidate, *parity_refs.values()]
    reports = {label: load(args.out, label) for label in dict.fromkeys(labels)}
    invalid = check_reports(list(reports.values()), args.allow_self_judge)
    versions = {}
    for label in args.candidate:
        comparison = compare_versions(reports[args.base], reports[label], rules)
        parity = gguf_parity(reports[parity_refs[label]], reports[label]) if label in parity_refs else None
        reasons = decide_version(comparison, parity, rules, invalid, not args.no_parity_required)
        versions[label] = {"model": reports[label]["model"], "decision": "NO-GO" if reasons else "GO",
                           "reasons": reasons, "comparison": comparison, "parity": parity,
                           "parity_reference": parity_refs.get(label)}
    decision = {"base": {"label": args.base, "model": reports[args.base]["model"]},
                "judge": reports[args.base]["judge"], "rules": rules, "versions": versions}
    (args.out / "decision.json").write_text(json.dumps(decision, indent=2, ensure_ascii=False) + "\n",
                                            encoding="utf-8")
    table = render(decision)
    (args.out / "decision.md").write_text(table, encoding="utf-8")
    print(table, end="")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("collect", help="benchmark qualité d'un modèle enregistré, rapport figé")
    p.add_argument("--api", default="http://localhost:8080")
    p.add_argument("--model", required=True, help="nom du modèle enregistré dans Spectra")
    p.add_argument("--label", required=True, help="étiquette : base, qlora, dpo, qlora-f16…")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--timeout", type=float, default=7200, help="secondes (benchmark bloquant, lent sur CPU)")
    p.set_defaults(action=collect)
    p = commands.add_parser("decide", help="go/no-go de chaque version contre la base")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--base", required=True)
    p.add_argument("--candidate", action="append", required=True, help="version fine-tunée (GGUF servi)")
    p.add_argument("--parity", action="append", default=[], metavar="VERSION=REFERENCE",
                   help="référence non quantifiée (f16) de la version, pour la parité GGUF")
    p.add_argument("--no-parity-required", action="store_true",
                   help="ne pas refuser une version faute de référence f16")
    p.add_argument("--allow-self-judge", action="store_true")
    for key, value in DEFAULTS.items():
        p.add_argument("--" + key.replace("_", "-"), type=type(value), default=value)
    p.set_defaults(action=decide)
    args = parser.parse_args()
    try:
        args.action(args)
    except (OSError, ValueError, KeyError, urllib.error.URLError) as exc:
        print(f"[{args.command}] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
