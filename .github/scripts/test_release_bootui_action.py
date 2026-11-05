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

    def test_fallback_summary_and_result_do_not_run_after_cancellation(self):
        action = (ACTION / "action.yml").read_text()
        tail = action.split("    - id: report\n", 1)[1]
        self.assertIn(
            "if: ${{ !cancelled() && inputs.phase == 'assess' && steps.invoke.outputs.summary-fresh != 'true' }}",
            tail,
        )
        self.assertIn("    - if: ${{ !cancelled() && inputs.phase == 'assess' }}", tail)
        self.assertIn("    - if: ${{ !cancelled() && inputs.phase != 'prepare-cli' }}", tail)
        self.assertNotIn("always()", tail)

    def test_action_preserves_consumer_invocation_and_fallback_contract(self):
        action = (ACTION / "action.yml").read_text()
        prepare = action.split("    - id: prepare\n", 1)[1].split("    - id: invoke\n", 1)[0]
        invoke = action.split("    - id: invoke\n", 1)[1].split("    - id: report\n", 1)[0]
        report = action.split("    - id: report\n", 1)[1].split("    - if:", 1)[0]
        self.assertIn("if: ${{ inputs.phase != 'assess' || inputs.attach-exit-code == '0' }}", prepare)
        self.assertIn(
            "if: ${{ !cancelled() && inputs.phase != 'prepare-cli' && steps.prepare.outcome == 'success' }}",
            invoke,
        )
        self.assertIn("required: false", action.split("  assessment-id:\n", 1)[1].split("  policy:\n", 1)[0])
        self.assertIn('[[ "$phase" == offline ]]', (ACTION / "invoke.sh").read_text())
        self.assertIn("input-bundle:", action)
        self.assertIn("steps.invoke.outputs.exit-code }}", action)
        self.assertIn("inputs.phase != 'prepare-cli'", action)
        self.assertIn(
            "if: ${{ !cancelled() && inputs.phase == 'assess' && steps.invoke.outputs.summary-fresh != 'true' }}",
            report,
        )
        for step, script in ((prepare, "prepare-cli.sh"), (invoke, "invoke.sh"), (report, "report-error.sh")):
            self.assertIn(f'run: bash "$ACTION_PATH/{script}"', step)
        self.assertIn(
            'echo "BOOTUI_CLI_JAR=$cli_jar" >> "$GITHUB_ENV"',
            (ACTION / "prepare-cli.sh").read_text(),
        )
        shared = {
            "BOOTUI_OUTPUT_DIR": "inputs.output-dir",
            "BOOTUI_ASSESSMENT_ID": "inputs.assessment-id",
            "BOOTUI_ATTACH_EXIT_CODE": "inputs.attach-exit-code",
        }
        invoke_inputs = {
            **shared,
            "BOOTUI_PHASE": "inputs.phase",
            "BOOTUI_URL": "inputs.url",
            "BOOTUI_POLICY": "inputs.policy",
            "BOOTUI_WITNESS": "inputs.witness",
            "BOOTUI_INPUT_BUNDLE": "inputs.input-bundle",
            "BOOTUI_API_PATH": "inputs.api-path",
            "BOOTUI_TIMEOUT": "inputs.timeout",
        }
        report_inputs = {**shared, "BOOTUI_PREPARE_OUTCOME": "steps.prepare.outcome"}
        for step, mappings in ((invoke, invoke_inputs), (report, report_inputs)):
            for name, expression in mappings.items():
                with self.subTest(name=name, expression=expression):
                    self.assertIn(f"{name}: ${{{{ {expression} }}}}", step)

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

    def test_missing_report_after_successful_setup_is_an_error(self):
        self.environment.update(BOOTUI_ATTACH_EXIT_CODE="0", BOOTUI_PREPARE_OUTCOME="success")
        result = self.run_script("report-error.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        bundle = json.loads((self.root / "assessment.action-error" / "bundle.json").read_text())
        self.assertEqual(bundle["status"], "NOT_ASSESSED")
        self.assertEqual(bundle["errors"], [{"code": "REPORT_UNAVAILABLE"}])
        self.assertIn("exit-code=1", self.outputs.read_text())

    def test_assessment_rejects_a_stale_output_directory(self):
        self.prepare_fake_cli()
        output = self.root / "assessment"
        output.mkdir()
        stale = output / "summary.md"
        stale.write_text("stale summary")
        self.environment.update(FAKE_EXIT="0", FAKE_REPORTS="complete")
        result = self.run_script("invoke.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("output-dir must be a fresh path", result.stderr)
        self.assertIn("exit-code=1", self.outputs.read_text())
        self.assertNotIn("summary-fresh", self.outputs.read_text())
        self.assertEqual(stale.read_text(), "stale summary")
        self.assertEqual({path.name for path in output.iterdir()}, {"summary.md"})

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

    def test_offline_phase_revalidates_saved_bundle_and_writes_a_safe_summary(self):
        self.prepare_fake_cli()
        bundle = self.root / "input-bundle.json"
        bundle.write_text('{"status":"VIOLATION"}')
        summary = self.root / "step-summary.md"
        arguments = self.root / "arguments.txt"
        self.environment.update(
            BOOTUI_PHASE="offline",
            BOOTUI_INPUT_BUNDLE=str(bundle),
            BOOTUI_ASSESSMENT_ID="123:1:orders",
            FAKE_MODE="offline",
            FAKE_EXIT="3",
            GITHUB_STEP_SUMMARY=str(summary),
            FAKE_ARGS=str(arguments),
        )
        result = self.run_script("invoke.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(arguments.read_text().split(), [
            "ci", "check", "--input", str(bundle), "--assessment-id", "123:1:orders",
            "--policy", str(self.root / "policy.json"),
        ])
        self.assertIn("exit-code=3", self.outputs.read_text())
        self.assertEqual(
            summary.read_text(),
            "## BootUI offline assessment: VIOLATION\n\n"
            "The saved bundle contains findings at or above the configured threshold.\n",
        )
        self.assertFalse((self.root / "assessment").exists())

    def test_offline_phase_requires_an_existing_bundle(self):
        self.prepare_fake_cli()
        summary = self.root / "step-summary.md"
        self.environment.update(
            BOOTUI_PHASE="offline",
            BOOTUI_INPUT_BUNDLE=str(self.root / "missing.json"),
            GITHUB_STEP_SUMMARY=str(summary),
        )
        result = self.run_script("invoke.sh")
        self.assertEqual(result.returncode, 0)
        self.assertIn("input-bundle must name a readable saved bundle", result.stderr)
        self.assertIn("exit-code=1", self.outputs.read_text())
        self.assertEqual(
            summary.read_text(),
            "## BootUI offline assessment: NOT_ASSESSED\n\n"
            "The saved bundle was invalid or incomplete; no pass was inferred.\n",
        )

    def test_offline_phase_writes_pass_and_not_assessed_summaries(self):
        self.prepare_fake_cli()
        bundle = self.root / "input-bundle.json"
        bundle.write_text("{}")
        for status, expected in (
            (0, "## BootUI offline assessment: PASS\n\nThe saved bundle passed policy validation.\n"),
            (
                1,
                "## BootUI offline assessment: NOT_ASSESSED\n\n"
                "The saved bundle was invalid or incomplete; no pass was inferred.\n",
            ),
        ):
            with self.subTest(status=status):
                summary = self.root / f"summary-{status}.md"
                self.outputs.write_text("")
                self.environment.update(
                    BOOTUI_PHASE="offline",
                    BOOTUI_INPUT_BUNDLE=str(bundle),
                    FAKE_MODE="offline",
                    FAKE_EXIT=str(status),
                    GITHUB_STEP_SUMMARY=str(summary),
                )
                result = self.run_script("invoke.sh")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(summary.read_text(), expected)

    def prepare_fake_cli(self):
        binary_dir = self.root / "bin"
        binary_dir.mkdir()
        java = binary_dir / "java"
        java.write_text(
            "#!/usr/bin/env bash\n"
            "test \"$BOOTUI_NO_UPDATE_CHECK\" = 1 || exit 99\n"
            "if [[ \"$FAKE_MODE\" == offline ]]; then\n"
            "  shift 2\n"
            "  printf '%s\\n' \"$@\" > \"$FAKE_ARGS\"\n"
            "  exit \"$FAKE_EXIT\"\n"
            "fi\n"
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
            FAKE_MODE="live",
            FAKE_ARGS=str(self.root / "arguments.txt"),
        )


if __name__ == "__main__":
    unittest.main()
