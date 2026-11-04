#!/usr/bin/env python3
"""Exercise the local CI CLI and Action scripts against opt-in Spring consumers.

First install the current reactor's Spring starter and CLI into an isolated Maven repository. This test uses
the shaded CLI from the checkout and verifies the consumer's embedded BootUI jars against the checkout's
built jars; it does not exercise the Action's release-signature download or the GitHub Actions runner.
"""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / ".github/scripts/fixtures/ci-consumer"
ACTION = ROOT / ".github/actions/bootui"
API_PATH = "/diagnostics/api"
REPORT_FILES = {"bundle.json", "summary.md", "junit.xml"}
FINDING_SENTINEL = "bootui-ci-private-finding-payload"
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
SERVERS = {
    "mvc": "org.springframework.boot.tomcat.TomcatWebServer",
    "webflux": "org.springframework.boot.reactor.netty.NettyWebServer",
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def project_versions():
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    project = ET.parse(ROOT / "pom.xml").getroot()
    version = project.findtext("m:version", namespaces=namespace)
    spring = project.findtext("m:properties/m:spring-boot.version", namespaces=namespace)
    require(bool(version and spring), "The reactor must declare its BootUI and Spring Boot versions")
    require(bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", spring)), "Invalid Spring Boot version")
    return version, spring


def run(command, *, env=None, timeout=300, expected=0):
    process = subprocess.Popen(
        command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True
    )
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired as timeout_error:
        stdout, stderr = stop_command(process)
        raise AssertionError(
            f"{command[0]} timed out after {timeout} seconds\nstdout:\n{stdout[-12000:]}\nstderr:\n{stderr[-12000:]}"
        ) from timeout_error
    except BaseException:
        stop_command(process)
        raise
    result = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    require(
        result.returncode == expected,
        f"{command[0]} exited {result.returncode}, expected {expected}\n"
        f"stdout:\n{result.stdout[-12000:]}\nstderr:\n{result.stderr[-12000:]}",
    )
    return result


def stop_command(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        output = process.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        output = None
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    return output if output is not None else process.communicate(timeout=5)


def digest(path):
    return hashlib.sha256(path.read_bytes()).digest()


def candidate_artifacts(version, repository):
    artifacts = {}
    for module in ("bootui-engine", "bootui-ui", "bootui-spring-boot-starter"):
        jar = ROOT / module / "target" / f"{module}-{version}.jar"
        flattened_pom = ROOT / module / ".flattened-pom.xml"
        installed = repository / "com/julien-dubois/bootui" / module / version
        installed_jar = installed / jar.name
        installed_pom = installed / f"{module}-{version}.pom"
        for path in (jar, flattened_pom, installed_jar, installed_pom):
            require(path.is_file(), f"Missing candidate artifact: {path}; install the current reactor first")
        require(digest(jar) == digest(installed_jar), f"{installed_jar} is not the checkout's built artifact")
        require(digest(flattened_pom) == digest(installed_pom), f"{installed_pom} is not the checkout's flattened POM")
        artifacts[f"BOOT-INF/lib/{jar.name}"] = digest(jar)
    cli = ROOT / "bootui-cli/target" / f"bootui-cli-{version}-all.jar"
    require(cli.is_file(), f"Missing checkout CLI: {cli}; install the current reactor first")
    installed_cli = repository / "com/julien-dubois/bootui/bootui-cli" / version / cli.name
    require(installed_cli.is_file(), f"Missing installed CLI: {installed_cli}; install the current reactor first")
    require(digest(cli) == digest(installed_cli), f"{installed_cli} is not the checkout's built CLI")
    return artifacts, cli


def verify_server(output, stack):
    require(stack in SERVERS, f"Unknown consumer stack: {stack}")
    match = re.search(r"^BOOTUI_CI_CONSUMER_SERVER=(\S+)$", output, re.MULTILINE)
    require(match is not None, "Consumer did not report its web server")
    require(match.group(1) == SERVERS[stack], f"{stack} consumer started unexpected web server {match.group(1)}")


def verify_application_jar(path, expected_artifacts, stack):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        bootui_entries = {
            name
            for name in names
            if (name.startswith("BOOT-INF/lib/bootui-") and name.endswith(".jar"))
            or name.startswith("BOOT-INF/classes/io/github/jdubois/bootui/")
        }
        require(
            bootui_entries == set(expected_artifacts),
            f"{path.name} contains unexpected BootUI entries: {sorted(bootui_entries)}",
        )
        for name, expected in expected_artifacts.items():
            require(hashlib.sha256(jar.read(name)).digest() == expected, f"{path.name} contains a stale {name}")
        stacks = {
            "mvc": (("spring-webmvc-", "tomcat-embed-core-"), ("spring-webflux-", "reactor-netty-http-")),
            "webflux": (
                ("spring-webflux-", "reactor-netty-http-"),
                ("spring-webmvc-", "jakarta.servlet-api-", "tomcat-embed-core-"),
            ),
        }
        require(stack in stacks, f"Unknown consumer stack: {stack}")
        required, forbidden = stacks[stack]
        for prefix in required:
            require(any(name.startswith("BOOT-INF/lib/" + prefix) for name in names), f"{path.name} lacks {prefix}")
        for prefix in forbidden:
            require(
                not any(name.startswith("BOOT-INF/lib/" + prefix) for name in names),
                f"{path.name} contains {prefix}",
            )


def build_consumer(project, work, repository, version, stack, diagnostic, artifacts, *, broken=False):
    require(not broken or diagnostic, "The broken fixture must be a diagnostic build")
    profiles = (["webflux"] if stack == "webflux" else []) + (["bootui-ci"] if diagnostic else [])
    command = [
        str(ROOT / "mvnw"),
        "-B",
        "-ntp",
        "-f",
        str(project / "pom.xml"),
        f"-Dmaven.repo.local={repository}",
        f"-Dbootui.version={version}",
        "-DskipTests",
    ]
    if profiles:
        command.append("-P" + ",".join(profiles))
    command.extend(("clean", "package"))
    broken_source = project / "src/main/java/example/BrokenController.java"
    if broken:
        require(not broken_source.exists(), "The normal consumer already contains the broken controller")
        shutil.copyfile(project / "broken/BrokenController.java", broken_source)
    try:
        run(command, timeout=600)
        built = project / "target/bootui-ci-consumer-1.0.0.jar"
        variant = "violation" if broken else ("diagnostic" if diagnostic else "production")
        destination = work / f"{stack}-{variant}.jar"
        shutil.copyfile(built, destination)
        verify_application_jar(destination, artifacts if diagnostic else {}, stack)
        return destination
    finally:
        if broken:
            broken_source.unlink()


@contextmanager
def application(jar, work, *, stack, diagnostic, port=0, disable_rest=False):
    work.mkdir(parents=True)
    log_path = work / "application.log"
    instance = uuid.uuid4().hex
    environment = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith(("BOOTUI_", "SPRING_", "SERVER_"))
        and key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}
    }
    command = [
        "java",
        "-jar",
        str(jar),
        "--server.address=127.0.0.1",
        f"--server.port={port}",
        "--spring.application.name=bootui-ci-consumer",
        f"--ci.consumer.instance={instance}",
    ]
    if diagnostic:
        environment.update(
            BOOTUI_ENABLED="ON",
            BOOTUI_CLI_ENABLED="true",
            BOOTUI_MCP_ENABLED="false",
            BOOTUI_EXPOSE_VALUES="METADATA_ONLY",
            BOOTUI_OVERRIDES_FILE=str(work / "boot-ui.yml"),
        )
        command.append(f"--bootui.api-path={API_PATH}")
        if disable_rest:
            command.append("--bootui.panels.rest-api.enabled=false")
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command, cwd=work, env=environment, stdout=log, stderr=subprocess.STDOUT, text=True
        )
        try:
            deadline = time.monotonic() + 180
            actual_port = None
            output = ""
            while time.monotonic() < deadline:
                output = log_path.read_text(encoding="utf-8", errors="replace")
                match = re.search(r"^BOOTUI_CI_CONSUMER_PORT=(\d+)$", output, re.MULTILINE)
                if match:
                    actual_port = int(match.group(1))
                    break
                if process.poll() is not None:
                    output = log_path.read_text(encoding="utf-8", errors="replace")
                    raise RuntimeError(f"Consumer exited before readiness:\n{output[-12000:]}")
                time.sleep(0.25)
            require(actual_port is not None and actual_port > 0, f"Consumer did not become ready:\n{output[-12000:]}")
            verify_server(output, stack)
            require(process.poll() is None, "Consumer exited immediately after reporting readiness")
            if port:
                require(actual_port == port, f"Restart bound port {actual_port}, expected {port}")
            yield f"http://127.0.0.1:{actual_port}", actual_port, instance, process
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


def workload(url, sentinel, instance, process):
    require(process.poll() is None, "The owned application exited before the workload")
    request = urllib.request.Request(
        f"{url}/orders?api_key={sentinel}", headers={"Authorization": f"Bearer {sentinel}"}
    )
    with HTTP.open(request, timeout=15) as response:
        require(response.status == 200, f"Workload returned HTTP {response.status}")
        require(json.load(response) == ["order", instance], "Workload did not reach the owned application")
    require(process.poll() is None, "The owned application exited during the workload")


def make_case(root, label, threshold):
    directory = root / label
    directory.mkdir()
    policy = directory / "policy.json"
    policy.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "advisors": {"rest-api": {"required": True, "failOn": threshold}},
                "runtimeContext": {"includeInsights": True},
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return {
        "id": label,
        "dir": directory,
        "policy": policy,
        "witness": directory / "witness.json",
        "reports": directory / "reports",
    }


def action_outputs(path):
    outputs = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        require(bool(separator) and key not in outputs, f"Invalid or duplicate Action output: {line}")
        outputs[key] = value
    return outputs


def invoke(case, cli, url, phase, label, *, sentinel, attach_exit="0", reports=None):
    output = case["dir"] / f"{label}.outputs"
    environment = os.environ.copy()
    environment.update(
        GITHUB_OUTPUT=str(output),
        BOOTUI_CLI_JAR=str(cli),
        BOOTUI_PHASE=phase,
        BOOTUI_URL=url,
        BOOTUI_ASSESSMENT_ID=case["id"],
        BOOTUI_POLICY=str(case["policy"]),
        BOOTUI_WITNESS=str(case["witness"]),
        BOOTUI_OUTPUT_DIR=str(reports or case["reports"]),
        BOOTUI_ATTACH_EXIT_CODE=attach_exit,
        BOOTUI_API_PATH=API_PATH,
        BOOTUI_TIMEOUT="60",
    )
    result = run(["bash", str(ACTION / "invoke.sh")], env=environment, timeout=240)
    require_no_sentinel(result.stdout + result.stderr, sentinel, "Action output")
    require_no_sentinel(output.read_text(encoding="utf-8"), sentinel, "Action outputs file")
    if case["witness"].is_file():
        require_no_sentinel(case["witness"].read_text(encoding="utf-8"), sentinel, "Witness")
    return action_outputs(output), result


def require_action_exit(outputs, result, expected, label, reports=None):
    detail = ""
    bundle = reports / "bundle.json" if reports else None
    if outputs.get("exit-code") != expected and bundle and bundle.is_file():
        detail = f"\nbundle:\n{bundle.read_text(encoding='utf-8')[-12000:]}"
    require(
        outputs.get("exit-code") == expected,
        f"{label} produced {outputs.get('exit-code')}, expected {expected}\n"
        f"stdout:\n{result.stdout[-12000:]}\nstderr:\n{result.stderr[-12000:]}{detail}",
    )


def check_bundle(case, cli, reports, expected_exit, sentinel):
    environment = os.environ.copy()
    environment["BOOTUI_NO_UPDATE_CHECK"] = "1"
    result = run(
        [
            "java",
            "-jar",
            str(cli),
            "ci",
            "check",
            "--input",
            str(reports / "bundle.json"),
            "--assessment-id",
            case["id"],
            "--policy",
            str(case["policy"]),
        ],
        env=environment,
        expected=expected_exit,
    )
    require_no_sentinel(result.stdout + result.stderr, sentinel, "Offline check output")


def require_no_sentinel(data, sentinel, source):
    for secret in (sentinel, FINDING_SENTINEL):
        require(secret not in data, f"Synthetic secret or finding detail leaked into {source}")


def verify_reports(reports, status, sentinel):
    require({path.name for path in reports.iterdir()} == REPORT_FILES, f"Incomplete reports in {reports}")
    for name in REPORT_FILES:
        require_no_sentinel((reports / name).read_text(encoding="utf-8"), sentinel, name)
    require((reports / "bundle.json").stat().st_size <= 2 * 1024 * 1024, "Bundle exceeded its byte limit")
    require((reports / "summary.md").stat().st_size <= 256 * 1024, "Summary exceeded its byte limit")
    bundle = json.loads((reports / "bundle.json").read_text(encoding="utf-8"))
    require(bundle["status"] == status, f"Expected {status}, got {bundle['status']}")
    require(
        (reports / "summary.md").read_text(encoding="utf-8").startswith(f"# BootUI assessment: {status}\n"),
        "Summary does not expose the assessment status",
    )
    suite = ET.parse(reports / "junit.xml").getroot()
    require(suite.tag == "testsuite" and suite.get("name") == "bootui-ci", "Invalid JUnit suite")
    expected_failures = "1" if status == "VIOLATION" else "0"
    expected_errors = "1" if status == "NOT_ASSESSED" else "0"
    require(suite.get("failures") == expected_failures, "JUnit failure count disagrees with the bundle")
    require(suite.get("errors") == expected_errors, "JUnit error count disagrees with the bundle")
    cases = suite.findall("testcase")
    require(suite.get("tests") == str(len(cases)), "JUnit test count disagrees with its test cases")
    require(suite.get("tests") == "1" and suite.get("skipped") == "0", "JUnit omitted or skipped the expected test")
    expected_name = "assessment" if status == "NOT_ASSESSED" else "rest-api"
    require(
        cases[0].get("classname") == "bootui.ci" and cases[0].get("name") == expected_name,
        "JUnit reported the wrong testcase",
    )
    require(len(suite.findall("testcase/failure")) == int(expected_failures), "JUnit failure element is missing")
    require(len(suite.findall("testcase/error")) == int(expected_errors), "JUnit error element is missing")
    require(not cases[0].findall("skipped"), "JUnit marked the expected test as skipped")
    return bundle


def failure_report(case, cli, url, sentinel):
    refused, result = invoke(case, cli, url, "assess", "refused-assess", sentinel=sentinel, attach_exit="1")
    require_action_exit(refused, result, "1", "Assessment after failed attach")
    require("summary-fresh" not in refused, "A failed attach advertised a fresh summary")
    require(not case["reports"].exists(), "A failed attach unexpectedly wrote an assessment report")
    output = case["dir"] / "fallback.outputs"
    environment = os.environ.copy()
    environment.update(
        GITHUB_OUTPUT=str(output),
        BOOTUI_OUTPUT_DIR=str(case["reports"]),
        BOOTUI_ASSESSMENT_ID=case["id"],
        BOOTUI_ATTACH_EXIT_CODE="1",
        BOOTUI_PREPARE_OUTCOME="skipped",
    )
    result = run(["bash", str(ACTION / "report-error.sh")], env=environment)
    require_no_sentinel(result.stdout + result.stderr, sentinel, "Fallback output")
    require_no_sentinel(output.read_text(encoding="utf-8"), sentinel, "Fallback outputs file")
    outputs = action_outputs(output)
    reports = Path(outputs["report-dir"])
    require(outputs["exit-code"] == "1", "An attach failure must remain a failing result")
    require(reports == Path(str(case["reports"]) + ".action-error"), "Unexpected fallback report directory")
    bundle = verify_reports(reports, "NOT_ASSESSED", sentinel)
    require(
        bundle
        == {
            "schemaVersion": 1,
            "assessmentId": case["id"],
            "status": "NOT_ASSESSED",
            "errors": [{"code": "ATTACH_FAILED"}],
        },
        "Fallback report lost the attach failure or assessment identity",
    )


def assess_case(case, cli, url, sentinel, instance, process, expected_status, expected_exit, rule, severity):
    outputs, result = invoke(case, cli, url, "attach", "attach", sentinel=sentinel)
    require_action_exit(outputs, result, "0", f"{case['id']} attach")
    witness = json.loads(case["witness"].read_text(encoding="utf-8"))
    require(
        witness["schemaVersion"] == 2
        and witness["frameworkName"] == "Spring Boot"
        and witness["instanceId"]
        and witness["runId"]
        and witness["apiPath"] == API_PATH,
        "Witness did not bind the framework, identity, and custom API path",
    )
    workload(url, sentinel, instance, process)
    outputs, result = invoke(case, cli, url, "assess", "assess", sentinel=sentinel)
    require_action_exit(outputs, result, expected_exit, f"{case['id']} assessment", case["reports"])
    require(outputs.get("summary-fresh") == "true", "Action did not advertise complete fresh reports")
    require(outputs.get("report-dir") == str(case["reports"]), "Action advertised the wrong report directory")
    bundle = verify_reports(case["reports"], expected_status, sentinel)
    require(bundle["schemaVersion"] == 2 and bundle["frameworkName"] == "Spring Boot", "Invalid framework")
    require(bundle["assessmentId"] == case["id"] and not bundle["errors"], "Invalid assessment identity")
    for key in ("instanceId", "runId", "policySha256"):
        require(bundle[key] == witness[key], f"Bundle {key} differs from the pre-workload witness")
    require(len(bundle["advisors"]) == 1, "Assessment did not retain the selected scope")
    advisor = bundle["advisors"][0]
    require(advisor["name"] == "rest-api" and advisor["status"] == "ASSESSED", "REST advisor was not assessed")
    require(advisor["coverageComplete"] and advisor["usable"], "REST advisor evidence was incomplete")
    matching = [
        finding for finding in advisor["findings"] if finding["ruleId"] == rule and finding["severity"] == severity
    ]
    require(len(matching) == 1, f"The expected {severity} finding {rule} was not retained exactly once")
    meets_threshold = "yes" if expected_status == "VIOLATION" else "no"
    expected_row = f"| rest-api | {rule} | {severity} | {matching[0]['count']} | {meets_threshold} |"
    require(
        expected_row in (case["reports"] / "summary.md").read_text(encoding="utf-8"),
        "Finding missing in summary",
    )
    context = bundle["runtimeContext"]
    require(
        context["status"] in {"CAPTURED", "PARTIAL"}
        and context["workloadAttribution"] == "UNVERIFIED"
        and context["httpExchangesRead"] >= 1,
        f"Runtime context must include HTTP activity without claiming workload attribution: {context}",
    )
    if expected_status == "VIOLATION":
        with HTTP.open(f"{url}{API_PATH}/rest-api", timeout=15) as response:
            report_bytes = response.read(2 * 1024 * 1024 + 1)
        require(len(report_bytes) <= 2 * 1024 * 1024, "Cached REST report exceeded its test limit")
        scan_id = json.loads(report_bytes)["violationDetails"]["scanId"]
        require(isinstance(scan_id, str) and scan_id, "Cached REST report did not expose a scan ID")
        query = urllib.parse.urlencode({"scanId": scan_id, "limit": 1})
        with HTTP.open(f"{url}{API_PATH}/rest-api/rules/{rule}/violations?{query}", timeout=15) as response:
            detail = response.read(64 * 1024 + 1)
        require(len(detail) <= 64 * 1024, "Finding detail exceeded its test limit")
        require(FINDING_SENTINEL.encode() in detail, "The raw finding detail did not include its sentinel")
    check_bundle(case, cli, case["reports"], int(expected_exit), sentinel)
    require(process.poll() is None, "The owned application exited before assessment completed")


def exercise_stack(stack, production, diagnostic, violation_jar, cli, work, sentinel):
    cases = work / f"{stack}-cases"
    cases.mkdir()
    missing = make_case(cases, f"{stack}-missing", "critical")
    with application(production, work / f"{stack}-production-app", stack=stack, diagnostic=False) as (
        url,
        _,
        instance,
        process,
    ):
        outputs, result = invoke(missing, cli, url, "attach", "attach", sentinel=sentinel)
        require_action_exit(outputs, result, "1", "A BootUI-free application")
        witness = json.loads(missing["witness"].read_text(encoding="utf-8"))
        require(
            witness["status"] == "NOT_ASSESSED" and witness["errors"] == [{"code": "TARGET_REQUEST_FAILED"}],
            "A BootUI-free application did not produce the expected attach failure",
        )
        workload(url, sentinel, instance, process)
        failure_report(missing, cli, url, sentinel)
        require(process.poll() is None, "The owned application exited during failure reporting")

    disabled = make_case(cases, f"{stack}-disabled", "critical")
    with application(diagnostic, work / f"{stack}-disabled-app", stack=stack, diagnostic=True, disable_rest=True) as (
        url,
        _,
        instance,
        process,
    ):
        outputs, result = invoke(disabled, cli, url, "attach", "attach", sentinel=sentinel)
        require_action_exit(outputs, result, "1", "A disabled required advisor")
        witness = json.loads(disabled["witness"].read_text(encoding="utf-8"))
        require(
            witness["status"] == "NOT_ASSESSED" and witness["errors"] == [{"code": "REQUIRED_ADVISOR_UNAVAILABLE"}],
            "A disabled required advisor did not produce the expected refusal",
        )
        workload(url, sentinel, instance, process)
        failure_report(disabled, cli, url, sentinel)
        require(process.poll() is None, "The owned application exited during failure reporting")

    positive = make_case(cases, f"{stack}-pass", "high")
    violation = make_case(cases, f"{stack}-violation", "high")
    with application(diagnostic, work / f"{stack}-diagnostic-app", stack=stack, diagnostic=True) as (
        url,
        port,
        instance,
        process,
    ):
        assess_case(positive, cli, url, sentinel, instance, process, "PASS", "0", "RAPI-PAGE-001", "LOW")

    stale_reports = positive["dir"] / "after-restart"
    with application(diagnostic, work / f"{stack}-restarted-app", stack=stack, diagnostic=True, port=port) as (
        url,
        _,
        instance,
        process,
    ):
        workload(url, sentinel, instance, process)
        outputs, result = invoke(
            positive, cli, url, "assess", "after-restart", sentinel=sentinel, reports=stale_reports
        )
        require_action_exit(outputs, result, "1", "A previous process witness after restart")
        require(outputs.get("summary-fresh") == "true", "Restart failure did not produce fresh reports")
        require(outputs.get("report-dir") == str(stale_reports), "Restart failure advertised the wrong reports")
        bundle = verify_reports(stale_reports, "NOT_ASSESSED", sentinel)
        require(bundle["errors"] == [{"code": "IDENTITY_CHANGED"}], "Restart did not report an identity change")
        require(
            "Assessment errors: `IDENTITY_CHANGED`." in (stale_reports / "summary.md").read_text(encoding="utf-8"),
            "Restart failure was not explained in the summary",
        )
        check_bundle(positive, cli, stale_reports, 1, sentinel)
        require(process.poll() is None, "The restarted application exited before assessment completed")

    with application(violation_jar, work / f"{stack}-violation-app", stack=stack, diagnostic=True) as (
        url,
        _,
        instance,
        process,
    ):
        assess_case(violation, cli, url, sentinel, instance, process, "VIOLATION", "3", "RAPI-MAP-006", "HIGH")
    print(f"{stack}: opt-in packaging, live assessment, failure reporting, and identity checks passed", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-repository", type=Path, required=True, help="Absolute isolated Maven repository")
    arguments = parser.parse_args()
    require(arguments.maven_repository.is_absolute(), "The Maven repository path must be absolute")
    repository = arguments.maven_repository.resolve(strict=True)
    version, spring = project_versions()
    artifacts, cli = candidate_artifacts(version, repository)
    sentinel = "bootui-ci-synthetic-secret-" + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix="bootui-ci-consumer-") as directory:
        work = Path(directory)
        project = work / "project"
        shutil.copytree(FIXTURE, project)
        template = (project / "pom.xml.template").read_text(encoding="utf-8")
        require(template.count("@SPRING_BOOT_VERSION@") == 1, "Consumer POM template has an invalid version marker")
        (project / "pom.xml").write_text(template.replace("@SPRING_BOOT_VERSION@", spring), encoding="utf-8")
        for stack in ("mvc", "webflux"):
            production = build_consumer(project, work, repository, version, stack, False, artifacts)
            diagnostic = build_consumer(project, work, repository, version, stack, True, artifacts)
            violation = build_consumer(project, work, repository, version, stack, True, artifacts, broken=True)
            exercise_stack(stack, production, diagnostic, violation, cli, work, sentinel)
    print("Spring consumer acceptance passed; release-signature and GitHub runner execution were not exercised.")


def handle_termination(signum, _frame):
    raise SystemExit(128 + signum)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, handle_termination)
    main()
