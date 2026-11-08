#!/usr/bin/env python3
"""Diagnostics Onyx sur dev-120, à partir de captures et d'instances dédiées."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode

from enterprise_rag_bench import NO_GOLD_TYPES, SpectraClient, load_questions
from enterprise_rag_eval import load_metric, paired_report
from enterprise_rag_experiment import (
    HERE, corpus_identity, freeze, judge, judge_data, jsonl, lock, read_json,
    require_public_url, run, run_data, sha256, write_json,
)


def development(split_dir: Path) -> Path:
    path = split_dir / "dev-120.jsonl"
    rows = jsonl(path)
    if len(rows) != 120 or list(rows) != read_json(split_dir / "split.json")["dev_ids"]:
        raise ValueError("lot dev-120 incompatible avec split.json")
    return path


def judge_run(args: argparse.Namespace, directory: Path) -> None:
    judge(argparse.Namespace(out=directory, evaluator_dir=args.evaluator_dir,
                             python=args.python, parallelism=args.judge_parallelism))


def oracle(args: argparse.Namespace) -> None:
    dev = development(args.split_dir)
    manifest, _, records = run_data(args.reference)
    if manifest["mode"] != "answer" or manifest["questions_sha256"] != sha256(dev):
        raise ValueError("la référence doit être une capture answer du lot dev-120")
    if manifest["corpus"] != corpus_identity(args.corpus_dir):
        raise ValueError("le corpus ne correspond pas à la référence")
    profile = read_json(args.profile)
    if profile["chat"] != manifest["profile"]["chat"]:
        raise ValueError("le modèle oracle doit correspondre au modèle de référence")
    results = args.reference / "results.json"
    judge_data(results, set(records))
    metadata = read_json(args.reference / "judge.json")
    if metadata["answers_sha256"] != sha256(args.reference / "records.jsonl"):
        raise ValueError("les réponses de référence ont changé après jugement")
    scores, types = load_metric(results, "score")
    labeled = {q.question_id for q in load_questions(dev)
               if q.expected_doc_ids and q.question_type not in NO_GOLD_TYPES}
    selected = sorted((qid for qid in scores if qid in labeled and scores[qid] < 100),
                      key=scores.__getitem__)[:20]
    if not selected:
        raise ValueError("aucun échec annoté dans la référence jugée")

    with lock(args.out):
        freeze(args.out / "selection.json", {
            "reference_results_sha256": sha256(results), "question_ids": selected,
        })
        questions = args.out / "oracle-dev.jsonl"
        rows = jsonl(dev)
        questions.write_text("".join(json.dumps(rows[qid], ensure_ascii=False) + "\n" for qid in selected),
                             encoding="utf-8")
        captures = {"rag": results}
        for style in ("concise", "detailed"):
            directory = args.out / style
            run(argparse.Namespace(mode="oracle", questions=questions, corpus_dir=args.corpus_dir,
                                   profile=args.profile, out=directory, api=args.api, collection=None,
                                   parallelism=1, timeout=args.timeout, model=args.model,
                                   style=style, max_tokens=args.max_tokens))
            judge_run(args, directory)
            if read_json(directory / "judge.json")["configuration"] != metadata["configuration"]:
                raise ValueError("le juge oracle diffère de celui de la référence")
            captures[style] = directory / "results.json"

        report = {"n": len(selected), "comparisons": {}}
        for a, b in (("rag", "concise"), ("rag", "detailed"), ("concise", "detailed")):
            metrics = {}
            for metric in ("score", "correct", "completeness"):
                left, _ = load_metric(captures[a], metric)
                right, _ = load_metric(captures[b], metric)
                metrics[metric] = paired_report({qid: left[qid] for qid in selected},
                                                {qid: right[qid] for qid in selected}, types)
            report["comparisons"][f"{a}_vs_{b}"] = metrics
        write_json(args.out / "report.json", report)


def measurement(client: SpectraClient, metric: str, statistic: str, tag: str) -> float:
    query = urlencode({"tag": tag})
    try:
        response = client.get(f"/actuator/metrics/{metric}?{query}")
    except HTTPError as exc:
        if exc.code == 404 and statistic == "COUNT":
            return 0
        raise
    for item in response["measurements"]:
        if item["statistic"] == statistic:
            return item["value"]
    raise ValueError(f"statistique {statistic} absente pour {metric}")


def snapshot(client: SpectraClient) -> dict:
    metric = "spectra.retrieval.qdrant.hybrid"
    return {
        "used": measurement(client, metric, "COUNT", "outcome:used"),
        "fallback": measurement(client, metric, "COUNT", "outcome:fallback"),
        "heap_bytes": measurement(client, "jvm.memory.used", "VALUE", "area:heap"),
    }


def native(args: argparse.Namespace) -> None:
    if args.api.rstrip("/") == args.native_api.rstrip("/"):
        raise ValueError("les deux modes doivent être accessibles sur des API distinctes")
    if not args.retrieval_only and args.evaluator_dir is None:
        raise ValueError("--evaluator-dir est requis pour mesurer le score")
    questions = development(args.split_dir)
    all_questions = args.work_dir / "questions.jsonl"
    if sha256(all_questions) != read_json(args.split_dir / "split.json")["source_sha256"]:
        raise ValueError("questions du corpus incompatibles avec le découpage")
    corpus = args.work_dir / "documents"
    gold = {doc for q in load_questions(all_questions) for doc in q.expected_doc_ids}
    distractors = sum(path.stem not in gold for path in corpus.glob("dsid_*.txt"))
    if distractors != args.distractors:
        raise ValueError(f"palier attendu : {args.distractors} distracteurs, trouvé : {distractors}")

    profile = read_json(args.profile)
    with lock(args.out):
        report = {"distractors": distractors, "modes": {}}
        for mode in (("retrieve",) if args.retrieval_only else ("retrieve", "answer")):
            captures = []
            activity = {}
            indexed_chunks = None
            for name, api in (("java", args.api), ("native", args.native_api)):
                require_public_url(api)
                client = SpectraClient(api, min(args.timeout, 30))
                client.wait_ready(args.wait_ready)
                status = client.get("/api/status")["services"]
                ready = {item["name"] for item in status if item["available"] is True}
                if not {"qdrant", "reranker"} <= ready:
                    raise ValueError(f"{api} : Qdrant ou reranker indisponible")
                fts = client.get("/api/status/fts?" + urlencode({"collection": args.collection}))
                if not fts["ready"] or fts["indexedChunks"] <= 0:
                    raise ValueError(f"{api} : index lexical indisponible")
                if indexed_chunks is not None and indexed_chunks != fts["indexedChunks"]:
                    raise ValueError("les instances n'indexent pas le même nombre de passages")
                indexed_chunks = fts["indexedChunks"]
                directory = args.out / f"{mode}-{name}"
                proof = args.out / f"{mode}-{name}-activity.json"
                config = args.out / f"profile-{name}.json"
                freeze(config, {**profile, "qdrant_native_hybrid": name == "native"})
                before = snapshot(client) if not proof.exists() else None
                if before is not None and (directory / "records.jsonl").exists():
                    raise ValueError(f"{directory} : capture sans relevé complet, utiliser un nouveau répertoire")
                run(argparse.Namespace(mode=mode, out=directory, api=api, questions=questions,
                                       profile=config, corpus_dir=corpus, collection=args.collection,
                                       timeout=args.timeout, wait_ready=args.wait_ready, parallelism=1,
                                       hybrid="on", multi_query="off", self_rag="off", max_context_chunks=10,
                                       top_candidates=args.top_candidates, neighbor_window=0, rerank_min_score=None))
                if before is not None:
                    after = snapshot(client)
                    used = after["used"] - before["used"]
                    fallback = after["fallback"] - before["fallback"]
                    if min(used, fallback) < 0 or (name == "native" and used <= 0) or (
                        name == "java" and (used or fallback)
                    ):
                        raise ValueError(f"{api} : compteurs incompatibles avec le profil {name}")
                    freeze(proof, {"used": used, "fallback": fallback,
                                   "heap_before_bytes": before["heap_bytes"],
                                   "heap_after_bytes": after["heap_bytes"],
                                   "records_sha256": sha256(directory / "records.jsonl")})
                activity[name] = read_json(proof)
                if activity[name]["records_sha256"] != sha256(directory / "records.jsonl"):
                    raise ValueError(f"{directory} : capture modifiée après le relevé")
                if mode == "answer":
                    judge_run(args, directory)
                captures.append(directory)
            comparison = subprocess.check_output(
                [sys.executable, str(HERE / "enterprise_rag_experiment.py"), "compare", *map(str, captures)],
                text=True,
            )
            report["modes"][mode] = {"activity": activity, "comparison": json.loads(comparison)}
            write_json(args.out / "report.json", report)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name, action in (("oracle", oracle), ("native", native)):
        command = commands.add_parser(name)
        command.set_defaults(action=action)
        for option in ("split-dir", "profile", "out"):
            command.add_argument(f"--{option}", type=Path, required=True)
        command.add_argument("--evaluator-dir", type=Path, required=name == "oracle")
        command.add_argument("--python", default=sys.executable)
        command.add_argument("--judge-parallelism", type=int, default=4)
        command.add_argument("--timeout", type=float, default=600)
        if name == "oracle":
            command.add_argument("--reference", type=Path, required=True)
            command.add_argument("--corpus-dir", type=Path, required=True)
            command.add_argument("--api", default="http://localhost:8091")
            command.add_argument("--model", required=True)
            command.add_argument("--max-tokens", type=int, default=512)
        else:
            command.add_argument("--work-dir", type=Path, required=True)
            command.add_argument("--api", required=True)
            command.add_argument("--native-api", required=True)
            command.add_argument("--collection", required=True)
            command.add_argument("--distractors", type=int, choices=(5000, 20000), required=True)
            command.add_argument("--top-candidates", type=int, choices=(20, 50), required=True)
            command.add_argument("--wait-ready", type=float, default=600)
            command.add_argument("--retrieval-only", action="store_true")
    args = parser.parse_args()
    try:
        args.action(args)
    except (OSError, ValueError, KeyError, TypeError, IndexError, subprocess.CalledProcessError) as exc:
        print(f"[{args.command}] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
