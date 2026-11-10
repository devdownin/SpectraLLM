"""Logique de scripts/privacy_runtime_check.sh contre une API factice (la vraie pile n'est pas requise)."""
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "privacy_runtime_check.sh"


class FakeApi(BaseHTTPRequestHandler):
    """Écrit le contenu ingéré dans data/qdrant ; le comportement de la suppression et des journaux varie."""
    data_dir: Path
    purge_on_delete = True
    persist_text = True
    log_questions = False

    def log_message(self, *_):
        pass

    def reply(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply({"status": "COMPLETED"} if "/api/ingest/" in self.path else {"status": "UP"})

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        cls = type(self)
        if self.path == "/api/ingest":
            if cls.persist_text:
                mark = re.search(rb"zzdocmark\w+", body).group(0).decode()
                (cls.data_dir / "qdrant" / "segment.bin").write_text("payload " + mark)
            self.reply({"taskId": "t1"})
        else:
            if cls.log_questions:
                mark = re.search(rb"zzquestionmark\w+", body).group(0).decode()
                (cls.data_dir / "logs" / "spectra.log").write_text("question " + mark)
            self.reply({"answer": "ok"})

    def do_DELETE(self):
        if type(self).purge_on_delete:
            (type(self).data_dir / "qdrant" / "segment.bin").write_text("")
        self.reply({"status": "DELETED"})


@unittest.skipUnless(shutil.which("bash") and shutil.which("curl"), "bash ou curl absent")
class RuntimeCheckTest(unittest.TestCase):
    def run_check(self, **behaviour):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            (data / "qdrant").mkdir()
            (data / "logs").mkdir()
            handler = type("Handler", (FakeApi,), {"data_dir": data, "purge_on_delete": True, "persist_text": True,
                                                   "log_questions": False, **behaviour})
            server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                return subprocess.run(
                    ["bash", str(SCRIPT), "--api", f"http://127.0.0.1:{server.server_port}", "--data-dir", str(data)],
                    capture_output=True, text=True, timeout=60, check=False,
                    env={**os.environ, "PRIVACY_CHECK_SETTLE": "0", "PRIVACY_CHECK_DELETE_SETTLE": "0"})
            finally:
                server.shutdown()
                server.server_close()

    def test_clean_stack_is_compliant(self):
        done = self.run_check()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("Contrôle conforme", done.stdout)

    def test_deleted_text_still_in_qdrant_storage_is_a_residue(self):
        done = self.run_check(purge_on_delete=False)
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("RÉSIDU  document / stockage Qdrant après suppression", done.stdout)

    def test_question_written_to_logs_is_a_residue(self):
        done = self.run_check(log_questions=True)
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("RÉSIDU  question / journaux fichiers", done.stdout)

    def test_check_is_not_conclusive_when_the_text_was_never_visible_in_storage(self):
        done = self.run_check(persist_text=False)
        self.assertEqual(done.returncode, 2, done.stdout + done.stderr)
        self.assertIn("non probant", done.stderr)

    def test_unreachable_api_is_reported(self):
        done = subprocess.run(["bash", str(SCRIPT), "--api", "http://127.0.0.1:1"], capture_output=True, text=True,
                              timeout=30, check=False)
        self.assertEqual(done.returncode, 2)
        self.assertIn("API injoignable", done.stderr)


if __name__ == "__main__":
    unittest.main()
