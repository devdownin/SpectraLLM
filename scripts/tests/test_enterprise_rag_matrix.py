import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import enterprise_rag_matrix as matrix  # noqa: E402


def write_report(root: Path, index: str, variant: str, recall: float, p95: float, **extra) -> None:
    directory = root / index / variant
    directory.mkdir(parents=True)
    retrieval = {"overall": {"n": 120}, "recall@10": {"mean": recall, "ci95": [recall - 0.02, recall + 0.02]},
                 "ndcg@10": {"mean": recall - 0.1}, "mrr@10": {"mean": recall - 0.2},
                 "all-gold@10": {"mean": recall - 0.3}, "extra@10": {"mean": 7.0}}
    report = {"mode": "retrieve", "profile": {"variant": {"embedding": index}}, "retrieval": retrieval,
              "latency_ms": {"client": {"p50": p95 / 2, "p95": p95}}, "applied": {"rerankApplied": 1.0}, **extra}
    (directory / "report.json").write_text(json.dumps(report))


class MatrixTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_pareto_keeps_only_non_dominated_configurations(self):
        write_report(self.root, "nomic", "dense", 0.60, 100)
        write_report(self.root, "nomic", "hybrid", 0.70, 150)
        write_report(self.root, "nomic", "slow-poor", 0.65, 400)  # dominée par hybrid
        write_report(self.root, "bge-m3", "hybrid", 0.80, 300)
        rows = matrix.load_rows(self.root)
        matrix.mark_pareto(rows)
        self.assertEqual({(r["index"], r["variant"]) for r in rows if r["pareto"]},
                         {("nomic", "dense"), ("nomic", "hybrid"), ("bge-m3", "hybrid")})

    def test_render_lists_every_run_and_skips_non_retrieval_reports(self):
        write_report(self.root, "nomic", "dense", 0.6, 100)
        answer = self.root / "nomic" / "answer"
        answer.mkdir()
        (answer / "report.json").write_text(json.dumps({"mode": "answer", "generation": None}))
        rows = matrix.load_rows(self.root)
        matrix.mark_pareto(rows)
        table = matrix.render(rows, with_delta=False)
        self.assertEqual(len(rows), 1)
        self.assertIn("| nomic | dense | 0.600 |", table)
        self.assertIn("★", table)

    def test_significant_delta_is_starred(self):
        delta = {"recall@10": {"mean_diff": 0.05, "ci95": [0.01, 0.09], "significant": True}}
        self.assertEqual(matrix.signed(delta, "recall@10"), "+0.050*")
        self.assertEqual(matrix.signed(delta, "ndcg@10"), "—")
        self.assertEqual(matrix.signed(None, "recall@10"), "—")


if __name__ == "__main__":
    unittest.main()
