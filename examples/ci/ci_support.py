"""Shared bounded process and evidence handling for the runnable CI recipes."""

import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import time
import xml.etree.ElementTree as ET


EXAMPLES = Path(__file__).resolve().parent
REPORT_LIMITS = {
    "bundle.json": 16 * 1024 * 1024,
    "summary.md": 256 * 1024,
    "junit.xml": 256 * 1024,
    "sarif.json": 4 * 1024 * 1024,
}


def interrupted(signum, frame):
    raise KeyboardInterrupt("CI runner interrupted")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def terminate(process):
    """Terminate only the session/process group created by this runner."""
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)
    # Maven can exit while a forked JVM remains. Its owned process group must not survive.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def run(command, *, cwd, env, timeout, log=None):
    output = subprocess.PIPE if log is None else log
    process = subprocess.Popen(
        command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT,
        text=True, start_new_session=True,
    )
    try:
        payload, _ = process.communicate(timeout=timeout)
        return process.returncode, payload or ""
    except subprocess.TimeoutExpired:
        terminate(process)
        return 1, "COMMAND_TIMEOUT\n"
    finally:
        terminate(process)


class Recipe:
    def __init__(self, project):
        require(os.name == "posix", "These process-owning recipes require Linux or macOS")
        signal.signal(signal.SIGTERM, interrupted)
        self.project = EXAMPLES / project
        self.version = os.environ.get("BOOTUI_VERSION", "")
        require(re.fullmatch(r"\d+\.\d+\.\d+(?:-SNAPSHOT)?", self.version), "Set BOOTUI_VERSION explicitly")
        jar = Path(os.environ.get("BOOTUI_CLI_JAR", ""))
        require(jar.is_absolute() and jar.is_file() and not jar.is_symlink(), "Set an absolute BOOTUI_CLI_JAR")
        self.cli = [str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java",
                    "-jar", str(jar)]
        cli_env = dict(os.environ, BOOTUI_NO_UPDATE_CHECK="1")
        code, output = run(self.cli + ["--version"], cwd=EXAMPLES, env=cli_env, timeout=30)
        require(code == 0 and output.strip() == "bootui " + self.version, "CLI and Maven BootUI versions must match")
        code, help_output = run(self.cli + ["ci", "--help"], cwd=EXAMPLES, env=cli_env, timeout=30)
        require(code == 0 and re.search(r"(?m)^Usage: bootui ci(?:\s|$)", help_output),
                "This CLI has no CI commands; published 2.3.0 is not CI-capable")

        raw_root = os.environ.get("BOOTUI_CI_ROOT", "")
        self.root = Path(raw_root)
        require(self.root.is_absolute(), "Set a fresh absolute BOOTUI_CI_ROOT outside the projects")
        require(self.root == self.root.resolve(), "BOOTUI_CI_ROOT must not contain symlinks or '..'")
        require(not any(self.root.is_relative_to(EXAMPLES / name) for name in ("spring", "quarkus")),
                "Evidence and dismissals must be outside cleanable/watched project roots")
        require(self.root.parent.is_dir(), "BOOTUI_CI_ROOT's parent must already exist")
        self.assessment_id = os.environ.get("BOOTUI_ASSESSMENT_ID", "orders-" + self.root.name)
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", self.assessment_id),
                "Use a bounded assessment ID containing only letters, digits, '.', '_', ':', '-'")
        self.root.mkdir(mode=0o700)
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()
        self.env = os.environ.copy()
        self.env.update(
            BOOTUI_ENABLED="ON", BOOTUI_CLI_ENABLED="true", BOOTUI_MCP_ENABLED="false",
            BOOTUI_EXPOSE_VALUES="METADATA_ONLY", BOOTUI_LOCALHOST_ONLY="true",
            BOOTUI_NO_UPDATE_CHECK="1",
            BOOTUI_OVERRIDES_FILE=str(self.root / "boot-ui.yml"),
        )
        (self.root / "boot-ui.yml").touch(mode=0o600)
        self.policy = Path(os.environ.get("BOOTUI_POLICY", str(EXAMPLES / "bootui-policy.json"))).resolve()
        self.result = {"schemaVersion": 1, "workloadExit": 1, "assessmentExit": 1}
        self.persist()

    def persist(self):
        (self.root / "result.json").write_text(json.dumps(self.result) + "\n", encoding="utf-8")

    def bootstrap(self):
        code, _ = run(["bash", str(EXAMPLES / "bootstrap.sh"), self.project.name],
                      cwd=EXAMPLES, env=self.env, timeout=30)
        require(code == 0, "Bootstrap failed; use a source checkout or a generated kit containing Maven wrappers")
        version = ET.parse(self.project / "pom.xml").getroot().findtext(
            "{http://maven.apache.org/POM/4.0.0}properties/{http://maven.apache.org/POM/4.0.0}bootui.version"
        )
        require(version == self.version,
                "POM and CLI versions must match; bootstrap source POMs with BOOTUI_VERSION set")

    def maven(self, stack, *goals):
        profiles = "bootui-ci" + (",webflux" if stack == "webflux" else "")
        command = [str(self.project / "mvnw"), "-B", "-ntp", "-P" + profiles,
                   "-Dbootui.version=" + self.version]
        repository = os.environ.get("BOOTUI_MAVEN_REPO_LOCAL")
        if repository:
            require(Path(repository).is_absolute(), "BOOTUI_MAVEN_REPO_LOCAL must be absolute")
            command.append("-Dmaven.repo.local=" + repository)
        return command + list(goals)

    def cli_run(self, args, timeout=240):
        return run(self.cli + ["ci"] + args, cwd=EXAMPLES, env=self.env, timeout=timeout)

    def stage(self, reports, name):
        """Copy only CLI-produced sanitized report files, never logs, witnesses, or arbitrary directories."""
        require(reports.resolve() == reports and reports.is_dir(), "Report directories must not be symlinks")
        contents = {}
        for filename, limit in REPORT_LIMITS.items():
            source = reports / filename
            require(stat.S_ISREG(source.lstat().st_mode), "Report is not a regular file: " + filename)
            require(source.stat().st_size <= limit, "Report exceeds upload bound: " + filename)
            with source.open("rb") as stream:
                content = stream.read(limit + 1)
            require(len(content) <= limit, "Report grew beyond upload bound")
            contents[filename] = content
        destination = self.artifacts / name
        destination.mkdir()
        for filename, content in contents.items():
            (destination / filename).write_bytes(content)

    def failure_reports(self, code):
        require(code in ("ATTACH_FAILED", "SETUP_FAILED", "ASSESSMENT_INCOMPLETE", "COLLECTION_INCOMPLETE"),
                "Unknown failure report code")
        directory = self.artifacts / "not-assessed"
        directory.mkdir()
        bundle = {"schemaVersion": 1, "assessmentId": self.assessment_id,
                  "status": "NOT_ASSESSED", "errors": [{"code": code}]}
        (directory / "bundle.json").write_text(json.dumps(bundle) + "\n", encoding="utf-8")
        (directory / "summary.md").write_text(
            "# BootUI assessment: NOT_ASSESSED\n\n" + code + "\n", encoding="utf-8")
        (directory / "junit.xml").write_text(
            '<testsuite name="bootui-ci" tests="1" failures="0" errors="1" skipped="0">'
            '<testcase name="assessment"><error message="Assessment not completed"/></testcase>'
            '</testsuite>\n', encoding="utf-8")
        notification = {"level": "error", "descriptor": {"id": code}, "message": {"text": "Assessment error: " + code + "."}}
        sarif = {"$schema": "https://json.schemastore.org/sarif-2.1.0.json", "version": "2.1.0", "runs": [{
            "tool": {"driver": {"name": "BootUI", "informationUri": "https://github.com/jdubois/boot-ui", "rules": []}},
            "invocations": [{"executionSuccessful": False, "toolExecutionNotifications": [notification]}],
            "results": [],
            "properties": {"assessmentId": self.assessment_id, "status": "NOT_ASSESSED", "policySha256": ""}}]}
        (directory / "sarif.json").write_text(json.dumps(sarif) + "\n", encoding="utf-8")

    def cleanup(self):
        # Never remove a user-specified tree. Only these two files were created by this run.
        for name in ("boot-ui.yml", "application-bootui.properties"):
            path = self.root / name
            if path.exists():
                require(not path.is_symlink() and path.is_file(), "Unexpected dismissal-state path")
                path.unlink()


def wait_ready(process, log_path, timeout=120):
    """Use the actual random port printed by the owned application, not a reserved-port guess."""
    from http.client import HTTPException
    from urllib.request import ProxyHandler, build_opener

    opener = build_opener(ProxyHandler({}))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        require(process.poll() is None, "Application exited before readiness; inspect the private application log")
        text = log_path.read_text(encoding="utf-8")
        match = re.search(r"^CI_EXAMPLE_PORT=(\d+)$", text, re.MULTILINE)
        if match:
            port = int(match.group(1))
            require(0 < port <= 65535, "Application reported an invalid port")
            url = "http://127.0.0.1:" + str(port)
            try:
                with opener.open(url + "/orders", timeout=2) as response:
                    if response.status == 200 and response.read(4097) == b'["order-1","order-2"]':
                        return url
            except (OSError, HTTPException):
                pass
        time.sleep(0.2)
    raise ValueError("Application readiness exceeded 120 seconds")


def workload(url):
    from urllib.request import ProxyHandler, build_opener

    opener = build_opener(ProxyHandler({}))
    for path, expected in (("orders", ["order-1", "order-2"]), ("catalog", ["book", "pen"])):
        with opener.open(url + "/" + path, timeout=10) as response:
            require(response.status == 200 and json.loads(response.read(4097)) == expected,
                    "Workload response did not match: " + path)
        print("CI_WORKLOAD_COMPLETED=" + path)
    require(os.environ.get("CI_EXAMPLE_FAIL_WORKLOAD") != "true", "Requested workload failure")
