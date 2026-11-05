import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).with_name("ci-consumer-acceptance.py")
SPEC = importlib.util.spec_from_file_location("ci_consumer_acceptance", SCRIPT)
acceptance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acceptance)


class ConsumerAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_application_jar_rejects_bootui_in_production_and_stale_diagnostic_artifacts(self):
        jar = self.root / "consumer.jar"
        name = "BOOT-INF/lib/bootui-engine-1.0.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(name, b"candidate")
            archive.writestr("BOOT-INF/lib/spring-webmvc-1.jar", b"mvc")
            archive.writestr("BOOT-INF/lib/tomcat-embed-core-1.jar", b"tomcat")

        with self.assertRaisesRegex(AssertionError, "unexpected BootUI entries"):
            acceptance.verify_application_jar(jar, {}, "mvc")
        acceptance.verify_application_jar(jar, {name: hashlib.sha256(b"candidate").digest()}, "mvc")
        with self.assertRaisesRegex(AssertionError, "stale"):
            acceptance.verify_application_jar(jar, {name: hashlib.sha256(b"published").digest()}, "mvc")

    def test_consumers_require_their_stack_and_reject_cross_stack_libraries(self):
        jar = self.root / "consumer.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("BOOT-INF/lib/spring-webflux-1.jar", b"webflux")
            archive.writestr("BOOT-INF/lib/reactor-netty-http-1.jar", b"netty")
        acceptance.verify_application_jar(jar, {}, "webflux")
        with self.assertRaisesRegex(AssertionError, "lacks spring-webmvc"):
            acceptance.verify_application_jar(jar, {}, "mvc")
        with zipfile.ZipFile(jar, "a") as archive:
            archive.writestr("BOOT-INF/lib/jakarta.servlet-api-1.jar", b"servlet")
        with self.assertRaisesRegex(AssertionError, "contains jakarta.servlet-api"):
            acceptance.verify_application_jar(jar, {}, "webflux")

    def test_candidate_repository_must_match_built_jars_and_flattened_poms(self):
        repository = self.root / "repository"
        for module in ("bootui-engine", "bootui-ui", "bootui-spring-boot-starter"):
            source = self.root / module
            target = source / "target"
            target.mkdir(parents=True, exist_ok=True)
            (target / f"{module}-1.0.jar").write_bytes(b"candidate")
            (source / ".flattened-pom.xml").write_bytes(b"candidate pom")
            installed = repository / "com/julien-dubois/bootui" / module / "1.0"
            installed.mkdir(parents=True, exist_ok=True)
            (installed / f"{module}-1.0.jar").write_bytes(b"candidate")
            (installed / f"{module}-1.0.pom").write_bytes(b"candidate pom")
        for module in ("bootui-engine", "bootui-ui", "bootui-quarkus", "bootui-quarkus-deployment"):
            source = self.root / module
            target = source / "target"
            target.mkdir(parents=True, exist_ok=True)
            (target / f"{module}-1.0.jar").write_bytes(b"candidate")
            (source / ".flattened-pom.xml").write_bytes(b"candidate pom")
            installed = repository / "com/julien-dubois/bootui" / module / "1.0"
            installed.mkdir(parents=True, exist_ok=True)
            (installed / f"{module}-1.0.jar").write_bytes(b"candidate")
            (installed / f"{module}-1.0.pom").write_bytes(b"candidate pom")
        extension = self.root / "bootui-quarkus/target/bootui-quarkus-1.0.jar"
        with zipfile.ZipFile(extension, "w") as archive:
            archive.writestr(
                "META-INF/quarkus-extension.properties",
                "deployment-artifact=com.julien-dubois.bootui\\:bootui-quarkus-deployment\\:1.0\n",
            )
        installed_extension = repository / "com/julien-dubois/bootui/bootui-quarkus/1.0/bootui-quarkus-1.0.jar"
        installed_extension.write_bytes(extension.read_bytes())
        cli = self.root / "bootui-cli/target"
        cli.mkdir(parents=True)
        (cli / "bootui-cli-1.0-all.jar").write_bytes(b"cli")
        installed_cli = repository / "com/julien-dubois/bootui/bootui-cli/1.0"
        installed_cli.mkdir(parents=True)
        (installed_cli / "bootui-cli-1.0-all.jar").write_bytes(b"cli")
        with patch.object(acceptance, "ROOT", self.root):
            spring_artifacts, quarkus_artifacts, _ = acceptance.candidate_artifacts("1.0", repository)
            self.assertEqual(len(spring_artifacts), 3)
            self.assertEqual(len(quarkus_artifacts), 3)
            extension = self.root / "bootui-quarkus/target/bootui-quarkus-1.0.jar"
            with zipfile.ZipFile(extension, "w") as archive:
                archive.writestr(
                    "META-INF/quarkus-extension.properties",
                    "deployment-artifact=com.julien-dubois.bootui:bootui-quarkus-deployment:published\n",
                )
            installed_extension = repository / "com/julien-dubois/bootui/bootui-quarkus/1.0/bootui-quarkus-1.0.jar"
            installed_extension.write_bytes(extension.read_bytes())
            with self.assertRaisesRegex(AssertionError, "does not reference the current deployment artifact"):
                acceptance.candidate_artifacts("1.0", repository)
            with zipfile.ZipFile(extension, "w") as archive:
                archive.writestr(
                    "META-INF/quarkus-extension.properties",
                    "deployment-artifact=com.julien-dubois.bootui\\:bootui-quarkus-deployment\\:1.0\n",
                )
            installed_extension.write_bytes(extension.read_bytes())
            stale_jar = repository / "com/julien-dubois/bootui/bootui-engine/1.0/bootui-engine-1.0.jar"
            stale_jar.write_bytes(b"published")
            with self.assertRaisesRegex(AssertionError, "not the checkout's built artifact"):
                acceptance.candidate_artifacts("1.0", repository)
            stale_jar.write_bytes(b"candidate")
            stale_pom = stale_jar.with_suffix(".pom")
            stale_pom.write_bytes(b"published pom")
            with self.assertRaisesRegex(AssertionError, "not the checkout's flattened POM"):
                acceptance.candidate_artifacts("1.0", repository)
            stale_pom.write_bytes(b"candidate pom")
            stale_quarkus = installed_extension
            stale_quarkus.write_bytes(b"published")
            with self.assertRaisesRegex(AssertionError, "not the checkout's built artifact"):
                acceptance.candidate_artifacts("1.0", repository)
            stale_quarkus.write_bytes(extension.read_bytes())
            stale_deployment = (
                repository
                / "com/julien-dubois/bootui/bootui-quarkus-deployment/1.0/bootui-quarkus-deployment-1.0.jar"
            )
            stale_deployment.write_bytes(b"published")
            with self.assertRaisesRegex(AssertionError, "not the checkout's built artifact"):
                acceptance.candidate_artifacts("1.0", repository)
            stale_deployment.write_bytes(b"candidate")
            (installed_cli / "bootui-cli-1.0-all.jar").write_bytes(b"published cli")
            with self.assertRaisesRegex(AssertionError, "not the checkout's built CLI"):
                acceptance.candidate_artifacts("1.0", repository)

    def test_quarkus_fast_jar_checks_opt_in_artifacts_and_rejects_stale_jars(self):
        project = self.root / "consumer"
        libraries = project / "target/quarkus-app/lib/main"
        libraries.mkdir(parents=True)
        run_jar = project / "target/quarkus-app/quarkus-run.jar"
        with zipfile.ZipFile(run_jar, "w"):
            pass
        jar = libraries / "com.julien-dubois.bootui.bootui-quarkus-1.0.jar"
        jar.write_bytes(b"candidate")
        for extension in ("quarkus-security", "quarkus-elytron-security-properties-file"):
            (libraries / f"io.quarkus.{extension}-3.40.1.1.jar").write_bytes(b"diagnostic")
        expected = {jar.name: hashlib.sha256(b"candidate").digest()}
        acceptance.verify_quarkus_application(project, expected, diagnostic=True)
        embedded_store = libraries / "io.quarkus.quarkus-elytron-security-properties-file-3.40.1.1.jar"
        embedded_store.unlink()
        with self.assertRaisesRegex(AssertionError, "unexpected quarkus-elytron-security-properties-file dependency"):
            acceptance.verify_quarkus_application(project, expected, diagnostic=True)
        embedded_store.write_bytes(b"diagnostic")
        other = project / "target/quarkus-app/lib/boot/com.julien-dubois.bootui.bootui-quarkus-deployment-1.0.jar"
        other.parent.mkdir(parents=True)
        other.write_bytes(b"unexpected")
        with self.assertRaisesRegex(AssertionError, "unexpected BootUI artifacts"):
            acceptance.verify_quarkus_application(project, expected, diagnostic=True)
        other.unlink()
        with self.assertRaisesRegex(AssertionError, "unexpected BootUI artifacts"):
            acceptance.verify_quarkus_application(project, {}, diagnostic=True)
        jar.write_bytes(b"published")
        with self.assertRaisesRegex(AssertionError, "stale"):
            acceptance.verify_quarkus_application(project, expected, diagnostic=True)

    def test_quarkus_production_fast_jar_rejects_embedded_bootui_classes(self):
        project = self.root / "production"
        libraries = project / "target/quarkus-app/lib/main"
        libraries.mkdir(parents=True)
        with zipfile.ZipFile(project / "target/quarkus-app/quarkus-run.jar", "w"):
            pass
        app_jar = project / "target/quarkus-app/app/consumer.jar"
        app_jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(app_jar, "w"):
            pass
        store = libraries / "io.quarkus.quarkus-elytron-security-properties-file-3.40.1.1.jar"
        store.write_bytes(b"diagnostic-only")
        with self.assertRaisesRegex(AssertionError, "unexpected quarkus-elytron-security-properties-file dependency"):
            acceptance.verify_quarkus_application(project, {}, diagnostic=False)
        store.unlink()
        acceptance.verify_quarkus_application(project, {}, diagnostic=False)
        with zipfile.ZipFile(app_jar, "w") as archive:
            archive.writestr("io/github/jdubois/bootui/quarkus/Leaked.class", b"leaked")
        with self.assertRaisesRegex(AssertionError, "embeds BootUI classes"):
            acceptance.verify_quarkus_application(project, {}, diagnostic=False)

    def test_running_server_must_match_the_selected_stack(self):
        mvc = "BOOTUI_CI_CONSUMER_SERVER=org.springframework.boot.tomcat.TomcatWebServer\n"
        webflux = "BOOTUI_CI_CONSUMER_SERVER=org.springframework.boot.reactor.netty.NettyWebServer\n"
        acceptance.verify_server(mvc, "mvc")
        acceptance.verify_server(webflux, "webflux")
        with self.assertRaisesRegex(AssertionError, "unexpected web server"):
            acceptance.verify_server(mvc, "webflux")
        with self.assertRaisesRegex(AssertionError, "did not report its web server"):
            acceptance.verify_server("", "mvc")

    def test_witness_and_command_output_reject_secrets_and_encoded_credentials(self):
        acceptance.require_no_sentinel("safe output", "synthetic-secret", "test output")
        credential = "synthetic-secret"
        leaked_forms = (
            credential,
            f"ci:{credential}",
            base64.b64encode(credential.encode()).decode("ascii"),
            base64.b64encode(f"ci:{credential}".encode()).decode("ascii"),
            acceptance.FINDING_SENTINEL,
        )
        for secret in leaked_forms:
            with self.subTest(secret=secret), self.assertRaisesRegex(AssertionError, "leaked into test output"):
                acceptance.require_no_sentinel(f"output {secret}", credential, "test output")

    def test_duplicate_action_outputs_are_rejected(self):
        output = self.root / "outputs"
        output.write_text("exit-code=0\nexit-code=1\n", encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "duplicate Action output"):
            acceptance.action_outputs(output)

    def test_reports_reject_leaked_sentinel_and_inconsistent_junit(self):
        reports = self.root / "reports"
        reports.mkdir()
        (reports / "bundle.json").write_text('{"status":"PASS"}', encoding="utf-8")
        summary = reports / "summary.md"
        summary.write_text("# BootUI assessment: PASS\n", encoding="utf-8")
        junit = reports / "junit.xml"
        junit.write_text(
            '<testsuite name="bootui-ci" tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="bootui.ci" name="rest-api"/></testsuite>',
            encoding="utf-8",
        )
        acceptance.verify_reports(reports, "PASS", "synthetic-secret")
        summary.write_text("# BootUI assessment: PASS\nsynthetic-secret\n", encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "Synthetic secret or finding detail leaked"):
            acceptance.verify_reports(reports, "PASS", "synthetic-secret")
        summary.write_text("# BootUI assessment: PASS\n", encoding="utf-8")
        junit.write_text(
            '<testsuite name="bootui-ci" tests="2" failures="0" errors="0" skipped="0">'
            '<testcase classname="bootui.ci" name="rest-api"/></testsuite>',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AssertionError, "JUnit test count"):
            acceptance.verify_reports(reports, "PASS", "synthetic-secret")

    def test_reports_reject_empty_or_skipped_passing_junit_suites(self):
        reports = self.root / "reports"
        reports.mkdir()
        (reports / "bundle.json").write_text('{"status":"PASS"}', encoding="utf-8")
        (reports / "summary.md").write_text("# BootUI assessment: PASS\n", encoding="utf-8")
        junit = reports / "junit.xml"
        junit.write_text(
            '<testsuite name="bootui-ci" tests="0" failures="0" errors="0" skipped="0"/>',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AssertionError, "JUnit omitted or skipped"):
            acceptance.verify_reports(reports, "PASS", "synthetic-secret")
        junit.write_text(
            '<testsuite name="bootui-ci" tests="1" failures="0" errors="0" skipped="1">'
            '<testcase classname="bootui.ci" name="rest-api"><skipped/></testcase></testsuite>',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AssertionError, "JUnit omitted or skipped"):
            acceptance.verify_reports(reports, "PASS", "synthetic-secret")

    def test_command_timeout_terminates_its_owned_child(self):
        marker = self.root / "terminated"
        ready = self.root / "ready"
        stubborn_pid = self.root / "stubborn-pid"
        child = (
            "import pathlib,signal,sys,time\n"
            "def terminate(signum, frame):\n"
            " pathlib.Path(sys.argv[2]).write_text('terminated')\n"
            " sys.exit(0)\n"
            "signal.signal(signal.SIGTERM, terminate)\n"
            "pathlib.Path(sys.argv[1]).write_text('ready')\n"
            "time.sleep(60)\n"
        )
        stubborn = (
            "import os,pathlib,signal,sys,time\n"
            "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
            "pathlib.Path(sys.argv[1]).write_text(str(os.getpid()))\n"
            "time.sleep(60)\n"
        )
        wrapper = (
            "import pathlib,subprocess,sys,time\n"
            "child = subprocess.Popen([sys.executable, '-c', sys.argv[1], sys.argv[2], sys.argv[3]])\n"
            "subprocess.Popen([sys.executable, '-c', sys.argv[4], sys.argv[5]], "
            "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
            "while not (pathlib.Path(sys.argv[2]).exists() and pathlib.Path(sys.argv[5]).exists()): time.sleep(0.01)\n"
            "child.wait()\n"
        )
        with self.assertRaisesRegex(AssertionError, "timed out"):
            acceptance.run(
                [sys.executable, "-c", wrapper, child, str(ready), str(marker), stubborn, str(stubborn_pid)],
                timeout=3,
            )
        self.assertTrue(ready.is_file(), "Child did not start before the timeout")
        self.assertEqual(marker.read_text(encoding="utf-8"), "terminated")
        self.assertTrue(stubborn_pid.is_file(), "The signal-resistant child did not start")
        pid = int(stubborn_pid.read_text(encoding="utf-8"))
        try:
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                status = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True)
                if status.returncode != 0 or status.stdout.strip().startswith("Z"):
                    break
                time.sleep(0.05)
            else:
                self.fail("A signal-resistant child survived command cleanup")
        finally:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass


if __name__ == "__main__":
    unittest.main()
