import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / ".github/actions/bootui"


class QuickActionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.outputs = self.root / "output"
        self.summary = self.root / "summary"
        self.env = os.environ.copy()
        self.env.update(
            GITHUB_OUTPUT=str(self.outputs),
            GITHUB_STEP_SUMMARY=str(self.summary),
            GITHUB_RUN_ID="12345",
            GITHUB_RUN_ATTEMPT="2",
            RUNNER_TEMP=str(self.root),
            BOOTUI_TEST_COMMAND="printf 'workload ran\\n'",
            BOOTUI_UPLOAD_ARTIFACT="true",
            BOOTUI_ASSESSMENT_ID="",
        )

    def run_script(self, name):
        return subprocess.run(
            ["bash", str(ACTION / name)], env=self.env, capture_output=True, text=True, check=False
        )

    def output(self):
        if not self.outputs.exists():
            return {}
        return dict(line.split("=", 1) for line in self.outputs.read_text().splitlines())

    def test_default_policy_is_strict_and_does_not_require_a_user_file(self):
        policy = json.loads((ACTION / "default-policy.json").read_text())
        self.assertEqual(policy, {
            "schemaVersion": 1,
            "advisors": {"architecture": {"required": True, "failOn": "high"}},
        })
        self.assertEqual(policy, json.loads((ROOT / "examples/ci/bootui-policy.json").read_text()))
        action = (ACTION / "action.yml").read_text()
        self.assertIn("    default: run\n", action)
        self.assertEqual(action.count("inputs.policy || format('{0}/default-policy.json', github.action_path)"), 2)
        self.assertEqual(action.count("inputs.url || 'http://127.0.0.1:8080'"), 2)
        self.assertEqual(action.count("BOOTUI_CLI_JAR: ${{ steps.prepare.outputs.cli-jar }}"), 2)
        self.assertIn('echo "cli-jar=$cli_jar" >> "$GITHUB_OUTPUT"', (ACTION / "prepare-cli.sh").read_text())

    def test_workload_runs_even_when_setup_or_attachment_fails(self):
        action = (ACTION / "action.yml").read_text()
        workload = action.split("    - id: quick_workload\n", 1)[1].split("    - id: quick_assess\n", 1)[0]
        self.assertIn("if: ${{ !cancelled() && inputs.phase == 'run' }}", workload)
        self.assertIn("continue-on-error: true", workload)
        self.assertNotIn("steps.prepare", workload)
        self.assertNotIn("steps.quick_attach", workload)
        assess = action.split("    - id: quick_assess\n", 1)[1].split("    - id: quick_report\n", 1)[0]
        self.assertIn("steps.quick_attach.outputs.exit-code == '0'", assess)
        self.assertNotIn("steps.quick_workload.outcome", assess)

    def test_only_validated_reports_are_uploaded_and_cancellation_stays_respected(self):
        action = (ACTION / "action.yml").read_text()
        upload = action.split("    - id: quick_upload\n", 1)[1].split("    - id: quick_gate\n", 1)[0]
        self.assertIn("!cancelled()", upload)
        self.assertIn("steps.quick_validate.outcome == 'success'", upload)
        for name in ("bundle.json", "summary.md", "junit.xml"):
            self.assertIn(f"steps.quick_validate.outputs.report-dir }}}}/{name}", upload)
        self.assertNotIn("witness", upload)
        self.assertNotIn("always()", action)

    def test_quick_phase_order_and_fail_closed_outcome_wiring(self):
        action = (ACTION / "action.yml").read_text()
        names = (
            "quick_setup", "prepare", "quick_attach", "quick_workload", "quick_assess",
            "quick_report", "quick_validate", "quick_upload", "quick_gate",
        )
        positions = [action.index(f"    - id: {name}\n") for name in names]
        self.assertEqual(positions, sorted(positions))
        self.assertNotIn(".conclusion", action)
        for index, name in enumerate(names[2:], start=2):
            end = positions[index + 1] if index + 1 < len(positions) else action.index("    - id: invoke\n")
            with self.subTest(step=name):
                self.assertIn("!cancelled()", action[positions[index]:end])
        report = action[positions[5]:positions[6]]
        self.assertIn(
            "steps.prepare.outcome == 'success' && (steps.quick_attach.outputs.exit-code || '1') || '0'",
            report,
        )
        validate = action[positions[6]:positions[7]]
        self.assertIn("steps.quick_report.outputs.report-dir || steps.quick_assess.outputs.report-dir", validate)
        gate = action[positions[8]:action.index("    - id: invoke\n")]
        for name, expression in (
            ("BOOTUI_SETUP_OUTCOME", "steps.quick_setup.outcome"),
            ("BOOTUI_PREPARE_OUTCOME", "steps.prepare.outcome"),
            ("BOOTUI_ATTACH_EXIT_CODE", "steps.quick_attach.outputs.exit-code"),
            ("BOOTUI_WORKLOAD_OUTCOME", "steps.quick_workload.outcome"),
            ("BOOTUI_ASSESS_EXIT_CODE", "steps.quick_report.outputs.exit-code || steps.quick_assess.outputs.exit-code"),
            ("BOOTUI_REPORT_OUTCOME", "steps.quick_validate.outcome"),
            ("BOOTUI_UPLOAD_OUTCOME", "steps.quick_upload.outcome"),
        ):
            with self.subTest(name=name):
                self.assertIn(f"{name}: ${{{{ {expression} }}}}", gate)

    def test_setup_creates_fresh_private_state_and_valid_identifiers(self):
        first = self.run_script("quick-setup.sh")
        self.assertEqual(first.returncode, 0, first.stderr)
        values = self.output()
        self.assertRegex(values["assessment-id"], r"^gha-12345-2-[A-Za-z0-9]{8}$")
        self.assertRegex(values["artifact-name"], r"^bootui-[A-Za-z0-9]{8}$")
        state = Path(values["witness"]).parent
        self.assertTrue(state.is_dir())
        self.assertEqual(state.stat().st_mode & 0o777, 0o700)
        self.assertEqual(values["report-dir"], str(state / "assessment"))
        self.assertNotIn(self.env["BOOTUI_TEST_COMMAND"], self.outputs.read_text())
        self.outputs.unlink()
        second = self.run_script("quick-setup.sh")
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertNotEqual(values["assessment-id"], self.output()["assessment-id"])
        self.assertNotEqual(values["artifact-name"], self.output()["artifact-name"])

    def test_setup_preserves_a_valid_explicit_assessment_id(self):
        self.env["BOOTUI_ASSESSMENT_ID"] = "run:orders-1"
        result = self.run_script("quick-setup.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output()["assessment-id"], "run:orders-1")

    def test_invalid_setup_is_explicit_and_never_advertises_state(self):
        for key, value, message in (
            ("BOOTUI_TEST_COMMAND", " \n ", "test-command"),
            ("BOOTUI_UPLOAD_ARTIFACT", "yes", "upload-artifact"),
            ("BOOTUI_ASSESSMENT_ID", "bad\nid", "assessment-id"),
            ("RUNNER_TEMP", "relative", "RUNNER_TEMP"),
        ):
            with self.subTest(key=key):
                original = self.env.get(key)
                self.env[key] = value
                result = self.run_script("quick-setup.sh")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertFalse(self.outputs.exists())
                if original is None:
                    del self.env[key]
                else:
                    self.env[key] = original

    def test_workload_preserves_failure_and_bash_pipeline_status(self):
        self.env["BOOTUI_TEST_COMMAND"] = "printf 'before\\n'; false | cat; echo unreachable"
        result = self.run_script("quick-workload.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("before", result.stdout)
        self.assertNotIn("unreachable", result.stdout)
        self.env["BOOTUI_TEST_COMMAND"] = "printf 'passed\\n'"
        result = self.run_script("quick-workload.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "passed\n")

    def report(self):
        report = self.root / "report"
        report.mkdir()
        (report / "bundle.json").write_text('{"status":"PASS"}')
        (report / "summary.md").write_text("# BootUI assessment: PASS\n")
        (report / "junit.xml").write_text("<testsuite/>")
        self.env["BOOTUI_REPORT_DIR"] = str(report)
        return report

    def test_complete_report_is_bounded_and_added_to_the_job_summary(self):
        report = self.report()
        result = self.run_script("quick-report.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.summary.read_text(), "# BootUI assessment: PASS\n")
        self.assertEqual(self.output()["report-dir"], str(report))

    def test_report_enforces_each_file_type_and_size_bound(self):
        report = self.report()
        outside = self.root / "outside"
        outside.write_text("not a report")
        for name, limit in (("bundle.json", 16 * 1024 * 1024), ("summary.md", 256 * 1024),
                            ("junit.xml", 256 * 1024)):
            path = report / name
            original = path.read_bytes()
            for problem in ("missing", "symlink", "empty", "at-limit", "oversized"):
                with self.subTest(name=name, problem=problem):
                    path.unlink(missing_ok=True)
                    if problem == "symlink":
                        path.symlink_to(outside)
                    elif problem in ("empty", "at-limit", "oversized"):
                        with path.open("wb") as file:
                            file.truncate({"empty": 0, "at-limit": limit, "oversized": limit + 1}[problem])
                    self.summary.unlink(missing_ok=True)
                    self.outputs.unlink(missing_ok=True)
                    result = self.run_script("quick-report.sh")
                    if problem == "at-limit":
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self.assertEqual(self.output()["report-dir"], str(report))
                    else:
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn("assessment unavailable", self.summary.read_text())
                        self.assertFalse(self.outputs.exists())
            path.unlink()
            path.write_bytes(original)

    def test_report_rejects_symlinked_directories_even_with_trailing_slashes(self):
        report = self.report()
        alias = self.root / "report-alias"
        alias.symlink_to(report, target_is_directory=True)
        for suffix in ("", "/", "///"):
            with self.subTest(suffix=suffix):
                self.summary.unlink(missing_ok=True)
                self.outputs.unlink(missing_ok=True)
                self.env["BOOTUI_REPORT_DIR"] = f"{alias}{suffix}"
                result = self.run_script("quick-report.sh")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("assessment unavailable", self.summary.read_text())
                self.assertFalse(self.outputs.exists())

    def successful_gate(self):
        self.env.update(
            BOOTUI_SETUP_OUTCOME="success",
            BOOTUI_PREPARE_OUTCOME="success",
            BOOTUI_ATTACH_EXIT_CODE="0",
            BOOTUI_WORKLOAD_OUTCOME="success",
            BOOTUI_ASSESS_EXIT_CODE="0",
            BOOTUI_REPORT_OUTCOME="success",
            BOOTUI_UPLOAD_OUTCOME="success",
        )

    def test_gate_requires_workload_assessment_report_and_upload_success(self):
        self.successful_gate()
        self.assertEqual(self.run_script("quick-gate.sh").returncode, 0)
        for key, value in (
            ("BOOTUI_SETUP_OUTCOME", "failure"),
            ("BOOTUI_PREPARE_OUTCOME", "failure"),
            ("BOOTUI_ATTACH_EXIT_CODE", "1"),
            ("BOOTUI_WORKLOAD_OUTCOME", "failure"),
            ("BOOTUI_ASSESS_EXIT_CODE", "1"),
            ("BOOTUI_REPORT_OUTCOME", "failure"),
            ("BOOTUI_UPLOAD_OUTCOME", "failure"),
        ):
            with self.subTest(key=key):
                original = self.env[key]
                self.env[key] = value
                self.assertNotEqual(self.run_script("quick-gate.sh").returncode, 0)
                self.env[key] = original
        self.env.update(BOOTUI_UPLOAD_ARTIFACT="false", BOOTUI_UPLOAD_OUTCOME="skipped")
        self.assertEqual(self.run_script("quick-gate.sh").returncode, 0)

    def test_gate_preserves_violation_and_rejects_unknown_assessment_codes(self):
        for value, expected in (("3", "3"), ("42", "1"), ("", "1"), (None, "1")):
            with self.subTest(value=value):
                self.outputs.unlink(missing_ok=True)
                self.successful_gate()
                if value is None:
                    del self.env["BOOTUI_ASSESS_EXIT_CODE"]
                else:
                    self.env["BOOTUI_ASSESS_EXIT_CODE"] = value
                self.assertEqual(self.run_script("quick-gate.sh").returncode, 1)
                self.assertEqual(self.output()["exit-code"], expected)

    def test_gate_does_not_report_a_pass_when_assessment_report_is_untrustworthy(self):
        self.env.update(
            BOOTUI_SETUP_OUTCOME="success", BOOTUI_PREPARE_OUTCOME="success",
            BOOTUI_ATTACH_EXIT_CODE="0", BOOTUI_WORKLOAD_OUTCOME="success",
            BOOTUI_ASSESS_EXIT_CODE="0", BOOTUI_REPORT_OUTCOME="failure",
            BOOTUI_UPLOAD_OUTCOME="skipped",
        )
        self.assertNotEqual(self.run_script("quick-gate.sh").returncode, 0)
        self.assertEqual(self.output()["exit-code"], "1")

    def test_gate_identifies_the_actual_setup_or_attachment_failure(self):
        for key, value, message in (
            ("BOOTUI_SETUP_OUTCOME", "failure", "input or runner setup failed"),
            ("BOOTUI_PREPARE_OUTCOME", "failure", "CLI setup failed"),
            ("BOOTUI_ATTACH_EXIT_CODE", "1", "could not attach"),
        ):
            with self.subTest(key=key):
                self.outputs.unlink(missing_ok=True)
                self.successful_gate()
                self.env[key] = value
                result = self.run_script("quick-gate.sh")
                self.assertEqual(result.returncode, 1)
                self.assertIn(message, result.stderr)
                self.assertEqual(self.output()["exit-code"], "1")

    def test_upload_failure_does_not_relabel_a_trustworthy_assessment(self):
        self.env.update(
            BOOTUI_SETUP_OUTCOME="success", BOOTUI_PREPARE_OUTCOME="success",
            BOOTUI_ATTACH_EXIT_CODE="0", BOOTUI_WORKLOAD_OUTCOME="success",
            BOOTUI_ASSESS_EXIT_CODE="0", BOOTUI_REPORT_OUTCOME="success",
            BOOTUI_UPLOAD_OUTCOME="failure",
        )
        self.assertNotEqual(self.run_script("quick-gate.sh").returncode, 0)
        self.assertEqual(self.output()["exit-code"], "0")

    def test_workload_failure_does_not_relabel_a_trustworthy_assessment(self):
        self.successful_gate()
        self.env["BOOTUI_WORKLOAD_OUTCOME"] = "failure"
        result = self.run_script("quick-gate.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("test command failed", result.stderr)
        self.assertEqual(self.output()["exit-code"], "0")


if __name__ == "__main__":
    unittest.main()
