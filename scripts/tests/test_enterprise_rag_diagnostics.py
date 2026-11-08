import argparse
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import enterprise_rag_diagnostics as diagnostics
from enterprise_rag_experiment import corpus_identity, sha256


class DiagnosticsTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = Path(work.name)
        self.gold = "dsid_a0000000"
        self.corpus = self.root / "documents"
        self.corpus.mkdir()
        (self.corpus / f"{self.gold}.txt").write_text("evidence")
        self.rows = [{"question_id": str(i), "question_type": "basic", "question": "Why?",
                      "expected_doc_ids": [self.gold]} for i in range(120)]
        self.questions = self.root / "dev-120.jsonl"
        self.questions.write_text("".join(json.dumps(row) + "\n" for row in self.rows))
        (self.root / "questions.jsonl").write_bytes(self.questions.read_bytes())
        (self.root / "split.json").write_text(json.dumps({
            "dev_ids": [row["question_id"] for row in self.rows],
            "source_sha256": sha256(self.questions),
        }))
        self.profile = self.root / "profile.json"
        self.profile.write_text(json.dumps({"chat": "test-model"}))
        self.out = self.root / "out"

    def test_oracle_selects_lowest_scores_and_reuses_reference_judgment(self):
        reference = self.root / "reference"
        reference.mkdir()
        (reference / "questions.jsonl").write_bytes(self.questions.read_bytes())
        records = reference / "records.jsonl"
        records.write_text(self.questions.read_text())
        (reference / "run.json").write_text(json.dumps({
            "mode": "answer", "questions_sha256": sha256(self.questions),
            "corpus": corpus_identity(self.corpus), "profile": {"chat": "test-model"},
        }))
        metadata = {"configuration": {"model": "judge"}, "answers_sha256": sha256(records)}
        (reference / "judge.json").write_text(json.dumps(metadata))
        judged = [{"question_id": str(i), "question_type": "basic", "answer_correct": True,
                   "completeness_pct": max(0, 100 - i)} for i in range(120)]
        (reference / "results.json").write_text(json.dumps({"questions": judged}))

        def run(args):
            args.out.mkdir()
            self.assertEqual([row["question_id"] for row in diagnostics.jsonl(args.questions).values()],
                             [str(i) for i in range(100, 120)])

        def judge(_, directory):
            (directory / "judge.json").write_text(json.dumps(metadata))
            results = [{**row, "completeness_pct": 100} for row in judged[-20:]]
            (directory / "results.json").write_text(json.dumps({"questions": results}))

        args = argparse.Namespace(split_dir=self.root, reference=reference, corpus_dir=self.corpus,
                                  out=self.out, profile=self.profile, api="http://localhost:8091",
                                  timeout=5, model="test-model", max_tokens=100)
        with patch.object(diagnostics, "run", side_effect=run) as generation, \
             patch.object(diagnostics, "judge_run", side_effect=judge) as evaluation:
            diagnostics.oracle(args)
        self.assertEqual([c.args[0].style for c in generation.call_args_list], ["concise", "detailed"])
        self.assertEqual(evaluation.call_count, 2)
        report = diagnostics.read_json(self.out / "report.json")
        self.assertEqual(report["comparisons"]["rag_vs_detailed"]["score"]["mean_diff"], 100)

    def test_native_refuses_a_capture_served_entirely_by_fallback(self):
        # A single distractor is sufficient to exercise the orchestration directly.
        (self.corpus / "dsid_a0000001.txt").write_text("noise")
        args = argparse.Namespace(api="http://localhost:8089", native_api="http://localhost:18089",
                                  split_dir=self.root, work_dir=self.root, profile=self.profile,
                                  out=self.out, retrieval_only=True, timeout=5, wait_ready=5,
                                  collection="bench", top_candidates=50, distractors=1)

        def run(args):
            args.out.mkdir()
            (args.out / "records.jsonl").write_text(self.questions.read_text())

        empty = {"used": 0, "fallback": 0, "heap_bytes": 100}
        fallback = {**empty, "fallback": 120}
        with patch.object(diagnostics, "SpectraClient") as client, \
             patch.object(diagnostics, "run", side_effect=run), \
             patch.object(diagnostics, "snapshot", side_effect=[empty, empty, empty, fallback]):
            client.return_value.get.side_effect = lambda path: (
                {"services": [{"name": name, "available": True} for name in ("qdrant", "reranker")]}
                if path == "/api/status" else {"ready": True, "indexedChunks": 10}
            )
            with self.assertRaisesRegex(ValueError, "profil native"):
                diagnostics.native(args)
        self.assertFalse((self.out / "report.json").exists())
