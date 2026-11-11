import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import optimization_campaign as campaign  # noqa: E402


class CampaignTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.work = Path(temporary.name) / "work"
        self.cfg = campaign.settings({"WORK": str(self.work), "API": "http://127.0.0.1:1",
                                      "EVALUATOR": str(Path(temporary.name) / "onyx")})

    def steps(self, **kwargs):
        return campaign.build_steps(self.cfg, **kwargs)

    def test_plan_orders_the_stages_and_flags_manual_index_changes(self):
        steps = self.steps()
        self.assertEqual([s.stage for s in steps], sorted(s.stage for s in steps))
        self.assertEqual(steps[0].id, "baseline")
        self.assertEqual(steps[-1].id, "summary")
        manual = {s.id for s in steps if s.manual}
        self.assertEqual(manual, {"stage2-group", "audit-indexes", "finetune"})
        plan = campaign.render_plan(steps, self.work)
        self.assertIn("| baseline | 1 | à faire |", plan)
        self.assertIn("| audit-indexes | 3 | manuelle |", plan)

    def test_a_step_whose_output_exists_is_done_and_skipped(self):
        steps = {s.id: s for s in self.steps()}
        self.assertFalse(campaign.is_done(steps["baseline"], self.work))
        output = self.work / "baseline-answer" / "report.json"
        output.parent.mkdir(parents=True)
        output.write_text("{}")
        self.assertTrue(campaign.is_done(steps["baseline"], self.work))
        self.assertIn("| baseline | 1 | faite |", campaign.render_plan(list(steps.values()), self.work))

    def test_finetune_step_builds_collect_and_decide_with_parity_references(self):
        step = next(s for s in self.steps(finetune={"base": "phi3-base", "qlora": "qlora-q8", "qlora-f16": "qlora-f16"})
                    if s.id == "finetune")
        self.assertIsNone(step.manual)
        command = step.command[-1]
        self.assertEqual(command.count("finetune_audit.py collect"), 3)
        self.assertIn("--candidate qlora", command)
        self.assertNotIn("--candidate qlora-f16", command)
        self.assertIn("--parity qlora=qlora-f16", command)
        self.assertEqual(step.produces, "finetune-audit/decision.json")

    def test_select_supports_only_from_until_and_rejects_unknown_ids(self):
        steps = self.steps()
        self.assertEqual([s.id for s in campaign.select(steps, "baseline", None, None)], ["baseline"])
        window = campaign.select(steps, None, "audit-modes", "generation")
        self.assertEqual(window[0].id, "audit-modes")
        self.assertEqual(window[-1].id, "generation")
        with self.assertRaisesRegex(ValueError, "étape inconnue"):
            campaign.select(steps, "nope", None, None)

    def test_preflight_reports_missing_key_clone_and_tools(self):
        problems = campaign.preflight(self.steps(), self.cfg, {})
        self.assertTrue(any("LLM_API_KEY" in p for p in problems))
        self.assertTrue(any("EnterpriseRAG-Bench absent" in p for p in problems))

    def test_run_stops_on_unreachable_api_and_never_executes_manual_steps(self):
        steps = campaign.select(self.steps(), "baseline", None, None)
        self.assertEqual(campaign.run(steps, self.cfg, dict(os.environ)), 2)
        manual = campaign.select(self.steps(), "audit-indexes", None, None)
        self.assertEqual(campaign.run(manual, self.cfg, dict(os.environ)), 0)

    def test_run_executes_a_command_logs_it_and_stops_at_the_first_failure(self):
        ok = campaign.Step("ok", "ok", command=("bash", "-c", "echo fait"), needs_stack=False, produces="ok.txt")
        bad = campaign.Step("bad", "bad", command=("bash", "-c", "echo boom; exit 3"), needs_stack=False)
        never = campaign.Step("never", "never", command=("bash", "-c", "touch never.txt"), needs_stack=False)
        self.assertEqual(campaign.run([ok, bad, never], self.cfg, dict(os.environ)), 1)
        log = (self.work / "campaign.log").read_text()
        self.assertIn("fait", log)
        self.assertIn("boom", log)
        self.assertNotIn("never.txt", log)

    def test_dry_run_prints_commands_without_running_them(self):
        steps = campaign.select(self.steps(), "privacy-static", None, None)
        self.assertEqual(campaign.run(steps, self.cfg, dict(os.environ), dry_run=True), 0)
        self.assertFalse((self.work / "campaign.log").exists())

    def test_finetune_option_requires_a_base_model(self):
        self.assertIsNone(campaign.parse_finetune(None))
        self.assertEqual(campaign.parse_finetune("base=a,qlora=b"), {"base": "a", "qlora": "b"})
        with self.assertRaisesRegex(ValueError, "base="):
            campaign.parse_finetune("qlora=b")


if __name__ == "__main__":
    unittest.main()
