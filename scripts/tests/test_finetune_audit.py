import argparse
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import finetune_audit as audit  # noqa: E402

JUDGE = "judge-neutre"
ANSWERABLE = [f"Q{i}" for i in range(8)]
OUT_OF_CORPUS = ["H1", "H2", "H3"]


def report(model, scores, hallucinated, judge=JUDGE, answers=None):
    items = [{"question": q, "category": "procedures" if i < 4 else "securite", "answerable": True,
              "reference": "ref", "modelAnswer": (answers or {}).get(q, f"réponse {q}"), "score": score,
              "refused": None, "hallucinated": None, "judgeNote": ""}
             for i, (q, score) in enumerate(zip(ANSWERABLE, scores))]
    items += [{"question": q, "category": "hors-corpus", "answerable": False, "reference": None,
               "modelAnswer": "invention" if flag else "Je ne sais pas.", "score": None,
               "refused": not flag, "hallucinated": flag, "judgeNote": ""}
              for q, flag in zip(OUT_OF_CORPUS, hallucinated)]
    return {"model": model, "judgeModel": judge, "items": items}


class DecideTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.out = Path(temporary.name)
        self.write("base", report("qwen-base", [5] * 8, [False, False, True]))

    def write(self, label, data):
        (self.out / label).mkdir()
        (self.out / label / "report.json").write_text(json.dumps(data))

    def decide(self, *candidates, parity=(), **overrides):
        rules = dict(audit.DEFAULTS, **overrides)
        args = argparse.Namespace(out=self.out, base="base", candidate=list(candidates), parity=list(parity),
                                  no_parity_required=False, allow_self_judge=False, **rules)
        audit.decide(args)
        return json.loads((self.out / "decision.json").read_text())["versions"]

    def test_better_version_with_faithful_gguf_is_go(self):
        self.write("qlora", report("qlora-q8", [7] * 8, [False, False, False]))
        self.write("qlora-f16", report("qlora-f16", [7] * 8, [False, False, False]))
        version = self.decide("qlora", parity=["qlora=qlora-f16"])["qlora"]
        self.assertEqual((version["decision"], version["reasons"]), ("GO", []))
        self.assertEqual(version["comparison"]["fixed_hallucinations"], ["H3"])
        self.assertEqual(version["parity"]["exact_match"], 1.0)
        self.assertIn("| qlora | +2.00", (self.out / "decision.md").read_text())

    def test_new_out_of_corpus_hallucination_is_no_go_even_with_a_gain(self):
        self.write("dpo", report("dpo-q8", [8] * 8, [True, False, True]))
        self.write("dpo-f16", report("dpo-f16", [8] * 8, [True, False, True]))
        version = self.decide("dpo", parity=["dpo=dpo-f16"])["dpo"]
        self.assertEqual(version["decision"], "NO-GO")
        self.assertEqual(version["comparison"]["new_hallucinations"], ["H1"])
        self.assertIn("1 nouvelle(s) hallucination(s) hors corpus", version["reasons"])

    def test_no_gain_category_regression_and_unchecked_gguf_are_reported(self):
        self.write("qlora", report("qlora-q8", [8, 8, 8, 8, 2, 2, 2, 2], [False, False, True]))
        reasons = self.decide("qlora")["qlora"]["reasons"]
        self.assertTrue(any(r.startswith("aucun gain mesuré") for r in reasons))
        self.assertTrue(any(r.startswith("régression en « securite »") for r in reasons))
        self.assertIn("parité GGUF non vérifiée (aucune référence f16 fournie)", reasons)
        regressions = json.loads((self.out / "decision.json").read_text())["versions"]["qlora"]["comparison"]["regressions"]
        self.assertEqual(len(regressions), 4)

    def test_quantization_drift_is_no_go(self):
        self.write("qlora", report("qlora-q8", [7] * 8, [False, True, False], answers={"Q0": "autre"}))
        self.write("qlora-f16", report("qlora-f16", [8] * 8, [False, False, False]))
        version = self.decide("qlora", parity=["qlora=qlora-f16"])["qlora"]
        self.assertIn("quantification : Δ score -1.00 vs référence", version["reasons"])
        self.assertIn("quantification : accord de refus 67%", version["reasons"])
        self.assertLess(version["parity"]["exact_match"], 1.0)

    def test_self_judged_reports_invalidate_the_decision(self):
        self.write("qlora", report("qlora-q8", [7] * 8, [False, False, False], judge="qlora-q8"))
        reasons = self.decide("qlora", parity=[])["qlora"]["reasons"]
        self.assertTrue(any("auto-jugé" in r for r in reasons))
        self.assertTrue(any(r.startswith("juges différents") for r in reasons))


class FakeSpectra(BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *_):
        pass

    def reply(self, data):
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        FakeSpectra.calls.append(("GET", self.path))
        self.reply({"source": "CLASSPATH", "total": 11, "answerable": 8, "unanswerable": 3})

    def do_POST(self):
        FakeSpectra.calls.append(("POST", self.path))
        self.reply(report("qlora-q8", [7] * 8, [False] * 3))


class CollectTest(unittest.TestCase):
    def test_collect_freezes_the_report_and_does_not_rerun(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeSpectra)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(api=f"http://127.0.0.1:{server.server_address[1]}", model="qlora-q8",
                                      label="qlora", out=Path(directory), timeout=10)
            audit.collect(args)
            audit.collect(args)
            saved = json.loads((Path(directory) / "qlora" / "report.json").read_text())
            manifest = json.loads((Path(directory) / "qlora" / "manifest.json").read_text())
        self.assertEqual(saved["model"], "qlora-q8")
        self.assertEqual(manifest["benchmark"]["total"], 11)
        self.assertEqual([c for c in FakeSpectra.calls if c[0] == "POST"], [("POST", "/api/quality-benchmark?model=qlora-q8")])


if __name__ == "__main__":
    unittest.main()
