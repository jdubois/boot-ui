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
        self.assertIn("    - if: ${{ !cancelled() && inputs.phase != 'run' && inputs.phase != 'prepare-cli' }}", tail)
        self.assertNotIn("always()", tail)

    def test_action_preserves_consumer_invocation_and_fallback_contract(self):
        action = (ACTION / "action.yml").read_text()
        prepare = action.split("    - id: prepare\n", 1)[1].split("    - id: quick_attach\n", 1)[0]
        invoke = action.split("    - id: invoke\n", 1)[1].split("    - id: report\n", 1)[0]
        report = action.split("    - id: report\n", 1)[1].split("    - if:", 1)[0]
        self.assertIn("(inputs.phase != 'assess' || inputs.attach-exit-code == '0')", prepare)
        self.assertIn("continue-on-error: ${{ inputs.phase == 'run' }}", prepare)
        self.assertIn(
            "if: ${{ !cancelled() && inputs.phase != 'run' && inputs.phase != 'prepare-cli' && steps.prepare.outcome == 'success' }}",
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

    def test_application_start_failure_produces_a_distinct_error(self):
        self.environment.update(
            BOOTUI_ATTACH_EXIT_CODE="1", BOOTUI_PREPARE_OUTCOME="success",
            BOOTUI_START_OUTCOME="failure",
        )
        result = self.run_script("report-error.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        bundle = json.loads((self.root / "assessment.action-error" / "bundle.json").read_text())
        self.assertEqual(bundle["errors"], [{"code": "APP_START_FAILED"}])

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

    def test_invalid_cli_reports_trigger_a_safe_fallback(self):
        self.prepare_fake_cli()
        target = self.root / "outside"
        target.write_text("not a report")
        for problem in ("empty", "symlink", "oversized"):
            with self.subTest(problem=problem):
                self.outputs.unlink(missing_ok=True)
                output = self.root / f"assessment-{problem}"
                self.environment.update(
                    BOOTUI_OUTPUT_DIR=str(output), BOOTUI_PREPARE_OUTCOME="success",
                    FAKE_EXIT="0", FAKE_REPORTS=problem, FAKE_TARGET=str(target),
                )
                result = self.run_script("invoke.sh")
                self.assertEqual(result.returncode, 0)
                self.assertEqual(self.outputs.read_text(), "exit-code=1\n")
                self.assertNotIn("summary-fresh", self.outputs.read_text())
                self.outputs.unlink()
                fallback = self.run_script("report-error.sh")
                self.assertEqual(fallback.returncode, 0, fallback.stderr)
                report = Path(str(output) + ".action-error")
                bundle = json.loads((report / "bundle.json").read_text())
                self.assertEqual(bundle["status"], "NOT_ASSESSED")
                self.assertEqual(bundle["errors"], [{"code": "REPORT_UNAVAILABLE"}])
                self.assertIn("exit-code=1", self.outputs.read_text())
                self.environment.update(BOOTUI_REPORT_DIR=str(report), GITHUB_STEP_SUMMARY=str(self.root / "summary"))
                validated = self.run_script("quick-report.sh")
                self.assertEqual(validated.returncode, 0, validated.stderr)

    def test_invalid_assessment_stays_failed_if_fallback_directory_exists(self):
        self.prepare_fake_cli()
        for cli_status in ("0", "3"):
            with self.subTest(cli_status=cli_status):
                self.outputs.unlink(missing_ok=True)
                output = self.root / f"assessment-{cli_status}"
                Path(str(output) + ".action-error").mkdir()
                self.environment.update(
                    BOOTUI_OUTPUT_DIR=str(output), BOOTUI_PREPARE_OUTCOME="success",
                    FAKE_EXIT=cli_status, FAKE_REPORTS="empty",
                )
                result = self.run_script("invoke.sh")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.outputs.read_text(), "exit-code=1\n")
                fallback = self.run_script("report-error.sh")
                self.assertNotEqual(fallback.returncode, 0)
                self.assertIn("Failure report directory is not fresh", fallback.stderr)
                self.assertEqual(self.outputs.read_text(), "exit-code=1\n")

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

    def test_unknown_assessment_exit_code_is_not_advertised_as_a_fresh_report(self):
        self.prepare_fake_cli()
        self.environment.update(FAKE_EXIT="2", FAKE_REPORTS="complete")
        result = self.run_script("invoke.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs.read_text(), "exit-code=1\n")

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
            "if [[ \"$FAKE_REPORTS\" != partial ]]; then\n"
            "  echo '{}' > \"$BOOTUI_OUTPUT_DIR/bundle.json\"\n"
            "  echo '<testsuite/>' > \"$BOOTUI_OUTPUT_DIR/junit.xml\"\n"
            "fi\n"
            "case \"$FAKE_REPORTS\" in\n"
            "  empty) : > \"$BOOTUI_OUTPUT_DIR/bundle.json\" ;;\n"
            "  symlink) rm \"$BOOTUI_OUTPUT_DIR/summary.md\"; "
            "ln -s \"$FAKE_TARGET\" \"$BOOTUI_OUTPUT_DIR/summary.md\" ;;\n"
            "  oversized) head -c 262145 /dev/zero > \"$BOOTUI_OUTPUT_DIR/summary.md\" ;;\n"
            "esac\n"
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


class PrepareCliTests(unittest.TestCase):
    FINGERPRINT = "7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9"

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.outputs = self.root / "output"
        self.github_env = self.root / "github-env"
        self.log = self.root / "commands"
        self.jar = self.root / "bootui-cli/2.3.0/bootui-cli-2.3.0-all.jar"
        self.environment = dict(
            os.environ,
            PATH=f"{self.bin}:{os.environ['PATH']}",
            RUNNER_TEMP=str(self.root),
            BOOTUI_VERSION="2.3.0",
            GITHUB_OUTPUT=str(self.outputs),
            GITHUB_ENV=str(self.github_env),
            FAKE_LOG=str(self.log),
            FAKE_KEY_CACHED="true",
            FAKE_PRIMARY=self.FINGERPRINT,
            FAKE_VERIFY="valid",
            FAKE_CURL_FAIL="false",
            FAKE_IMPORT_FAIL="false",
        )
        self.tool(
            "gpg",
            """#!/usr/bin/env bash
set -eu
echo "gpg $*" >> "$FAKE_LOG"
echo "gnupghome=$GNUPGHOME" >> "$FAKE_LOG"
if [[ "${FAKE_REQUIRE_SHORT_ALIAS:-false}" == true ]]; then
  [[ -L "$GNUPGHOME" && "${#GNUPGHOME}" -le 70 ]] || exit 93
fi
case " $* " in
  *" --list-keys "*) [[ "$FAKE_KEY_CACHED" == true ]];;
  *" --import "*) [[ "$FAKE_IMPORT_FAIL" == false ]];;
  *" --verify "*)
    [[ ! -s "$GITHUB_OUTPUT" && ! -s "$GITHUB_ENV" ]] || exit 90
    [[ "$FAKE_VERIFY" == valid ]] || exit 1
    printf '[GNUPG:] VALIDSIG SIGNING-SUBKEY 0 %s\\n' "$FAKE_PRIMARY"
    ;;
  *) exit 91;;
esac
""",
        )
        self.tool(
            "curl",
            """#!/usr/bin/env bash
set -eu
echo "curl $*" >> "$FAKE_LOG"
[[ "$FAKE_CURL_FAIL" == false ]] || exit 22
while (( $# )); do
  if [[ "$1" == --output ]]; then
    printf 'fixture' > "$2"
    exit 0
  fi
  shift
done
exit 92
""",
        )
        self.tool(
            "java",
            """#!/usr/bin/env bash
if [[ "${FAKE_CI_HELP:-available}" == available ]]; then
  printf 'Usage: bootui ci <command>\\n'
  exit 0
fi
printf 'Usage: bootui <command>\\n'
exit 0
""",
        )

    def tool(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def cache_cli(self):
        self.jar.parent.mkdir(parents=True)
        self.jar.write_text("candidate")
        self.jar.with_suffix(".jar.asc").write_text("signature")

    def run_prepare(self):
        return subprocess.run(
            ["bash", str(ACTION / "prepare-cli.sh")],
            env=self.environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_not_advertised(self):
        for path in (self.outputs, self.github_env):
            self.assertFalse(path.exists() and path.read_text(), path)

    def assert_advertised(self):
        self.assertEqual(self.outputs.read_text(), f"cli-jar={self.jar}\n")
        self.assertEqual(self.github_env.read_text(), f"BOOTUI_CLI_JAR={self.jar}\n")

    def test_cached_cli_is_advertised_only_after_signature_verification(self):
        self.cache_cli()
        result = self.run_prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_advertised()
        commands = self.log.read_text().splitlines()
        self.assertEqual(sum(" --verify " in line for line in commands), 1)
        self.assertFalse(any(line.startswith("curl ") for line in commands))

    def test_auto_version_prepares_the_cli_declared_by_the_application(self):
        project = self.root / "application"
        project.mkdir()
        (project / "pom.xml").write_text(
            "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
            "<dependencies><dependency>"
            "<groupId>com.julien-dubois.bootui</groupId>"
            "<artifactId>bootui-spring-boot-starter</artifactId>"
            "<version>2.3.0</version>"
            "</dependency></dependencies></project>"
        )
        self.environment.update(BOOTUI_VERSION="auto", GITHUB_WORKSPACE=str(project))
        self.cache_cli()
        result = self.run_prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_advertised()

    def test_old_cli_help_is_not_advertised_as_ci_capable(self):
        self.cache_cli()
        self.environment["FAKE_CI_HELP"] = "legacy"
        result = self.run_prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not contain the CI command interface", result.stderr)
        self.assert_not_advertised()

    def test_cached_bad_signature_or_wrong_primary_key_is_never_advertised(self):
        self.cache_cli()
        for verify, primary in (("invalid", self.FINGERPRINT), ("valid", "F" * 40)):
            with self.subTest(verify=verify, primary=primary):
                self.environment.update(FAKE_VERIFY=verify, FAKE_PRIMARY=primary)
                result = self.run_prepare()
                self.assertNotEqual(result.returncode, 0)
                self.assert_not_advertised()

    def test_download_is_verified_before_install_and_advertisement(self):
        self.environment["FAKE_KEY_CACHED"] = "false"
        result = self.run_prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_advertised()
        self.assertTrue(self.jar.is_file())
        self.assertTrue(self.jar.with_suffix(".jar.asc").is_file())
        commands = self.log.read_text().splitlines()
        self.assertEqual(sum(" --verify " in line for line in commands), 2)
        self.assertTrue(any(" --import " in line for line in commands))
        self.assertEqual(sum(line.startswith("curl ") for line in commands), 3)

    def test_download_with_bad_signature_or_wrong_primary_key_is_not_installed(self):
        for verify, primary in (("invalid", self.FINGERPRINT), ("valid", "F" * 40)):
            with self.subTest(verify=verify, primary=primary):
                self.environment.update(FAKE_VERIFY=verify, FAKE_PRIMARY=primary)
                result = self.run_prepare()
                self.assertNotEqual(result.returncode, 0)
                self.assert_not_advertised()
                self.assertFalse(self.jar.exists())

    def test_download_and_key_import_failures_are_not_advertised(self):
        for key, value in (("FAKE_CURL_FAIL", "true"), ("FAKE_IMPORT_FAIL", "true")):
            with self.subTest(key=key):
                self.environment["FAKE_KEY_CACHED"] = "false"
                self.environment[key] = value
                result = self.run_prepare()
                self.assertNotEqual(result.returncode, 0)
                self.assert_not_advertised()
                self.assertFalse(self.jar.exists())
                self.environment[key] = "false"

    def test_runner_temp_newlines_are_rejected_before_outputs_are_written(self):
        self.environment["RUNNER_TEMP"] = str(self.root) + "\ninjected"
        result = self.run_prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must not contain a newline", result.stderr)
        self.assert_not_advertised()

    def test_long_runner_temp_uses_a_private_short_gpg_alias_and_cleans_it(self):
        long_root = self.root / ("runner-" * 15)
        long_root.mkdir()
        self.environment.update(RUNNER_TEMP=str(long_root), FAKE_REQUIRE_SHORT_ALIAS="true")
        self.jar = long_root / "bootui-cli/2.3.0/bootui-cli-2.3.0-all.jar"
        self.cache_cli()
        result = self.run_prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_advertised()
        homes = [line.removeprefix("gnupghome=") for line in self.log.read_text().splitlines()
                 if line.startswith("gnupghome=")]
        self.assertTrue(homes)
        self.assertTrue(all(not Path(home).parent.exists() for home in homes))

    def test_long_gpg_alias_is_cleaned_even_when_signature_is_invalid(self):
        long_root = self.root / ("runner-" * 15)
        long_root.mkdir()
        self.environment.update(
            RUNNER_TEMP=str(long_root), FAKE_REQUIRE_SHORT_ALIAS="true", FAKE_VERIFY="invalid"
        )
        self.jar = long_root / "bootui-cli/2.3.0/bootui-cli-2.3.0-all.jar"
        self.cache_cli()
        result = self.run_prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assert_not_advertised()
        homes = [line.removeprefix("gnupghome=") for line in self.log.read_text().splitlines()
                 if line.startswith("gnupghome=")]
        self.assertTrue(homes)
        self.assertTrue(all(not Path(home).parent.exists() for home in homes))

    def test_gpg_socket_limit_counts_bytes_not_unicode_characters(self):
        with tempfile.TemporaryDirectory(prefix="b-", dir="/tmp") as name:
            root = Path(name) / ("\u00e9" * 23)
            root.mkdir()
            keyring = str(root / "bootui-cli/gnupg")
            self.assertLessEqual(len(keyring), 70)
            self.assertGreater(len(os.fsencode(keyring)), 70)
            self.environment.update(RUNNER_TEMP=str(root), FAKE_REQUIRE_SHORT_ALIAS="true")
            self.jar = root / "bootui-cli/2.3.0/bootui-cli-2.3.0-all.jar"
            self.cache_cli()
            result = self.run_prepare()
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assert_advertised()


if __name__ == "__main__":
    unittest.main()
