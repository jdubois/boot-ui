import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / ".github/actions/bootui"
ACTION_METADATA = ROOT / "action.yml"


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
            BOOTUI_APP_COMMAND="auto",
            BOOTUI_STARTUP_TIMEOUT="120",
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
            "advisors": {
                "architecture": {"required": True, "failOn": "high"},
                "hibernate": {"required": False, "failOn": "high"},
                "spring": {"required": False, "failOn": "high"},
                "rest-api": {"required": False, "failOn": "high"},
                "security": {"required": False, "failOn": "high"},
                "database-advisor": {"required": False, "failOn": "high"},
            },
        })
        self.assertEqual(policy, json.loads((ROOT / "examples/ci/bootui-policy.json").read_text()))
        action = ACTION_METADATA.read_text()
        self.assertIn("    default: run\n", action)
        self.assertIn("    default: auto\n", action)
        self.assertEqual(
            action.count("inputs.policy || format('{0}/.github/actions/bootui/default-policy.json', github.action_path)"),
            2,
        )
        self.assertEqual(action.count("inputs.url || 'http://127.0.0.1:8080'"), 4)
        self.assertEqual(action.count("BOOTUI_CLI_JAR: ${{ steps.prepare.outputs.cli-jar }}"), 2)
        self.assertIn('echo "cli-jar=$cli_jar" >> "$GITHUB_OUTPUT"', (ACTION / "prepare-cli.sh").read_text())

    def test_composite_steps_have_user_facing_names(self):
        steps = ACTION_METADATA.read_text().split("  steps:\n", 1)[1].split("\n    - ")
        for step in steps:
            if step.strip():
                self.assertRegex(step, r"(?:^|\n)\s*(?:- )?name: [A-Z][^\n]+")

    def test_workload_runs_even_when_setup_or_attachment_fails(self):
        action = ACTION_METADATA.read_text()
        workload = action.split("    - id: quick_workload\n", 1)[1].split("    - id: quick_assess\n", 1)[0]
        self.assertIn("if: ${{ !cancelled() && inputs.phase == 'run' }}", workload)
        self.assertIn("continue-on-error: true", workload)
        self.assertNotIn("steps.prepare", workload)
        self.assertNotIn("steps.quick_attach", workload)
        assess = action.split("    - id: quick_assess\n", 1)[1].split("    - id: quick_report\n", 1)[0]
        self.assertIn("steps.quick_attach.outputs.exit-code == '0'", assess)
        self.assertNotIn("steps.quick_workload.outcome", assess)

    def test_action_starts_and_stops_only_its_managed_application(self):
        action = ACTION_METADATA.read_text()
        start = action.split("    - id: quick_start\n", 1)[1].split("    - id: quick_attach\n", 1)[0]
        stop = action.split("    - id: quick_stop\n", 1)[1].split("    - id: quick_validate\n", 1)[0]
        self.assertIn("BOOTUI_APP_COMMAND: ${{ inputs.app-command }}", start)
        self.assertIn("steps.quick_setup.outputs.state-dir", start)
        self.assertIn("steps.prepare.outcome == 'success'", start)
        self.assertIn("continue-on-error: true", start)
        self.assertIn("always()", stop)
        self.assertIn("steps.quick_start.outputs.pid != ''", stop)
        self.assertIn("BOOTUI_APP_URL:", stop)
        self.assertIn('run: bash "$ACTION_PATH/quick-stop.sh"', stop)

    def test_only_validated_reports_are_uploaded_and_cancellation_stays_respected(self):
        action = ACTION_METADATA.read_text()
        upload = action.split("    - id: quick_upload\n", 1)[1].split("    - id: quick_gate\n", 1)[0]
        self.assertIn("!cancelled()", upload)
        self.assertIn("steps.quick_validate.outcome == 'success'", upload)
        for name in ("bundle.json", "summary.md", "junit.xml"):
            self.assertIn(f"steps.quick_validate.outputs.report-dir }}}}/{name}", upload)
        self.assertNotIn("witness", upload)
        self.assertEqual(action.count("always()"), 1)

    def test_quick_phase_order_and_fail_closed_outcome_wiring(self):
        action = ACTION_METADATA.read_text()
        names = (
            "quick_setup", "prepare", "quick_start", "quick_attach", "quick_workload", "quick_assess",
            "quick_report", "quick_stop", "quick_validate", "quick_upload", "quick_gate",
        )
        positions = [action.index(f"    - id: {name}\n") for name in names]
        self.assertEqual(positions, sorted(positions))
        self.assertNotIn(".conclusion", action)
        for index, name in enumerate(names[3:7], start=3):
            end = positions[index + 1] if index + 1 < len(positions) else action.index("    - id: invoke\n")
            with self.subTest(step=name):
                self.assertIn("!cancelled()", action[positions[index]:end])
        report = action[positions[6]:positions[7]]
        self.assertIn(
            "steps.prepare.outcome == 'success' && (steps.quick_attach.outputs.exit-code || '1') || '0'",
            report,
        )
        validate = action[positions[8]:positions[9]]
        self.assertIn("steps.quick_report.outputs.report-dir || steps.quick_assess.outputs.report-dir", validate)
        gate = action[positions[10]:action.index("    - id: invoke\n")]
        for name, expression in (
            ("BOOTUI_SETUP_OUTCOME", "steps.quick_setup.outcome"),
            ("BOOTUI_PREPARE_OUTCOME", "steps.prepare.outcome"),
            ("BOOTUI_START_OUTCOME", "steps.quick_start.outcome"),
            ("BOOTUI_STOP_OUTCOME", "steps.quick_stop.outcome"),
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
        self.assertEqual(values["state-dir"], str(state))
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
            ("BOOTUI_APP_COMMAND", " \n ", "app-command"),
            ("BOOTUI_STARTUP_TIMEOUT", "601", "startup-timeout"),
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

    def test_command_detection_selects_maven_and_gradle_defaults(self):
        for files, expected_app, expected_test in (
            ({"pom.xml": "<artifactId>spring-boot-starter-web</artifactId>"}, "spring-boot:run", "verify"),
            ({"pom.xml": "<artifactId>quarkus-maven-plugin</artifactId>"}, "quarkus:dev", "verify"),
            ({"build.gradle": "plugins { id 'org.springframework.boot' }"}, "bootRun", "check"),
            ({"build.gradle.kts": 'plugins { id("io.quarkus") }'}, "quarkusDev", "check"),
        ):
            with self.subTest(files=files):
                project = self.root / str(len(list(self.root.iterdir())))
                project.mkdir()
                for name, content in files.items():
                    (project / name).write_text(content)
                for wrapper in ("mvnw", "gradlew"):
                    if (wrapper == "mvnw") == ("pom.xml" in files):
                        path = project / wrapper
                        path.write_text("#!/bin/sh\nexit 0\n")
                        path.chmod(0o755)
                environment = dict(self.env, BOOTUI_WORKING_DIRECTORY=str(project))
                app = subprocess.run(
                    ["bash", str(ACTION / "detect-command.sh"), "app", "auto"],
                    env=environment, capture_output=True, text=True, check=False,
                )
                test = subprocess.run(
                    ["bash", str(ACTION / "detect-command.sh"), "test", "auto"],
                    env=environment, capture_output=True, text=True, check=False,
                )
                self.assertEqual(app.returncode, 0, app.stderr)
                self.assertEqual(test.returncode, 0, test.stderr)
                self.assertTrue(app.stdout.rstrip().endswith(expected_app), app.stdout)
                self.assertTrue(test.stdout.rstrip().endswith(expected_test), test.stdout)

    def test_command_detection_honors_custom_commands_and_rejects_ambiguity(self):
        project = self.root / "custom"
        project.mkdir()
        (project / "pom.xml").write_text(
            "<artifactId>spring-boot-maven-plugin</artifactId>"
            "<artifactId>quarkus-maven-plugin</artifactId>"
        )
        environment = dict(self.env, BOOTUI_WORKING_DIRECTORY=str(project))
        custom = subprocess.run(
            ["bash", str(ACTION / "detect-command.sh"), "app", "./run-app --profile ci"],
            env=environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual(custom.returncode, 0, custom.stderr)
        self.assertEqual(custom.stdout, "./run-app --profile ci\n")
        ambiguous = subprocess.run(
            ["bash", str(ACTION / "detect-command.sh"), "app", "auto"],
            env=environment, capture_output=True, text=True, check=False,
        )
        self.assertNotEqual(ambiguous.returncode, 0)
        self.assertIn("Both Spring Boot and Quarkus", ambiguous.stderr)

    def test_command_detection_activates_the_conventional_diagnostic_profile(self):
        project = self.root / "profiled"
        project.mkdir()
        (project / "mvnw").write_text("#!/bin/sh\nexit 0\n")
        (project / "mvnw").chmod(0o755)
        (project / "pom.xml").write_text(
            "<project><profiles><profile><id>bootui-ci</id><dependencies><dependency>"
            "<groupId>com.julien-dubois.bootui</groupId>"
            "<artifactId>bootui-spring-boot-starter</artifactId>"
            "</dependency></dependencies></profile></profiles>"
            "<dependencies><dependency><artifactId>spring-boot-starter-web</artifactId>"
            "</dependency></dependencies></project>"
        )
        result = subprocess.run(
            ["bash", str(ACTION / "detect-command.sh"), "app", "auto"],
            env=dict(self.env, BOOTUI_WORKING_DIRECTORY=str(project)),
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "./mvnw -Pbootui-ci spring-boot:run\n")
        workload = subprocess.run(
            ["bash", str(ACTION / "detect-command.sh"), "test", "auto"],
            env=dict(self.env, BOOTUI_WORKING_DIRECTORY=str(project)),
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(workload.returncode, 0, workload.stderr)
        self.assertEqual(workload.stdout, "./mvnw -Pbootui-ci verify\n")

    def maven_project(self, body):
        project = self.root / "maven"
        project.mkdir(exist_ok=True)
        (project / "mvnw").write_text("#!/bin/sh\nexit 0\n")
        (project / "mvnw").chmod(0o755)
        (project / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<build><plugins><plugin><artifactId>spring-boot-maven-plugin</artifactId>'
            '</plugin></plugins></build>' + body + '</project>'
        )
        return project

    def dependency(self, artifact="bootui-spring-boot-starter", version="2.4.0", extra="",
                   group="com.julien-dubois.bootui"):
        return (
            f"<dependencies><dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId>"
            + (f"<version>{version}</version>" if version else "")
            + extra + "</dependency></dependencies>"
        )

    def profile(self, name, body):
        return f"<profile><id>{escape(name)}</id>{body}</profile>"

    def detect(self, project, kind="app", configured="auto", **environment):
        return subprocess.run(
            ["bash", str(ACTION / "detect-command.sh"), kind, configured],
            env={**dict(self.env, BOOTUI_WORKING_DIRECTORY=str(project), MAVEN_ARGS=""), **environment},
            capture_output=True, text=True, check=False,
        )

    def resolve(self, project, **environment):
        return subprocess.run(
            ["python3", str(ACTION / "resolve-version.py"), "auto"],
            env={**dict(self.env, GITHUB_WORKSPACE=str(project), BOOTUI_PHASE="run",
                        MAVEN_ARGS=""), **environment},
            capture_output=True, text=True, check=False,
        )

    def test_unique_runtime_profile_is_selected_for_startup_not_workload(self):
        for artifact, extra, profile in (
            ("bootui-spring-boot-starter", "", "dev"),
            ("bootui-spring-boot-starter", "<optional>true</optional>", "local-ui"),
            ("bootui-quarkus", "<scope>runtime</scope>", "diagnostic"),
        ):
            with self.subTest(artifact=artifact, profile=profile):
                project = self.maven_project(
                    "<profiles>" + self.profile(profile, self.dependency(artifact, extra=extra)) + "</profiles>"
                )
                if artifact == "bootui-quarkus":
                    pom = project / "pom.xml"
                    pom.write_text(pom.read_text().replace("spring-boot-maven-plugin", "quarkus-maven-plugin"))
                app = self.detect(project)
                self.assertEqual(app.returncode, 0, app.stderr)
                self.assertIn(f"-P{profile} ", app.stdout)
                self.assertIn("quarkus:dev" if artifact == "bootui-quarkus" else "spring-boot:run", app.stdout)
                workload = self.detect(project, "test")
                self.assertEqual(workload.stdout, "./mvnw verify\n")
                version = self.resolve(project)
                self.assertEqual(version.returncode, 0, version.stderr)
                self.assertEqual(version.stdout, "2.4.0\n")

    def test_profile_version_properties_override_root_and_dependency_management_resolves_versions(self):
        project = self.maven_project(
            "<properties><bootui.version>2.3.0</bootui.version></properties><profiles>"
            + self.profile("dev", "<properties><bootui.version>2.4.0</bootui.version></properties>"
                           "<dependencyManagement>" + self.dependency(version="${bootui.version}")
                           + "</dependencyManagement>" + self.dependency(version=""))
            + "</profiles>"
        )
        self.assertIn("-Pdev ", self.detect(project).stdout)
        version = self.resolve(project)
        self.assertEqual(version.returncode, 0, version.stderr)
        self.assertEqual(version.stdout, "2.4.0\n")

    def test_unrelated_conventional_profile_does_not_hide_the_runtime_profile(self):
        project = self.maven_project("<profiles>"
                                     + self.profile("bootui-ci", "<properties><example>true</example></properties>")
                                     + self.profile("dev", self.dependency()) + "</profiles>")
        self.assertEqual(self.detect(project).stdout, "./mvnw -Pdev spring-boot:run\n")
        self.assertEqual(self.resolve(project).stdout, "2.4.0\n")
        project = self.maven_project(self.dependency() + "<profiles>"
                                     + self.profile("bootui-ci", "<properties><example>true</example></properties>")
                                     + "</profiles>")
        self.assertEqual(self.detect(project).stdout, "./mvnw -Pbootui-ci spring-boot:run\n")
        self.assertEqual(self.detect(project, "test").stdout, "./mvnw -Pbootui-ci verify\n")

    def test_conventional_profile_properties_and_inactive_alternative_versions(self):
        project = self.maven_project(
            self.dependency(version="${bootui.version}") + "<profiles>"
            + self.profile("bootui-ci", "<properties><bootui.version>2.4.0</bootui.version></properties>")
            + self.profile("other", self.dependency(version="2.5.0")) + "</profiles>"
        )
        self.assertEqual(self.detect(project).stdout, "./mvnw -Pbootui-ci spring-boot:run\n")
        self.assertEqual(self.detect(project, "test").stdout, "./mvnw -Pbootui-ci verify\n")
        self.assertEqual(self.resolve(project).stdout, "2.4.0\n")
        for environment in ({"BOOTUI_PHASE": "attach"}, {"BOOTUI_APP_COMMAND": "none"},
                            {"BOOTUI_APP_COMMAND": "./mvnw -Pother spring-boot:run"},
                            {"MAVEN_ARGS": "-Pother"}):
            result = self.resolve(project, **environment)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Multiple BootUI versions", result.stderr)
        self.assertEqual(self.detect(project, MAVEN_ARGS="-Pother").stdout,
                         "./mvnw -Pbootui-ci spring-boot:run\n")

    def test_non_runtime_entries_do_not_activate_profiles(self):
        for body in (
            "<dependencyManagement>" + self.dependency() + "</dependencyManagement>",
            self.dependency(group="example"),
            self.dependency(extra="<scope>test</scope>"),
            self.dependency(artifact="bootui-cli"),
            "<!-- " + self.dependency() + " -->",
            "<build><plugins><plugin><artifactId>example</artifactId>"
            + self.dependency() + "</plugin></plugins></build>",
        ):
            with self.subTest(body=body):
                project = self.maven_project("<profiles>" + self.profile("dev", body) + "</profiles>")
                result = self.detect(project)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "./mvnw spring-boot:run\n")
        project = self.maven_project(self.dependency() + "<profiles>"
                                     + self.profile("dev", self.dependency(version="2.5.0")) + "</profiles>")
        self.assertEqual(self.detect(project).stdout, "./mvnw spring-boot:run\n")
        self.assertEqual(self.resolve(project).stdout, "2.4.0\n")

    def test_ambiguous_and_unsafe_profile_ids_require_explicit_commands(self):
        for profiles, message in (
            (self.profile("dev", self.dependency()) + self.profile("local", self.dependency()), "dev, local"),
            *[(self.profile(name, self.dependency()), "profile ID")
              for name in ("bad,id", "!dev", "-dev", "?dev", "$(touch injected)", "bad\nid")],
        ):
            with self.subTest(profiles=profiles):
                project = self.maven_project("<profiles>" + profiles + "</profiles>")
                result = self.detect(project)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertEqual(result.stdout, "")
                for configured in ("./mvnw -Pdev spring-boot:run", "none"):
                    result = self.detect(project, configured=configured)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stdout, configured + "\n")

    def test_existing_activation_and_external_profile_selection_keep_legacy_behavior(self):
        for activation, arguments, configuration in (
            ("<activation><activeByDefault>true</activeByDefault></activation>", "", ""),
            ("<activation><property><name>dev</name></property></activation>", "", ""),
            ("", "-Pdev", ""),
            ("", "--activate-profiles=dev", ""),
            ("", "-Dbootui.version=2.5.0", ""),
            ("", "-f service/pom.xml", ""),
            ("", "", "-Pdev\n"),
        ):
            with self.subTest(activation=activation, arguments=arguments, configuration=configuration):
                project = self.maven_project("<profiles>"
                                             + self.profile("dev", self.dependency() + activation) + "</profiles>")
                config = project / ".mvn"
                config.mkdir(exist_ok=True)
                (config / "maven.config").write_text(configuration)
                result = self.detect(project, MAVEN_ARGS=arguments)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "./mvnw spring-boot:run\n")
                self.assertIn("preserved", result.stderr)

    def test_profile_ambiguity_fails_before_download_and_retains_application_error_classification(self):
        project = self.maven_project("<profiles>"
                                     + self.profile("dev", self.dependency())
                                     + self.profile("local", self.dependency()) + "</profiles>")
        self.env.update(GITHUB_WORKSPACE=str(project), BOOTUI_PHASE="run")
        result = self.run_script("prepare-cli.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Multiple Maven profiles", result.stderr)
        self.assertEqual(self.output(), {"app-config-error": "true"})
        self.assertFalse((self.root / "bootui-cli").exists())
        self.outputs.unlink()
        self.env.update(
            BOOTUI_OUTPUT_DIR=str(self.root / "failed-assessment"),
            BOOTUI_APP_CONFIG_ERROR="true",
            BOOTUI_PREPARE_OUTCOME="failure",
        )
        report = self.run_script("report-error.sh")
        self.assertEqual(report.returncode, 0, report.stderr)
        bundle = Path(self.output()["report-dir"]) / "bundle.json"
        self.assertIn("APP_START_FAILED", bundle.read_text())

    def test_auto_workload_preserves_maven_defaults_and_custom_override(self):
        project = self.root / "workload"
        project.mkdir()
        wrapper = project / "mvnw"
        wrapper.write_text("#!/usr/bin/env bash\nprintf 'executed:%s\\n' \"$*\"\n")
        wrapper.chmod(0o755)
        (project / "pom.xml").write_text("<artifactId>spring-boot-starter-web</artifactId>")
        self.env.update(GITHUB_WORKSPACE=str(project), BOOTUI_TEST_COMMAND="auto")
        result = self.run_script("quick-workload.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "executed:verify\n")
        self.env["BOOTUI_TEST_COMMAND"] = "printf 'custom workload\\n'"
        result = self.run_script("quick-workload.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "custom workload\n")

    def test_no_workload_is_the_default(self):
        self.env.update(
            GITHUB_WORKSPACE=str(self.root),
            BOOTUI_TEST_COMMAND="none",
        )
        result = self.run_script("quick-workload.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("No workload command configured", result.stdout)
        self.assertNotIn("verify", result.stdout)

    def test_version_detection_uses_exactly_one_stable_application_dependency(self):
        project = self.root / "version-project"
        project.mkdir()
        environment = dict(self.env, GITHUB_WORKSPACE=str(project))

        (project / "pom.xml").write_text(
            "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
            "<properties><bootui.version>2.4.0</bootui.version></properties>"
            "<dependencies><dependency>"
            "<groupId>com.julien-dubois.bootui</groupId>"
            "<artifactId>bootui-spring-boot-starter</artifactId>"
            "<version>${bootui.version}</version>"
            "</dependency></dependencies></project>"
        )
        detected = subprocess.run(
            ["python3", str(ACTION / "resolve-version.py"), "auto"],
            env=environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual(detected.returncode, 0, detected.stderr)
        self.assertEqual(detected.stdout, "2.4.0\n")

        (project / "build.gradle").write_text(
            'runtimeOnly "com.julien-dubois.bootui:bootui-quarkus:2.5.0"\n'
        )
        ambiguous = subprocess.run(
            ["python3", str(ACTION / "resolve-version.py"), "auto"],
            env=environment, capture_output=True, text=True, check=False,
        )
        self.assertNotEqual(ambiguous.returncode, 0)
        self.assertIn("Multiple BootUI versions", ambiguous.stderr)

        explicit = subprocess.run(
            ["python3", str(ACTION / "resolve-version.py"), "2.6.0"],
            env=environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual(explicit.returncode, 0, explicit.stderr)
        self.assertEqual(explicit.stdout, "2.6.0\n")

    def test_version_detection_supports_namespace_free_and_profile_properties(self):
        project = self.root / "version-profile"
        project.mkdir()
        (project / "pom.xml").write_text(
            "<project><profiles><profile><id>bootui-ci</id><properties>"
            "<bootui.version>2.7.0</bootui.version></properties></profile></profiles>"
            "<dependencies><dependency><groupId>com.julien-dubois.bootui</groupId>"
            "<artifactId>bootui-spring-boot-starter</artifactId>"
            "<version>${bootui.version}</version></dependency></dependencies></project>"
        )
        result = subprocess.run(
            ["python3", str(ACTION / "resolve-version.py"), "auto"],
            env=dict(self.env, GITHUB_WORKSPACE=str(project)),
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "2.7.0\n")

        (project / "pom.xml").write_text(
            "<project><properties><a>${b}</a><b>${a}</b></properties>"
            "<dependencies><dependency><groupId>com.julien-dubois.bootui</groupId>"
            "<artifactId>bootui-spring-boot-starter</artifactId>"
            "<version>${a}</version></dependency></dependencies></project>"
        )
        cyclic = subprocess.run(
            ["python3", str(ACTION / "resolve-version.py"), "auto"],
            env=dict(self.env, GITHUB_WORKSPACE=str(project)),
            capture_output=True, text=True, check=False, timeout=5,
        )
        self.assertNotEqual(cyclic.returncode, 0)
        self.assertIn("Could not detect", cyclic.stderr)

    def test_version_detection_supports_parent_properties_and_gradle_catalogs(self):
        project = self.root / "multi-module"
        module = project / "service"
        module.mkdir(parents=True)
        (project / "pom.xml").write_text(
            "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
            "<properties><bootui.version>2.8.0</bootui.version></properties></project>"
        )
        (module / "pom.xml").write_text(
            "<project xmlns='http://maven.apache.org/POM/4.0.0'><dependencies><dependency>"
            "<groupId>com.julien-dubois.bootui</groupId>"
            "<artifactId>bootui-spring-boot-starter</artifactId>"
            "<version>${bootui.version}</version></dependency></dependencies></project>"
        )
        detected = subprocess.run(
            ["python3", str(ACTION / "resolve-version.py"), "auto"],
            env=dict(self.env, GITHUB_WORKSPACE=str(project)),
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(detected.returncode, 0, detected.stderr)
        self.assertEqual(detected.stdout, "2.8.0\n")

        (project / "pom.xml").unlink()
        (module / "pom.xml").unlink()
        (project / "gradle").mkdir()
        (project / "gradle" / "libs.versions.toml").write_text(
            "[versions]\nbootui = '2.9.0'\n"
            "[libraries]\nbootui-starter = { module = "
            "'com.julien-dubois.bootui:bootui-spring-boot-starter', version.ref = 'bootui' }\n"
            "[plugins]\nspring-boot = { id = 'org.springframework.boot', version = '4.1.2' }\n"
        )
        catalog = subprocess.run(
            ["python3", str(ACTION / "resolve-version.py"), "auto"],
            env=dict(self.env, GITHUB_WORKSPACE=str(project)),
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(catalog.returncode, 0, catalog.stderr)
        self.assertEqual(catalog.stdout, "2.9.0\n")

    def test_version_resolution_does_not_require_tomllib_for_explicit_versions(self):
        program = r"""
import builtins
import runpy
import sys
original = builtins.__import__
def without_tomllib(name, *args, **kwargs):
    if name == "tomllib":
        raise ModuleNotFoundError(name)
    return original(name, *args, **kwargs)
builtins.__import__ = without_tomllib
sys.argv = [sys.argv[1], "2.8.0"]
runpy.run_path(sys.argv[0], run_name="__main__")
"""
        result = subprocess.run(
            ["python3", "-c", program, str(ACTION / "resolve-version.py")],
            env=self.env, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "2.8.0\n")

    def test_start_launches_waits_and_stops_a_custom_application(self):
        project = self.root / "server"
        state = self.root / "state"
        project.mkdir()
        state.mkdir()
        with socket.socket() as candidate:
            candidate.bind(("127.0.0.1", 0))
            port = candidate.getsockname()[1]
        environment_file = self.root / "environment.json"
        server = project / "server.py"
        server.write_text(
            "import json, os\n"
            "from http.server import BaseHTTPRequestHandler, HTTPServer\n"
            "from pathlib import Path\n"
            "Path(os.environ['TEST_ENVIRONMENT_FILE']).write_text(json.dumps({\n"
            "  'enabled': os.environ.get('BOOTUI_ENABLED'),\n"
            "  'cli': os.environ.get('BOOTUI_CLI_ENABLED'),\n"
            "  'exposure': os.environ.get('BOOTUI_EXPOSE_VALUES'),\n"
            "  'overrides': os.environ.get('BOOTUI_OVERRIDES_FILE'),\n"
            "  'port': os.environ.get('SERVER_PORT'),\n"
            "  'quarkus_console': os.environ.get('QUARKUS_CONSOLE_ENABLED'),\n"
            "  'quarkus_testing': os.environ.get('QUARKUS_TEST_CONTINUOUS_TESTING'),\n"
            "  'quarkus_analytics': os.environ.get('QUARKUS_ANALYTICS_DISABLED'),\n"
            "}))\n"
            "class Handler(BaseHTTPRequestHandler):\n"
            "  def do_GET(self):\n"
            "    body = b'{\"enabled\":true}'\n"
            "    self.send_response(200)\n"
            "    self.send_header('Content-Length', str(len(body)))\n"
            "    self.end_headers()\n"
            "    self.wfile.write(body)\n"
            "  def log_message(self, *args):\n"
            "    pass\n"
            f"HTTPServer(('127.0.0.1', {port}), Handler).serve_forever()\n"
        )
        self.env.update(
            GITHUB_WORKSPACE=str(project),
            BOOTUI_STATE_DIR=str(state),
            BOOTUI_APP_COMMAND="python3 server.py",
            BOOTUI_URL=f"http://127.0.0.1:{port}",
            BOOTUI_API_PATH="/bootui/api",
            BOOTUI_STARTUP_TIMEOUT="10",
            TEST_ENVIRONMENT_FILE=str(environment_file),
        )
        result = self.run_script("quick-start.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = self.output()
        self.assertEqual(outputs["managed"], "true")
        pid = outputs["pid"]
        self.assertTrue(int(pid) > 1)
        started_environment = json.loads(environment_file.read_text())
        self.assertEqual(started_environment, {
            "enabled": "ON",
            "cli": "true",
            "exposure": "METADATA_ONLY",
            "overrides": str(state / "boot-ui.yml"),
            "port": str(port),
            "quarkus_console": "false",
            "quarkus_testing": "disabled",
            "quarkus_analytics": "true",
        })
        self.assertTrue((state / "ready.json").is_file())
        self.env["BOOTUI_APP_PID"] = pid
        stopped = self.run_script("quick-stop.sh")
        self.assertEqual(stopped.returncode, 0, stopped.stderr)
        self.assertNotEqual(
            subprocess.run(["kill", "-0", pid], capture_output=True, check=False).returncode,
            0,
        )

    def test_external_application_mode_does_not_start_or_stop_a_process(self):
        state = self.root / "external-state"
        state.mkdir()
        self.env.update(
            BOOTUI_STATE_DIR=str(state),
            BOOTUI_APP_COMMAND="none",
        )
        result = self.run_script("quick-start.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output(), {"managed": "false"})
        stopped = self.run_script("quick-stop.sh")
        self.assertEqual(stopped.returncode, 0, stopped.stderr)

    def test_start_failure_cleans_up_before_timing_out(self):
        state = self.root / "failed-state"
        state.mkdir()
        with socket.socket() as candidate:
            candidate.bind(("127.0.0.1", 0))
            port = candidate.getsockname()[1]
        self.env.update(
            BOOTUI_STATE_DIR=str(state),
            BOOTUI_APP_COMMAND="sleep 30",
            BOOTUI_URL=f"http://127.0.0.1:{port}",
            BOOTUI_STARTUP_TIMEOUT="1",
        )
        result = self.run_script("quick-start.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("within 1 seconds", result.stderr)
        pid = self.output()["pid"]
        self.assertNotEqual(
            subprocess.run(["kill", "-0", "--", f"-{pid}"], capture_output=True).returncode,
            0,
        )

    def test_early_process_exit_is_reported_instead_of_aborting_the_process_probe(self):
        state = self.root / "early-exit-state"
        state.mkdir()
        with socket.socket() as candidate:
            candidate.bind(("127.0.0.1", 0))
            port = candidate.getsockname()[1]
        self.env.update(
            BOOTUI_STATE_DIR=str(state),
            BOOTUI_APP_COMMAND="exit 2",
            BOOTUI_URL=f"http://127.0.0.1:{port}",
            BOOTUI_STARTUP_TIMEOUT="5",
        )
        result = self.run_script("quick-start.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exited before BootUI became ready", result.stderr)

    def test_start_rejects_non_loopback_targets_and_occupied_endpoints(self):
        state = self.root / "invalid-start"
        state.mkdir()
        marker = self.root / "launched"
        self.env.update(
            BOOTUI_STATE_DIR=str(state),
            BOOTUI_APP_COMMAND=f"touch {marker}",
            BOOTUI_STARTUP_TIMEOUT="1",
        )
        for url in ("http://localhost:8080", "http://0.0.0.0:8080", "http://192.168.1.2:8080"):
            with self.subTest(url=url):
                marker.unlink(missing_ok=True)
                self.outputs.unlink(missing_ok=True)
                self.env["BOOTUI_URL"] = url
                result = self.run_script("quick-start.sh")
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(marker.exists())
                self.assertFalse(self.outputs.exists())

        self.env["BOOTUI_URL"] = "https://127.0.0.1:8443"
        https = self.run_script("quick-start.sh")
        self.assertNotEqual(https.returncode, 0)
        self.assertIn("Managed applications must use http", https.stderr)
        self.assertFalse(marker.exists())

        with socket.socket() as occupied:
            occupied.bind(("127.0.0.1", 0))
            occupied.listen()
            port = occupied.getsockname()[1]
            self.env.update(
                BOOTUI_URL=f"http://127.0.0.1:{port}",
                BOOTUI_APP_COMMAND=f"touch {marker}",
            )
            result = self.run_script("quick-start.sh")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("already occupied", result.stderr)
            self.assertFalse(marker.exists())
            self.assertFalse(self.outputs.exists())

    def test_stop_treats_an_unreaped_zombie_as_stopped(self):
        process = subprocess.Popen(
            ["sleep", "300"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, start_new_session=True,
        )
        self.addCleanup(lambda: process.poll() is None and process.kill())
        process.terminate()
        started = __import__("time").monotonic()
        self.env["BOOTUI_APP_PID"] = str(process.pid)
        result = self.run_script("quick-stop.sh")
        elapsed = __import__("time").monotonic() - started
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertLess(elapsed, 5)
        process.wait(timeout=1)

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
            BOOTUI_START_OUTCOME="success",
            BOOTUI_STOP_OUTCOME="skipped",
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
            ("BOOTUI_START_OUTCOME", "failure"),
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
            BOOTUI_START_OUTCOME="success", BOOTUI_STOP_OUTCOME="success",
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
            ("BOOTUI_START_OUTCOME", "failure", "could not start the application"),
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
            BOOTUI_START_OUTCOME="success", BOOTUI_STOP_OUTCOME="success",
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
