"""Cache freshness and compatibility are security gates, including on fork PRs."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("nvd_cache", ROOT / "scripts/nvd_cache.py")
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class NvdCacheTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        (self.directory / "odc.mv.db").write_bytes(b"database fixture")
        self.now = 1_800_000_000

    def test_fresh_successful_update_is_accepted(self):
        cache.stamp(self.directory, "12.2.2", self.now - 3600)
        cache.validate(self.directory, "12.2.2", self.now)

    def test_48_hour_boundary_is_accepted_but_older_cache_is_rejected(self):
        cache.stamp(self.directory, "12.2.2", self.now - cache.MAX_AGE_SECONDS)
        cache.validate(self.directory, "12.2.2", self.now)
        with self.assertRaisesRegex(ValueError, "périmé"):
            cache.validate(self.directory, "12.2.2", self.now + 1)

    def test_missing_database_cannot_be_stamped_or_validated(self):
        (self.directory / "odc.mv.db").unlink()
        with self.assertRaisesRegex(ValueError, "absente"):
            cache.stamp(self.directory, "12.2.2", self.now)
        with self.assertRaisesRegex(ValueError, "absente"):
            cache.validate(self.directory, "12.2.2", self.now)

    def test_empty_database_is_rejected(self):
        (self.directory / "odc.mv.db").write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "vide"):
            cache.stamp(self.directory, "12.2.2", self.now)

    def test_incomplete_update_without_marker_is_rejected(self):
        with self.assertRaises(FileNotFoundError):
            cache.validate(self.directory, "12.2.2", self.now)

    def test_different_plugin_version_is_rejected(self):
        cache.stamp(self.directory, "12.1.0", self.now)
        with self.assertRaisesRegex(ValueError, "incompatible"):
            cache.validate(self.directory, "12.2.2", self.now)

    def test_future_or_invalid_timestamp_is_rejected(self):
        for timestamp in (self.now + 1, "today", True, None):
            with self.subTest(timestamp=timestamp):
                (self.directory / cache.MARKER).write_text(json.dumps(
                    {"plugin_version": "12.2.2", "updated_at": timestamp}))
                with self.assertRaisesRegex(ValueError, "date invalide"):
                    cache.validate(self.directory, "12.2.2", self.now)

    def test_corrupt_marker_is_rejected(self):
        (self.directory / cache.MARKER).write_text("not JSON")
        with self.assertRaises(ValueError):
            cache.validate(self.directory, "12.2.2", self.now)

    def test_version_is_read_from_the_dependency_check_plugin(self):
        pom = self.directory / "pom.xml"
        pom.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><build><plugins>
        <plugin><artifactId>other-plugin</artifactId><version>1.0.0</version></plugin>
        <plugin><artifactId>dependency-check-maven</artifactId><version>12.2.2</version></plugin>
        </plugins></build></project>''')
        self.assertEqual(cache.plugin_version(pom), "12.2.2")


class NvdWorkflowTest(unittest.TestCase):
    def test_pull_request_scan_has_no_secret_update_or_cache_write(self):
        workflow = (ROOT / ".github/workflows/dependency-scan.yml").read_text()
        scan = workflow.split("  depcheck:\n", 1)[1]
        self.assertNotIn("NVD_API_KEY", scan)
        self.assertNotIn("-DnvdApiKey", scan)
        self.assertNotIn("actions/cache/save", scan)
        self.assertIn("-DautoUpdate=false", scan)
        self.assertIn("-DfailOnError=true", scan)
        self.assertLess(scan.index("nvd_cache.py validate"), scan.index("dependency-check-maven:check"))

    def test_pull_request_rebuild_is_a_keyless_fallback_that_is_never_published(self):
        # Cache évincé : la PR reconstruit la base dans son job depuis les flux publics (aucune clé),
        # sans la publier. L'analyse qui suit reste sans mise à jour (autoUpdate=false).
        workflow = (ROOT / ".github/workflows/dependency-scan.yml").read_text()
        scan = workflow.split("  depcheck:\n", 1)[1]
        self.assertEqual(scan.count("dependency-check-maven:update-only"), 1)
        rebuild = scan[scan.index("Reconstruire la base NVD"):scan.index("dependency-check-maven:check")]
        self.assertIn("steps.validate.outputs.valid == 'false'", rebuild)
        self.assertIn("nvdcve-2.0-{0}.json.gz", rebuild)
        self.assertLess(rebuild.index("update-only"), rebuild.index("nvd_cache.py stamp"))
        self.assertLess(rebuild.index("nvd_cache.py stamp"), rebuild.index("nvd_cache.py validate"))
        self.assertIn("validate --soft", scan)

    def test_only_successful_default_branch_updates_publish_cache(self):
        workflow = (ROOT / ".github/workflows/dependency-scan.yml").read_text()
        update = workflow.split("  depcheck:\n", 1)[0]
        self.assertIn("github.event_name != 'pull_request'", update)
        self.assertIn("github.event.repository.default_branch", update)
        self.assertLess(update.index("dependency-check-maven:update-only"), update.index("nvd_cache.py stamp"))
        self.assertLess(update.index("nvd_cache.py stamp"), update.index("actions/cache/save"))
        self.assertNotIn("continue-on-error", workflow)

    def test_update_rebuilds_from_the_yearly_feeds_not_the_api(self):
        # Interrompue, une mise à jour par l'API repart de zéro au passage suivant, et une
        # alimentation complète par l'API dépasse les 6 h d'un runner : un cache évincé ne se
        # reconstruisait jamais. Les flux annuels tiennent dans le budget, sans clé.
        workflow = (ROOT / ".github/workflows/dependency-scan.yml").read_text()
        update = workflow.split("  depcheck:\n", 1)[0]
        self.assertIn("-DnvdDatafeedUrl=https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz", update)
        self.assertNotIn("-DnvdApiKey", update)


class SoftValidationTest(unittest.TestCase):
    """`validate --soft` laisse le workflow reconstruire la base au lieu d'échouer sur un cache évincé."""

    def run_validate(self, directory, *extra):
        import contextlib
        import io
        import os
        import sys
        from unittest import mock
        output = Path(directory) / "github_output"
        argv = ["nvd_cache.py", "validate", "--data", str(directory), *extra]
        with mock.patch.object(sys, "argv", argv), mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), \
                contextlib.redirect_stdout(io.StringIO()):
            code = cache.main()
        return code, output.read_text() if output.exists() else ""

    def test_missing_database_is_a_failure_by_default_and_a_rebuild_signal_when_soft(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(self.run_validate(directory), (1, ""))
            self.assertEqual(self.run_validate(directory, "--soft"), (0, "valid=false\n"))

    def test_fresh_database_is_valid_in_soft_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "odc.mv.db").write_bytes(b"database fixture")
            cache.stamp(Path(directory), cache.plugin_version(ROOT / "backend/pom.xml"), int(__import__("time").time()))
            self.assertEqual(self.run_validate(directory, "--soft"), (0, "valid=true\n"))


if __name__ == "__main__":
    unittest.main()
