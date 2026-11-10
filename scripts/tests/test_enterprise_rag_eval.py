"""Évaluation fiable : sonde du juge, rejet des zéros silencieux, comparaison appariée."""

import importlib.util
import json
import sys
import tempfile
import textwrap
import threading
import unittest
import unittest.mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("enterprise_rag_eval", ROOT / "scripts/enterprise_rag_eval.py")
ev = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = ev
spec.loader.exec_module(ev)


class FakeJudgeApi(BaseHTTPRequestHandler):
    status = 200
    body = {"choices": [{"message": {"content": "ok"}}]}

    def log_message(self, *_):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers["Content-Length"]))
        payload = json.dumps(FakeJudgeApi.body).encode()
        self.send_response(FakeJudgeApi.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


FAKE_EVALUATOR = textwrap.dedent('''
    import argparse, json, os, sys
    p = argparse.ArgumentParser()
    for name in ("--answers-file", "--questions-file", "--results-file", "--parallelism"):
        p.add_argument(name)
    p.add_argument("--resume", action="store_true")
    p.add_argument("--no-correction", action="store_true")
    a = p.parse_args()
    open(os.path.join(os.path.dirname(a.results_file) or ".", "evaluator_ran"), "w").write("1")
    if os.environ.get("FAKE_JUDGE_FAIL"):
        print("  [WARN] qst_0001: citation stripping failed, using original")
    json.dump({"questions": []}, open(a.results_file, "w"))
    print("Done.")
''')


def result_file(path: Path, rows: list[dict]) -> Path:
    path.write_text(json.dumps({"questions": rows}), encoding="utf-8")
    return path


def row(qid, correct, completeness, qtype="basic", recall=None):
    return {"question_id": qid, "question_type": qtype, "answer_correct": correct,
            "completeness_pct": completeness, "document_recall_pct": recall, "invalid_extra_docs": None}


class JudgePreflightTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeJudgeApi)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}/v1"
        FakeJudgeApi.status, FakeJudgeApi.body = 200, {"choices": [{"message": {"content": "ok"}}]}

    def test_a_working_judge_passes(self):
        ev.preflight_judge(self.base, "k", "gpt-5.4")

    def test_exhausted_credit_is_named_as_such(self):
        FakeJudgeApi.status = 429
        FakeJudgeApi.body = {"error": {"code": "credit_balance_exhausted",
                                       "message": "You have no credits remaining."}}
        with self.assertRaisesRegex(ev.JudgeUnavailable, "crédit épuisé"):
            ev.preflight_judge(self.base, "k", "gpt-5.4")

    def test_free_tier_quota_is_a_quota_error(self):
        FakeJudgeApi.status = 429
        FakeJudgeApi.body = {"error": {"message": "Rate limit exceeded: free-models-per-day"}}
        with self.assertRaisesRegex(ev.JudgeUnavailable, "quota"):
            ev.preflight_judge(self.base, "k", "openrouter/free")

    def test_a_rejected_key_is_reported(self):
        FakeJudgeApi.status = 401
        FakeJudgeApi.body = {"error": {"message": "Incorrect API key provided"}}
        with self.assertRaisesRegex(ev.JudgeUnavailable, "clé refusée"):
            ev.preflight_judge(self.base, "k", "gpt-5.4")

    def test_an_unreachable_api_is_reported(self):
        with self.assertRaisesRegex(ev.JudgeUnavailable, "injoignable"):
            ev.preflight_judge("http://127.0.0.1:1/v1", "k", "gpt-5.4", timeout=2)


class AnthropicJudgeTest(unittest.TestCase):
    """Juge Claude : sonde via le SDK de l'évaluateur (simulé ici par un interpréteur factice)."""

    def fake_python(self, body: str) -> str:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        script = Path(directory.name) / "python"
        script.write_text("#!/bin/sh\n" + body + "\n")
        script.chmod(0o755)
        return str(script)

    def test_the_sdk_probe_receives_the_model_and_passes(self):
        python = self.fake_python('[ "$1" = "-c" ] && [ "$3" = "claude-opus-5-5" ] || exit 9')
        ev.preflight_anthropic_judge(python, "claude-opus-5-5")

    def test_the_probe_reports_the_last_line_of_the_sdk_error(self):
        python = self.fake_python('echo "Traceback" >&2; echo "clé refusée (HTTP 401)" >&2; exit 1')
        with self.assertRaisesRegex(ev.JudgeUnavailable, r"clé refusée \(HTTP 401\) — Anthropic"):
            ev.preflight_anthropic_judge(python, "claude-opus-5-5")

    def test_the_probe_source_uses_the_official_sdk_and_handles_refusals(self):
        compile(ev.ANTHROPIC_PREFLIGHT, "preflight", "exec")
        self.assertIn("anthropic.Anthropic(", ev.ANTHROPIC_PREFLIGHT)
        self.assertIn('stop_reason == "refusal"', ev.ANTHROPIC_PREFLIGHT)

    def test_provider_defaults_follow_the_onyx_factory(self):
        environment = {key: value for key, value in __import__("os").environ.items() if key != "LLM_MODEL_NAME"}
        with unittest.mock.patch.dict("os.environ", {**environment, "LLM_PROVIDER": "anthropic"}, clear=True):
            self.assertEqual(ev.judge_provider(), ("anthropic", "claude-opus-5-5"))
        with unittest.mock.patch.dict("os.environ", {"LLM_PROVIDER": "vertex"}):
            with self.assertRaisesRegex(ValueError, "LLM_PROVIDER inconnu"):
                ev.judge_provider()


class JudgeCommandTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.evaluator = root / "evaluator"
        package = self.evaluator / "src" / "scripts" / "answer_evaluation"
        package.mkdir(parents=True)
        (package / "metrics_based_eval.py").write_text(FAKE_EVALUATOR, encoding="utf-8")
        self.results = root / "results.json"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeJudgeApi)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        FakeJudgeApi.status, FakeJudgeApi.body = 200, {"choices": [{"message": {"content": "ok"}}]}
        self.env = {"OPENAI_BASE_URL": f"http://127.0.0.1:{self.server.server_address[1]}/v1",
                    "LLM_API_KEY": "k", "LLM_MODEL_NAME": "gpt-5.4"}

    def run_judge(self, extra_env=None, *flags):
        import os
        saved = {k: os.environ.get(k) for k in (*self.env, "FAKE_JUDGE_FAIL")}
        os.environ.update(self.env | (extra_env or {}))
        self.addCleanup(lambda: [os.environ.pop(k, None) if v is None else os.environ.__setitem__(k, v)
                                 for k, v in saved.items()])
        return ev.main(["judge", "--evaluator-dir", str(self.evaluator), "--answers-file", "a.jsonl",
                        "--questions-file", "q.jsonl", "--results-file", str(self.results), *flags])

    def test_a_clean_run_is_accepted(self):
        self.assertEqual(self.run_judge(), 0)
        self.assertTrue(self.results.exists())

    def test_judge_failures_invalidate_the_result_instead_of_reporting_zeros(self):
        code = self.run_judge({"FAKE_JUDGE_FAIL": "1"})

        self.assertEqual(code, ev.EXIT_JUDGE_FAILURES)
        self.assertFalse(self.results.exists())
        self.assertTrue(self.results.with_name("results.INVALID-judge-failures.json").exists())

    def test_failures_can_be_explicitly_tolerated(self):
        self.assertEqual(self.run_judge({"FAKE_JUDGE_FAIL": "1"}, "--allow-judge-failures"), 0)
        self.assertTrue(self.results.exists())

    def test_an_unavailable_judge_stops_before_the_evaluator_runs(self):
        FakeJudgeApi.status = 429
        FakeJudgeApi.body = {"error": {"code": "credit_balance_exhausted", "message": "no credits"}}

        self.assertEqual(self.run_judge(), ev.EXIT_JUDGE_UNAVAILABLE)
        self.assertFalse((Path(self.tmp.name) / "evaluator_ran").exists())

    def test_a_missing_key_is_refused(self):
        self.assertEqual(self.run_judge({"LLM_API_KEY": ""}), ev.EXIT_JUDGE_UNAVAILABLE)

    def test_failure_lines_are_counted(self):
        log = "ok\n  [WARN] qst_1: citation stripping failed\n[WARN] x: something error\n[INFO] fine\n"
        self.assertEqual(ev.count_judge_failures(log), 2)


class PairedComparisonTest(unittest.TestCase):
    def test_a_constant_improvement_is_distinguishable_from_noise(self):
        a = {f"q{i}": 40.0 for i in range(30)}
        b = {k: v + 10 for k, v in a.items()}

        report = ev.paired_report(a, b, resamples=500)

        self.assertEqual((report["wins"], report["losses"], report["ties"]), (30, 0, 0))
        self.assertAlmostEqual(report["mean_diff"], 10.0)
        self.assertTrue(report["significant"])
        self.assertGreater(report["ci95"][0], 0)

    def test_a_mixed_difference_is_not(self):
        a = {f"q{i}": 50.0 for i in range(20)}
        b = {f"q{i}": 50.0 + (30 if i % 2 else -30) for i in range(20)}

        report = ev.paired_report(a, b, resamples=500)

        self.assertFalse(report["significant"])
        self.assertEqual((report["wins"], report["losses"]), (10, 10))

    def test_only_common_questions_are_compared_and_the_bootstrap_is_reproducible(self):
        a = {"q1": 0.0, "q2": 10.0, "q3": 5.0}
        b = {"q2": 30.0, "q3": 5.0, "q4": 99.0}

        first = ev.paired_report(a, b, resamples=200, seed=7)

        self.assertEqual(first["n"], 2)
        self.assertEqual(first, ev.paired_report(a, b, resamples=200, seed=7))

    def test_no_common_question_is_an_error(self):
        with self.assertRaises(ValueError):
            ev.paired_report({"q1": 1.0}, {"q2": 1.0})

    def test_per_type_differences(self):
        a = {"q1": 0.0, "q2": 0.0, "q3": 10.0}
        b = {"q1": 20.0, "q2": 40.0, "q3": 10.0}

        report = ev.paired_report(a, b, {"q1": "basic", "q2": "basic", "q3": "semantic"}, resamples=100)

        self.assertEqual(report["by_type"]["basic"], {"n": 2, "mean_diff": 30.0})
        self.assertEqual(report["by_type"]["semantic"], {"n": 1, "mean_diff": 0.0})

    def test_score_is_correctness_times_completeness_and_recall_skips_questions_without_references(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = result_file(Path(tmp) / "r.json", [
                row("q1", True, 80.0, recall=100.0),
                row("q2", False, 90.0, recall=50.0),
                row("q3", True, 40.0, qtype="high_level", recall=None),
            ])

            score, types = ev.load_metric(path, "score")
            recall, _ = ev.load_metric(path, "recall")

        self.assertEqual(score, {"q1": 80.0, "q2": 0.0, "q3": 40.0})
        self.assertEqual(recall, {"q1": 100.0, "q2": 50.0})
        self.assertEqual(types["q3"], "high_level")

    def test_unknown_metric_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                ev.load_metric(result_file(Path(tmp) / "r.json", []), "magie")


if __name__ == "__main__":
    unittest.main()
