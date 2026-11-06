"""Fast fail-closed checks for the public runnable CI example helpers."""

import importlib.util
from http.client import IncompleteRead
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import textwrap
import unittest
from unittest.mock import Mock, patch
import uuid


ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "examples/ci"
sys.path.insert(0, str(EXAMPLES))


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, EXAMPLES / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


support = load("example_support_tests", "ci_support.py")
external = load("example_external_tests", "run-external.py")
framework = load("example_framework_tests", "run-framework.py")
gate = load("example_gate_tests", "check-result.py")


class CiExampleTests(unittest.TestCase):
    def setUp(self):
        self.root = ROOT / ".m2" / ("ci-example-unit-" + uuid.uuid4().hex)
        self.root.mkdir(parents=True)
        self.addCleanup(shutil.rmtree, self.root)
        previous = signal.getsignal(signal.SIGTERM)
        self.addCleanup(signal.signal, signal.SIGTERM, previous)
        self.jar = self.root / "cli.jar"
        self.jar.write_bytes(b"fixture")
        self.environment = {
            "BOOTUI_VERSION": "2.4.0",
            "BOOTUI_CLI_JAR": str(self.jar),
            "BOOTUI_CI_ROOT": str(self.root / "state"),
        }

    def recipe(self, **overrides):
        with patch.dict(os.environ, self.environment | overrides, clear=True):
            with patch.object(
                support, "run", side_effect=[(0, "bootui 2.4.0\n"), (0, "Usage: bootui ci [-hV] [COMMAND]\n")]
            ):
                return support.Recipe("spring")

    def test_fresh_state_and_dismissals_are_private_and_not_reused(self):
        recipe = self.recipe()
        self.assertEqual(recipe.root.stat().st_mode & 0o777, 0o700)
        self.assertEqual((recipe.root / "boot-ui.yml").read_bytes(), b"")
        self.assertEqual(recipe.result["workloadExit"], 1)
        with self.assertRaises(FileExistsError):
            self.recipe()
        recipe.cleanup()
        self.assertFalse((recipe.root / "boot-ui.yml").exists())
        self.assertTrue((recipe.root / "result.json").exists())

    def test_cli_version_mismatch_is_rejected_before_state_creation(self):
        with patch.dict(os.environ, self.environment, clear=True):
            with patch.object(support, "run", return_value=(0, "bootui 2.3.0\n")):
                with self.assertRaisesRegex(ValueError, "must match"):
                    support.Recipe("spring")
        self.assertFalse((self.root / "state").exists())

    def test_old_cli_without_ci_commands_is_not_a_fallback(self):
        for help_result in ((2, "unknown command"), (0, "Usage: bootui [-hV] [COMMAND]\n")):
            with self.subTest(help_result=help_result), patch.dict(os.environ, self.environment, clear=True):
                with patch.object(support, "run", side_effect=[(0, "bootui 2.4.0\n"), help_result]):
                    with self.assertRaisesRegex(ValueError, "no CI commands"):
                        support.Recipe("spring")
        self.assertFalse((self.root / "state").exists())

    def test_watched_and_relative_state_paths_are_rejected(self):
        for value in ("relative-state", str(EXAMPLES / "quarkus/target/state")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.recipe(BOOTUI_CI_ROOT=value)

    def test_symlink_state_path_is_rejected(self):
        (self.root / "link").symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            self.recipe(BOOTUI_CI_ROOT=str(self.root / "link/state"))

    def test_maven_always_uses_wrapper_matching_version_and_absolute_repository(self):
        recipe = self.recipe()
        with patch.dict(os.environ, {"BOOTUI_MAVEN_REPO_LOCAL": str(self.root / "repository")}, clear=True):
            command = recipe.maven("webflux", "test")
        self.assertTrue(command[0].endswith("/spring/mvnw"))
        self.assertIn("-Pbootui-ci,webflux", command)
        self.assertIn("-Dbootui.version=2.4.0", command)
        self.assertIn("-Dmaven.repo.local=" + str(self.root / "repository"), command)
        with patch.dict(os.environ, {"BOOTUI_MAVEN_REPO_LOCAL": "relative"}, clear=True):
            with self.assertRaisesRegex(ValueError, "absolute"):
                recipe.maven("mvc", "test")

    def test_extracted_kit_bootstrap_uses_root_wrapper_without_system_maven(self):
        kit = self.root / "kit"
        project = kit / "spring"
        project.mkdir(parents=True)
        (project / "pom.xml").write_text("<project/>", encoding="utf-8")
        (kit / ".mvn/wrapper").mkdir(parents=True)
        (kit / "mvnw").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        (kit / ".mvn/wrapper/maven-wrapper.properties").write_text("wrapper-fixture\n", encoding="utf-8")
        shutil.copy2(EXAMPLES / "bootstrap.sh", kit / "bootstrap.sh")
        result = subprocess.run(
            ["bash", str(kit / "bootstrap.sh"), "spring"], cwd=kit, capture_output=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((project / "mvnw").read_bytes(), (kit / "mvnw").read_bytes())
        self.assertTrue(os.access(project / "mvnw", os.X_OK))
        self.assertEqual(
            (project / ".mvn/wrapper/maven-wrapper.properties").read_bytes(),
            (kit / ".mvn/wrapper/maven-wrapper.properties").read_bytes(),
        )

    def test_extracted_kit_keeps_rendered_poms_and_wrappers_trackable(self):
        kit = self.root / "kit"
        kit.mkdir()
        shutil.copy2(EXAMPLES / ".gitignore", kit / ".gitignore")
        for name in ("spring/pom.xml", "mvnw", ".mvn/wrapper/maven-wrapper.properties", "spring/target/file"):
            path = kit / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        subprocess.run(["git", "init", "--quiet"], cwd=kit, check=True, timeout=10)
        for name in ("spring/pom.xml", "mvnw", ".mvn/wrapper/maven-wrapper.properties"):
            result = subprocess.run(["git", "check-ignore", "--quiet", name], cwd=kit, timeout=10)
            self.assertEqual(result.returncode, 1, name + " must be committed with the starter")
        result = subprocess.run(["git", "check-ignore", "--quiet", "spring/target/file"], cwd=kit, timeout=10)
        self.assertEqual(result.returncode, 0)

    def test_source_bootstrap_files_are_ignored_without_hiding_kit_files(self):
        for project in ("spring", "quarkus"):
            for name in ("pom.xml", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"):
                path = f"examples/ci/{project}/{name}"
                with self.subTest(path=path):
                    result = subprocess.run(
                        ["git", "check-ignore", "--no-index", "--quiet", path], cwd=ROOT, timeout=10
                    )
                    self.assertEqual(result.returncode, 0, path + " is generated only in the source checkout")

    def test_example_action_shas_match_the_reviewed_baseline_majors(self):
        baseline = (ROOT / ".github/workflows/build.yml").read_text()
        workflows = (
            (EXAMPLES / "workflows/external.yml.template").read_text(),
            (EXAMPLES / "workflows/framework.yml.template").read_text(),
        )
        for action in ("checkout", "setup-java", "upload-artifact"):
            majors = set(re.findall(rf"uses:\s+actions/{action}@v(\d+)\b", baseline))
            self.assertEqual(len(majors), 1, f"Expected one baseline major for actions/{action}")
            for workflow in workflows:
                pins = re.findall(
                    rf"^\s*(?:-\s+)?uses:\s+actions/{action}@([0-9a-f]{{40}})\s+#\s+v(\d+)\.\d+\.\d+\s*$",
                    workflow, re.MULTILINE,
                )
                self.assertEqual(len(pins), 1, f"Expected one reviewed full SHA for actions/{action}")
                self.assertEqual(pins[0][1], next(iter(majors)), f"Update the actions/{action} example pin")

    def test_framework_reports_have_stack_appropriate_labels(self):
        workflow = (EXAMPLES / "workflows/framework.yml.template").read_text()
        self.assertEqual(
            set(re.findall(r"^\s+label:\s+(\S+)$", workflow, re.MULTILINE)),
            {"spring-mvc", "spring-webflux", "quarkus"},
        )
        self.assertIn("name: bootui-framework-${{ matrix.label }}", workflow)

    def test_staging_uploads_only_the_three_bounded_reports(self):
        recipe = self.recipe()
        reports = recipe.root / "reports"
        reports.mkdir()
        for name in support.REPORT_LIMITS:
            (reports / name).write_text("sanitized", encoding="utf-8")
        (reports / "raw-token.log").write_text("never upload", encoding="utf-8")
        recipe.stage(reports, "assessment")
        self.assertEqual(
            {path.name for path in (recipe.artifacts / "assessment").iterdir()},
            set(support.REPORT_LIMITS),
        )

    def test_staging_rejects_symlink_and_oversized_reports(self):
        recipe = self.recipe()
        reports = recipe.root / "reports"
        reports.mkdir()
        (reports / "bundle.json").symlink_to(self.jar)
        with self.assertRaisesRegex(ValueError, "regular file"):
            recipe.stage(reports, "symlink")
        self.assertFalse((recipe.artifacts / "symlink").exists())
        (reports / "bundle.json").unlink()
        (reports / "bundle.json").write_bytes(b"1234")
        with patch.dict(support.REPORT_LIMITS, {"bundle.json": 3}, clear=True):
            with self.assertRaisesRegex(ValueError, "upload bound"):
                recipe.stage(reports, "oversize")

    def test_timeout_terminates_only_the_owned_process_group(self):
        process = Mock(pid=45678)
        process.communicate.side_effect = subprocess.TimeoutExpired("owned", 1)
        process.poll.side_effect = [None, 0]
        with patch.object(support.subprocess, "Popen", return_value=process), patch.object(support.os, "killpg") as kill:
            code, output = support.run(["owned"], cwd=self.root, env={}, timeout=1)
        self.assertEqual(code, 1)
        self.assertEqual(output, "COMMAND_TIMEOUT\n")
        self.assertTrue(all(call.args[0] == 45678 for call in kill.call_args_list))
        process.wait.assert_called_with(timeout=15)

    def test_final_gate_rejects_missing_incomplete_and_boolean_statuses(self):
        recipe = self.recipe()
        with patch.dict(os.environ, {"BOOTUI_CI_ROOT": str(recipe.root)}, clear=True):
            self.assertEqual(gate.main(), 1)
            for result in (
                {"schemaVersion": 1, "workloadExit": False, "assessmentExit": 0},
                {"schemaVersion": 1, "workloadExit": 0},
            ):
                (recipe.root / "result.json").write_text(json.dumps(result), encoding="utf-8")
                with self.assertRaises(ValueError):
                    gate.main()
            recipe.result = {"schemaVersion": 1, "workloadExit": 0, "assessmentExit": 0}
            recipe.persist()
            self.assertEqual(gate.main(), 0)
            recipe.result["workloadExit"] = 1
            recipe.persist()
            self.assertEqual(gate.main(), 1)

    def test_attach_refusal_still_runs_workload_and_never_assesses(self):
        recipe = self.recipe()
        recipe.project = self.root / "project"
        recipe.project.mkdir()
        recipe.bootstrap = Mock()
        recipe.cli_run = Mock(return_value=(1, "ATTACH_FAILED\n"))
        process = Mock()
        with (
            patch.object(external, "Recipe", return_value=recipe),
            patch.object(external, "run", return_value=(0, "")),
            patch.object(external.subprocess, "Popen", return_value=process),
            patch.object(external, "wait_ready", return_value="http://127.0.0.1:18080"),
            patch.object(external, "workload") as workload,
            patch.object(external, "terminate") as cleanup,
            patch.object(sys, "argv", ["run-external.py"]),
        ):
            self.assertEqual(external.main(), 1)
        workload.assert_called_once_with("http://127.0.0.1:18080")
        self.assertEqual(recipe.cli_run.call_count, 1)
        self.assertEqual(recipe.cli_run.call_args.args[0][0], "attach")
        self.assertEqual(recipe.result["workloadExit"], 0)
        self.assertEqual(recipe.result["assessmentExit"], 1)
        cleanup.assert_called_once_with(process)
        bundle = json.loads((recipe.artifacts / "not-assessed/bundle.json").read_text(encoding="utf-8"))
        self.assertEqual(bundle["status"], "NOT_ASSESSED")

    def test_http_workload_failure_does_not_skip_assessment_or_turn_green(self):
        recipe = self.recipe()
        recipe.project = self.root / "project"
        recipe.project.mkdir()
        recipe.bootstrap = Mock()
        recipe.stage = Mock()
        recipe.cli_run = Mock(side_effect=[(0, "attached\n"), (0, "assessed\n")])
        with (
            patch.object(external, "Recipe", return_value=recipe),
            patch.object(external, "run", return_value=(0, "")),
            patch.object(external.subprocess, "Popen"),
            patch.object(external, "wait_ready", return_value="http://127.0.0.1:18080"),
            patch.object(external, "workload", side_effect=IncompleteRead(b"partial")),
            patch.object(external, "terminate"),
            patch.object(sys, "argv", ["run-external.py"]),
        ):
            self.assertEqual(external.main(), 1)
        self.assertEqual(recipe.cli_run.call_count, 2)
        self.assertEqual(recipe.cli_run.call_args.args[0][0], "assess")
        self.assertEqual(recipe.result["workloadExit"], 1)
        self.assertEqual(recipe.result["assessmentExit"], 0)

    def test_readiness_failure_creates_a_not_assessed_report_and_stays_failed(self):
        recipe = self.recipe()
        recipe.project = self.root / "project"
        recipe.project.mkdir()
        recipe.bootstrap = Mock()
        with (
            patch.object(external, "Recipe", return_value=recipe),
            patch.object(external, "run", return_value=(0, "")),
            patch.object(external.subprocess, "Popen"),
            patch.object(external, "wait_ready", side_effect=ValueError("readiness failed")),
            patch.object(external, "terminate"),
            patch.object(sys, "argv", ["run-external.py"]),
            self.assertRaisesRegex(ValueError, "readiness failed"),
        ):
            external.main()
        bundle = json.loads((recipe.artifacts / "not-assessed/bundle.json").read_text())
        self.assertEqual(bundle["status"], "NOT_ASSESSED")
        self.assertEqual(bundle["errors"][0]["code"], "SETUP_FAILED")
        self.assertEqual(recipe.result["assessmentExit"], 1)

    def test_application_build_failure_creates_not_assessed_without_starting_a_jvm(self):
        recipe = self.recipe()
        recipe.project = self.root / "project"
        recipe.project.mkdir()
        recipe.bootstrap = Mock()
        with (
            patch.object(external, "Recipe", return_value=recipe),
            patch.object(external, "run", return_value=(1, "")),
            patch.object(external.subprocess, "Popen") as process,
            patch.object(sys, "argv", ["run-external.py"]),
            self.assertRaisesRegex(ValueError, "Application build failed"),
        ):
            external.main()
        process.assert_not_called()
        bundle = json.loads((recipe.artifacts / "not-assessed/bundle.json").read_text())
        self.assertEqual(bundle["status"], "NOT_ASSESSED")
        self.assertEqual(bundle["errors"][0]["code"], "SETUP_FAILED")

    def test_assessment_without_reports_creates_not_assessed_after_the_workload(self):
        recipe = self.recipe()
        recipe.project = self.root / "project"
        recipe.project.mkdir()
        recipe.bootstrap = Mock()
        recipe.cli_run = Mock(side_effect=[(0, "attached\n"), (1, "assessment failed\n")])
        with (
            patch.object(external, "Recipe", return_value=recipe),
            patch.object(external, "run", return_value=(0, "")),
            patch.object(external.subprocess, "Popen"),
            patch.object(external, "wait_ready", return_value="http://127.0.0.1:18080"),
            patch.object(external, "workload") as workload,
            patch.object(external, "terminate"),
            patch.object(sys, "argv", ["run-external.py"]),
            self.assertRaisesRegex(ValueError, "Report directories"),
        ):
            external.main()
        workload.assert_called_once()
        bundle = json.loads((recipe.artifacts / "not-assessed/bundle.json").read_text())
        self.assertEqual(bundle["status"], "NOT_ASSESSED")
        self.assertEqual(bundle["errors"][0]["code"], "ASSESSMENT_INCOMPLETE")
        self.assertEqual(recipe.result["workloadExit"], 0)
        self.assertEqual(recipe.result["assessmentExit"], 1)

    def test_incomplete_report_set_is_not_staged_as_a_complete_assessment(self):
        recipe = self.recipe()
        reports = recipe.root / "reports"
        reports.mkdir()
        (reports / "bundle.json").write_text("{}")
        (reports / "summary.md").write_text("partial")
        with self.assertRaises(FileNotFoundError):
            recipe.stage(reports, "assessment")
        self.assertFalse((recipe.artifacts / "assessment").exists())

    def test_framework_plan_failure_creates_not_assessed_and_stays_failed(self):
        recipe = self.recipe()
        recipe.bootstrap = Mock()
        recipe.cli_run = Mock(return_value=(1, "cannot prepare\n"))
        with (
            patch.object(framework, "Recipe", return_value=recipe),
            patch.object(sys, "argv", ["run-framework.py", "spring"]),
            self.assertRaisesRegex(ValueError, "Cannot prepare"),
        ):
            framework.main()
        bundle = json.loads((recipe.artifacts / "not-assessed/bundle.json").read_text())
        self.assertEqual(bundle["status"], "NOT_ASSESSED")
        self.assertEqual(bundle["errors"][0]["code"], "COLLECTION_INCOMPLETE")
        self.assertEqual(recipe.result["assessmentExit"], 1)

    def summary_script(self):
        workflow = (EXAMPLES / "workflows/external.yml.template").read_text()
        section = workflow.split("      - name: Add BootUI report to job summary\n", 1)[1]
        section = section.split("      - name: Upload only bounded sanitized BootUI reports\n", 1)[0]
        return textwrap.dedent(section.split("        run: |\n", 1)[1])

    def test_external_workflow_publishes_all_three_outcomes_and_missing_report(self):
        for name, directory, text in (
            ("pass", "assessment", "# BootUI assessment: PASS\n"),
            ("violation", "assessment", "# BootUI assessment: VIOLATION\n"),
            ("not-assessed", "not-assessed", "# BootUI assessment: NOT_ASSESSED\n"),
            ("missing", None, "BootUI assessment unavailable"),
        ):
            with self.subTest(name=name):
                state = self.root / name
                if directory:
                    report = state / "artifacts" / directory / "summary.md"
                    report.parent.mkdir(parents=True)
                    report.write_text(text)
                output = self.root / (name + "-summary.md")
                output.touch()
                env = os.environ | {"BOOTUI_CI_ROOT": str(state), "GITHUB_STEP_SUMMARY": str(output)}
                result = subprocess.run(
                    ["bash", "-euo", "pipefail", "-c", self.summary_script()],
                    env=env, capture_output=True, text=True, timeout=10,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(text.strip(), output.read_text())

    def test_external_summary_rejects_symlink_and_oversized_reports(self):
        state = self.root / "state"
        report = state / "artifacts/assessment/summary.md"
        report.parent.mkdir(parents=True)
        output = self.root / "summary.md"
        output.touch()
        env = os.environ | {"BOOTUI_CI_ROOT": str(state), "GITHUB_STEP_SUMMARY": str(output)}
        for oversized in (False, True):
            with self.subTest(oversized=oversized):
                if oversized:
                    report.write_bytes(b"x" * (256 * 1024 + 1))
                else:
                    report.symlink_to(self.jar)
                result = subprocess.run(
                    ["bash", "-euo", "pipefail", "-c", self.summary_script()],
                    env=env, capture_output=True, text=True, timeout=10,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(output.read_text(), "")
                report.unlink()

    def test_external_summary_prioritizes_not_assessed_over_a_partial_assessment(self):
        state = self.root / "state"
        for directory, text in (("assessment", "PASS"), ("not-assessed", "NOT_ASSESSED")):
            report = state / "artifacts" / directory / "summary.md"
            report.parent.mkdir(parents=True)
            report.write_text(text)
        output = self.root / "summary.md"
        output.touch()
        env = os.environ | {"BOOTUI_CI_ROOT": str(state), "GITHUB_STEP_SUMMARY": str(output)}
        result = subprocess.run(
            ["bash", "-euo", "pipefail", "-c", self.summary_script()],
            env=env, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), "NOT_ASSESSED")

    def test_workflows_keep_gate_failures_and_upload_only_staged_files(self):
        for path in (EXAMPLES / "workflows").glob("*.template"):
            workflow = path.read_text(encoding="utf-8")
            self.assertNotIn("continue-on-error", workflow)
            self.assertIn("workflow_dispatch:", workflow)
            self.assertIn('python3 "$BOOTUI_EXAMPLE_DIR/check-result.py"', workflow)
            self.assertIn("if: ${{ !cancelled() }}", workflow)
            self.assertIn("path: ${{ env.BOOTUI_CI_ROOT }}/artifacts/", workflow)
            self.assertIn("retention-days: 7", workflow)


if __name__ == "__main__":
    unittest.main()
