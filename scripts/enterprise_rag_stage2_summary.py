#!/usr/bin/env python3
"""Verdict adopter/rejeter pour les comparaisons de la piste recherche (mode retrieve).

Entrée : le JSON de `enterprise_rag_experiment.py compare`, ou le `report.json` de `suite`
(un verdict par comparaison de `retrieval`).

Règle de l'étape 2 : adopter la variante si rappel@10 ET nDCG@10 progressent (IC95 % > 0), si le bruit
`extra@10` n'augmente pas significativement et si le p95 client reste sous ``--max-p95-ratio``.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def verdict(report: dict, max_p95_ratio: float = 2.0) -> dict:
    def gain(metric):
        row = report.get(metric)
        return bool(row) and row["ci95"][0] > 0

    def worse(metric):  # extra@10 : plus haut = pire
        row = report.get(metric)
        return bool(row) and row["ci95"][0] > 0

    latency = report.get("client-ms") or {}
    ratio = latency["p95_b"] / latency["p95_a"] if latency.get("p95_a") else None
    checks = {"recall@10": gain("recall@10"), "ndcg@10": gain("ndcg@10"),
              "noise_not_worse": not worse("extra@10"),
              "p95_within_budget": ratio is None or ratio <= max_p95_ratio}
    return {"adopt": all(checks.values()), "checks": checks, "p95_ratio": ratio,
            "mean_diff": {m: report[m]["mean_diff"] for m in ("recall@10", "mrr@10", "ndcg@10", "all-gold@10", "extra@10")
                          if m in report}}


def verdicts(data: dict, max_p95_ratio: float = 2.0) -> dict:
    if "retrieval" in data:  # report.json de ``suite``
        return {name: verdict(report, max_p95_ratio) for name, report in data["retrieval"].items()}
    return verdict(data, max_p95_ratio)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="compare JSON ou report.json de suite")
    parser.add_argument("--max-p95-ratio", type=float, default=2.0)
    args = parser.parse_args()
    print(json.dumps(verdicts(json.loads(args.report.read_text()), args.max_p95_ratio), indent=2))


if __name__ == "__main__":
    main()
