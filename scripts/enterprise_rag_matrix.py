#!/usr/bin/env python3
"""Tableau comparatif rappel/latence des configurations de retrieval (audit de l'étape 3).

Lit ``<audit-dir>/<index>/<variante>/report.json`` (produit par ``enterprise_rag_experiment.py report``
sur des runs ``retrieve``), où ``<index>`` identifie un index (embedding, graphe HNSW, quantification)
et ``<variante>`` un réglage de recherche. Écrit ``matrix.md`` et ``matrix.json`` dans ``--audit-dir``.

Avec ``--reference <index>/<variante>``, ajoute l'écart apparié de rappel@10 et de nDCG@10 à cette
référence (IC95 % bootstrap). Le signe ★ marque les configurations non dominées : aucune autre n'a à la
fois un meilleur rappel@10 et un p95 client plus bas.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

METRICS = ("recall@10", "ndcg@10", "mrr@10", "all-gold@10", "extra@10")


def load_rows(audit_dir: Path) -> list[dict]:
    rows = []
    for report_path in sorted(audit_dir.glob("*/*/report.json")):
        report = json.loads(report_path.read_text(encoding="utf-8"))
        if "retrieval" not in report or "overall" not in report["retrieval"]:
            continue  # run answer ou oracle : pas de classement documentaire
        run = report_path.parent
        index, variant = run.parent.name, run.name
        retrieval, latency = report["retrieval"], report["latency_ms"]["client"]
        rows.append({
            "index": index, "variant": variant, "dir": str(run), "n": retrieval["overall"].get("n", 0),
            "settings": report["profile"].get("variant", {}),
            **{metric: retrieval.get(metric, {}).get("mean") for metric in METRICS},
            "recall_ci95": retrieval.get("recall@10", {}).get("ci95"),
            "p50_ms": latency.get("p50"), "p95_ms": latency.get("p95"),
            "applied": report.get("applied", {}),
        })
    return rows


def mark_pareto(rows: list[dict]) -> None:
    """★ si aucune autre ligne n'est au moins aussi bonne sur les deux axes et meilleure sur un."""
    for row in rows:
        row["pareto"] = row["recall@10"] is not None and row["p95_ms"] is not None and not any(
            other is not row and other["recall@10"] is not None and other["p95_ms"] is not None
            and other["recall@10"] >= row["recall@10"] and other["p95_ms"] <= row["p95_ms"]
            and (other["recall@10"] > row["recall@10"] or other["p95_ms"] < row["p95_ms"])
            for other in rows)


def add_deltas(rows: list[dict], reference: Path) -> None:
    from enterprise_rag_experiment import compare_runs  # import tardif : n'est utile qu'avec --reference

    for row in rows:
        row["delta"] = None
        if Path(row["dir"]).resolve() == reference.resolve():
            continue
        try:
            report = compare_runs(reference, Path(row["dir"]))
        except (ValueError, KeyError, OSError) as exc:
            row["delta"] = {"error": str(exc)}
            continue
        row["delta"] = {metric: {key: report[metric][key] for key in ("mean_diff", "ci95", "significant")}
                        for metric in ("recall@10", "ndcg@10") if metric in report}


def cell(value, digits=3) -> str:
    return "—" if value is None else f"{value:.{digits}f}"


def signed(delta: dict | None, metric: str) -> str:
    if not delta or metric not in delta:
        return "—"
    row = delta[metric]
    return f"{row['mean_diff']:+.3f}{'*' if row['significant'] else ''}"


def render(rows: list[dict], with_delta: bool) -> str:
    header = ["Index", "Variante", "recall@10", "ndcg@10", "mrr@10", "all-gold@10", "extra@10",
              "p50 (ms)", "p95 (ms)"] + (["Δ recall@10", "Δ ndcg@10"] if with_delta else []) + ["Pareto"]
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    for row in rows:
        values = [row["index"], row["variant"], *(cell(row[m]) for m in METRICS),
                  cell(row["p50_ms"], 0), cell(row["p95_ms"], 0)]
        if with_delta:
            values += [signed(row.get("delta"), "recall@10"), signed(row.get("delta"), "ndcg@10")]
        values.append("★" if row["pareto"] else "")
        lines.append("| " + " | ".join(values) + " |")
    if with_delta:
        lines += ["", "`*` : écart dont l'IC95 % exclut 0 (comparaison appariée à la référence)."]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--audit-dir", type=Path, required=True)
    parser.add_argument("--reference", help="<index>/<variante> servant de référence aux écarts appariés")
    args = parser.parse_args()
    rows = load_rows(args.audit_dir)
    if not rows:
        print(f"{args.audit_dir} : aucun <index>/<variante>/report.json de run retrieve", file=sys.stderr)
        return 1
    mark_pareto(rows)
    if args.reference:
        add_deltas(rows, args.audit_dir / args.reference)
    table = render(rows, bool(args.reference))
    (args.audit_dir / "matrix.md").write_text(table, encoding="utf-8")
    (args.audit_dir / "matrix.json").write_text(json.dumps(rows, indent=2, ensure_ascii=False) + "\n",
                                                encoding="utf-8")
    print(table, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
