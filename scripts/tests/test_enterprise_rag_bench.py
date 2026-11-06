"""Harness EnterpriseRAG-Bench : format de sortie, reprise et extraction des doc_ids."""

import importlib.util
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("enterprise_rag_bench", ROOT / "scripts/enterprise_rag_bench.py")
bench = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bench  # requis par @dataclass à l'exécution du module
spec.loader.exec_module(bench)

GOLD_A = "dsid_" + "a" * 32
GOLD_B = "dsid_" + "b" * 32
OTHER = "dsid_" + "c" * 32


def write_questions(path: Path) -> None:
    rows = [
        {"question_id": "qst_0001", "question_type": "basic", "question": "Q1?", "expected_doc_ids": [GOLD_A]},
        {"question_id": "qst_0002", "question_type": "completeness", "question": "Q2?",
         "expected_doc_ids": [GOLD_A, GOLD_B]},
        {"question_id": "qst_0003", "question_type": "info_not_found", "question": "Q3?", "expected_doc_ids": []},
    ]
    path.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")


class FakeSpectra(BaseHTTPRequestHandler):
    queries: list = []
    uploads: list = []

    def log_message(self, *_):
        pass

    def _reply(self, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path == "/api/ingest":
            FakeSpectra.uploads.append(body.count(b'name="files"'))
            self._reply({"taskId": f"t{len(FakeSpectra.uploads)}", "status": "PENDING"})
            return
        FakeSpectra.queries.append(json.loads(body))
        if self.path == "/api/query/retrieve":
            self._reply({"answer": "", "durationMs": 7, "sources": [
                {"sourceFile": f"{OTHER}.txt", "rerankScore": 0.8},
                {"sourceFile": f"{GOLD_A}.txt", "rerankScore": 0.6},
                {"sourceFile": f"{GOLD_A}.txt", "rerankScore": 0.5},
                {"sourceFile": f"{GOLD_B}.txt", "rerankScore": 0.1}]})
            return
        self._reply({
            "answer": "42",
            "sources": [
                {"sourceFile": f"{GOLD_A}.txt", "rerankScore": 0.91},
                {"sourceFile": f"{GOLD_A}.txt"},
                {"sourceFile": f"{OTHER}.txt"},
                {"sourceFile": "notes.pdf"},
            ],
            "ragStrategy": "STANDARD",
            "contextChars": 1234,
            "durationMs": 12,
        })

    def do_GET(self):
        self._reply({"taskId": self.path.rsplit("/", 1)[-1], "status": "COMPLETED", "chunksCreated": 3,
                     "fileErrors": []})


class EnterpriseRagBenchTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.work = Path(tmp.name)
        write_questions(self.work / "questions.jsonl")
        FakeSpectra.queries, FakeSpectra.uploads = [], []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeSpectra)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.api = f"http://127.0.0.1:{self.server.server_address[1]}"

    def run_cli(self, *args: str) -> None:
        bench.main(["--work-dir", str(self.work), "--api", self.api, "--timeout", "5", *args])

    def test_doc_ids_are_deduplicated_in_rank_order_and_capped(self):
        sources = [{"sourceFile": f"{OTHER}.txt"}, {"sourceFile": f"{GOLD_A}.TXT"}, {"sourceFile": f"{OTHER}.txt"},
                   {"sourceFile": None}, {"sourceFile": f"archive.zip/{GOLD_B}.txt"}]
        self.assertEqual(bench.extract_doc_ids(sources), [OTHER, GOLD_A, GOLD_B])
        self.assertEqual(bench.extract_doc_ids(sources, limit=1), [OTHER])

    def test_subset_keeps_every_gold_doc_and_is_reproducible(self):
        ids = [f"dsid_{i:032x}" for i in range(100)]
        gold = {ids[3], ids[50]}
        first = bench.select_subset(ids, gold, 10, seed=7)
        self.assertEqual(first, bench.select_subset(ids, gold, 10, seed=7))
        self.assertTrue(gold <= first)
        self.assertEqual(len(first), 12)

    def test_rendered_document_is_unique_per_doc_id_even_with_identical_content(self):
        self.assertNotEqual(bench.render_document(GOLD_A, "slack", "T", "same"),
                            bench.render_document(GOLD_B, "slack", "T", "same"))

    def test_batches_respect_file_count_and_byte_budget(self):
        docs = self.work / "documents"
        docs.mkdir()
        for i in range(5):
            (docs / f"dsid_{i:032x}.txt").write_bytes(b"x" * 10)
        files = list(docs.iterdir())
        self.assertEqual([len(b) for b in bench.plan_batches(files, max_files=2)], [2, 2, 1])
        self.assertEqual([len(b) for b in bench.plan_batches(files, max_files=10, max_bytes=25)], [2, 2, 1])

    def test_answer_writes_official_format_and_resumes(self):
        self.run_cli("answer", "--limit", "2")
        self.run_cli("answer")

        rows = [json.loads(line) for line in (self.work / "answers.jsonl").read_text().splitlines()]
        self.assertEqual(sorted(r["question_id"] for r in rows), ["qst_0001", "qst_0002", "qst_0003"])
        self.assertEqual(len(FakeSpectra.queries), 3)
        self.assertEqual(rows[0]["document_ids"], [GOLD_A, OTHER])
        self.assertEqual(rows[0]["answer"], "42")
        self.assertTrue(FakeSpectra.queries[0]["question"].endswith("Answer in English."))
        self.assertEqual(FakeSpectra.queries[0]["temperature"], 0.0)

    def test_ingest_uploads_each_batch_once_across_restarts(self):
        docs = self.work / "documents"
        docs.mkdir()
        for i in range(5):
            (docs / f"dsid_{i:032x}.txt").write_text("doc")
        self.run_cli("ingest", "--batch-size", "2", "--poll", "0")
        self.run_cli("ingest", "--batch-size", "2", "--poll", "0")
        self.assertEqual(FakeSpectra.uploads, [2, 2, 1])

    def test_prepare_from_github_source_dir_writes_subset_with_flattened_fields(self):
        source = self.work / "generated_data"
        docs = {
            GOLD_A: ("hubspot/acme.json", {"company_name": "Acme", "title_field_name": "company_name",
                                           "content_field_names": ["next_step", "blockers"],
                                           "next_step": "Sign SLA", "blockers": ["legal", "pricing"]}),
            OTHER: ("slack/eng/x.json", {"channel": "eng", "title_field_name": "channel",
                                         "content_field_names": ["messages"], "messages": "Ava: hi"}),
        }
        for doc_id, (rel, raw) in docs.items():
            path = source / "sources" / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({**raw, "dataset_doc_uuid": doc_id}))
        (source / "uuid_index.json").write_text(json.dumps({d: rel for d, (rel, _) in docs.items()}))

        bench.main(["--work-dir", str(self.work), "prepare", "--subset", "0", "--source-dir", str(source),
                    "--questions-url", "http://unused.invalid"])

        written = sorted(p.name for p in (self.work / "documents").iterdir())
        self.assertEqual(written, [f"{GOLD_A}.txt"])
        text = (self.work / "documents" / f"{GOLD_A}.txt").read_text()
        self.assertTrue(text.startswith(f"[{GOLD_A}] [hubspot] Acme"))
        self.assertIn("Sign SLA\n\nlegal\n\npricing", text)

    def test_per_type_sample_takes_first_questions_of_each_category(self):
        questions = [bench.Question(f"q{i}", t, "?", ()) for i, t in enumerate("aabbbca")]
        self.assertEqual([q.question_id for q in bench.sample_per_type(questions, 2)], ["q0", "q1", "q2", "q3", "q5"])

    def test_citation_numbers_cover_single_grouped_and_repeated_forms(self):
        self.assertEqual(bench.cited_source_numbers("Vrai [3]. Aussi [1, 2] et [3][4]."), [3, 1, 2, 4])
        self.assertEqual(bench.cited_source_numbers("Aucune citation, un tableau[]"), [])
        self.assertEqual(bench.cited_source_numbers(None), [])

    def test_cited_doc_ids_keep_only_cited_passages_in_citation_order(self):
        sources = [{"sourceFile": f"{GOLD_A}.txt"}, {"sourceFile": f"{GOLD_B}.txt"}, {"sourceFile": f"{OTHER}.txt"}]

        self.assertEqual(bench.cited_doc_ids("Fait [3] puis fait [1].", sources), [OTHER, GOLD_A])

    def test_cited_doc_ids_ignore_numbers_that_do_not_exist(self):
        sources = [{"sourceFile": f"{GOLD_A}.txt"}, {"sourceFile": f"{GOLD_B}.txt"}]

        self.assertEqual(bench.cited_doc_ids("Fait [9] et [2] et [0].", sources), [GOLD_B])

    def test_cited_doc_ids_fall_back_to_all_retrieved_when_nothing_valid_is_cited(self):
        sources = [{"sourceFile": f"{GOLD_A}.txt"}, {"sourceFile": f"{GOLD_B}.txt"}]

        self.assertEqual(bench.cited_doc_ids("Pas de citation.", sources), [GOLD_A, GOLD_B])
        self.assertEqual(bench.cited_doc_ids("Citation invalide [7].", sources), [GOLD_A, GOLD_B])

    def test_answer_can_return_only_cited_documents_and_keeps_both_lists(self):
        self.run_cli("answer", "--limit", "1", "--sources", "cited")

        row = json.loads((self.work / "answers.jsonl").read_text().splitlines()[0])
        self.assertEqual(row["spectra"]["retrievedDocumentIds"], [GOLD_A, OTHER])
        self.assertEqual(row["spectra"]["citedDocumentIds"], [GOLD_A, OTHER])  # la réponse factice ne cite rien
        self.assertEqual(row["document_ids"], [GOLD_A, OTHER])

    def test_query_overrides_are_sent_only_when_requested(self):
        self.run_cli("answer", "--limit", "1")
        self.run_cli("answer", "--limit", "1", "--output", str(self.work / "b.jsonl"),
                     "--hybrid", "off", "--neighbor-window", "3")

        self.assertNotIn("overrides", FakeSpectra.queries[0])
        self.assertEqual(FakeSpectra.queries[1]["overrides"], {"hybrid": False, "neighborWindow": 3})

    def test_noise_and_multi_document_overrides_are_sent_only_when_requested(self):
        self.run_cli("answer", "--limit", "1", "--multi-query", "on", "--dedup", "mmr", "--rerank-min-score", "0.2")

        self.assertEqual(FakeSpectra.queries[0]["overrides"],
                         {"multiQuery": True, "dedupStrategy": "mmr", "rerankMinScore": 0.2})

    def test_answer_keeps_rerank_scores_and_prompt_size_for_calibration(self):
        self.run_cli("answer", "--limit", "1")

        spectra = json.loads((self.work / "answers.jsonl").read_text().splitlines()[0])["spectra"]
        self.assertEqual(spectra["contextChars"], 1234)
        self.assertEqual(spectra["sourceScores"][0], {"docIds": [GOLD_A], "rerankScore": 0.91})

    def test_retrieval_report_ranks_distinct_documents_and_scores_recall_hit_mrr_and_extras(self):
        questions = bench.load_questions(self.work / "questions.jsonl")
        # qst_0001 : référence A au 2e rang ; qst_0002 : références A et B, B hors du top 3.
        rankings = {"qst_0001": [OTHER, GOLD_A], "qst_0002": [GOLD_A, OTHER, "dsid_" + "d" * 32, GOLD_B]}

        report = bench.retrieval_report(questions, rankings, ks=(1, 3))

        self.assertEqual(report["overall"]["n"], 2)
        self.assertAlmostEqual(report["overall"]["mrr"], (0.5 + 1.0) / 2)
        self.assertAlmostEqual(report["overall"]["recall@1"], (0.0 + 0.5) / 2)
        self.assertAlmostEqual(report["overall"]["recall@3"], (1.0 + 0.5) / 2)
        self.assertAlmostEqual(report["overall"]["hit@1"], 0.5)
        self.assertAlmostEqual(report["overall"]["extra@3"], (1 + 2) / 2)
        self.assertEqual(sorted(report["by_type"]), ["basic", "completeness"])

    def test_retrieval_report_ignores_questions_without_references_or_without_a_ranking(self):
        questions = bench.load_questions(self.work / "questions.jsonl")

        report = bench.retrieval_report(questions, {"qst_0003": [GOLD_A]})

        self.assertEqual(report["overall"], {"n": 0})

    def test_retrieve_calls_the_generation_free_endpoint_and_keeps_the_ranking(self):
        self.run_cli("retrieve", "--hybrid", "off", "--rerank-min-score", "0.2")

        rows = [json.loads(line) for line in (self.work / "retrieval.jsonl").read_text().splitlines()]
        self.assertEqual(len(rows), 3)
        self.assertEqual(rows[0]["document_ids"], [OTHER, GOLD_A, GOLD_B])
        self.assertEqual(rows[0]["sourceScores"][0], {"docIds": [OTHER], "rerankScore": 0.8})
        # Pas de suffixe de langue (propre à la génération) et surcharges transmises.
        self.assertEqual(FakeSpectra.queries[0]["question"], "Q1?")
        self.assertEqual(FakeSpectra.queries[0]["overrides"], {"hybrid": False, "rerankMinScore": 0.2})

    def test_retrieve_resumes_without_asking_again(self):
        self.run_cli("retrieve", "--limit", "1")
        self.run_cli("retrieve")

        self.assertEqual(len(FakeSpectra.queries), 3)
        self.assertEqual(len((self.work / "retrieval.jsonl").read_text().splitlines()), 3)

    def test_recall_ignores_questions_without_gold_documents(self):
        questions = bench.load_questions(self.work / "questions.jsonl")
        report = bench.recall_report(questions, {"qst_0001": [GOLD_A, OTHER], "qst_0002": [GOLD_A]})
        self.assertEqual(report["questions"], 2)
        self.assertAlmostEqual(report["recall"], 0.75)
        self.assertEqual(report["by_type"]["basic"]["mean_extra_docs"], 1)

    def test_recall_only_scores_answered_questions(self):
        questions = bench.load_questions(self.work / "questions.jsonl")
        report = bench.recall_report(questions, {"qst_0001": [GOLD_A]})
        self.assertEqual(report["questions"], 1)
        self.assertEqual(report["recall"], 1.0)


if __name__ == "__main__":
    unittest.main()
