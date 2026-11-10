import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import optimization_summary as summary  # noqa: E402


def write(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def retrieval_report(applied=1.0):
    row = lambda mean: {"mean": mean, "ci95": [mean - 0.02, mean + 0.02], "n": 100}  # noqa: E731
    return {"mode": "retrieve", "retrieval": {"overall": {"n": 100}, "recall@10": row(0.7), "ndcg@10": row(0.6),
                                              "mrr@10": {"mean": 0.5}, "extra@10": {"mean": 6.0}},
            "applied": {"rerankApplied": applied, "hybridSearchApplied": 1.0},
            "latency_ms": {"client": {"p50": 800, "p95": 2100}}}


def comparison(low):
    row = lambda diff: {"mean_diff": diff, "ci95": [low, diff + 0.05], "significant": low > 0}  # noqa: E731
    return {"recall@10": row(0.05), "ndcg@10": row(0.04), "extra@10": {"mean_diff": 0.0, "ci95": [-0.2, 0.2]},
            "client-ms": {"p95_a": 1000, "p95_b": 1400}}


class SummaryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.work = Path(temporary.name)

    def test_nothing_measured_is_reported_as_incomplete_without_any_figure(self):
        stages = [summary.stage1(self.work), summary.stage2(self.work), summary.stage3(self.work),
                  summary.stage4(self.work), summary.stage5(None)]
        self.assertTrue(all(stage.status == "NON MESURÉE" for stage in stages))
        report = summary.render([*stages, summary.stage6(False)])
        self.assertIn("**INCOMPLET**", report)
        self.assertIn("scripts/enterprise_rag_baseline.sh", report)
        self.assertNotRegex(report, r"recall@10 \d")

    def test_baseline_with_a_variant_that_did_not_apply_the_reranker_is_refused(self):
        write(self.work / "baseline-retrieve" / "report.json", retrieval_report(applied=0.4))
        stage = summary.stage1(self.work)
        self.assertEqual(stage.status, "REFUS")
        self.assertIn("rerankApplied", " ".join(stage.lines))

    def test_stage_two_reports_adopt_and_reject_per_comparison(self):
        write(self.work / "stage2" / "report.json", {
            "candidate_budget": 50, "shortlist": ["top50", "group2"],
            "retrieval": {"top20_vs_top50": comparison(0.01), "top50_vs_group2": comparison(-0.02)}})
        decisions = summary.stage2(self.work).decisions
        self.assertIn("top20_vs_top50 : **ADOPTER**", decisions[0])
        self.assertIn("top50_vs_group2 : **rejeter**", decisions[1])
        self.assertIn("recall@10", decisions[1])

    def test_matrix_and_generation_audit_are_summarised(self):
        write(self.work / "audit" / "matrix.json", [
            {"index": "nomic", "variant": "dense", "recall@10": 0.6, "ndcg@10": 0.5, "p95_ms": 300, "pareto": True},
            {"index": "bge-m3", "variant": "hybrid-rerank", "recall@10": 0.8, "ndcg@10": 0.7, "p95_ms": 900,
             "pareto": True}])
        stage3 = summary.stage3(self.work)
        self.assertIn("bge-m3/hybrid-rerank", stage3.lines[0])
        self.assertEqual(len(stage3.decisions), 2)
        write(self.work / "baseline-answer-opus" / "generation-audit.json", {
            "overall": {"correct": 62.0, "completeness": 70.0, "grounded": 0.8, "citation_precision": 0.7,
                        "correct_refusal": 0.9, "false_refusal": 0.05},
            "by_type": {"basic": {"n": 20, "correct": 80.0}, "conflicting_info": {"n": 10, "correct": 30.0}},
            "grounding": {"judge": {"model": "claude-opus-5-5"}}})
        stage4 = summary.stage4(self.work)
        self.assertIn("claude-opus-5-5", stage4.lines[0])
        self.assertIn("conflicting_info", stage4.decisions[0])

    def test_a_no_go_version_makes_the_stage_and_the_exit_status_a_refusal(self):
        write(self.work / "decision.json", {
            "base": {"model": "phi3-base"}, "judge": "claude-opus-5-5",
            "versions": {"qlora": {"decision": "GO", "reasons": []},
                         "dpo": {"decision": "NO-GO", "reasons": ["1 nouvelle(s) hallucination(s) hors corpus"]}}})
        stage = summary.stage5(self.work)
        self.assertEqual(stage.status, "REFUS")
        self.assertIn("dpo : **NO-GO** — 1 nouvelle(s)", stage.decisions[1])

    def test_complete_and_clean_results_name_the_adopted_levers_and_require_a_joint_replay(self):
        stages = []
        for number in range(1, 7):
            stage = summary.Stage(number, measured=True, lines=["ok"])
            stages.append(stage)
        stages[1].decisions = ["multi_query : **ADOPTER** (Δ recall@10 0.050)"]
        report = summary.render(stages)
        self.assertIn("Toutes les étapes sont mesurées et sans refus", report)
        self.assertIn("multi_query", report)
        self.assertIn("ENSEMBLE", report)

    def test_runtime_checks_are_never_assumed(self):
        self.assertEqual(summary.stage6(False).status, "PARTIELLE")
        self.assertEqual(summary.stage6(True).status, "MESURÉE")


if __name__ == "__main__":
    unittest.main()
