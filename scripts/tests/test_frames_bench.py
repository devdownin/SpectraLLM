import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from frames_bench import diagnose, indexed_articles, summarize


class FramesDiagnosticsTest(unittest.TestCase):
    def test_paginated_inventory_excludes_incomplete_and_archived_articles(self):
        record = {"fileName": "indexed.html", "ingestionComplete": True,
                  "deletionPending": False, "lifecycle": "INGESTED", "chunksCreated": 3}
        client = Mock()
        client.get.side_effect = [
            {"totalPages": 2, "content": [record]},
            {"totalPages": 2, "content": [
                {**record, "fileName": "partial.html", "ingestionComplete": False},
                {**record, "fileName": "archived.html", "lifecycle": "ARCHIVED"},
            ]},
        ]
        self.assertEqual(indexed_articles(client, {
            "indexed.html": "Indexed", "partial.html": "Partial", "archived.html": "Archived",
        }), {"Indexed"})
        self.assertEqual(client.get.call_count, 2)

    def test_diagnostic_preserves_both_missing_article_lists(self):
        result = diagnose({"Missing", "Not returned", "Returned"},
                          {"Not returned", "Returned"}, {"Returned"}, False)
        self.assertEqual(result, {
            "category": "missing_from_index",
            "missingFromIndex": ["Missing"],
            "indexedButNotReturned": ["Not returned"],
        })

    def test_false_verdict_remains_a_failure_with_complete_sources(self):
        diagnostic = diagnose({"Article"}, {"Article"}, {"Article"}, False)
        result = summarize([
            {"latencyMs": 12, "recall": 1, "correct": False, "diagnostic": diagnostic},
            {"latencyMs": 16, "recall": None, "correct": None, "diagnostic": None},
        ])
        self.assertEqual(result["accuracy"], 0)
        self.assertEqual(result["judged"], 1)
        self.assertEqual(result["meanArticleRecall"], 1)
        self.assertEqual(result["diagnostics"], {"incorrect_with_all_articles": 1})
