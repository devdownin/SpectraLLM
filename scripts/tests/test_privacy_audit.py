import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import privacy_audit as audit  # noqa: E402


def java(directory: Path, body: str) -> Path:
    (directory / "A.java").write_text("class A { void f() {\n" + body + "\n} }\n", encoding="utf-8")
    return directory


class LogScanTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def scan(self, body):
        return audit.scan_logs(java(self.directory, body))

    def test_raw_question_url_and_multiline_calls_are_reported(self):
        self.assertEqual(len(self.scan('log.info("q {}", question);')), 1)
        self.assertEqual(len(self.scan('log.warn("u {} {}",\n    arm.label(), url);')), 1)
        self.assertIn("question, reformulated", self.scan('log.info("{} {}", question, reformulated);')[0])

    def test_wrapped_sizes_and_literals_are_accepted(self):
        for body in ('log.info("q {}", LogSafe.text(question));',
                     'log.info("u {}", fr.spectra.util.LogSafe.url(url));',
                     'log.debug("{} chars", text.length());',
                     'log.info("{} requêtes", queries.size());',
                     'log.info("la question est ignorée");',
                     'log.warn("échec : {}", e.getMessage());'):
            self.assertEqual(self.scan(body), [], body)

    def test_privacy_ok_marker_is_the_only_escape_hatch(self):
        self.assertEqual(self.scan('log.info("q {}", question); // privacy-ok: valeur de configuration'), [])
        self.assertEqual(len(self.scan('log.info("q {}", question); // TODO')), 1)

    def test_the_repository_backend_has_no_raw_user_data_in_logs(self):
        self.assertEqual(audit.scan_logs(audit.ROOT / "backend" / "src" / "main" / "java"), [])


class EgressTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "backend/src/main/java").mkdir(parents=True)
        self.allowlist = self.root / "allow.txt"

    def test_unlisted_host_fails_and_comments_and_examples_are_ignored(self):
        (self.root / "backend/src/main/java/A.java").write_text(
            '// voir https://ignore.example.org/doc\nString u = "https://telemetry.vendor.io/ping";\n'
            'String ok = "https://docs.example.com/x";\n', encoding="utf-8")
        self.allowlist.write_text("# vide\n", encoding="utf-8")
        unknown, stale = audit.check_egress(self.root, self.allowlist)
        self.assertEqual(len(unknown), 1)
        self.assertIn("telemetry.vendor.io", unknown[0])
        self.assertEqual(stale, [])

    def test_declared_host_passes_and_a_vanished_one_is_flagged_stale(self):
        (self.root / "backend/src/main/java/A.java").write_text('String u = "https://huggingface.co/api";\n')
        self.allowlist.write_text("runtime | huggingface.co | Model Hub\nbuild | gone.example.net | ancien\n")
        unknown, stale = audit.check_egress(self.root, self.allowlist)
        self.assertEqual(unknown, [])
        self.assertEqual(len(stale), 1)

    def test_malformed_allowlist_lines_are_rejected(self):
        self.allowlist.write_text("runtime | huggingface.co\n")
        with self.assertRaisesRegex(ValueError, "format attendu"):
            audit.load_allowlist(self.allowlist)
        self.allowlist.write_text("dev | huggingface.co | x\n")
        with self.assertRaisesRegex(ValueError, "portée inconnue"):
            audit.load_allowlist(self.allowlist)

    def test_the_repository_allowlist_matches_the_code(self):
        unknown, stale = audit.check_egress(audit.ROOT, audit.ALLOWLIST)
        self.assertEqual((unknown, stale), ([], []))


def compliant() -> dict:
    def service(**extra):
        return {"networks": {"default": None}, "environment": {}, **extra}
    return {
        "networks": {"default": {"internal": True}, "edge": {}},
        "services": {
            "frontend": service(networks={"default": None, "edge": None},
                                ports=[{"host_ip": "127.0.0.1", "published": "80"}]),
            "spectra-api": service(environment={"SPECTRA_OFFLINE": "true",
                                                "SPECTRA_STARTUP_AUTO_INSTALL_MODELS": "false"}),
            "qdrant": service(environment={"QDRANT__TELEMETRY_DISABLED": "true"}),
            "reranker": service(environment={"HF_HUB_OFFLINE": "1"}),
        },
    }


class AirgapTest(unittest.TestCase):
    def problems(self, mutate):
        config = compliant()
        mutate(config)
        return audit.check_airgap(config)

    def test_compliant_configuration_has_no_problem(self):
        self.assertEqual(audit.check_airgap(compliant()), [])

    def test_each_escape_route_is_reported(self):
        cases = {
            "pas interne": lambda c: c["networks"]["default"].update(internal=False),
            "n'est pas interne": lambda c: c["services"]["qdrant"].update(networks={"default": None, "edge": None}),
            "publie le port 8080": lambda c: c["services"]["spectra-api"].update(
                ports=[{"host_ip": "127.0.0.1", "published": "8080"}]),
            "boucle locale": lambda c: c["services"]["frontend"].update(
                ports=[{"host_ip": "0.0.0.0", "published": "80"}]),
            "network_mode=host": lambda c: c["services"]["reranker"].update(network_mode="host"),
            "SPECTRA_OFFLINE": lambda c: c["services"]["spectra-api"]["environment"].update(SPECTRA_OFFLINE="false"),
            "AUTO_INSTALL": lambda c: c["services"]["spectra-api"]["environment"].update(
                SPECTRA_STARTUP_AUTO_INSTALL_MODELS="true"),
            "HF_HUB_OFFLINE": lambda c: c["services"]["reranker"]["environment"].pop("HF_HUB_OFFLINE"),
            "TELEMETRY": lambda c: c["services"]["qdrant"]["environment"].pop("QDRANT__TELEMETRY_DISABLED"),
            "service absent": lambda c: c["services"].pop("qdrant"),
        }
        for expected, mutate in cases.items():
            with self.subTest(expected):
                self.assertTrue(any(expected in problem for problem in self.problems(mutate)), expected)

    @unittest.skipUnless(shutil.which("docker"), "docker absent")
    def test_the_repository_airgap_overlay_is_compliant(self):
        try:
            config = audit.compose_config(audit.ROOT / "deploy/docker/docker-compose.yml",
                                          audit.ROOT / "deploy/docker/docker-compose.airgap.yml")
        except (ValueError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
            self.skipTest(f"docker compose config indisponible : {exc}")
        self.assertEqual(audit.check_airgap(config), [])


if __name__ == "__main__":
    unittest.main()
