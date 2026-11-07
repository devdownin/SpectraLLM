"""scripts/bench-resume.sh : relance Docker proprement après un redémarrage, puis passe la main au harnais."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "bench-resume.sh"


class BenchResumeTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.bin = self.dir / "bin"
        self.bin.mkdir()
        self.log = self.dir / "calls.log"

    def stub(self, name: str, body: str) -> None:
        path = self.bin / name
        path.write_text("#!/usr/bin/env bash\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def run_script(self, *args: str, docker_down_for: int = 0, dockerd: bool = True, compose: str = "") -> subprocess.CompletedProcess:
        counter = self.dir / "info.count"
        counter.write_text("0")
        self.stub("docker", f'''
if [ "$1" = "info" ]; then
  n=$(cat {counter}); echo $((n+1)) > {counter}
  [ -e {self.dir}/started ] && [ "$n" -ge {docker_down_for} ] && exit 0
  [ {docker_down_for} -eq 0 ] && exit 0
  exit 1
fi
echo "docker $*" >> {self.log}
''')
        if dockerd:
            self.stub("dockerd", f'touch {self.dir}/started; echo "dockerd" >> {self.log}')
        self.stub("python3", f'echo "python3 $*" >> {self.log}')
        pid = self.dir / "docker.pid"
        pid.write_text("1234")
        env = {**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}", "BENCH_PID_FILES": str(pid),
               "BENCH_DOCKER_WAIT": "5", "BENCH_COMPOSE": compose or f"{self.bin}/docker compose up-stub"}
        if not dockerd:
            env["BENCH_DOCKERD"] = "dockerd-absent"
        return subprocess.run(["bash", str(SCRIPT), *args], env=env, capture_output=True, text=True, timeout=60)

    def calls(self) -> list[str]:
        return self.log.read_text().splitlines() if self.log.exists() else []

    def test_docker_up_starts_the_stack_then_hands_over_to_the_harness(self):
        done = self.run_script("retrieve", "--hybrid", "on")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(self.calls(), ["docker compose up-stub up -d", "python3 scripts/enterprise_rag_bench.py retrieve --hybrid on"])
        self.assertTrue((self.dir / "docker.pid").exists(), "un démon sain ne perd pas son fichier pid")

    def test_a_stopped_daemon_is_restarted_after_removing_the_stale_pid_files(self):
        done = self.run_script("ingest", docker_down_for=1)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertFalse((self.dir / "docker.pid").exists(), "le fichier pid périmé doit être supprimé")
        self.assertEqual(self.calls()[0], "dockerd")
        self.assertEqual(self.calls()[-1], "python3 scripts/enterprise_rag_bench.py ingest")

    def test_it_fails_clearly_when_the_daemon_never_answers(self):
        done = self.run_script("ingest", docker_down_for=10_000, dockerd=False)
        self.assertEqual(done.returncode, 1)
        self.assertIn("ne répond pas", done.stderr)
        self.assertNotIn("python3 scripts/enterprise_rag_bench.py ingest", self.calls())

    def test_compose_can_be_skipped(self):
        done = self.run_script("answer", compose="none")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(self.calls(), ["python3 scripts/enterprise_rag_bench.py answer"])


if __name__ == "__main__":
    unittest.main()
