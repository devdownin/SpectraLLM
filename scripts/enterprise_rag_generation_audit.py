#!/usr/bin/env python3
"""Audit de la génération (étape 4) : fidélité aux sources, citations et refus, notés par Claude.

Complète l'évaluateur officiel d'Onyx, qui note l'exactitude, la complétude et les documents cités, sur
trois points qu'il ne mesure pas :

* **fidélité** : chaque affirmation de la réponse est-elle étayée par les documents récupérés ?
* **qualité des citations** : chaque affirmation qui cite ``[n]`` est-elle étayée par les documents cités ?
* **refus** : la réponse déclare-t-elle l'information indisponible ? Correct pour ``info_not_found``,
  refus abusif ailleurs.

Les trois contrôles réutilisent tel quel le prompt publié par Onyx pour valider un fait
(``INDIVIDUAL_FACT_VALIDATOR_PROMPT``, lu dans le clone figé de l'évaluateur) : « l'answer » y est la
preuve (documents) et le « statement » l'affirmation à vérifier. Le verdict suit la règle d'Onyx :
« yes » sur la première ligne.

Sous-commandes :

    ground  note un run ``answer`` (reprenable) : ``grounding.jsonl`` et le manifeste ``grounding.json`` ;
    report  croise ``grounding.jsonl`` et le ``results.json`` du juge Onyx, par catégorie de question :
            ``generation-audit.json`` et ``generation-audit.md``.

Le juge (``--model``, Claude Opus 5.5 par défaut) passe par le SDK officiel ``anthropic`` et la clé
``LLM_API_KEY`` (même variable que l'évaluateur d'Onyx). Il doit être distinct du modèle évalué.
Un refus du juge (``stop_reason == "refusal"``) est une panne : la question reste à noter, sans repli
sur un autre modèle, qui changerait le juge en cours de mesure.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from enterprise_rag_bench import CITATION_PATTERN, NO_GOLD_TYPES, cited_source_numbers, load_questions
from enterprise_rag_experiment import freeze, lock, read_json, require_distinct_judge, run_data, sha256, write_json

HERE = Path(__file__).resolve().parent
DEFAULT_MODEL = "claude-opus-5-5"
# Au-delà, les affirmations suivantes ne sont pas notées (compté dans ``claims_skipped``).
MAX_CLAIMS = 12
# Une « affirmation » de moins de mots est une formule (titre, transition), pas un fait vérifiable.
MIN_CLAIM_WORDS = 4
REFUSAL_STATEMENT = ("The answer states that the requested information is not available or cannot be found "
                     "in the provided documents.")
SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+|\n+")
SPACE_BEFORE_PUNCTUATION = re.compile(r"\s+([.,;:!?])")
STATEMENT_MARKER = "\x00STATEMENT\x00"


class JudgeFailure(RuntimeError):
    """Le juge n'a pas rendu de verdict exploitable (refus, erreur d'API) : la question reste à noter."""


# ── Prompt d'Onyx ─────────────────────────────────────────────────────────────


def load_onyx_prompt(evaluator_dir: Path) -> tuple[str, dict]:
    """``INDIVIDUAL_FACT_VALIDATOR_PROMPT`` du clone figé d'Onyx, avec sa provenance."""
    path = evaluator_dir / "src" / "prompts" / "answer_evaluation.py"
    spec = importlib.util.spec_from_file_location("onyx_answer_evaluation_prompts", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)  # module de constantes, sans import du reste de l'évaluateur
    prompt = module.INDIVIDUAL_FACT_VALIDATOR_PROMPT
    revision = subprocess.check_output(["git", "-C", str(evaluator_dir), "rev-parse", "HEAD"], text=True).strip()
    if subprocess.check_output(["git", "-C", str(evaluator_dir), "status", "--porcelain", "-uno"], text=True):
        raise ValueError("l'évaluateur Onyx contient des modifications locales")
    return prompt, {"evaluator_revision": revision, "prompt": "INDIVIDUAL_FACT_VALIDATOR_PROMPT",
                    "prompt_sha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest()}


def prompt_blocks(template: str, evidence: str, statement: str) -> list[dict]:
    """Le prompt d'Onyx en deux blocs : la preuve (mise en cache, commune aux affirmations) puis le reste.

    La concaténation des deux blocs est exactement ``template.format(answer=…, statement=…)``.
    """
    head, tail = template.format(answer=evidence, statement=STATEMENT_MARKER).split(STATEMENT_MARKER)
    return [{"type": "text", "text": head, "cache_control": {"type": "ephemeral"}},
            {"type": "text", "text": statement + tail}]


# ── Juge ──────────────────────────────────────────────────────────────────────


class ClaudeJudge:
    def __init__(self, template: str, model: str, effort: str, client=None):
        if client is None:
            import anthropic  # SDK officiel : seul ce sous-ensemble du banc en dépend

            client = anthropic.Anthropic(api_key=os.environ.get("LLM_API_KEY") or None, max_retries=5)
        self.client, self.template, self.model, self.effort = client, template, model, effort

    def supports(self, evidence: str, statement: str) -> bool:
        try:
            response = self.client.messages.create(
                model=self.model, max_tokens=16000, output_config={"effort": self.effort},
                messages=[{"role": "user", "content": prompt_blocks(self.template, evidence, statement)}])
        except Exception as exc:  # erreurs d'API typées du SDK, déjà relancées par max_retries
            raise JudgeFailure(f"{type(exc).__name__} : {exc}") from exc
        if response.stop_reason == "refusal":
            raise JudgeFailure("refus du juge (stop_reason refusal)")
        if response.stop_reason == "max_tokens":
            raise JudgeFailure("réponse du juge tronquée (max_tokens)")
        text = "".join(block.text for block in response.content if block.type == "text").strip()
        first_line = text.splitlines()[0].strip() if text else ""
        return re.search(r"\byes\b", first_line, re.IGNORECASE) is not None  # règle d'Onyx


# ── Affirmations et preuves ───────────────────────────────────────────────────


def split_claims(answer: str) -> list[dict]:
    """Phrases de la réponse avec leurs citations ``[n]`` ; les formules trop courtes sont écartées."""
    claims = []
    for sentence in SENTENCE_SPLIT.split(answer or ""):
        statement = SPACE_BEFORE_PUNCTUATION.sub(r"\1", CITATION_PATTERN.sub("", sentence)).strip(" -*#>\t")
        if len(statement.split()) < MIN_CLAIM_WORDS:
            continue
        claims.append({"statement": statement, "cited": cited_source_numbers(sentence)})
    return claims


def evidence(doc_ids: list[str], corpus_dir: Path) -> str:
    """Texte intégral des documents, dans l'ordre donné, sans troncature."""
    parts = []
    for doc_id in dict.fromkeys(doc_ids):
        path = corpus_dir / f"{doc_id}.txt"
        if not path.is_file():
            raise ValueError(f"document absent du corpus local : {path}")
        parts.append(path.read_text(encoding="utf-8"))
    return "\n\n---\n\n".join(parts)


def source_doc_ids(record: dict) -> list[list[str]]:
    """doc_ids de chaque source numérotée [1], [2]… telle que présentée au modèle."""
    return [row.get("docIds") or [] for row in record.get("spectra", {}).get("sourceScores") or []]


def ground_one(judge: ClaudeJudge, record: dict, corpus_dir: Path) -> dict:
    answer = record.get("answer") or ""
    sources = source_doc_ids(record)
    retrieved = [doc for docs in sources for doc in docs]
    claims = split_claims(answer)
    scored, skipped = claims[:MAX_CLAIMS], max(len(claims) - MAX_CLAIMS, 0)
    all_evidence = evidence(retrieved, corpus_dir) if retrieved else ""
    rows = []
    for claim in scored:
        valid = [n for n in claim["cited"] if 1 <= n <= len(sources)]
        row = {"statement": claim["statement"], "cited": valid, "invalid_citations": len(claim["cited"]) - len(valid),
               "grounded": judge.supports(all_evidence, claim["statement"]) if all_evidence else False}
        if valid:
            cited_docs = [doc for n in valid for doc in sources[n - 1]]
            row["citation_supported"] = judge.supports(evidence(cited_docs, corpus_dir), claim["statement"])
        rows.append(row)
    return {"question_id": record["question_id"], "claims": rows, "claims_skipped": skipped,
            "refusal": judge.supports(answer, REFUSAL_STATEMENT) if answer.strip() else True}


def ground(args: argparse.Namespace) -> None:
    with lock(args.out):
        manifest, _, records = run_data(args.out)
        if manifest["mode"] != "answer":
            raise ValueError("l'audit de génération attend un run du mode answer")
        require_distinct_judge(manifest, args.model)
        template, provenance = load_onyx_prompt(args.evaluator_dir.resolve())
        config = {"judge": {"provider": "anthropic", "model": args.model, "effort": args.effort, **provenance},
                  "max_claims": MAX_CLAIMS, "min_claim_words": MIN_CLAIM_WORDS, "refusal_statement": REFUSAL_STATEMENT,
                  "answers_sha256": sha256(args.out / "records.jsonl"),
                  "script_sha256": sha256(Path(__file__))}
        freeze(args.out / "grounding.json", config)
        output = args.out / "grounding.jsonl"
        done = set()
        if output.exists():
            done = {json.loads(line)["question_id"] for line in output.read_text(encoding="utf-8").splitlines() if line}
        todo = [row for qid, row in records.items() if qid not in done]
        print(f"[ground] {len(todo)} réponses à noter ({len(done)} déjà notées)", file=sys.stderr)
        judge = ClaudeJudge(template, args.model, args.effort, client=getattr(args, "client", None))
        failures = 0
        with output.open("a", encoding="utf-8") as fh, ThreadPoolExecutor(max_workers=args.parallelism) as pool:
            futures = {pool.submit(ground_one, judge, row, args.corpus_dir): qid for qid, row in
                       ((row["question_id"], row) for row in todo)}
            for future in as_completed(futures):
                try:
                    fh.write(json.dumps(future.result(), ensure_ascii=False) + "\n")
                    fh.flush()
                except JudgeFailure as exc:
                    failures += 1
                    print(f"[ground] {futures[future]} : {exc}", file=sys.stderr)
        if failures:
            raise ValueError(f"{failures} réponse(s) sans verdict du juge : relancer la commande pour les reprendre")


# ── Rapport par catégorie ─────────────────────────────────────────────────────


def mean(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def question_metrics(grounding: dict) -> dict:
    claims = grounding["claims"]
    cited = [c for c in claims if "citation_supported" in c]
    return {
        "grounded": mean([float(c["grounded"]) for c in claims]),
        "citation_precision": mean([float(c["citation_supported"]) for c in cited]),
        "citation_coverage": mean([float(bool(c["cited"])) for c in claims]),
        "invalid_citations": sum(c["invalid_citations"] for c in claims),
        "refusal": float(grounding["refusal"]),
    }


def audit_report(questions_path: Path, grounding_path: Path, results_path: Path | None) -> dict:
    types = {q.question_id: q.question_type for q in load_questions(questions_path)}
    grounding = {row["question_id"]: row for row in map(json.loads, grounding_path.read_text().splitlines()) if row}
    missing = set(types) - set(grounding)
    if missing:
        raise ValueError(f"{grounding_path} : {len(missing)} questions non notées, relancer ground")
    judged = {}
    if results_path is not None:
        judged = {row["question_id"]: row for row in read_json(results_path)["questions"]}
        if set(judged) != set(types):
            raise ValueError(f"{results_path} : questions différentes du run")
    rows = defaultdict(list)
    for qid, question_type in types.items():
        row = question_metrics(grounding[qid])
        if qid in judged:
            onyx = judged[qid]
            row["correct"] = 100.0 if onyx["answer_correct"] else 0.0
            row["completeness"] = onyx["completeness_pct"]
            row["score"] = onyx["completeness_pct"] if onyx["answer_correct"] else 0.0
            if onyx.get("invalid_extra_docs") is not None:
                row["invalid_extra_docs"] = float(onyx["invalid_extra_docs"])
        rows[question_type].append(row)
        rows["(toutes)"].append(row)

    def summarize(question_type: str, items: list[dict]) -> dict:
        summary = {"n": len(items)}
        for key in ("correct", "completeness", "score", "grounded", "citation_precision", "citation_coverage",
                    "invalid_extra_docs"):
            summary[key] = mean([item[key] for item in items if item.get(key) is not None])
        refusals = mean([item["refusal"] for item in items])
        # Refus correct pour les questions sans réponse, abusif pour les autres.
        summary["correct_refusal" if question_type == "info_not_found" else "false_refusal"] = refusals
        return summary

    ordered = sorted(rows, key=lambda t: (t == "(toutes)", t))
    report = {"by_type": {t: summarize(t, rows[t]) for t in ordered if t != "(toutes)"}}
    answerable = [item for t, items in rows.items() if t not in ("(toutes)", "info_not_found") for item in items]
    report["overall"] = summarize("(toutes)", rows["(toutes)"])
    report["overall"]["false_refusal"] = mean([item["refusal"] for item in answerable])
    report["overall"]["correct_refusal"] = report["by_type"].get("info_not_found", {}).get("correct_refusal")
    report["no_gold_types"] = sorted(NO_GOLD_TYPES)
    return report


def render(report: dict) -> str:
    columns = [("n", "n", None), ("Exactitude %", "correct", 1), ("Complétude %", "completeness", 1),
               ("Score", "score", 1), ("Fidélité %", "grounded", 100), ("Précision citations %", "citation_precision", 100),
               ("Couverture citations %", "citation_coverage", 100), ("Docs invalides", "invalid_extra_docs", None),
               ("Refus correct %", "correct_refusal", 100), ("Refus abusif %", "false_refusal", 100)]
    lines = ["| Catégorie | " + " | ".join(name for name, _, _ in columns) + " |", "|---" * (len(columns) + 1) + "|"]
    for name, summary in [*report["by_type"].items(), ("(toutes)", report["overall"])]:
        cells = []
        for _, key, scale in columns:
            value = summary.get(key)
            if value is None:
                cells.append("—")
            elif key == "n":
                cells.append(str(value))
            elif scale is None:
                cells.append(f"{value:.2f}")
            else:
                cells.append(f"{value * scale:.1f}")
        lines.append(f"| {name} | " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def report(args: argparse.Namespace) -> None:
    manifest, questions, _ = run_data(args.out)
    results = args.out / "results.json"
    data = audit_report(questions, args.out / "grounding.jsonl", results if results.exists() else None)
    data["grounding"] = read_json(args.out / "grounding.json")
    data["onyx_judge"] = read_json(args.out / "judge.json")["configuration"] if results.exists() else None
    write_json(args.out / "generation-audit.json", data)
    table = render(data)
    (args.out / "generation-audit.md").write_text(table, encoding="utf-8")
    print(table, end="")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("ground", help="noter fidélité, citations et refus d'un run answer")
    p.add_argument("--out", type=Path, required=True, help="répertoire du run answer")
    p.add_argument("--corpus-dir", type=Path, required=True)
    p.add_argument("--evaluator-dir", type=Path, required=True, help="clone figé de EnterpriseRAG-Bench")
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--effort", choices=("low", "medium", "high", "xhigh", "max"), default="medium")
    p.add_argument("--parallelism", type=int, default=4)
    p.set_defaults(action=ground)
    p = commands.add_parser("report", help="scores par catégorie de question")
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(action=report)
    args = parser.parse_args()
    try:
        args.action(args)
    except (OSError, ValueError, KeyError) as exc:
        print(f"[{args.command}] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
