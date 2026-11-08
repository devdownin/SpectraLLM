#!/usr/bin/env python3
"""Capture and compare two SpectraLLM runs against the same frozen corpus."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
import os
from pathlib import Path
import statistics
import time
from urllib.request import Request, urlopen


def capture(args):
    raw = args.questions.read_bytes()
    cases = [json.loads(line) for line in raw.splitlines() if line.strip()]
    if (not cases or not 1 <= args.k <= 20 or args.warmup < 0
            or min(args.repeat, args.concurrency) < 1 or args.timeout <= 0):
        raise ValueError("Expected cases, 1 <= k <= 20, nonnegative warmup and positive repeat, concurrency and timeout")
    options = {"collection": args.collection, "temperature": 0, "maxContextChunks": args.k,
               "overrides": {"hybrid": args.hybrid, "multiQuery": False}}
    headers = {"Content-Type": "application/json"}
    if key := os.environ.get("SPECTRA_API_KEY"):
        headers["X-API-Key"] = key

    def query(case):
        body = options | {"question": case["question"], "filter": case.get("filter") or {}}
        endpoint = "/api/query" if args.generate else "/api/query/retrieve"
        request = Request(args.api.rstrip("/") + endpoint, json.dumps(body).encode(), headers)
        start = time.perf_counter()
        with urlopen(request, timeout=args.timeout) as response:
            result = json.load(response)
        elapsed = (time.perf_counter() - start) * 1000
        passages = result["sources"]
        sources = [source["sourceFile"] for source in passages]
        ranked = list(dict.fromkeys(sources))
        expected = case.get("expectedSources") or []
        names = {name for source in ranked[:args.k] for name in (source, Path(source).stem)}
        allowed = body["filter"].get("sources") or []
        citations = result.get("citations")
        return {
            "clientMs": elapsed, "serverMs": result["durationMs"], "sources": ranked,
            "passages": [{
                "source": source["sourceFile"],
                "revision": (source.get("provenance") or {}).get("revision"),
                "chunkIndex": (source.get("provenance") or {}).get("chunkIndex"),
                "textSha256": hashlib.sha256(source["text"].encode()).hexdigest(),
            } for source in passages],
            "recall": len(set(expected) & names) / len(set(expected)) if expected else None,
            "unexpectedSources": sorted(set(ranked) - set(allowed)) if allowed else [],
            "unexpectedNonempty": bool(case.get("expectEmpty") and ranked),
            "hybridApplied": result["hybridSearchApplied"], "rerankApplied": result["rerankApplied"],
            "citations": ({key: citations[key] for key in (
                "citedSentences", "supportedSentences", "invalidCitations")} if citations is not None else None),
        }

    if args.output.exists():
        raise ValueError(f"Capture already exists: {args.output}")
    for index in range(args.warmup):
        query(cases[index % len(cases)])
    workload = cases * args.repeat
    started = time.perf_counter()
    if args.concurrency == 1:
        samples = [query(case) for case in workload]
    else:
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            samples = list(pool.map(query, workload))
    elapsed = time.perf_counter() - started
    manifest = {"suite": hashlib.sha256(raw).hexdigest(), "options": options, "generate": args.generate,
                "warmup": args.warmup, "repeat": args.repeat, "concurrency": args.concurrency}
    with args.output.open("x", encoding="utf-8") as output:
        json.dump({"engine": args.engine, "manifest": manifest, "elapsedSeconds": elapsed, "samples": samples}, output, indent=2)


def summarize(rows):
    result = {"requests": len(rows), "empty": sum(not r["sources"] for r in rows),
              "scopeViolations": sum(bool(r["unexpectedSources"] or r["unexpectedNonempty"]) for r in rows),
              "hybridApplied": sum(r["hybridApplied"] for r in rows),
              "rerankApplied": sum(r["rerankApplied"] for r in rows)}
    for field in ("clientMs", "serverMs"):
        values = sorted(r[field] for r in rows)
        result[field] = {"p50": statistics.median(values), "p95": values[math.ceil(0.95 * len(values)) - 1]}
    recalls = [r["recall"] for r in rows if r["recall"] is not None]
    result["recallAtK"] = statistics.mean(recalls) if recalls else None
    citations = [r["citations"] for r in rows if r["citations"] is not None]
    result["citationReports"] = len(citations)
    for field in ("citedSentences", "supportedSentences", "invalidCitations"):
        result[field] = sum(c[field] for c in citations) if citations else None
    return result


def compare(args):
    chroma = json.loads(args.chroma.read_text(encoding="utf-8"))
    qdrant = json.loads(args.qdrant.read_text(encoding="utf-8"))
    left, right = chroma["samples"], qdrant["samples"]
    if (chroma["engine"] != "chroma" or qdrant["engine"] != "qdrant"
            or chroma["manifest"] != qdrant["manifest"] or not left or len(left) != len(right)):
        raise ValueError("Expected complete Chroma and Qdrant captures with the same suite and options")
    report = {"chroma": summarize(left), "qdrant": summarize(right)}
    for capture_data in (chroma, qdrant):
        if elapsed := capture_data.get("elapsedSeconds"):
            report[capture_data["engine"]]["requestsPerSecond"] = len(left) / elapsed
    baseline = report["chroma"]["clientMs"]["p95"]
    candidate = report["qdrant"]["clientMs"]["p95"]
    report["clientP95GainPct"] = 100 * (baseline - candidate) / baseline if baseline else None
    baseline_recall = report["chroma"]["recallAtK"]
    candidate_recall = report["qdrant"]["recallAtK"]
    report["recallDeltaPoints"] = (100 * (candidate_recall - baseline_recall)
                                   if baseline_recall is not None and candidate_recall is not None else None)
    report["rankedSourcesChanged"] = sum(a["sources"] != b["sources"] for a, b in zip(left, right, strict=True))
    report["rankedPassagesChanged"] = sum(
        a["passages"] != b["passages"] for a, b in zip(left, right, strict=True)
    )
    report["pipelineFlagsChanged"] = sum(
        (a["hybridApplied"], a["rerankApplied"]) != (b["hybridApplied"], b["rerankApplied"])
        for a, b in zip(left, right, strict=True)
    )
    print(json.dumps(report, indent=2))
    return 1 if report["chroma"]["scopeViolations"] or report["qdrant"]["scopeViolations"] else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("capture")
    run.add_argument("--engine", choices=("chroma", "qdrant"), required=True)
    run.add_argument("--api", default="http://127.0.0.1:8080")
    run.add_argument("--collection", required=True)
    run.add_argument("--questions", type=Path, required=True)
    run.add_argument("--output", type=Path, required=True)
    run.add_argument("--k", type=int, default=10)
    run.add_argument("--warmup", type=int, default=3)
    run.add_argument("--repeat", type=int, default=1)
    run.add_argument("--concurrency", type=int, default=1)
    run.add_argument("--timeout", type=float, default=120)
    run.add_argument("--hybrid", action="store_true")
    run.add_argument("--generate", action="store_true")
    diff = commands.add_parser("compare")
    diff.add_argument("chroma", type=Path)
    diff.add_argument("qdrant", type=Path)
    args = parser.parse_args()
    if args.command == "capture":
        capture(args)
        return 0
    return compare(args)


if __name__ == "__main__":
    raise SystemExit(main())
