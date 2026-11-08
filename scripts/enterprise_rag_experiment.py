#!/usr/bin/env python3
"""Figer, exécuter et comparer les variantes SpectraLLM d'EnterpriseRAG-Bench."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from collections import defaultdict
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import urlsplit

from enterprise_rag_bench import load_questions, retrieval_report
from enterprise_rag_eval import load_metric, paired_report

HERE = Path(__file__).resolve().parent
SPLIT_SEED = "spectrallm-onyx-quality-v1"


def sha256(path: Path) -> str:
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def write_json(path: Path, data: dict) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def freeze(path: Path, data: dict) -> None:
    if not path.exists():
        write_json(path, data)
    elif read_json(path) != data:
        raise ValueError(f"{path} : configuration différente, utilisez un nouveau répertoire")


def unique_rows(rows, path: Path) -> dict:
    by_id = {}
    for row in rows:
        question_id = row["question_id"]
        if question_id in by_id:
            raise ValueError(f"{path} : identifiant dupliqué {question_id}")
        by_id[question_id] = row
    return by_id


def jsonl(path: Path) -> dict:
    with path.open(encoding="utf-8") as file:
        return unique_rows((json.loads(line) for line in file if line.strip()), path)


def require_ids(path: Path, rows: dict, expected: set[str], *, partial: bool = False) -> None:
    missing = expected - rows.keys()
    unexpected = rows.keys() - expected
    if unexpected or (missing and not partial):
        raise ValueError(f"{path} : {len(missing)} questions manquantes, {len(unexpected)} inattendues")


@contextmanager
def lock(directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    marker = directory / ".running"
    try:
        marker.mkdir()
    except FileExistsError as exc:
        raise ValueError(f"{directory} : une exécution détient déjà le verrou {marker.name}") from exc
    try:
        yield
    finally:
        marker.rmdir()


def split(args: argparse.Namespace) -> None:
    rows = jsonl(args.questions)
    categories = defaultdict(list)
    for row in rows.values():
        categories[row["question_type"]].append(row["question_id"])
    if len(rows) != 500 or len(categories) != 10 or min(map(len, categories.values())) < 3:
        raise ValueError("le découpage 120/380 attend les 500 questions Onyx et leurs 10 catégories")

    def rank(question_id: str) -> bytes:
        return hashlib.sha256(f"{SPLIT_SEED}:{question_id}".encode()).digest()

    development = {qid for group in categories.values()
                   for qid in sorted(group, key=rank)[:min(12, len(group) - 2)]}
    reserve = sorted((qid for group in categories.values() if len(group) >= 20
                      for qid in sorted(group, key=rank)[:-2] if qid not in development), key=rank)
    development.update(reserve[:120 - len(development)])
    if len(development) != 120:
        raise ValueError("impossible de réserver des questions de chaque catégorie dans les deux lots")

    with lock(args.out):
        freeze(args.out / "split.json", {"source_sha256": sha256(args.questions), "seed": SPLIT_SEED,
                                       "dev_ids": [qid for qid in rows if qid in development]})
        for name, ids in (("dev-120", development), ("holdout-380", rows.keys() - development)):
            path = args.out / f"{name}.jsonl"
            temporary = path.with_suffix(".tmp")
            with temporary.open("w", encoding="utf-8", newline="\n") as file:
                for qid, row in rows.items():
                    if qid in ids:
                        file.write(json.dumps(row, ensure_ascii=False) + "\n")
            temporary.replace(path)


def corpus_identity(directory: Path) -> dict:
    digest = hashlib.sha256()
    documents = sorted(directory.glob("dsid_*.txt"))
    if not documents:
        raise ValueError(f"{directory} : aucun document dsid_*.txt")
    for path in documents:
        digest.update(f"{path.name}\0{sha256(path)}\n".encode())
    return {"count": len(documents), "sha256": digest.hexdigest()}


def require_public_url(url: str) -> None:
    parsed = urlsplit(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or any(
        (parsed.username, parsed.password, parsed.query, parsed.fragment)
    ):
        raise ValueError("utilisez une URL HTTP(S) sans identifiants, paramètres ni fragment")


def run(args: argparse.Namespace) -> None:
    require_public_url(args.api)
    if args.mode == "retrieve" and args.self_rag == "on":
        raise ValueError("Self-RAG nécessite le mode answer")
    questions = args.questions.resolve()
    rows = jsonl(questions)
    if not rows:
        raise ValueError("le lot de questions est vide")
    profile = read_json(args.profile)
    required = {"backend_revision", "index", "embedding", "chat", "reranker", "context_tokens_per_request"}
    if not isinstance(profile, dict) or not required <= profile.keys():
        raise ValueError(f"--profile doit décrire la pile, sans secrets ({', '.join(sorted(required))})")
    query = {"hybrid": args.hybrid, "multi-query": args.multi_query, "dedup": "none",
             "max-context-chunks": args.max_context_chunks, "top-candidates": args.top_candidates,
             "neighbor-window": args.neighbor_window, "temperature": 0}
    if args.rerank_min_score is not None:
        query["rerank-min-score"] = args.rerank_min_score
    if args.mode == "answer":
        query["self-rag"] = args.self_rag
    # Même requête de recherche dans les deux modes, suffixe compris.
    query["question-suffix"] = "\n\nAnswer in English."
    with lock(args.out):
        manifest = {"mode": args.mode, "api": args.api, "collection": args.collection,
                    "questions_sha256": sha256(questions), "corpus": corpus_identity(args.corpus_dir),
                    "profile": profile, "query": query, "parallelism": args.parallelism,
                    "scripts": {name: sha256(HERE / name) for name in
                                (Path(__file__).name, "enterprise_rag_bench.py")}}
        manifest_path = args.out / "run.json"
        records = args.out / "records.jsonl"
        snapshot = args.out / "questions.jsonl"
        if records.exists() and not (manifest_path.exists() and snapshot.exists()):
            raise ValueError("fichier de résultats sans manifeste et questions, utilisez un nouveau répertoire")
        freeze(manifest_path, manifest)
        if not snapshot.exists():
            temporary = snapshot.with_suffix(".tmp")
            temporary.write_bytes(questions.read_bytes())
            temporary.replace(snapshot)
        if sha256(snapshot) != manifest["questions_sha256"]:
            raise ValueError(f"{snapshot} : empreinte différente du manifeste")
        if records.exists():
            require_ids(records, jsonl(records), set(rows), partial=True)
        command = [sys.executable, str(HERE / "enterprise_rag_bench.py"), "--api", args.api,
                   "--timeout", str(args.timeout), "--wait-ready", str(args.wait_ready), args.mode,
                   "--questions", str(snapshot), "--collection", args.collection,
                   "--output", str(records), "--parallelism", str(args.parallelism)]
        for option, value in query.items():
            command.extend((f"--{option}", str(value)))
        subprocess.run(command, check=True)
        require_ids(records, jsonl(records), set(rows))


def run_data(directory: Path) -> tuple[dict, Path, dict]:
    manifest = read_json(directory / "run.json")
    questions = directory / "questions.jsonl"
    if sha256(questions) != manifest["questions_sha256"]:
        raise ValueError(f"{questions} : empreinte différente du manifeste")
    path = directory / "records.jsonl"
    records = jsonl(path)
    require_ids(path, records, set(jsonl(questions)))
    return manifest, questions, records


def judge_data(path: Path, expected: set[str]) -> None:
    rows = unique_rows(read_json(path)["questions"], path)
    require_ids(path, rows, expected)
    for row in rows.values():
        score = row.get("completeness_pct")
        if not isinstance(row.get("answer_correct"), bool) or type(score) not in (int, float) or not 0 <= score <= 100:
            raise ValueError(f"{path} : note absente ou invalide pour {row['question_id']}")


def judge(args: argparse.Namespace) -> None:
    with lock(args.out):
        manifest, questions, records = run_data(args.out)
        if manifest["mode"] != "answer":
            raise ValueError("le juge officiel attend les réponses du mode answer")
        evaluator = args.evaluator_dir.resolve()
        revision = subprocess.check_output(["git", "-C", evaluator, "rev-parse", "HEAD"], text=True).strip()
        if subprocess.check_output(["git", "-C", evaluator, "status", "--porcelain", "-uno"], text=True):
            raise ValueError("l'évaluateur Onyx contient des modifications locales")
        base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1")
        require_public_url(base_url)
        if os.environ.get("LLM_PROVIDER", "openai") != "openai":
            raise ValueError("le précontrôle du juge requiert une API compatible OpenAI")
        config = {"revision": revision, "base_url": base_url,
                  "model": os.environ.get("LLM_MODEL_NAME", "gpt-5.4"),
                  "parallelism": args.parallelism, "correction": False,
                  "wrapper_sha256": sha256(HERE / "enterprise_rag_eval.py")}
        answers = args.out.resolve() / "records.jsonl"
        result = args.out.resolve() / "results.json"
        metadata = args.out / "judge.json"
        if result.exists() and not metadata.exists():
            raise ValueError("résultat du juge sans manifeste, utilisez un nouveau répertoire")
        freeze(metadata, {"configuration": config, "answers_sha256": sha256(answers)})
        if result.exists():
            judge_data(result, set(records))
            return
        interpreter = shutil.which(args.python)
        if interpreter is None:
            raise ValueError(f"interpréteur du juge introuvable : {args.python}")
        # A partial judge file may contain silent zeroes from a process interrupted before
        # the wrapper could inspect its logs. Publish only the validated, complete result.
        with tempfile.TemporaryDirectory(prefix="judge-", dir=args.out.resolve()) as staging:
            pending = Path(staging) / result.name
            subprocess.run([sys.executable, str(HERE / "enterprise_rag_eval.py"), "judge",
                            "--evaluator-dir", str(evaluator), "--answers-file", str(answers),
                            "--questions-file", str(questions.resolve()), "--results-file", str(pending),
                            "--python", str(Path(interpreter).absolute()), "--parallelism",
                            str(args.parallelism)], check=True)
            judge_data(pending, set(records))
            pending.replace(result)


def compare(args: argparse.Namespace) -> None:
    baseline, questions, first = run_data(args.baseline)
    candidate, _, second = run_data(args.candidate)
    for field in ("mode", "questions_sha256", "corpus", "parallelism", "scripts"):
        if baseline[field] != candidate[field]:
            raise ValueError(f"comparaison impossible : {field} différent")
    mode = baseline["mode"]
    if mode == "retrieve":
        def recall(records):
            rankings = {qid: row["document_ids"] for qid, row in records.items()}
            return {q.question_id: value for q in load_questions(questions)
                    if (value := retrieval_report([q], rankings, ks=(10,))["overall"].get("recall@10"))
                    is not None}

        types = {q.question_id: q.question_type for q in load_questions(questions)}
        reports = {"recall@10": paired_report(recall(first), recall(second), types)}
    else:
        judges = []
        for directory, records in ((args.baseline, first), (args.candidate, second)):
            judge_data(directory / "results.json", set(records))
            judge_metadata = read_json(directory / "judge.json")
            if judge_metadata["answers_sha256"] != sha256(directory / "records.jsonl"):
                raise ValueError(f"{directory} : les réponses jugées ont changé")
            judges.append(judge_metadata["configuration"])
        if judges[0] != judges[1]:
            raise ValueError("comparaison impossible : juges différents")
        reports = {}
        for metric in ("score", "correct", "completeness", "recall", "extra"):
            a, types = load_metric(args.baseline / "results.json", metric)
            b, _ = load_metric(args.candidate / "results.json", metric)
            if a.keys() != b.keys():
                raise ValueError(f"métrique {metric} absente pour une partie des paires")
            if a:
                reports[metric] = paired_report(a, b, types)
    print(json.dumps(reports, indent=2, ensure_ascii=False))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("split", help="recréer les lots fixes 120/380")
    p.add_argument("--questions", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(action=split)

    p = commands.add_parser("run", help="exécuter ou reprendre une variante")
    for option in ("questions", "corpus-dir", "profile", "out"):
        p.add_argument(f"--{option}", type=Path, required=True)
    p.add_argument("--mode", choices=("retrieve", "answer"), required=True)
    p.add_argument("--collection", required=True)
    p.add_argument("--api", default="http://localhost:8080")
    p.add_argument("--timeout", type=float, default=600)
    p.add_argument("--wait-ready", type=float, default=600)
    p.add_argument("--parallelism", type=int, default=1)
    p.add_argument("--max-context-chunks", type=int, default=10)
    p.add_argument("--top-candidates", type=int, default=50)
    p.add_argument("--neighbor-window", type=int, default=0)
    p.add_argument("--rerank-min-score", type=float)
    p.add_argument("--hybrid", choices=("on", "off"), default="on")
    p.add_argument("--multi-query", choices=("on", "off"), default="off")
    p.add_argument("--self-rag", choices=("on", "off"), default="off")
    p.set_defaults(action=run)

    p = commands.add_parser("judge", help="évaluer les réponses avec Onyx, sans correction du gold")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--evaluator-dir", type=Path, required=True)
    p.add_argument("--python", default=sys.executable, help="interpréteur de l'évaluateur")
    p.add_argument("--parallelism", type=int, default=4)
    p.set_defaults(action=judge)

    p = commands.add_parser("compare", help="rapports appariés en JSON")
    p.add_argument("baseline", type=Path)
    p.add_argument("candidate", type=Path)
    p.set_defaults(action=compare)

    args = parser.parse_args()
    try:
        args.action(args)
    except subprocess.CalledProcessError as exc:
        return exc.returncode or 1
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"[{args.command}] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
