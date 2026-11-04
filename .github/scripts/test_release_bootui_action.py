import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / ".github" / "actions" / "bootui"


class BootUiActionTests(unittest.TestCase):
    def test_invoke_does_not_run_after_cancellation(self):
        action = (ACTION / "action.yml").read_text()
        invoke = action.split("    - id: invoke\n", 1)[1].split("    - id: report\n", 1)[0]
        self.assertIn("!cancelled()", invoke)
        self.assertNotIn("always()", invoke)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.outputs = self.root / "outputs"
        self.environment = os.environ.copy()
        self.environment.update(
            GITHUB_OUTPUT=str(self.outputs),
            BOOTUI_OUTPUT_DIR=str(self.root / "assessment"),
            BOOTUI_ASSESSMENT_ID="123:1:orders",
        )

    def run_script(self, name):
        return subprocess.run(
            ["bash", str(ACTION / name)],
            env=self.environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_attach_failure_writes_a_sanitized_fresh_report_without_cli(self):
        self.environment["BOOTUI_ATTACH_EXIT_CODE"] = "1"
        result = self.run_script("report-error.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        report = self.root / "assessment.action-error"
        bundle = json.loads((report / "bundle.json").read_text())
        self.assertEqual(bundle["status"], "NOT_ASSESSED")
        self.assertEqual(bundle["errors"], [{"code": "ATTACH_FAILED"}])
        self.assertIn("<error", (report / "junit.xml").read_text())
        self.assertIn(f"report-dir={report}", self.outputs.read_text())
        second = self.run_script("report-error.sh")
        self.assertNotEqual(second.returncode, 0)
        self.assertEqual(json.loads((report / "bundle.json").read_text()), bundle)

    def test_setup_failure_produces_a_different_error(self):
        self.environment.update(BOOTUI_ATTACH_EXIT_CODE="0", BOOTUI_PREPARE_OUTCOME="failure")
        result = self.run_script("report-error.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        bundle = json.loads((self.root / "assessment.action-error" / "bundle.json").read_text())
        self.assertEqual(bundle["errors"], [{"code": "CLI_SETUP_FAILED"}])

    def test_incomplete_cli_output_is_not_advertised_as_a_fresh_summary(self):
        self.prepare_fake_cli()
        self.environment.update(FAKE_EXIT="1", FAKE_REPORTS="partial")
        result = self.run_script("invoke.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("exit-code=1", self.outputs.read_text())
        self.assertNotIn("summary-fresh=true", self.outputs.read_text())

    def test_failed_attach_cannot_reuse_a_stale_witness(self):
        self.prepare_fake_cli()
        Path(self.environment["BOOTUI_WITNESS"]).write_text('{"stale":true}')
        self.environment.update(BOOTUI_ATTACH_EXIT_CODE="1", FAKE_EXIT="0", FAKE_REPORTS="complete")
        result = self.run_script("invoke.sh")
        self.assertEqual(result.returncode, 0)
        self.assertIn("successful attach is required", result.stderr)
        self.assertIn("exit-code=1", self.outputs.read_text())
        self.assertFalse((self.root / "assessment").exists())

    def test_complete_cli_output_is_advertised_and_update_check_is_disabled(self):
        self.prepare_fake_cli()
        self.environment.update(FAKE_EXIT="3", FAKE_REPORTS="complete")
        result = self.run_script("invoke.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("exit-code=3", self.outputs.read_text())
        self.assertIn("summary-fresh=true", self.outputs.read_text())
        self.assertIn(f"report-dir={self.root / 'assessment'}", self.outputs.read_text())

    def prepare_fake_cli(self):
        binary_dir = self.root / "bin"
        binary_dir.mkdir()
        java = binary_dir / "java"
        java.write_text(
            "#!/usr/bin/env bash\n"
            "test \"$BOOTUI_NO_UPDATE_CHECK\" = 1 || exit 99\n"
            "mkdir \"$BOOTUI_OUTPUT_DIR\"\n"
            "echo '# BootUI assessment' > \"$BOOTUI_OUTPUT_DIR/summary.md\"\n"
            "if [[ \"$FAKE_REPORTS\" == complete ]]; then\n"
            "  echo '{}' > \"$BOOTUI_OUTPUT_DIR/bundle.json\"\n"
            "  echo '<testsuite/>' > \"$BOOTUI_OUTPUT_DIR/junit.xml\"\n"
            "fi\n"
            "exit \"$FAKE_EXIT\"\n"
        )
        java.chmod(0o755)
        jar = self.root / "cli.jar"
        jar.touch()
        policy = self.root / "policy.json"
        policy.write_text("{}")
        self.environment.update(
            PATH=f"{binary_dir}:{self.environment['PATH']}",
            BOOTUI_CLI_JAR=str(jar),
            BOOTUI_PHASE="assess",
            BOOTUI_URL="http://127.0.0.1:8080",
            BOOTUI_POLICY=str(policy),
            BOOTUI_WITNESS=str(self.root / "witness"),
            BOOTUI_ATTACH_EXIT_CODE="0",
        )


if __name__ == "__main__":
    unittest.main()
