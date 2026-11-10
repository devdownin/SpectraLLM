import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import enterprise_rag_generation_audit as audit  # noqa: E402

GOLD = "dsid_aaaaaaaa"
OTHER = "dsid_bbbbbbbb"
# Copie du gabarit publié par Onyx (structure : preuve, puis affirmation).
ONYX_PROMPT = '''INDIVIDUAL_FACT_VALIDATOR_PROMPT = """
You are an answer validator.

## Answer
```
{answer}
```

## Statement
```
{statement}
```

CRITICAL: output only a simple yes or no.
""".strip()
'''


class FakeClient:
    """Juge factice : « yes » si l'affirmation figure mot pour mot dans la preuve."""

    def __init__(self, refuse_on=None):
        self.calls, self.refuse_on = [], refuse_on
        self.messages = self

    def create(self, **request):
        self.calls.append(request)
        blocks = request["messages"][0]["content"]
        evidence, statement = blocks[0]["text"], blocks[1]["text"].split("\n```")[0]
        if self.refuse_on and self.refuse_on in statement:
            return SimpleNamespace(stop_reason="refusal", content=[])
        if statement == audit.REFUSAL_STATEMENT:
            verdict = "yes" if "not available" in evidence else "no"
        else:
            verdict = "yes" if statement.rstrip(".") in evidence else "no"
        return SimpleNamespace(stop_reason="end_turn", content=[SimpleNamespace(type="text", text=verdict)])


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


class GenerationAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.corpus = self.root / "corpus"
        self.corpus.mkdir()
        (self.corpus / f"{GOLD}.txt").write_text("The upload limit is 10 MiB per file")
        (self.corpus / f"{OTHER}.txt").write_text("Rollbacks need two approvals from the on-call team")
        self.evaluator = self.root / "evaluator"
        prompts = self.evaluator / "src" / "prompts" / "answer_evaluation.py"
        prompts.parent.mkdir(parents=True)
        prompts.write_text(ONYX_PROMPT)
        for command in (["init", "-q"], ["add", "."],
                        ["-c", "user.name=T", "-c", "user.email=t@example.com", "commit", "-qm", "Fixture"]):
            subprocess.run(["git", "-C", str(self.evaluator), *command], check=True)
        self.run_dir = self.root / "answer-reference"
        self.run_dir.mkdir()
        write_jsonl(self.run_dir / "questions.jsonl", [
            {"question_id": "q1", "question_type": "basic", "question": "Limit?", "expected_doc_ids": [GOLD]},
            {"question_id": "q2", "question_type": "info_not_found", "question": "Who?", "expected_doc_ids": []},
        ])
        sources = [{"docIds": [GOLD]}, {"docIds": [OTHER]}]
        write_jsonl(self.run_dir / "records.jsonl", [
            {"question_id": "q1", "document_ids": [GOLD],
             "answer": "The upload limit is 10 MiB per file [1]. Rollbacks need two approvals from finance [2]. Ok.",
             "spectra": {"sourceScores": sources}},
            {"question_id": "q2", "document_ids": [],
             "answer": "This information is not available in the provided documents.",
             "spectra": {"sourceScores": sources}},
        ])
        questions_sha = hashlib.sha256((self.run_dir / "questions.jsonl").read_bytes()).hexdigest()
        (self.run_dir / "run.json").write_text(json.dumps({
            "mode": "answer", "questions_sha256": questions_sha, "query": {},
            "profile": {"chat": "Qwen2.5-7B-Instruct Q4_K_M"}}))

    def ground(self, client, model=audit.DEFAULT_MODEL):
        audit.ground(argparse.Namespace(out=self.run_dir, corpus_dir=self.corpus, evaluator_dir=self.evaluator,
                                        model=model, effort="medium", parallelism=1, client=client))

    def test_prompt_blocks_rebuild_the_onyx_prompt_exactly_and_cache_the_evidence(self):
        template, provenance = audit.load_onyx_prompt(self.evaluator)
        blocks = audit.prompt_blocks(template, "EVIDENCE", "A statement")
        self.assertEqual(blocks[0]["text"] + blocks[1]["text"], template.format(answer="EVIDENCE", statement="A statement"))
        self.assertEqual(blocks[0]["cache_control"], {"type": "ephemeral"})
        self.assertNotIn("A statement", blocks[0]["text"])
        self.assertEqual(provenance["prompt"], "INDIVIDUAL_FACT_VALIDATOR_PROMPT")

    def test_claims_drop_short_phrases_and_keep_their_citations(self):
        claims = audit.split_claims("Intro.\n- The limit is 10 MiB [1][2]. Ok. Second fact holds true here [3].")
        self.assertEqual(claims, [{"statement": "The limit is 10 MiB.", "cited": [1, 2]},
                                  {"statement": "Second fact holds true here.", "cited": [3]}])

    def test_ground_scores_faithfulness_citations_and_refusals(self):
        client = FakeClient()
        self.ground(client)
        rows = {row["question_id"]: row for row in
                map(json.loads, (self.run_dir / "grounding.jsonl").read_text().splitlines())}
        claims = rows["q1"]["claims"]
        self.assertEqual([c["grounded"] for c in claims], [True, False])
        self.assertEqual([c["citation_supported"] for c in claims], [True, False])
        self.assertFalse(rows["q1"]["refusal"])
        self.assertTrue(rows["q2"]["refusal"])
        self.assertEqual({call["model"] for call in client.calls}, {"claude-opus-5-5"})
        self.assertEqual(client.calls[0]["output_config"], {"effort": "medium"})

        calls = len(client.calls)
        self.ground(client)  # reprise : rien à renoter
        self.assertEqual(len(client.calls), calls)

        (self.run_dir / "results.json").write_text(json.dumps({"questions": [
            {"question_id": "q1", "question_type": "basic", "answer_correct": True, "completeness_pct": 50.0,
             "invalid_extra_docs": 1},
            {"question_id": "q2", "question_type": "info_not_found", "answer_correct": True, "completeness_pct": 100.0,
             "invalid_extra_docs": None}]}))
        (self.run_dir / "judge.json").write_text(json.dumps({"configuration": {"model": "claude-opus-5-5"}}))
        audit.report(argparse.Namespace(out=self.run_dir))
        data = json.loads((self.run_dir / "generation-audit.json").read_text())
        basic = data["by_type"]["basic"]
        self.assertEqual((basic["grounded"], basic["citation_precision"], basic["false_refusal"]), (0.5, 0.5, 0.0))
        self.assertEqual((basic["score"], basic["invalid_extra_docs"]), (50.0, 1.0))
        self.assertEqual(data["by_type"]["info_not_found"]["correct_refusal"], 1.0)
        self.assertEqual(data["overall"]["false_refusal"], 0.0)
        self.assertIn("| basic | 1 | 100.0 | 50.0 | 50.0 | 50.0 | 50.0 |",
                      (self.run_dir / "generation-audit.md").read_text())

    def test_judge_refusal_leaves_the_question_unscored(self):
        with self.assertRaisesRegex(ValueError, "1 réponse"):
            self.ground(FakeClient(refuse_on="upload limit"))
        rows = (self.run_dir / "grounding.jsonl").read_text().splitlines()
        self.assertEqual([json.loads(row)["question_id"] for row in rows], ["q2"])

    def test_the_judge_must_differ_from_the_evaluated_model(self):
        with self.assertRaisesRegex(ValueError, "juge distinct"):
            self.ground(FakeClient(), model="qwen2.5-7b")


if __name__ == "__main__":
    unittest.main()
