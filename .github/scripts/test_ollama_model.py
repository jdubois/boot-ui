import importlib.util
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).with_name("prepare-ollama-model.py")
RETRY = Path(__file__).with_name("retry.sh")
WORKFLOW = ROOT / ".github/workflows/docker-publish.yml"
SPEC = importlib.util.spec_from_file_location("prepare_ollama_model", SCRIPT)
ollama = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ollama)

FAKE_COMMAND = r"""
import json
import os
import sys
from pathlib import Path

directory = Path(os.environ["FAKE_COMMANDS"])
scenario = os.environ.get("SCENARIO", "success")
name = Path(sys.argv[0]).name
arguments = sys.argv[1:]
with (directory / "calls").open("a") as log:
    log.write(json.dumps([name, arguments]) + "\n")
if name == "docker":
    sys.exit(7 if scenario == "docker-failure" else 0)
if name == "sleep":
    sys.exit(0)
url = next(argument for argument in arguments if argument.startswith("http://"))
if url.endswith("/api/pull"):
    counter = directory / "pulls"
    count = int(counter.read_text()) + 1 if counter.exists() else 1
    counter.write_text(str(count))
    if scenario == "persistent" or (scenario == "transient" and count == 1):
        print("connection reset by peer", file=sys.stderr)
        sys.exit(22)
    if scenario == "api-error":
        print(json.dumps({"error": "registry unavailable"}))
    elif scenario == "http-error":
        print(json.dumps({"error": "upstream HTTP failure"}))
        sys.exit(22)
    elif scenario == "incomplete":
        print(json.dumps({"status": "pulling manifest"}))
    elif scenario == "malformed":
        print("not JSON")
    else:
        print(json.dumps({"status": "success"}))
else:
    model = os.environ["EXPECTED_MODEL"]
    models = [{"name": model}]
    if scenario == "missing" or (
        scenario == "missing-once" and (directory / "pulls").read_text() == "1"
    ):
        models = [{"name": model + "-other"}]
    if scenario == "invalid-inventory":
        models = [{"name": None}]
    print(json.dumps({"models": models}))
"""


class OllamaModelTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        for name in ("curl", "docker", "sleep"):
            command = self.directory / name
            command.write_text(f"#!{sys.executable}\n{FAKE_COMMAND}")
            command.chmod(0o755)
        _, model = ollama.configuration(ollama.PROPERTIES)
        self.env = {
            **os.environ,
            "PATH": f"{self.directory}{os.pathsep}{os.environ['PATH']}",
            "FAKE_COMMANDS": str(self.directory),
            "EXPECTED_MODEL": model,
            "PYTHONDONTWRITEBYTECODE": "1",
        }

    def run_preparation(self, scenario="success"):
        return subprocess.run(
            ["bash", str(RETRY), "3", "0", sys.executable, str(SCRIPT)],
            env={**self.env, "SCENARIO": scenario},
            capture_output=True,
            text=True,
            check=False,
            timeout=15,
        )

    def calls(self):
        return [
            json.loads(line)
            for line in (self.directory / "calls").read_text().splitlines()
        ]

    def test_transient_pull_failure_is_retried_and_verified(self):
        result = self.run_preparation("transient")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / "pulls").read_text(), "2")
        self.assertIn("available locally", result.stdout)
        self.assertIn("connection reset by peer", result.stderr)
        calls = self.calls()
        self.assertTrue(calls[-1][1][-1].endswith("/api/tags"))
        pull = calls[0][1]
        self.assertEqual(
            json.loads(pull[pull.index("--data") + 1]),
            {"model": self.env["EXPECTED_MODEL"], "stream": False},
        )
        self.assertEqual(pull[pull.index("--max-time") + 1], "180")

    def test_persistent_failure_exhausts_retries_and_fails(self):
        result = self.run_preparation("persistent")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.directory / "pulls").read_text(), "3")
        self.assertIn("after 3 attempts", result.stderr)
        self.assertFalse(
            any(argument.endswith("/api/tags") for _, args in self.calls() for argument in args)
        )

    def test_verification_failure_retries_the_pull(self):
        result = self.run_preparation("missing-once")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.directory / "pulls").read_text(), "2")
        self.assertIn("not available locally", result.stderr)

    def test_invalid_or_unsuccessful_responses_never_succeed(self):
        for scenario in (
            "missing", "api-error", "http-error", "incomplete", "malformed", "invalid-inventory"
        ):
            with self.subTest(scenario=scenario):
                result = self.run_preparation(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Ollama model preparation failed", result.stderr)
                self.assertIn("after 3 attempts", result.stderr)
                if scenario == "http-error":
                    self.assertIn("upstream HTTP failure", result.stderr)

    def test_unresponsive_local_server_hits_the_request_deadline(self):
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            url = f"http://127.0.0.1:{server.getsockname()[1]}/api/pull"
            started = time.monotonic()
            with self.assertRaises(subprocess.CalledProcessError) as failure:
                ollama.request(url, 0.1, {"model": "example", "stream": False})
            self.assertEqual(failure.exception.returncode, 28)
            self.assertLess(time.monotonic() - started, 3)

    def test_command_timeout_is_bounded_and_propagates(self):
        with patch.object(
            ollama.subprocess,
            "run",
            side_effect=subprocess.TimeoutExpired("curl", 185),
        ) as run:
            with self.assertRaises(subprocess.TimeoutExpired):
                ollama.request("http://localhost:11434/api/pull", 180)
        self.assertEqual(run.call_args.kwargs["timeout"], 185)

    def test_untagged_model_is_verified_using_ollamas_default_tag(self):
        with (
            patch.object(ollama, "configuration", return_value=("http://localhost:11434", "example")),
            patch.object(ollama, "request", side_effect=[
                {"status": "success"}, {"models": [{"name": "example:latest"}]}
            ]),
        ):
            ollama.prepare()

    def test_invalid_configuration_is_rejected_before_network_work(self):
        for content in (
            "",
            "spring.ai.ollama.base-url=http://localhost:11434\nspring.ai.ollama.chat.model=\n",
            "spring.ai.ollama.base-url=http://localhost:11434\nspring.ai.ollama.chat.model=${MODEL}\n",
            "spring.ai.ollama.base-url=http://localhost:11434\n"
            "spring.ai.ollama.chat.model=one\nspring.ai.ollama.chat.model=two\n",
            "spring.ai.ollama.base-url=https://example.com\nspring.ai.ollama.chat.model=one\n",
        ):
            with self.subTest(content=content):
                properties = self.directory / "application.properties"
                properties.write_text(content)
                with patch.object(ollama, "request") as request:
                    with self.assertRaises(ValueError):
                        ollama.prepare(properties)
                    request.assert_not_called()

    def workflow_preparation(self):
        workflow = WORKFLOW.read_text()
        name = "      - name: Prepare the Ollama model with bounded retries\n"
        start = workflow.index(name)
        end = workflow.index("      - name: Run the end-to-end suite", start)
        self.assertLess(workflow.index("      - name: Pre-pull the Docker Compose images"), start)
        step = workflow[start:end]
        self.assertIn("        timeout-minutes: 15\n", step)
        script = step.split("        run: |\n", 1)[1]
        return "\n".join(line[10:] for line in script.splitlines() if line.strip())

    def test_workflow_stops_without_removing_model_before_spring_boot_startup(self):
        result = subprocess.run(
            ["bash", "-e", "-c", self.workflow_preparation()],
            cwd=ROOT,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
            timeout=15,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls()
        self.assertEqual(
            calls[0],
            ["docker", [
                "compose", "-f", "bootui-spring-sample-app/compose.yaml",
                "up", "-d", "--wait", "--wait-timeout", "120", "ollama",
            ]],
        )
        self.assertEqual(
            calls[-1],
            ["docker", [
                "compose", "-f", "bootui-spring-sample-app/compose.yaml",
                "stop", "--timeout", "10", "ollama",
            ]],
        )
        self.assertEqual(sum(name == "docker" for name, _ in calls), 2)

    def test_workflow_startup_failure_prevents_model_preparation(self):
        result = subprocess.run(
            ["bash", "-e", "-c", self.workflow_preparation()],
            cwd=ROOT,
            env={**self.env, "SCENARIO": "docker-failure"},
            capture_output=True,
            text=True,
            check=False,
            timeout=15,
        )
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual(len(self.calls()), 1)


if __name__ == "__main__":
    unittest.main()
