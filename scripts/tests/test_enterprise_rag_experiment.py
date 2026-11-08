"""Exerce le lanceur via sa CLI et les frontières HTTP de Spectra et du juge."""

import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "enterprise_rag_experiment.py"
GOLD = "dsid_abcdef01"


class FakeApi(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def respond(self, data):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def do_GET(self):
        self.server.calls.append((self.path, {}, self.headers.get("Authorization")))
        self.respond({"default_generation_settings": {"n_ctx": 8192}})

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.calls.append((self.path, request, self.headers.get("X-API-Key") or self.headers.get("Authorization")))
        if self.path == "/v1/chat/completions":
            data = ({"choices": []} if request.get("model") == "gpt-5.4" else
                    {"choices": [{"message": {"content": "42 [1]"}, "finish_reason": "stop"}]})
        elif self.path == "/apply-template":
            data = {"prompt": "\n".join(m["content"] for m in request["messages"])}
        elif self.path == "/tokenize":
            data = {"tokens": [0] * self.server.prompt_tokens}
        else:
            doc_id = GOLD if request["overrides"]["hybrid"] else "dsid_abcdef02"
            sources = [{"sourceFile": doc_id + ".txt"}]
            if request["topCandidates"] == 50:
                sources.append({"sourceFile": "dsid_abcdef03.txt"})
            data = {"sources": sources, "answer": "42",
                    "durationMs": 2, "multiQueryApplied": request["overrides"].get("multiQuery"),
                    "selfRagApplied": request["overrides"].get("selfRag")}
        self.respond(data)


class EnterpriseRagExperimentTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.questions = self.root / "questions.jsonl"
        self.questions.write_text(json.dumps({"question_id": "one", "question_type": "basic",
                                               "question": "How many?", "expected_doc_ids": [GOLD]}) + "\n")
        self.corpus = self.root / "corpus"
        self.corpus.mkdir()
        self.document = self.corpus / (GOLD + ".txt")
        self.document.write_text("42")
        self.profile = self.root / "profile.json"
        self.profile.write_text(json.dumps({"backend_revision": "fixture", "index": "fixture",
                                            "chat": "fixture", "embedding": "fixture", "reranker": "fixture",
                                            "context_tokens_per_request": 8192}))
        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeApi)
        server.calls = []
        server.prompt_tokens = 64
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.server = server
        self.api = f"http://127.0.0.1:{server.server_port}"
        self.env = {**os.environ, "SPECTRA_API_KEY": "fixture-key", "LLM_API_KEY": "fixture-judge",
                    "OPENAI_BASE_URL": self.api + "/v1", "LLM_PROVIDER": "openai",
                    "LLM_MODEL_NAME": "gpt-5.4", "LLAMA_CPP_API_KEY": "fixture-llama"}

    def cli(self, *args, code=0):
        result = subprocess.run([sys.executable, str(SCRIPT), *map(str, args)],
                                capture_output=True, text=True, env=self.env, timeout=20)
        self.assertEqual(result.returncode, code, result.stdout + result.stderr)
        return result

    def variant(self, name, *options, mode="retrieve", code=0):
        return self.cli("run", "--out", self.root / name, "--questions", self.questions,
                        "--corpus-dir", self.corpus, "--profile", self.profile, "--collection", "bench",
                        "--api", self.api, "--wait-ready", "0", "--mode", mode, *options, code=code)

    def test_resume_and_pairing_reject_changed_inputs(self):
        self.variant("reference")
        self.variant("reference")
        self.assertEqual(len(self.server.calls), 1)
        path, request, key = self.server.calls[0]
        self.assertEqual((path, request["collection"], key), ("/api/query/retrieve", "bench", "fixture-key"))
        self.variant("candidate", "--hybrid", "off")
        result = self.cli("compare", self.root / "reference", self.root / "candidate")
        self.assertEqual(json.loads(result.stdout)["recall@10"]["mean_diff"], -1.0)

        self.document.write_text("43")
        self.assertIn("configuration différente", self.variant("reference", code=1).stderr)
        self.assertEqual(len(self.server.calls), 2)
        self.variant("other-corpus")
        self.assertIn("corpus différent",
                      self.cli("compare", self.root / "reference", self.root / "other-corpus", code=1).stderr)
        records = self.root / "candidate" / "records.jsonl"
        records.write_text("")
        self.assertIn("1 questions manquantes",
                      self.cli("compare", self.root / "reference", self.root / "candidate", code=1).stderr)

    def test_candidate_sweep_reports_complete_multidocument_recall(self):
        (self.corpus / "dsid_abcdef03.txt").write_text("second piece of evidence")
        rows = [json.loads(self.questions.read_text())]
        rows.append({**rows[0], "question_id": "multi",
                     "expected_doc_ids": [GOLD, "dsid_abcdef03"]})
        self.questions.write_text("".join(json.dumps(row) + "\n" for row in rows))
        self.variant("top20", "--top-candidates", 20)
        self.variant("top50", "--top-candidates", 50)
        report = json.loads(self.cli("compare", self.root / "top20", self.root / "top50").stdout)
        self.assertEqual([request["topCandidates"] for _, request, _ in self.server.calls],
                         [20, 20, 50, 50])
        self.assertEqual(report["recall@10"]["mean_diff"], 0.25)
        self.assertEqual(report["all-gold@10"]["n"], 1)
        self.assertEqual(report["all-gold@10"]["mean_diff"], 1.0)
        self.assertEqual(report["extra@10"]["mean_diff"], 0.5)

    def test_multi_query_comparison_reports_activation_and_latency(self):
        self.variant("single", "--multi-query", "off")
        self.variant("multiple", "--multi-query", "on")
        report = json.loads(self.cli("compare", self.root / "single", self.root / "multiple").stdout)
        self.assertEqual(report["multi-query-applied"]["mean_a"], 0)
        self.assertEqual(report["multi-query-applied"]["mean_b"], 1)
        for variant, label in (("single", "a"), ("multiple", "b")):
            row = json.loads((self.root / variant / "records.jsonl").read_text())
            self.assertEqual(report["client-ms"][f"p95_{label}"], row["clientMs"])

    def oracle(self, name, *options, code=0):
        return self.cli("oracle", "--out", self.root / name, "--questions", self.questions,
                        "--corpus-dir", self.corpus, "--profile", self.profile, "--api", self.api,
                        "--model", "local-chat", *options, code=code)

    def test_oracle_sends_only_gold_and_resumes(self):
        (self.corpus / "dsid_abcdef02.txt").write_text("Unrelated document")
        self.oracle("oracle", "--style", "detailed")
        self.oracle("oracle", "--style", "detailed")
        self.assertEqual([path for path, _, _ in self.server.calls],
                         ["/props", "/apply-template", "/tokenize", "/v1/chat/completions"])
        self.assertTrue(all(key == "Bearer fixture-llama" for _, _, key in self.server.calls))
        payload = self.server.calls[-1][1]
        self.assertIn("[1]\n42", payload["messages"][1]["content"])
        self.assertNotIn("Unrelated document", payload["messages"][1]["content"])
        self.assertIn("conditions and exceptions", payload["messages"][0]["content"])
        row = json.loads((self.root / "oracle" / "records.jsonl").read_text())
        self.assertEqual(row["document_ids"], [GOLD])
        self.assertEqual(row["oracle"], {"promptTokens": 64, "finishReason": "stop"})

    def test_oracle_rejects_oversized_prompt_before_generation(self):
        self.server.prompt_tokens = 8000
        result = self.oracle("too-long", code=1)
        self.assertIn("dépassent les 8192 tokens", result.stderr)
        self.assertEqual(self.server.calls[-1][0], "/tokenize")
        self.assertEqual((self.root / "too-long" / "records.jsonl").read_text(), "")

    def test_judge_rejects_silent_failure_and_publishes_complete_results(self):
        self.variant("answer", "--self-rag", "on", mode="answer")
        self.assertTrue(self.server.calls[0][1]["overrides"]["selfRag"])
        evaluator = self.root / "evaluator"
        module = evaluator / "src" / "scripts" / "answer_evaluation" / "metrics_based_eval.py"
        module.parent.mkdir(parents=True)
        module.write_text(
            "import json, os, sys\n"
            "from pathlib import Path\n"
            "assert '--no-correction' in sys.argv\n"
            "result = Path(sys.argv[sys.argv.index('--results-file') + 1])\n"
            "result.write_text(json.dumps({'questions': [{'question_id': 'one', "
            "'answer_correct': True, 'completeness_pct': 100}]}))\n"
            "if os.environ.get('TEST_JUDGE_FAIL') == '1': print('[WARN] citation stripping failed')\n"
        )
        subprocess.run(["git", "init", "-q", str(evaluator)], check=True)
        subprocess.run(["git", "-C", str(evaluator), "add", "."], check=True)
        subprocess.run(["git", "-C", str(evaluator), "-c", "user.name=Test",
                        "-c", "user.email=test@example.com", "commit", "-qm", "Fixture"], check=True)
        self.env["TEST_JUDGE_FAIL"] = "1"
        variant = self.root / "answer"
        args = ("judge", "--out", variant, "--evaluator-dir", evaluator,
                "--python", os.path.relpath(sys.executable))
        self.cli(*args, code=4)
        self.assertFalse((variant / "results.json").exists())
        self.env["TEST_JUDGE_FAIL"] = "0"
        self.cli(*args)
        self.assertTrue((variant / "results.json").is_file())
        comparison = self.cli("compare", variant, variant)
        self.assertEqual(json.loads(comparison.stdout)["score"]["mean_a"], 100.0)
        self.oracle("oracle")
        self.cli("judge", "--out", self.root / "oracle", "--evaluator-dir", evaluator,
                 "--python", os.path.relpath(sys.executable))
        report = json.loads(self.cli("compare", variant, self.root / "oracle").stdout)
        self.assertEqual(set(report), {"score", "correct", "completeness"})
        self.assertEqual(report["score"]["mean_diff"], 0)
