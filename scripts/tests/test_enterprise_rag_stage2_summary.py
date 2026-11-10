import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import enterprise_rag_stage2_summary as summary  # noqa: E402


def row(diff, low):
    return {"mean_diff": diff, "ci95": [low, diff + 0.1]}


class VerdictTest(unittest.TestCase):
    def report(self, **overrides):
        base = {"recall@10": row(0.05, 0.01), "ndcg@10": row(0.04, 0.01), "extra@10": row(0.1, -0.2),
                "client-ms": {"p95_a": 1000, "p95_b": 1500}}
        return base | overrides

    def test_adopts_when_quality_improves_within_latency_budget(self):
        self.assertTrue(summary.verdict(self.report())["adopt"])

    def test_rejects_non_significant_gain(self):
        self.assertFalse(summary.verdict(self.report(**{"ndcg@10": row(0.04, -0.01)}))["adopt"])

    def test_rejects_more_noise_or_slower_p95(self):
        self.assertFalse(summary.verdict(self.report(**{"extra@10": row(1.0, 0.5)}))["adopt"])
        slow = {"client-ms": {"p95_a": 1000, "p95_b": 2500}}
        result = summary.verdict(self.report(**slow))
        self.assertFalse(result["adopt"])
        self.assertEqual(result["p95_ratio"], 2.5)

    def test_reads_every_comparison_of_a_suite_report(self):
        report = {"retrieval": {"top20_vs_top50": self.report(), "top50_vs_group2": self.report(**{"recall@10": row(0.0, -0.1)})}}
        result = summary.verdicts(report)
        self.assertEqual({name: value["adopt"] for name, value in result.items()},
                         {"top20_vs_top50": True, "top50_vs_group2": False})


if __name__ == "__main__":
    unittest.main()
