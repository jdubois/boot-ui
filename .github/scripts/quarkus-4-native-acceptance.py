#!/usr/bin/env python3
"""Compile AND execute Quarkus 4 native acceptance on the actual target host.

No container compiler, emulation switch, skipped build, or source-only result is acceptance.
The fixture is opt-in, non-published test support. All app traffic stays on this host.
An optional absolute --npm-cache restores locked dependencies offline without inheriting npm configuration.
"""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import hashlib
import http.client
import http.server
import ipaddress
import json
import os
from pathlib import Path
import platform
import re
import select
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
from typing import Any
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "bootui-quarkus-4-integration-tests/native-console"
TARGETS = {
    "linux-x86_64": ("Linux", "x86_64"),
    "linux-arm64": ("Linux", "arm64"),
    "macos-arm64": ("Darwin", "arm64"),
}
PROTOCOL = "native-acceptance-v1"
APP_ROOT = "/acceptance"
HOST_PATH = APP_ROOT + "/native-fixture"
SECRET = "native-acceptance-known-secret"
TOKEN = "native-acceptance-test-token"
MCP_VERSION = "2026-07-28"
LEGACY_MCP_VERSION = "2025-06-18"
READ_LIMIT = 4 * 1024 * 1024
HTTP_TIMEOUT = 15


class AcceptanceFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def architecture(value: str) -> str:
    normalized = value.strip().lower()
    if normalized in ("x86_64", "amd64"):
        return "x86_64"
    if normalized in ("arm64", "aarch64"):
        return "arm64"
    raise AcceptanceFailure(f"Unknown architecture evidence: {value!r}")


def verify_host(target: str, evidence: dict[str, Any]) -> None:
    expected_os, expected_arch = TARGETS[target]
    require(evidence["system"] == expected_os, "The target OS does not match the real host")
    require(architecture(evidence["machine"]) == expected_arch, "The process architecture mismatches the target")
    require(evidence["bits"] == "64", "A 64-bit native host is required")
    require(evidence.get("processBits") == 64, "A known 64-bit native harness process is required")
    require(evidence.get("emulator") is False, "Emulation/translation is unknown or is not native acceptance")
    if expected_os == "Darwin":
        require(evidence["hw_optional_arm64"] == "1", "Apple Silicon CPU evidence is required")
        require(architecture(evidence["hw_machine"]) == "arm64", "Darwin hardware architecture mismatches")
        require(evidence["proc_translated"] == "0", "Rosetta status is unknown or translated")
        require("apple" in evidence["cpu_brand"].lower(), "An identifiable Apple Silicon host CPU is required")
        return
    cpuinfo = evidence["cpuinfo"]
    lscpu = evidence["lscpu"]
    require(architecture(lscpu.get("Architecture", "")) == expected_arch, "lscpu architecture mismatches")
    hypervisor = lscpu.get("Hypervisor vendor", "").lower()
    require(not any(word in hypervisor for word in ("qemu", "tcg")), "Emulated/unknown CPU hypervisor")
    if expected_arch == "x86_64":
        vendors = re.findall(r"^vendor_id\s*:\s*(\S+)", cpuinfo, re.MULTILINE)
        require(bool(vendors), "Missing independent x86 CPU evidence")
        require(set(vendors) <= {"GenuineIntel", "AuthenticAMD", "HygonGenuine"}, "Unknown physical x86 CPU")
        require(not re.search(r"^CPU (?:architecture|implementer)\s*:", cpuinfo, re.MULTILINE),
                "An x86 process/container is running on an ARM host")
    else:
        cpu_arches = re.findall(r"^CPU architecture\s*:\s*(\d+)", cpuinfo, re.MULTILINE)
        implementers = re.findall(r"^CPU implementer\s*:\s*(0x[0-9a-fA-F]+)", cpuinfo, re.MULTILINE)
        require(bool(cpu_arches) and all(int(value) >= 8 for value in cpu_arches),
                "Missing independent ARM64 CPU evidence")
        require(bool(implementers), "Missing ARM host CPU identity")
        require(not re.search(r"^vendor_id\s*:", cpuinfo, re.MULTILINE), "ARM process on an x86 CPU")
    require(not re.search(r"qemu|tcg|emulat", cpuinfo, re.IGNORECASE), "Emulated CPU evidence")


def verify_binary(path: Path, target: str) -> dict[str, Any]:
    with path.open("rb") as binary:
        data = binary.read(64)
    expected_os, expected_arch = TARGETS[target]
    if expected_os == "Linux":
        require(data[:4] == b"\x7fELF" and len(data) >= 20, "The native result is not ELF")
        require(data[4] == 2 and data[5] == 1, "The binary is not 64-bit little-endian ELF")
        machine = struct.unpack_from("<H", data, 18)[0]
        require(machine == (62 if expected_arch == "x86_64" else 183), "ELF architecture mismatches the target")
        binary_format = "ELF64"
    else:
        require(data[:4] == b"\xcf\xfa\xed\xfe" and len(data) >= 12, "The native result is not Mach-O64")
        require(struct.unpack_from("<I", data, 4)[0] == 0x0100000C, "Mach-O architecture is not ARM64")
        binary_format = "Mach-O64"
    require(os.access(path, os.X_OK), "The native result is not executable")
    return {"path": str(path), "sha256": sha256(path), "format": binary_format, "target": target}


class Journal:
    def __init__(self, output: Path, target: str, repository: Path):
        self.output = output
        self.data: dict[str, Any] = {
            "schema": PROTOCOL,
            "target": target,
            "mavenRepository": str(repository),
            "status": "running",
            "startedAt": time.time(),
            "commands": [],
            "builds": {},
            "cases": [],
            "limitations": ["HTTP/2 multiplexed sibling isolation is not exercised by this dependency-free harness"],
        }
        self.write()

    def write(self) -> None:
        staged = self.output / "acceptance-result.json.part"
        staged.write_text(json.dumps(self.data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        staged.replace(self.output / "acceptance-result.json")


def safe_environment(home: Path, java_home: Path, npm_cache: Path | None = None) -> dict[str, str]:
    # Ambient application flags, Java agents, Maven settings and exporters are not acceptance inputs.
    environment = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL", "SYSTEMROOT") if key in os.environ}
    home.mkdir(parents=True, exist_ok=True)
    scratch = home / "scratch"
    scratch.mkdir(exist_ok=True)
    environment.update({
        "LC_ALL": "C",
        "HOME": str(home),
        "JAVA_HOME": str(java_home),
        "GRAALVM_HOME": str(java_home),
        "TMPDIR": str(scratch),
        "TMP": str(scratch),
        "TEMP": str(scratch),
        "OTEL_TRACES_EXPORTER": "none",
        "OTEL_METRICS_EXPORTER": "none",
        "OTEL_LOGS_EXPORTER": "none",
        "BOOTUI_NO_UPDATE_CHECK": "1",
        "JAVA_TOOL_OPTIONS": "-XX:ActiveProcessorCount=2",
    })
    if npm_cache is not None:
        environment["NPM_CONFIG_CACHE"] = str(npm_cache)
        environment["NPM_CONFIG_OFFLINE"] = "true"
    return environment


class Commands:
    def __init__(self, journal: Journal, environment: dict[str, str]):
        self.journal = journal
        self.environment = environment

    @contextlib.contextmanager
    def process(self, name: str, command: list[str], cwd: Path, environment: dict[str, str] | None = None):
        folder = self.journal.output / "commands"
        folder.mkdir(exist_ok=True)
        stdout_path = folder / (name + ".out")
        stderr_path = folder / (name + ".err")
        entry: dict[str, Any] = {
            "name": name, "argv": command, "cwd": str(cwd),
            "stdout": str(stdout_path), "stderr": str(stderr_path), "startedAt": time.time(),
        }
        self.journal.data["commands"].append(entry)
        self.journal.write()
        with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
            process = subprocess.Popen(command, cwd=cwd, env=environment or self.environment,
                                       stdout=stdout, stderr=stderr, start_new_session=True)
            # This is a newly owned session/group, never a host-wide name or a caller's process group.
            group = process.pid
            self.verify_owned_group(process, group)
            entry["pid"] = process.pid
            try:
                yield process, entry
            finally:
                self.stop(process, group)
                entry["exitCode"] = process.returncode
                entry["completedAt"] = time.time()
                self.journal.write()

    @staticmethod
    def exited(process: subprocess.Popen) -> bool:
        # WNOWAIT retains the child's PID until tree cleanup has finished. A reaped leader's numeric
        # process-group id could otherwise be reused by unrelated work on this shared host.
        require(hasattr(os, "waitid") and hasattr(os, "WNOWAIT"), "Non-reaping POSIX child observation is required")
        return os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None

    @staticmethod
    def wait_owned(process: subprocess.Popen, timeout: int) -> int:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            if result is not None:
                return result.si_status if result.si_code == os.CLD_EXITED else -result.si_status
            time.sleep(0.05)
        raise subprocess.TimeoutExpired(process.args, timeout)

    @staticmethod
    def verify_owned_group(process: subprocess.Popen, group: int) -> None:
        require(group == process.pid and group != os.getpgrp(), "Refusing to signal an unowned process group")
        try:
            require(os.getpgid(process.pid) == group, "Subprocess group ownership changed")
        except ProcessLookupError:
            # Darwin no longer reports the group of an exited leader, even while WNOWAIT reserves its PID.
            require(Commands.exited(process), "Subprocess group is missing while its leader is still running")

    @staticmethod
    def signal_owned_group(process: subprocess.Popen, group: int, sig: int) -> None:
        try:
            os.killpg(group, sig)
        except ProcessLookupError:
            require(Commands.exited(process), "Subprocess group disappeared while its leader is still running")
        except PermissionError:
            # Darwin returns EPERM, rather than ESRCH, for an empty group whose unreaped leader has exited.
            require(platform.system() == "Darwin" and Commands.exited(process),
                    "Permission denied while signalling an owned subprocess group")
            members = subprocess.run(["ps", "-o", "pid=,stat=", "-g", str(group)], capture_output=True,
                                     text=True, timeout=5)
            require(members.returncode in (0, 1) and not members.stderr,
                    "Cannot verify that the exited subprocess group is empty")
            for member in members.stdout.splitlines():
                fields = member.split()
                require(len(fields) == 2 and fields[0].isdigit() and fields[1].startswith("Z"),
                        "Permission denied while subprocess group still has live members")

    @staticmethod
    def stop(process: subprocess.Popen, group: int) -> None:
        Commands.verify_owned_group(process, group)
        Commands.signal_owned_group(process, group, signal.SIGTERM)
        try:
            Commands.wait_owned(process, timeout=5)
        except subprocess.TimeoutExpired:
            Commands.signal_owned_group(process, group, signal.SIGKILL)
        # Kill remaining compiler/server descendants BEFORE reaping the reserved group leader PID.
        Commands.signal_owned_group(process, group, signal.SIGKILL)
        process.wait(timeout=5)

    def run(self, name: str, command: list[str], timeout: int = 30, cwd: Path = ROOT,
            expected: int = 0, environment: dict[str, str] | None = None) -> str:
        with self.process(name, command, cwd, environment) as (process, entry):
            try:
                status = self.wait_owned(process, timeout=timeout)
            except subprocess.TimeoutExpired as failure:
                entry["timedOut"] = True
                raise AcceptanceFailure(f"{name} exceeded its bounded {timeout}s timeout") from failure
            require(status == expected, f"{name} exited {status}, expected {expected}; see command logs")
        return Path(entry["stdout"]).read_text(encoding="utf-8") + Path(entry["stderr"]).read_text(encoding="utf-8")


def collect_host(commands: Commands, target: str) -> dict[str, Any]:
    evidence: dict[str, Any] = {
        "system": platform.system(), "machine": platform.machine(),
        "processBits": struct.calcsize("P") * 8,
        "bits": commands.run("host-bits", ["getconf", "LONG_BIT"]).strip(),
        "emulator": any(key.startswith("QEMU_") for key in os.environ),
    }
    if evidence["system"] == "Darwin":
        for key, oid in (("hw_machine", "hw.machine"), ("hw_optional_arm64", "hw.optional.arm64"),
                         ("proc_translated", "sysctl.proc_translated"), ("cpu_brand", "machdep.cpu.brand_string")):
            evidence[key] = commands.run("host-" + key, ["sysctl", "-n", oid]).strip()
    elif evidence["system"] == "Linux":
        evidence["cpuinfo"] = Path("/proc/cpuinfo").read_text(encoding="utf-8")
        evidence["emulator"] |= "qemu" in Path("/proc/self/maps").read_text(encoding="utf-8").lower()
        raw = json.loads(commands.run("host-lscpu", ["lscpu", "--json"]))
        evidence["lscpu"] = {item["field"].rstrip(":"): str(item["data"]).strip() for item in raw["lscpu"]}
    commands.journal.data["host"] = evidence
    commands.journal.write()
    verify_host(target, evidence)
    return evidence


@dataclasses.dataclass(frozen=True)
class Case:
    name: str
    active: bool = False
    properties: tuple[tuple[str, str], ...] = ()
    environment: tuple[tuple[str, str], ...] = ()
    file_properties: tuple[tuple[str, str], ...] = ()
    ui: str = "/console"
    api: str = "/diagnostics"
    all_interfaces: bool = False
    policy: str = "normal"


def activation_cases() -> list[Case]:
    cases = [
        Case("no-live-flags-after-local-on-build"),
        Case("system-on-local", True, (("bootui.enabled", "ON"), ("quarkus.profile", "local"))),
        Case("environment-on-local", True, environment=(("BOOTUI_ENABLED", "ON"), ("QUARKUS_PROFILE", "local"))),
        Case("on-without-local", properties=(("bootui.enabled", "ON"),)),
        Case("local-without-on", properties=(("quarkus.profile", "local"),)),
        Case("file-only-on-local", file_properties=(("bootui.enabled", "ON"), ("quarkus.profile", "local"))),
        Case("file-on-live-local", properties=(("quarkus.profile", "local"),),
             file_properties=(("bootui.enabled", "ON"),)),
        Case("live-on-file-local", properties=(("bootui.enabled", "ON"),),
             file_properties=(("quarkus.profile", "local"),)),
        Case("expression-only-mode", properties=(("bootui.enabled", "${NATIVE_ACCEPTANCE_MODE}"),
                                                 ("quarkus.profile", "local")),
             environment=(("NATIVE_ACCEPTANCE_MODE", "ON"),)),
        Case("expression-only-profile", properties=(("bootui.enabled", "ON"),
                                                    ("quarkus.profile", "${NATIVE_ACCEPTANCE_PROFILE}")),
             environment=(("NATIVE_ACCEPTANCE_PROFILE", "local"),)),
        Case("system-on-over-environment-off", True,
             properties=(("bootui.enabled", "ON"), ("quarkus.profile", "local")),
             environment=(("BOOTUI_ENABLED", "OFF"),)),
        Case("system-local-over-environment-production", True,
             properties=(("bootui.enabled", "ON"), ("quarkus.profile", "local")),
             environment=(("QUARKUS_PROFILE", "production"),)),
        Case("system-off-over-environment-on",
             properties=(("bootui.enabled", "OFF"), ("quarkus.profile", "local")),
             environment=(("BOOTUI_ENABLED", "ON"), ("QUARKUS_PROFILE", "local"))),
        Case("system-production-over-environment-local",
             properties=(("bootui.enabled", "ON"), ("quarkus.profile", "production")),
             environment=(("BOOTUI_ENABLED", "ON"), ("QUARKUS_PROFILE", "local"))),
        Case("inherited-local-only", properties=(("bootui.enabled", "ON"), ("quarkus.profile", "staging"),
                                                ("quarkus.config.profile.parent", "local"))),
        Case("multi-level-production-parent", properties=(("bootui.enabled", "ON"), ("quarkus.profile", "local"),
                                                          ("quarkus.config.profile.parent", "staging"),
                                                          ("%staging.quarkus.config.profile.parent", "production"))),
        Case("multi-level-file-production-parent",
             properties=(("bootui.enabled", "ON"), ("quarkus.profile", "local")),
             file_properties=(("quarkus.config.profile.parent", "staging"),
                              ("%staging.quarkus.config.profile.parent", "prod"))),
        Case("default-ui-and-api", True, (("bootui.enabled", "ON"), ("quarkus.profile", "local")),
             ui="/bootui", api="/bootui/api"),
        Case("all-interfaces-safety", True, (("bootui.enabled", "ON"), ("quarkus.profile", "local")),
             all_interfaces=True),
        Case("all-interfaces-token-policy", True, (("bootui.enabled", "ON"), ("quarkus.profile", "local"),
                                                  ("bootui.allow-non-localhost", "true")),
             all_interfaces=True, policy="token"),
        Case("read-only-policy", True, (("bootui.enabled", "ON"), ("quarkus.profile", "local"),
                                       ("bootui.read-only", "true")), policy="read-only"),
        Case("disabled-panel-policy", True, (("bootui.enabled", "ON"), ("quarkus.profile", "local"),
                                            ("bootui.panels.sql-trace.enabled", "false")), policy="disabled"),
    ]
    for index, mode in enumerate((" ON ", "on")):
        cases.append(Case(f"system-normalized-on-{index}", True,
                          properties=(("bootui.enabled", mode), ("quarkus.profile", "local"))))
        cases.append(Case(f"environment-normalized-on-{index}", True,
                          environment=(("BOOTUI_ENABLED", mode), ("QUARKUS_PROFILE", "local"))))
    for index, profile in enumerate(("local,", ",local")):
        cases.append(Case(f"system-local-profile-separator-{index}", True,
                          properties=(("bootui.enabled", "ON"), ("quarkus.profile", profile))))
        cases.append(Case(f"environment-local-profile-separator-{index}", True,
                          environment=(("BOOTUI_ENABLED", "ON"), ("QUARKUS_PROFILE", profile))))
    for index, mode in enumerate(("OFF", "AUTO", "", "invalid", "ON,OFF", "${missing}")):
        cases.append(Case(f"system-denied-mode-{index}",
                          properties=(("bootui.enabled", mode), ("quarkus.profile", "local"))))
        cases.append(Case(f"environment-denied-mode-{index}",
                          environment=(("BOOTUI_ENABLED", mode), ("QUARKUS_PROFILE", "local"))))
    for index, profile in enumerate(("prod", "production", "local,prod", "prod,local", "local,production",
                                     "production,local", "local,,prod", "", "local,${missing}")):
        cases.append(Case(f"system-denied-profile-{index}",
                          properties=(("bootui.enabled", "ON"), ("quarkus.profile", profile))))
        cases.append(Case(f"environment-denied-profile-{index}",
                          environment=(("BOOTUI_ENABLED", "ON"), ("QUARKUS_PROFILE", profile))))
    for production in ("prod", "production"):
        cases.append(Case("parent-" + production, properties=(("bootui.enabled", "ON"), ("quarkus.profile", "local"),
                                                              ("quarkus.config.profile.parent", production))))
    return cases


def maven_command(repository: Path, *arguments: str) -> list[str]:
    require(repository.is_absolute(), "The Maven repository must be absolute")
    return [str(ROOT / "mvnw"), "-B", "-ntp", f"-Dmaven.repo.local={repository}",
            "-DskipTests", "-Dbootui.skipAgentIts", *arguments]


def compile_fixture(commands: Commands, repository: Path, target: str, variant: str, timeout: int,
                    fixture: str = "jdbc") -> Path:
    require(fixture in ("jdbc", "hibernate"), "Unknown native acceptance fixture")
    build = commands.journal.output / ("build-" + variant)
    require(not build.exists(), f"Refusing stale/reused build directory: {build}")
    native = variant != "jvm"
    args = ["-f", str(FIXTURE / "pom.xml"), f"-Dnative.acceptance.build-directory={build}",
            f"-Dbootui.native-console.enabled={'false' if variant == 'excluded' else 'true'}",
            "-Dbootui.enabled=ON", "-Dquarkus.profile=local",
            f"-Dquarkus.native.enabled={str(native).lower()}",
            "-Dquarkus.native.container-build=false", "-Dquarkus.native.sources-only=false",
            f"-Dquarkus.package.jar.enabled={str(not native).lower()}", "package"]
    if native:
        args[-1:-1] = ["-Dquarkus.native.native-image-xmx=6g",
                       "-Dquarkus.native.additional-build-args=--parallelism=2"]
    if fixture == "hibernate":
        args[-1:-1] = ["-Pnative-orm"]
    commands.run("build-" + variant, maven_command(repository, *args), timeout=timeout)
    if native:
        binaries = list(build.glob("*-runner"))
        require(len(binaries) == 1, f"{variant}: native compilation did not produce exactly one binary")
        path = binaries[0]
        identity = verify_binary(path, target)
    else:
        path = build / "quarkus-app/quarkus-run.jar"
        require(path.is_file(), "Packaged JVM fixture was not produced")
        identity = {"path": str(path), "sha256": sha256(path), "format": "packaged-JVM"}
    commands.journal.data["builds"][variant] = identity
    commands.journal.write()
    return path


def published_cli_pin() -> tuple[str, str]:
    pin = (ROOT / ".github/scripts/published-cli-smoke.sh").read_text(encoding="utf-8")
    version = re.search(r'^readonly PUBLISHED_CLI_VERSION="([^"]+)"$', pin, re.MULTILINE)
    checksum = re.search(r'^readonly PUBLISHED_CLI_SHA256="([0-9a-f]{64})"$', pin, re.MULTILINE)
    require(version is not None and checksum is not None, "The published CLI identity must have an existing pin")
    return version.group(1), checksum.group(1)


def obtain_cli(commands: Commands, repository: Path) -> Path:
    version, checksum = published_cli_pin()
    directory = repository / f"com/julien-dubois/bootui/bootui-cli/{version}"
    installed = directory / f"bootui-cli-{version}-all.jar"
    target = commands.journal.output / installed.name
    if installed.is_file() and sha256(installed) == checksum:
        shutil.copy2(installed, target)
    else:
        # This is an explicit acceptance download, not application/page-load work. Source-build jars
        # with the same version are deliberately not accepted as the published-client witness.
        url = f"https://repo1.maven.org/maven2/com/julien-dubois/bootui/bootui-cli/{version}/{installed.name}"
        commands.run("download-published-cli",
                     ["curl", "--fail", "--location", "--connect-timeout", "30", "--max-time", "300",
                      "--proto", "=https", "--proto-redir", "=https", "--output", str(target), url], timeout=330)
    require(sha256(target) == checksum, "The CLI does not match the published SHA-256 pin")
    commands.journal.data["publishedCli"] = {"version": version, "sha256": checksum, "path": str(target)}
    commands.journal.write()
    return target


def bundled_assets(repository: Path) -> list[str]:
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = ET.parse(ROOT / "pom.xml").findtext("m:version", namespaces=namespace)
    require(bool(version), "Cannot resolve the source UI version")
    jar = repository / f"com/julien-dubois/bootui/bootui-ui/{version}/bootui-ui-{version}.jar"
    require(jar.is_file(), "The isolated repository has no built UI artifact")
    prefix = "META-INF/resources/bootui/"
    with zipfile.ZipFile(jar) as bundle:
        assets = sorted(name[len(prefix):] for name in bundle.namelist()
                        if name.startswith(prefix + "assets/") and name.endswith((".js", ".css")))
    require(any(name.endswith(".js") for name in assets) and any(name.endswith(".css") for name in assets),
            "The built UI must contain real JavaScript and CSS assets")
    return assets


def compile_http2_client(commands: Commands, binary: Path, java_home: Path) -> dict:
    libraries = list(binary.parent.glob("*-native-image-source-jar/lib"))
    require(len(libraries) == 1, "The native build must retain one managed client library directory")
    repository = Path(commands.journal.data["mavenRepository"])
    originals = []
    for artifact in ("netty-common", "netty-transport"):
        prefix = f"io.netty.{artifact}-"
        versions = {match.group(1) for path in libraries[0].glob("*" + prefix + "*.jar")
                    if (match := re.fullmatch(r"(?:modified-)?" + re.escape(prefix)
                                              + r"([0-9][A-Za-z0-9.+-]*)\.jar", path.name))}
        require(len(versions) == 1, f"Cannot identify the managed {artifact} version")
        version = next(iter(versions))
        original = repository / f"io/netty/{artifact}/{version}/{artifact}-{version}.jar"
        require(original.is_file(), f"Missing original managed client library: {original}")
        originals.append(original)
    classes = commands.journal.output / "http2-client"
    classes.mkdir()
    source = ROOT / ".github/scripts/Quarkus4NativeHttp2Witness.java"
    classpath = os.pathsep.join([*(str(path) for path in originals), str(libraries[0] / "*"), str(classes)])
    commands.run("compile-http2-client",
                 [str(java_home / "bin/javac"), "-J-Xmx256m", "-J-XX:ActiveProcessorCount=2",
                  "--release", "21", "-cp", classpath, "-d", str(classes), str(source)], timeout=120)
    result = {"classpath": classpath, "sourceSha256": sha256(source),
              "classSha256": sha256(classes / "Quarkus4NativeHttp2Witness.class"),
              "originalManagedLibraries": [{"path": str(path), "sha256": sha256(path)} for path in originals]}
    commands.journal.data["http2Client"] = result
    commands.journal.write()
    return result


class Client:
    def __init__(self, port: int, folder: Path, host: str = "127.0.0.1"):
        self.port, self.folder, self.host = port, folder, host
        self.sequence = 0
        self.requests: list[dict[str, Any]] = []

    def request(self, path: str, method: str = "GET", payload: Any = None,
                headers: dict[str, str] | None = None, expected: int | None = None) -> tuple[int, dict, bytes]:
        self.sequence += 1
        name = f"http-{self.sequence:04d}"
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        sent = {"Host": f"127.0.0.1:{self.port}", "Accept": "application/json", **(headers or {})}
        if body is not None:
            sent["Content-Type"] = "application/json"
        connection = http.client.HTTPConnection(self.host, self.port, timeout=HTTP_TIMEOUT)
        try:
            connection.request(method, path, body=body, headers=sent)
            response = connection.getresponse()
            data = response.read(READ_LIMIT + 1)
            status = response.status
            received = {name.lower(): value for name, value in response.getheaders()}
        finally:
            connection.close()
        require(len(data) <= READ_LIMIT, "The acceptance HTTP response exceeded its bound")
        (self.folder / (name + ".body")).write_bytes(data)
        entry = {"method": method, "path": path, "headers": sent, "payload": payload,
                 "status": status, "responseHeaders": received, "body": name + ".body"}
        self.requests.append(entry)
        (self.folder / "http-results.json").write_text(json.dumps(self.requests, indent=2) + "\n", encoding="utf-8")
        if expected is not None:
            require(status == expected, f"{method} {path} returned {status}, expected {expected}")
        return status, received, data

    def json(self, path: str, method: str = "GET", payload: Any = None,
             headers: dict[str, str] | None = None, expected: int = 200) -> Any:
        _, received, body = self.request(path, method, payload, headers, expected)
        require("application/json" in received.get("content-type", ""), f"{path} is not JSON")
        require(SECRET.encode() not in body, f"{path} exposed the known secret")
        return json.loads(body)

    def probe(self) -> dict:
        value = self.json(HOST_PATH + "/probe")
        require(value.get("protocol") == PROTOCOL, "The fixture probe protocol mismatches")
        return value

    def rpc(self, api: str, method: str, params: dict | None = None, identifier: int = 1) -> dict:
        supplied = dict(params or {})
        supplied["_meta"] = {"io.modelcontextprotocol/protocolVersion": MCP_VERSION,
                             "io.modelcontextprotocol/clientCapabilities": {}}
        request = {"jsonrpc": "2.0", "id": identifier, "method": method, "params": supplied}
        headers = {"MCP-Protocol-Version": MCP_VERSION, "Mcp-Method": method, "Accept": "application/json"}
        if "name" in supplied:
            headers["Mcp-Name"] = supplied["name"]
        return self.json(APP_ROOT + api + "/mcp", "POST", request,
                         headers)


class LoopbackOsvFixture:
    def __init__(self, folder: Path):
        self.folder = folder
        self.requests = []
        self.package = None
        self.fail_batch = False
        self.hold_batch = False
        self.state_lock = threading.RLock()
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def setup(self):
                super().setup()
                self.connection.settimeout(5)

            def log_message(self, _format, *_arguments):
                pass

            def reply(self, status, value, entry=None):
                body = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
                if entry is not None:
                    fixture.observe(entry, responded=True)

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                require(0 < length <= 1024 * 1024, "OSV fixture request exceeded its bound")
                payload = json.loads(self.rfile.read(length))
                with fixture.state_lock:
                    entry = {"method": "POST", "path": self.path, "payload": payload,
                             "id": 1 + sum(item["method"] == "POST" and item["path"] == "/v1/querybatch"
                                           for item in fixture.requests),
                             "held": False, "disconnected": False, "released": False,
                             "responded": False, "holdTimedOut": False}
                    fixture.requests.append(entry)
                    fixture.observe()
                if self.path != "/v1/querybatch":
                    self.reply(404, {"error": "Unknown OSV fixture path"})
                    return
                if fixture.fail_batch:
                    self.reply(503, {"error": "Deliberate bounded OSV fixture failure"}, entry)
                    return
                if fixture.hold_batch:
                    fixture.observe(entry, held=True)
                    deadline = time.monotonic() + 8
                    while fixture.hold_batch and time.monotonic() < deadline:
                        if select.select([self.connection], [], [], 0.05)[0]:
                            try:
                                disconnected = not self.connection.recv(1, socket.MSG_PEEK)
                            except ConnectionResetError:
                                disconnected = True
                            require(disconnected, "Unexpected pipelined OSV fixture request")
                            fixture.observe(entry, disconnected=True)
                            return
                    if fixture.hold_batch:
                        fixture.observe(entry, holdTimedOut=True)
                        self.reply(503, {"error": "Bounded OSV fixture hold expired"}, entry)
                        return
                    fixture.observe(entry, released=True)
                queries = payload["queries"]
                require(0 < len(queries) <= 2, "Native scanner ignored its configured package bound")
                fixture.package = queries[0]
                self.reply(200, {"results": [{"vulns": [{"id": "NATIVE-OSV-WITNESS"}]}]
                           + [{} for _ in queries[1:]]}, entry)

            def do_GET(self):
                fixture.requests.append({"method": "GET", "path": self.path})
                if self.path != "/v1/vulns/NATIVE-OSV-WITNESS" or fixture.package is None:
                    self.reply(404, {"error": "Unknown OSV fixture advisory"})
                    return
                self.reply(200, {
                    "id": "NATIVE-OSV-WITNESS", "summary": "Native OSV advisory witness",
                    "affected": [{"package": fixture.package["package"],
                                  "versions": [fixture.package["version"]]}],
                    "database_specific": {"severity": "HIGH"},
                })

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = False
        self.base_uri = f"http://127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05},
                                       daemon=True, name="native-acceptance-osv-fixture")
        self.observe()

    def observe(self, entry=None, **changes):
        with self.state_lock:
            if entry is not None:
                entry.update(changes)
            state = {"schema": "native-http2-osv-v1", "baseUri": self.base_uri,
                     "batches": [dict(item) for item in self.requests
                                 if item["method"] == "POST" and item["path"] == "/v1/querybatch"]}
            staged = self.folder / "state.json.part"
            staged.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
            staged.replace(self.folder / "state.json")

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, _type, _value, _traceback):
        self.hold_batch = False
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        (self.folder / "osv-requests.json").write_text(json.dumps(self.requests, indent=2) + "\n")
        require(not self.thread.is_alive(), "Owned OSV fixture did not stop")


def wait_until(description: str, predicate, timeout: int = 20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.1)
    raise AcceptanceFailure(f"Timed out waiting for {description}")


def assert_dormant(snapshot: dict) -> None:
    require(snapshot.get("protocol") == PROTOCOL, "No truthful dormancy probe")
    require(snapshot["ownedThreads"] == [], "A denied console started BootUI-owned threads")
    require(snapshot["installedHandlers"] == [], "A denied console installed BootUI JUL handlers")
    require(set(snapshot["counters"]) == {"http", "logs", "sql", "exceptions", "telemetry", "journal"},
            "Missing a capture dormancy counter")
    for name, counter in snapshot["counters"].items():
        require(counter["state"] in ("absent", "uninstantiated", "instantiated"), f"Unknown {name} observation")
        if counter["state"] == "instantiated":
            require(counter["value"] == 0, f"Denied console captured {name} evidence")
            # These stores have no public subscriber-count API. Requiring that they were never
            # created proves absence without reflection hints or fabricating an unsupported zero.
            require(name == "logs", f"Cannot prove dormant subscriptions of an instantiated {name} store")
        else:
            require(counter["value"] is None, f"Unobserved {name} evidence must not be reported as zero")
    subscriptions = snapshot["logSubscriptions"]
    require(subscriptions["state"] in ("absent", "uninstantiated")
            or (subscriptions["state"] == "instantiated" and subscriptions["value"] == 0),
            "Denied console retained log-tail subscriptions")


def host_traffic(client: Client, fixture: str = "jdbc") -> None:
    witness = client.json(HOST_PATH + "/witness")
    require(witness == {"name": "native-acceptance",
                        "detail": {"kind": "immutable-record", "items": [{"id": 7, "label": "nested"}]}},
            "Nested host record/Jackson 3 serialization failed")
    jdbc = client.json(HOST_PATH + "/jdbc")
    require(jdbc["value"] == 42 and jdbc["autoCommit"] is True, "The actual JDBC operation failed")
    if fixture == "hibernate":
        require(client.json(HOST_PATH + "/orm") == {"unit": "default", "entities": 0},
                "The default ORM unit failed its real query")
        require(client.json(HOST_PATH + "/orm/users") ==
                {"unit": "users", "entities": 0, "hostListenerInvoked": True},
                "The named ORM unit failed or BootUI replaced its host listener")
        client.request(HOST_PATH + "/validation", expected=500)
    client.request(HOST_PATH + "/failure", expected=500)
    require(client.json(HOST_PATH + "/health")["status"] == "UP", "The host did not survive error traffic")
    require(client.probe()["hostSpans"] > 0, "BootUI displaced or broke the host-owned telemetry processor")


def orm_contract(client: Client, prefix: str, exchanges: dict, sql: dict | None) -> None:
    requests = {}
    for unit, path in (("default", "/native-fixture/orm"), ("users", "/native-fixture/orm/users")):
        request = next((entry for entry in exchanges["exchanges"] if entry["path"].endswith(path)), None)
        require(request and request["status"] == 200 and request.get("requestId"),
                f"{unit}: the real ORM HTTP request was not captured")
        requests[unit] = request["requestId"]
        if sql is not None and unit == "default":
            require(any("NATIVE_ORM_WITNESS" in entry["sql"].upper() and entry["success"]
                        and entry.get("requestId") == request["requestId"] for entry in sql["entries"]),
                    f"{unit}: ORM-generated SQL did not correlate to its actual request")

    count_before = client.json(prefix + "/activity/journal")["recorded"].get("orm", 0)
    require(client.json(HOST_PATH + "/orm") == {"unit": "default", "entities": 0},
            "The default ORM unit failed its real capture query")
    wait_until("default-unit ORM listener recorded a native session",
               lambda: client.json(prefix + "/activity/journal")["recorded"].get("orm", 0) == count_before + 1)
    require(client.json(HOST_PATH + "/orm/users") ==
            {"unit": "users", "entities": 0, "hostListenerInvoked": True},
            "BootUI replaced the named unit's explicitly configured host listener")
    require(client.json(prefix + "/activity/journal")["recorded"].get("orm", 0) == count_before + 1,
            "BootUI recorded an ORM session instead of preserving the named host listener")

    panel = next(panel for panel in client.json(prefix + "/panels")["panels"] if panel["id"] == "hibernate")
    profile_available = panel["available"] and panel["enabled"]
    path = prefix + "/activity/request/" + requests["default"] + "/journal"
    wait_until("default-unit request journal recorded", lambda: client.json(path).get("available"))
    profile = client.json(path)
    if profile_available:
        orm = profile.get("orm")
        require(orm and orm["sessions"] > 0 and orm["statements"] > 0,
                "The available Hibernate panel did not expose its recorded ORM session")
    else:
        require(panel["available"] or panel.get("unavailableReason"),
                "The native Hibernate panel has no honest unavailable reason")
        require(profile.get("orm") is None,
                "An unavailable or disabled Hibernate panel exposed ORM session evidence")
        require(not any(entry["type"] == "ORM"
                        for entry in client.json(prefix + "/activity?source=journal")["entries"]),
                "An unavailable or disabled Hibernate panel exposed ORM feed evidence")
    path = prefix + "/activity/request/" + requests["users"] + "/journal"
    wait_until("named-unit request journal recorded", lambda: client.json(path).get("available"))
    require(client.json(path).get("orm") is None,
            "BootUI replaced the named unit's explicitly configured host listener")
    (client.folder / "orm-result.json").write_text(json.dumps({
        "status": "passed", "defaultRequestId": requests["default"], "namedRequestId": requests["users"],
        "defaultSessionRecorded": True, "namedHostListenerPreserved": True,
        "sessionProfileAvailable": profile_available, "ormCaptureEvidence": "One real default query adds one recorded ORM session",
        "sessionProfilePolicy": "Hibernate-owned evidence stays hidden when that panel is unavailable or disabled",
        "sqlCapture": "Default persistence-unit preparations only; named-unit SQL capture is not supported",
    }, indent=2) + "\n", encoding="utf-8")


def validation_contract(client: Client, prefix: str, exceptions: dict) -> None:
    group = next((entry for entry in exceptions["groups"]
                  if "native-validation-witness" in (entry.get("message") or "")), None)
    require(group is not None, "The real optional Bean Validation failure was not captured")
    detail = client.json(prefix + "/exceptions/" + group["id"])
    diagnostics = [item for occurrence in detail["occurrences"] for item in occurrence["bindingDiagnostics"]]
    require({"targetType": "NativeValidationWitness", "fieldPath": "value", "reason": "Pattern"} in diagnostics,
            "The optional validation reader did not retain metadata-only binding diagnostics")


def deny_surfaces(client: Client, case: Case, assets: list[str]) -> None:
    paths = {
        APP_ROOT + case.ui, APP_ROOT + case.ui + "/", APP_ROOT + case.ui + "/index.html",
        APP_ROOT + case.api + "/overview", APP_ROOT + case.api + "/panels",
        APP_ROOT + case.api + "/cli", APP_ROOT + case.api + "/mcp", APP_ROOT + case.api + "/mcp-server",
        "/bootui/", "/bootui/index.html", APP_ROOT + "/bootui/", APP_ROOT + "/bootui/index.html",
    }
    for mount in ("/bootui/", APP_ROOT + "/bootui/", APP_ROOT + case.ui + "/"):
        paths.update(mount + asset for asset in assets)
    paths.update(APP_ROOT + case.api + suffix for suffix in
                 ("/log-tail/stream", "/sql-trace/stream", "/exceptions/stream", "/activity/stream",
                  "/activity/journal", "/config", "/beans", "/mappings", "/traces"))
    for path in sorted(paths):
        client.request(path, expected=404)
    client.request(APP_ROOT + case.api + "/mcp", "POST", {"jsonrpc": "2.0", "id": 1, "method": "initialize"},
                   expected=404)
    client.request(APP_ROOT + case.api + "/cli/tools/get_overview", "POST", {}, expected=404)
    for path in {APP_ROOT + case.api + "/http-probe", "/bootui/api/http-probe",
                 APP_ROOT + "/bootui/api/http-probe"}:
        client.request(path, "POST", {"method": "GET", "path": HOST_PATH + "/probe",
                                     "body": None, "headers": {"Accept": "application/json"}}, expected=404)


# Explicit contracts: availability is not permission to silently skip an unimplemented native panel.
# Deep positive witnesses below supplement these root contract checks.
PANEL_CONTRACTS = {
    "overview": ("/overview", ("applicationName", "serverPort")),
    "health": ("/health", ("status",)),
    "config": ("/config", ("properties", "activeProfiles")),
    "profile-diff": ("/profile-diff", ("activeProfiles", "profileSources")),
    "loggers": ("/loggers", ("loggers",)),
    "beans": ("/beans", ("beans", "page")),
    "mappings": ("/mappings/flat", ("mappings", "page")),
    "database-connection-pools": ("/database-connection-pools/pools", ("pools", "poolLibraryPresent")),
    "traces": ("/traces", ("traces",)),
    "log-tail": ("/log-tail/recent", ()),
    "exceptions": ("/exceptions", ("groups", "totalExceptions")),
    "http-exchanges": ("/http-exchanges", ("exchanges", "retention")),
    "http-probe": ("/http-probe", ("status", "statusText", "headers", "body", "durationMs", "error", "truncated")),
    "sql-trace": ("/sql-trace", ("entries", "stats")),
    "mcp-server": ("/mcp-server", ("tools", "enabled")),
    "cli": ("/cli", ("tools", "enabled")),
    "activity": ("/activity", ("entries",)),
    "ai": ("/ai/overview", ("enabled", "aiFrameworkDetected", "recent")),
    "vulnerabilities": ("/vulnerabilities", ("dependencies",)),
    "dev-services": ("/dev-services", ("services",)),
    "copilot": ("/copilot/sessions", ("available", "sessions")),
    "claude-code": ("/claude-code/sessions", ("available", "sessions")),
}
REQUIRED_PANELS = {"overview", "config", "beans", "mappings", "database-connection-pools", "log-tail",
                   "http-exchanges", "http-probe", "sql-trace", "exceptions", "traces", "mcp-server", "cli"}
READ_TOOL_CONTRACTS = {
    "get_overview": "overview", "get_health": "health", "get_config": "config", "get_beans": "beans",
    "get_mappings": "mappings", "get_loggers": "loggers", "get_database_connection_pools": "database-connection-pools",
    "get_traces": "traces", "get_log_tail": "log-tail", "get_exceptions": "exceptions",
    "get_http_exchanges": "http-exchanges", "get_sql_traces": "sql-trace", "get_profile_diff": "profile-diff",
    "get_live_activity": "activity", "get_ai_overview": "ai", "get_vulnerabilities_report": "vulnerabilities",
    "get_dev_services": "dev-services", "get_copilot_sessions": "copilot", "get_claude_code_sessions": "claude-code",
    "get_request_profile": "activity", "get_exception_detail": "exceptions", "get_http_routes": "http-exchanges",
}
ACTION_TOOL_CONTRACTS = {
    "clear_sql_traces": "sql-trace", "pause_sql_trace_recording": "sql-trace",
    "resume_sql_trace_recording": "sql-trace", "clear_exceptions": "exceptions", "clear_traces": "traces",
    "vulnerabilities_scan": "vulnerabilities",
}
TOOL_SHAPES = {
    "get_log_tail": ("entries", "page"),
    "get_request_profile": ("available", "source", "journal", "buffers"),
    "get_exception_detail": ("group", "frames", "occurrences"),
    "get_http_routes": ("routes",),
    "vulnerabilities_scan": ("scan", "findingsFound", "severityCounts", "topFindings", "moreFindings",
                             "evidence", "dependencies", "scanningEnabled", "coverage"),
}


def check_manifest(manifest: dict) -> dict[str, dict]:
    require(manifest.get("platform") == "quarkus", "The native panel platform discriminator changed")
    panels = {panel["id"]: panel for panel in manifest["panels"]}
    require(len(panels) == len(manifest["panels"]), "Duplicate panel ids")
    require(REQUIRED_PANELS <= panels.keys(), "The required native panel manifest is incomplete")
    for panel in panels.values():
        if panel["available"]:
            require(panel["id"] in PANEL_CONTRACTS, f"No positive native contract for advertised panel {panel['id']}")
        else:
            require(bool(panel.get("unavailableReason")), f"{panel['id']} lacks an honest unavailable reason")
    for name in REQUIRED_PANELS:
        require(panels[name]["available"], f"Required native contract {name} is unavailable")
    return panels


def tool_payload(reply: dict) -> Any:
    require("error" not in reply, "MCP returned a protocol/tool error")
    result = reply["result"]
    require(not result.get("isError", False), "MCP returned an in-band tool failure")
    if "structuredContent" in result:
        return result["structuredContent"]
    content = result.get("content", [])
    require(bool(content) and content[0].get("type") == "text", "MCP omitted its DTO payload")
    return json.loads(content[0]["text"])


def validate_shape(value: Any, fields: tuple[str, ...], label: str) -> None:
    if fields:
        require(isinstance(value, dict) and all(key in value for key in fields), f"{label} DTO contract failed")
    else:
        require(isinstance(value, list), f"{label} must serialize a JSON array")


def assert_no_startup_console(commands: Commands, case: Case, leases: Path) -> None:
    folder = commands.journal.output / "commands"
    text = (folder / (case.name + ".out")).read_text(encoding="utf-8")
    text += (folder / (case.name + ".err")).read_text(encoding="utf-8")
    require(TOKEN not in text and not re.search(
        r"\bBootUI[ \t]+(?:v?\d|[^\r\n]*(?:available at|https?://|bearer token))"
        r"|\[BootUI\][^\r\n]*(?:available at|https?://|bearer token)", text, re.IGNORECASE),
            "A denied console emitted its startup banner/token")
    require(not any(leases.iterdir()), "A denied console wrote an instance lease file")


def nonlocal_address() -> str:
    # UDP connect performs route selection only; it sends no packet and contacts no external service.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
        route.connect(("192.0.2.1", 9))
        address = route.getsockname()[0]
    require(not ipaddress.ip_address(address).is_loopback and not ipaddress.ip_address(address).is_unspecified,
            "An actual non-loopback local interface is required for the peer safety gate")
    return address


def safety_contract(client: Client, case: Case) -> None:
    path = APP_ROOT + case.api
    rejection = client.json(path + "/overview", headers={"Host": "attacker.invalid"}, expected=403)
    require(rejection.get("error") == "BootUI rejected an unrecognized Host header. "
            "Add it to bootui.allowed-hosts to allow this hostname.", "Host rejection is not the canonical JSON error")
    rejection = client.json(path + "/mcp", "POST", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                            headers={"Origin": "https://attacker.invalid", "Sec-Fetch-Site": "cross-site"},
                            expected=403)
    require(rejection.get("error") == "BootUI rejected a cross-site request to a state-changing endpoint.",
            "Origin rejection is not the canonical JSON error")
    if case.all_interfaces:
        peer = Client(client.port, client.folder / "peer", nonlocal_address())
        peer.folder.mkdir()
        # Connecting to our own non-loopback address produces a real non-loopback socket peer.
        if case.policy == "token":
            peer.json(path + "/overview", expected=401)
            peer.json(path + "/overview", headers={"Authorization": "Bearer wrong-test-token"}, expected=401)
            accepted = peer.json(path + "/overview", headers={"Authorization": "Bearer " + TOKEN})
            require(accepted["applicationName"] == "bootui-native-acceptance", "Bearer admission failed")
            peer.json(path + "/mcp", "POST", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, expected=401)
        else:
            peer.json(path + "/overview", expected=403)


def policy_contract(client: Client, case: Case, cli_status: dict) -> None:
    api = APP_ROOT + case.api
    if case.policy == "read-only":
        tool = next(tool for tool in cli_status["tools"] if tool["name"] == "clear_sql_traces")
        require(tool["panelReadOnly"] is True, "Read-only policy is absent from CLI metadata")
        client.json(api + "/cli/tools/clear_sql_traces", "POST", {}, expected=403)
        reply = client.rpc(case.api, "tools/call", {"name": "clear_sql_traces", "arguments": {}})
        require(reply.get("result", {}).get("isError") is True, "MCP ignored read-only policy")
    if case.policy == "disabled":
        tool = next(tool for tool in cli_status["tools"] if tool["name"] == "get_sql_traces")
        require(tool["panelEnabled"] is False, "Disabled panel policy is absent from CLI metadata")
        client.json(api + "/sql-trace", expected=403)
        client.json(api + "/cli/tools/get_sql_traces", "POST", {}, expected=403)
        reply = client.rpc(case.api, "tools/call", {"name": "get_sql_traces", "arguments": {}})
        require(reply.get("result", {}).get("isError") is True, "MCP ignored disabled panel policy")


def denied_tool_contract(client: Client, api: str, name: str, arguments: dict, reason: str) -> dict:
    client.json(APP_ROOT + api + "/cli/tools/" + name, "POST", arguments, expected=403)
    reply = client.rpc(api, "tools/call", {"name": name, "arguments": arguments})
    require(reply.get("result", {}).get("isError") is True, f"{name}: MCP ignored {reason} policy")
    return {"name": name, "expected": "denied", "reason": reason, "status": "passed"}


def http_probe_contract(client: Client, case: Case, panel: dict) -> None:
    endpoint = APP_ROOT + case.api + "/http-probe"
    request = {"method": "GET", "path": HOST_PATH + "/probe", "body": None,
               "headers": {"Accept": "application/json", "Host": "attacker.invalid",
                           "Connection": "close", "Content-Length": "999999"}}
    # Quarkus uses the shared Origin/Fetch-Site CSRF defense, not a guessed XSRF cookie.
    browser_headers = {"Origin": f"http://127.0.0.1:{client.port}", "Sec-Fetch-Site": "same-origin"}
    evidence = {"status": "running", "endpoint": endpoint, "request": request,
                "browserHeaders": browser_headers, "checks": []}
    report = client.folder / "http-probe-result.json"
    report.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    try:
        host_error = ("BootUI rejected an unrecognized Host header. "
                      "Add it to bootui.allowed-hosts to allow this hostname.")
        csrf_error = "BootUI rejected a cross-site request to a state-changing endpoint."
        other_port = 1 if client.port == 65535 else client.port + 1
        denied = (
            ("host", {**browser_headers, "Host": "attacker.invalid"}, host_error),
            ("cross-site", {"Origin": "https://attacker.invalid", "Sec-Fetch-Site": "cross-site"}, csrf_error),
            ("same-site", {**browser_headers, "Sec-Fetch-Site": "same-site"}, csrf_error),
            ("origin-port", {"Origin": f"http://127.0.0.1:{other_port}"}, csrf_error),
        )
        for name, headers, message in denied:
            rejection = client.json(endpoint, "POST", request, headers, expected=403)
            require(rejection == {"error": message}, f"HTTP Probe {name} rejection is not canonical")
            evidence["checks"].append({"name": name, "expected": "denied", "status": "passed",
                                       "response": rejection})
        if panel["readOnly"]:
            rejection = client.json(endpoint, "POST", request, browser_headers, expected=403)
            require(rejection == {"error": "BootUI panel access denied", "panel": "http-probe",
                                  "reason": panel["readOnlyReason"]}, "HTTP Probe ignored read-only policy")
            evidence["checks"].append({"name": "read-only", "expected": "denied", "status": "passed",
                                       "response": rejection})
        else:
            _, original_headers, original_body = client.request(HOST_PATH + "/probe", expected=200)
            require(original_headers.get("x-native-fixture-private") == "fixture-only"
                    and original_headers.get("set-cookie", "").startswith("native-acceptance-private="),
                    "The fixture's private response-header witnesses are absent")
            require(SECRET.encode() not in original_body and TOKEN.encode() not in original_body,
                    "The fixture probe exposed a secret/token")
            response = client.json(endpoint, "POST", request, browser_headers)
            validate_shape(response, PANEL_CONTRACTS["http-probe"][1], "HTTP Probe")
            require(response["status"] == 200 and response["statusText"] == "OK" and response["error"] is None,
                    "HTTP Probe failed to reach the actual loopback fixture")
            require(response["truncated"] is False and type(response["durationMs"]) is int
                    and 0 <= response["durationMs"] <= HTTP_TIMEOUT * 1000,
                    "HTTP Probe returned an invalid or unbounded outcome")
            received = response["headers"]
            require(isinstance(received, dict)
                    and set(received) <= {"content-type", "content-length", "location", "x-bootui-request-id"}
                    and isinstance(received.get("content-type"), str)
                    and "application/json" in received.get("content-type", ""),
                    "HTTP Probe leaked private headers/cookies or omitted its JSON content type")
            require(isinstance(response["body"], str) and SECRET not in response["body"]
                    and TOKEN not in response["body"], "HTTP Probe returned an unsafe/non-string body")
            snapshot = json.loads(response["body"])
            validate_shape(snapshot, ("protocol", "counters", "logSubscriptions", "ownedThreads",
                                      "installedHandlers", "hostSpans", "slowConfig"), "HTTP Probe fixture")
            require(snapshot["protocol"] == PROTOCOL and isinstance(snapshot["counters"], dict)
                    and set(snapshot["counters"]) == {"http", "logs", "sql", "exceptions", "telemetry", "journal"},
                    "HTTP Probe returned a surrogate rather than the nested fixture DTO")
            for counter in snapshot["counters"].values():
                validate_shape(counter, ("state", "value"), "HTTP Probe nested counter")
                require((counter["state"] == "instantiated" and type(counter["value"]) is int
                         and counter["value"] >= 0)
                        or (counter["state"] in ("absent", "uninstantiated") and counter["value"] is None),
                        "HTTP Probe returned a fabricated/unobservable nested counter")
            evidence["response"] = response
            evidence["checks"].append({"name": "loopback-and-sanitization", "expected": "active", "status": "passed"})
            invalid = (
                ("method-bytes", {"method": "M" * 33}, "request method exceeds the maximum of 32 bytes"),
                ("path-bytes", {"path": "/" + "p" * 2048}, "request path exceeds the maximum of 2048 bytes"),
                ("body-bytes", {"body": "b" * 65537}, "request body exceeds the maximum of 65536 bytes"),
                ("body-utf8-bytes", {"body": "é" * 32769}, "request body exceeds the maximum of 65536 bytes"),
                ("header-count", {"headers": {f"X-Probe-{i}": "v" for i in range(51)}},
                 "request exceeds the maximum of 50 request headers"),
                ("header-name-bytes", {"headers": {"h" * 257: "v"}},
                 "request header name exceeds the maximum of 256 bytes"),
                ("header-value-bytes", {"headers": {"X-Probe": "v" * 8193}},
                 "request header value exceeds the maximum of 8192 bytes"),
                ("total-header-bytes", {"headers": {f"X-Probe-{i}": "v" * 6554 for i in range(5)}},
                 "request headers exceed the maximum total of 32768 bytes"),
            )
            for name, overrides, message in invalid:
                start = time.monotonic()
                rejection = client.json(endpoint, "POST", {**request, **overrides}, browser_headers, expected=400)
                elapsed_ms = int((time.monotonic() - start) * 1000)
                require(rejection == {"error": "HTTP Probe " + message},
                        f"HTTP Probe {name} did not return its canonical bounded-input error")
                require(elapsed_ms <= HTTP_TIMEOUT * 1000, f"HTTP Probe {name} validation was not bounded")
                evidence["checks"].append({"name": name, "expected": "invalid", "status": "passed",
                                           "durationMs": elapsed_ms, "response": rejection})
        require(client.json(HOST_PATH + "/health")["status"] == "UP", "HTTP Probe work damaged the host")
        evidence["status"] = "passed"
    except (AcceptanceFailure, OSError, ValueError, KeyError, http.client.HTTPException) as failure:
        evidence["status"], evidence["error"] = "failed", str(failure)
        raise
    finally:
        report.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")


def panel_contract(client: Client, case: Case, name: str, panel: dict) -> None:
    if name == "http-probe":
        http_probe_contract(client, case, panel)
    else:
        suffix, fields = PANEL_CONTRACTS[name]
        validate_shape(client.json(APP_ROOT + case.api + suffix), fields, name)


def streams_contract(client: Client, case: Case, osv: LoopbackOsvFixture) -> None:
    baseline = client.probe()["logSubscriptions"]
    require(baseline == {"state": "instantiated", "value": 0}, "Log subscriber baseline is not observable/empty")
    connections = []
    responses = []
    frames = []
    sibling_frame = None

    def log_event(response):
        for _ in range(100):
            line = response.readline(READ_LIMIT + 1)
            require(line and len(line) <= READ_LIMIT, "The native SSE frame was absent or oversized")
            require(SECRET.encode() not in line, "The native SSE frame exposed the known secret")
            if line.startswith(b"data:"):
                event = json.loads(line[5:].strip())
                validate_shape(event, ("timestamp", "level", "logger", "message"), "native log SSE")
                return event
        raise AcceptanceFailure("The native SSE stream never serialized a log DTO")

    try:
        for _ in range(2):
            connection = http.client.HTTPConnection("127.0.0.1", client.port, timeout=HTTP_TIMEOUT)
            connections.append(connection)
            connection.request("GET", APP_ROOT + case.api + "/log-tail/stream",
                               headers={"Accept": "text/event-stream"})
            response = connection.getresponse()
            responses.append(response)
            require(response.status == 200 and "text/event-stream" in response.getheader("Content-Type", ""),
                    "Log stream did not negotiate SSE")
            frames.append(log_event(response))
        wait_until("two native log subscriptions", lambda: client.probe()["logSubscriptions"]["value"] == 2)
        if connections[0].sock is not None:
            connections[0].sock.shutdown(socket.SHUT_RDWR)
        responses[0].close()
        connections[0].close()
        wait_until("first SSE cancellation without sibling loss",
                   lambda: client.probe()["logSubscriptions"]["value"] == 1)
        client.json(HOST_PATH + "/witness")
        invocation = client.probe()["hostSpans"]
        for _ in range(501):
            sibling_frame = log_event(responses[1])
            if f"invocation={invocation} " in (sibling_frame.get("message") or ""):
                break
        else:
            raise AcceptanceFailure("The surviving native SSE sibling stopped delivering host events")
        if connections[1].sock is not None:
            connections[1].sock.shutdown(socket.SHUT_RDWR)
        responses[1].close()
        connections[1].close()
        wait_until("SSE subscription cleanup", lambda: client.probe()["logSubscriptions"]["value"] == 0)
    finally:
        for response in responses:
            response.close()
        for connection in connections:
            connection.close()
    (client.folder / "log-stream-result.json").write_text(
        json.dumps({"status": "passed", "subscriptionsOpened": 2, "subscriptionsAfterCancel": 0,
                    "transport": "HTTP/1.1", "http2SiblingIsolation": "not-run",
                    "request": {"method": "GET", "path": APP_ROOT + case.api + "/log-tail/stream",
                                "headers": {"Accept": "text/event-stream"}},
                    "firstFrames": frames, "siblingFrameAfterCancel": sibling_frame}) + "\n", encoding="utf-8")

    if case.policy == "read-only":
        (client.folder / "mcp-stream-result.json").write_text(
            json.dumps({"status": "not-applicable", "reason": "The progress-reporting scan is read-only denied"}) + "\n")
        return
    before = len(osv.requests)
    osv.fail_batch = False
    osv.hold_batch = True
    connection = http.client.HTTPConnection("127.0.0.1", client.port, timeout=HTTP_TIMEOUT)
    try:
        request = {"jsonrpc": "2.0", "id": 99, "method": "tools/call",
                   "params": {"name": "vulnerabilities_scan", "arguments": {},
                              "_meta": {"progressToken": "native-acceptance-cancel",
                                        "io.modelcontextprotocol/protocolVersion": MCP_VERSION,
                                        "io.modelcontextprotocol/clientCapabilities": {}}}}
        connection.request("POST", APP_ROOT + case.api + "/mcp", body=json.dumps(request),
                           headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                                    "MCP-Protocol-Version": MCP_VERSION,
                                    "Mcp-Method": "tools/call", "Mcp-Name": "vulnerabilities_scan"})
        response = connection.getresponse()
        require(response.status == 200 and "text/event-stream" in response.getheader("Content-Type", ""),
                "Modern MCP did not open its native stream")
        wait_until("MCP actual OSV operation start",
                   lambda: any(entry.get("held") for entry in osv.requests[before:]), timeout=5)
        admission = client.rpc(case.api, "tools/call", {"name": "get_overview", "arguments": {}}, identifier=100)
        require(admission.get("error", {}).get("message") == "MCP server is at capacity; try again shortly.",
                "MCP failed to enforce its single-call admission budget")
        # Abort the actual socket, not merely the response object retaining its connection.
        cancelled_at = time.monotonic()
        if connection.sock is not None:
            connection.sock.shutdown(socket.SHUT_RDWR)
        response.close()
        connection.close()
        after = wait_until("modern MCP cancellation of actual OSV transport",
                           lambda: next((entry for entry in osv.requests[before:]
                                         if entry.get("disconnected")), None), timeout=5)
        tool_payload(client.rpc(case.api, "tools/call", {"name": "get_overview", "arguments": {}}))
        elapsed_ms = int((time.monotonic() - cancelled_at) * 1000)
        require(elapsed_ms <= 1500 and not after.get("holdTimedOut"),
                "Modern MCP cancellation did not release the real scan and admission within1500ms")
        (client.folder / "mcp-stream-result.json").write_text(
            json.dumps({"status": "passed", "osvRequest": after, "cancellationMillis": elapsed_ms,
                        "permitReused": True, "protocolVersion": MCP_VERSION,
                        "request": request, "admissionRefusal": admission}) + "\n", encoding="utf-8")
    finally:
        connection.close()


def positive_contract(client: Client, case: Case, assets: list[str], commands: Commands, java: Path, cli: Path,
                      fixture: str = "jdbc", osv: LoopbackOsvFixture | None = None) -> None:
    prefix = APP_ROOT + case.api
    _, headers, shell = client.request(APP_ROOT + case.ui + "/", expected=200)
    require("text/html" in headers.get("content-type", "") and b'<div id="app"' in shell, "The Vue shell is absent")
    shell_text = shell.decode("utf-8")
    require(any(asset.rsplit("/", 1)[-1] in shell_text for asset in assets if asset.endswith(".js")),
            "The shell did not reference the real built JavaScript bundle")
    for suffix in assets:
        _, _, body = client.request(APP_ROOT + case.ui + "/" + suffix, expected=200)
        require(bool(body) and not body.lstrip().startswith(b"<!DOCTYPE"), "An asset request returned a shell fallback")
    panels = check_manifest(client.json(prefix + "/panels"))
    require(panels["http-probe"]["enabled"], "The required native HTTP Probe was unexpectedly disabled")
    for name, panel in panels.items():
        if panel["available"] and panel["enabled"]:
            panel_contract(client, case, name, panel)
    host_traffic(client, fixture)
    config = client.json(prefix + "/config?q=fixture.database.password")
    properties = [item for item in config["properties"] if item["name"] == "fixture.database.password"]
    require(properties and all(item["masked"] and item["value"] != SECRET for item in properties),
            "Known property secret was not masked")
    beans = client.json(prefix + "/beans?q=WitnessService")
    require(any(bean["type"] == "org.acme.nativeconsole.WitnessService"
                and bean["scope"] == "ApplicationScoped" and "nativeAcceptanceLabel" in bean["dependencies"]
                for bean in beans["beans"]), "Native bean/injection-edge metadata is empty")
    mappings = client.json(prefix + "/mappings/flat")
    require(any("native-fixture/witness" in mapping["pattern"] and mapping["method"] == "GET"
                and "FixtureResource" in mapping["handler"] for mapping in mappings["mappings"]),
            "Native mapping witness metadata is missing")
    pools = client.json(prefix + "/database-connection-pools/pools")
    require(pools["poolLibraryPresent"] is True and pools["total"] > 0 and pools["pools"],
            "The live Agroal pool DTO is empty")
    require(any(pool["implementation"] == "Agroal" and pool["available"] and pool["maximumPoolSize"] > 0
                and pool["snapshot"] is not None for pool in pools["pools"]), "Live Agroal metadata/snapshot is missing")
    exchanges = client.json(prefix + "/http-exchanges")
    jdbc_http = next((entry for entry in exchanges["exchanges"] if entry["path"].endswith("/native-fixture/jdbc")), None)
    require(jdbc_http and jdbc_http["status"] == 200 and jdbc_http.get("requestId"), "HTTP capture/correlation is missing")
    logs = client.json(prefix + "/log-tail/recent")
    require(any("native-acceptance-witness" in (line.get("message") or "") for line in logs),
            "The host's actual log traffic was not captured")
    exceptions = client.json(prefix + "/exceptions")
    require(exceptions["totalExceptions"] > 0 and exceptions["groups"], "The host error was not captured")
    if fixture == "hibernate":
        validation_contract(client, prefix, exceptions)
    traces = client.json(prefix + "/traces")
    require(traces["traces"], "Public OpenTelemetry host spans were not captured")
    details = [client.json(prefix + "/traces/" + trace["traceId"]) for trace in traces["traces"]]
    require(any(span["name"] == "native-acceptance-witness" and span["attributes"]
                for detail in details for span in detail["spans"]), "The host's actual span/nested DTO is missing")
    sql = None
    if case.policy != "disabled":
        sql = client.json(prefix + "/sql-trace")
        require(sql["available"] is True and sql["totalCaptured"] > 0, "Actual JDBC-backed SQL was not recorded")
        jdbc_rows = [entry for entry in sql["entries"] if "NATIVE_WITNESS" in entry["sql"]]
        require(jdbc_rows and any(entry["success"] and entry.get("connectionId")
                                 and entry.get("requestId") == jdbc_http["requestId"] for entry in jdbc_rows),
                "JDBC execution evidence did not correlate to its real HTTP request")
    if fixture == "hibernate":
        orm_contract(client, prefix, exchanges, sql)
    initialized = client.json(prefix + "/mcp", "POST",
                              {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                               "params": {"protocolVersion": LEGACY_MCP_VERSION, "capabilities": {},
                                          "clientInfo": {"name": PROTOCOL, "version": "1"}}},
                              {"MCP-Protocol-Version": LEGACY_MCP_VERSION, "Accept": "application/json"})
    require(initialized.get("result", {}).get("protocolVersion") == LEGACY_MCP_VERSION, "MCP initialize failed")
    discovery = client.rpc(case.api, "server/discover")
    require(discovery.get("result", {}).get("resultType") == "complete", "Modern MCP discovery failed")
    cli_status = client.json(prefix + "/cli")
    require(cli_status["enabled"] is True and cli_status["toolCount"] == len(cli_status["tools"]) > 0,
            "CLI native tool catalog is incomplete")
    listed = client.rpc(case.api, "tools/list")
    rpc_names = {tool["name"] for tool in listed["result"]["tools"]}
    tools = {tool["name"]: tool for tool in cli_status["tools"]}
    require(rpc_names == set(tools), "Native MCP and CLI catalogs disagree")
    require({"get_overview", "get_config", "get_beans", "get_sql_traces"} <= rpc_names, "Required MCP tools are missing")
    tool_evidence = []

    def retain_tool_result(result):
        tool_evidence.append(result)
        (client.folder / "tool-results.json").write_text(json.dumps(tool_evidence, indent=2) + "\n", encoding="utf-8")

    exception_id = exceptions["groups"][0]["id"]
    ordered = sorted(tools.items(), key=lambda entry: (entry[1]["action"], entry[0]))
    for name, tool in ordered:
        require(tool["panel"] in panels and panels[tool["panel"]]["available"], "Tool advertised an unavailable panel")
        require(name in READ_TOOL_CONTRACTS or name in ACTION_TOOL_CONTRACTS,
                f"No implemented positive native contract for advertised tool {name}")
        arguments: dict[str, Any] = {}
        if name == "get_request_profile":
            arguments = {"id": jdbc_http["requestId"]}
        elif name == "get_exception_detail":
            arguments = {"id": exception_id}
        if not tool["panelEnabled"] or (tool["action"] and tool["panelReadOnly"]):
            reason = "disabled" if not tool["panelEnabled"] else "read-only"
            retain_tool_result(denied_tool_contract(client, case.api, name, arguments, reason))
            continue
        if name == "vulnerabilities_scan":
            require(osv is not None and not osv.requests, "A passive native read performed an OSV scan")
        result = tool_payload(client.rpc(case.api, "tools/call", {"name": name, "arguments": arguments}))
        require(isinstance(result, (dict, list)), f"{name} did not return a structured DTO")
        if name in READ_TOOL_CONTRACTS:
            validate_shape(result, TOOL_SHAPES.get(name, PANEL_CONTRACTS[READ_TOOL_CONTRACTS[name]][1]), name)
        elif name == "vulnerabilities_scan":
            validate_shape(result, TOOL_SHAPES[name], name)
            require(result["scan"]["status"] in ("SCANNED", "PARTIAL") and result["findingsFound"] == 1
                    and result["dependencies"] > 0 and result["scanningEnabled"],
                    "The native OSV action did not interpret its actual loopback advisory")
            require(result["scan"]["packagesScanned"] <= 2 and len(osv.requests) == 2,
                    "The native OSV action exceeded its configured package/advisory bounds")
            inventory = client.json(prefix + "/vulnerabilities")
            require(any(item["id"] == "NATIVE-OSV-WITNESS" for dependency in inventory["dependencies"]
                        for item in dependency["vulnerabilities"]), "The scanned OSV advisory was not retained")
            osv.fail_batch = True
            failure = tool_payload(client.rpc(case.api, "tools/call", {"name": name, "arguments": arguments}))
            validate_shape(failure, TOOL_SHAPES[name], name)
            require(failure["scan"]["status"] == "ERROR" and failure["scan"]["message"]
                    and failure["dependencies"] == result["dependencies"] and len(osv.requests) == 3,
                    "The bounded OSV failure did not clearly preserve the local inventory")
            require(len(client.json(prefix + "/vulnerabilities")["dependencies"]) == result["dependencies"],
                    "The native OSV failure discarded the retained local inventory")
            require(len(osv.requests) == 3, "Reading the retained OSV failure performed another scan")
            retain_tool_result({"name": name, "expected": "bounded-loopback-success-and-failure",
                                "status": "passed", "success": result, "failure": failure})
            continue
        else:
            validate_shape(result, ("action", "available", "capturing", "retained"), name)
            require(result["available"] is True, f"{name} advertised a control that is not available")
            action = "paused" if name.startswith("pause_") else "resumed" if name.startswith("resume_") else "cleared"
            require(result["action"] == action, f"{name} did not acknowledge the requested action")
            if action == "cleared":
                require(result["retained"] == 0, f"{name} did not clear its retained evidence")
            if name in ("pause_sql_trace_recording", "resume_sql_trace_recording"):
                before_sql = client.json(prefix + "/sql-trace")["totalCaptured"]
                client.json(HOST_PATH + "/jdbc")
                after_sql = client.json(prefix + "/sql-trace")["totalCaptured"]
                require((after_sql == before_sql) if action == "paused" else (after_sql > before_sql),
                        f"{name} did not control actual JDBC capture")
        retain_tool_result({"name": name, "expected": "allowed", "status": "passed"})
    cli_command = [str(java), "-jar", str(cli), "--url", f"http://127.0.0.1:{client.port}",
                   "--api-path", prefix, "overview", "--json"]
    commands.run(case.name + "-published-cli", cli_command, timeout=30)
    overview = json.loads((commands.journal.output / "commands" / (case.name + "-published-cli.out"))
                          .read_text(encoding="utf-8"))
    require(overview["applicationName"] == "bootui-native-acceptance" and overview["serverPort"] == client.port,
            "The actual published CLI did not execute its native-server request")
    safety_contract(client, case)
    policy_contract(client, case, cli_status)
    streams_contract(client, case, osv)
    if case.policy == "read-only":
        require(osv is not None and not osv.requests, "A read-only native console performed an OSV scan")


def shutdown_contract(path: Path, active: bool) -> None:
    require(path.is_file(), "The host did not record its shutdown dormancy witness")
    snapshot = json.loads(path.read_text(encoding="utf-8"))
    require(snapshot.get("protocol") == PROTOCOL, "The shutdown dormancy witness has an invalid protocol")
    if not active:
        assert_dormant(snapshot)


def check_http2_result(result: dict) -> None:
    require(result.get("schema") == "native-http2-mcp-witness-v1" and result.get("status") == "passed",
            "The native HTTP/2 witness did not complete successfully")
    connection = result.get("physicalConnection", {})
    require(connection.get("protocol") == "HTTP_2" and connection.get("explicitConnectionsOpened") == 1
            and connection.get("clearTextUpgrade") is False,
            "The native witness did not prove one prior-knowledge physical HTTP/2 connection")
    elapsed = result.get("cancellation", {}).get("elapsedMillis")
    require(type(elapsed) is int and 0 <= elapsed <= 1500,
            "Native HTTP/2 cancellation and permit reuse exceeded1500ms or were unobserved")
    require(result.get("samePhysicalConnection") is True and result.get("singleFlightPreserved") is True
            and result.get("goAwayBeforeOwnedClose") is False
            and result.get("connectionClosedBeforeOwnedClose") is False,
            "Native HTTP/2 sibling isolation or physical connection reuse was not proved")


def http2_case() -> Case:
    return Case("http2-multiplexed-sibling", True, properties=(
        ("bootui.enabled", "ON"), ("quarkus.profile", "local"), ("bootui.expose-values", "true"),
        ("bootui.mcp.max-concurrent-calls", "2"), ("bootui.mcp.execution-timeout", "PT20S"),
        ("bootui.vulnerabilities.request-timeout", "PT6S"), ("bootui.vulnerabilities.scan-timeout", "PT8S"),
    ), policy="http2")


def http2_contract(client: Client, case: Case, commands: Commands, java: Path,
                   osv: LoopbackOsvFixture, compiled: dict) -> None:
    result_path = client.folder / "http2-result.json"
    environment = {**commands.environment, "BOOTUI_NATIVE_HTTP2_OSV_WITNESS_DIR": str(client.folder)}
    osv.hold_batch = True
    try:
        commands.run("native-http2-multiplexed-sibling",
                     [str(java), "-Xmx256m", "-XX:ActiveProcessorCount=2",
                      f"-Djava.io.tmpdir={client.folder / 'scratch'}", "-cp", compiled["classpath"],
                      "Quarkus4NativeHttp2Witness", str(client.port), APP_ROOT + case.api, str(result_path)],
                     timeout=45, environment=environment)
        result = json.loads(result_path.read_text(encoding="utf-8"))
        check_http2_result(result)
        commands.journal.data["http2"] = result
        commands.journal.data["limitations"].remove(
            "HTTP/2 multiplexed sibling isolation is not exercised by this dependency-free harness")
    finally:
        osv.hold_batch = False


def run_case(commands: Commands, case: Case, binary: Path, java: Path, cli: Path, assets: list[str],
             variant: str, fixture: str = "jdbc", http2_client: dict | None = None) -> dict:
    if fixture == "hibernate":
        for limitation in (
            "ORM StatementInspector captures only default-unit preparations, not named-unit SQL",
            "ORM sessions are captured but their feed/profile evidence is hidden while the Hibernate owning panel is unavailable",
        ):
            if limitation not in commands.journal.data["limitations"]:
                commands.journal.data["limitations"].append(limitation)
    folder = commands.journal.output / "cases" / (variant + "-" + case.name)
    folder.mkdir(parents=True)
    leases = folder / "leases"
    leases.mkdir()
    with socket.socket() as reserve:
        reserve.bind(("127.0.0.1", 0))
        port = reserve.getsockname()[1]
    osv = LoopbackOsvFixture(folder)
    properties = {
        "quarkus.http.port": str(port), "quarkus.http.host": "0.0.0.0" if case.all_interfaces else "127.0.0.1",
        "bootui.path": case.ui, "bootui.api-path": case.api, "bootui.cli.instances-dir": str(leases),
        "user.home": str(folder / "home"), "java.io.tmpdir": str(folder / "scratch"),
        "native.acceptance.shutdown-file": str(folder / "shutdown-probe.json"),
        "bootui.vulnerabilities.osv-base-uri": osv.base_uri, "bootui.vulnerabilities.epss-enabled": "false",
        "bootui.vulnerabilities.epss-base-uri": osv.base_uri,
        "bootui.vulnerabilities.max-packages": "2", "bootui.vulnerabilities.max-advisories": "2",
        "bootui.vulnerabilities.request-timeout": "PT3S", "bootui.vulnerabilities.scan-timeout": "PT5S",
        **dict(case.properties),
    }
    (folder / "scratch").mkdir()
    config = None
    if case.file_properties:
        # Profile parents must be present before selection; quarkus.config.locations adds late sources.
        config = folder / "config/application.properties"
        config.parent.mkdir()
        config.write_text("config_ordinal=350\n" + "".join(f"{key}={value}\n" for key, value in case.file_properties),
                          encoding="utf-8")
    flags = [f"-D{key}={value}" for key, value in properties.items()]
    command = ([str(java), *flags, "-jar", str(binary)] if variant == "jvm" else [str(binary), *flags])
    environment = safe_environment(folder / "home", Path(commands.environment["JAVA_HOME"]))
    environment.update(dict(case.environment))
    entry = {"name": case.name, "variant": variant, "expected": "active" if case.active else "denied",
             "status": "running", "properties": properties, "environmentOverrides": dict(case.environment),
             "fileProperties": dict(case.file_properties), "configFile": str(config) if config else None,
             "binarySha256": sha256(binary), "artifacts": str(folder)}
    commands.journal.data["cases"].append(entry)
    commands.journal.write()
    client = Client(port, folder)
    try:
        with osv, commands.process(case.name, command, folder, environment) as (process, _):
            def ready():
                require(not Commands.exited(process), f"{case.name}: host exited before becoming healthy")
                try:
                    return client.json(HOST_PATH + "/health")["status"] == "UP"
                except (ConnectionRefusedError, ConnectionResetError, socket.timeout, http.client.RemoteDisconnected):
                    return False

            wait_until(case.name + " healthy host", ready, timeout=60)
            initial = client.probe()
            (folder / "startup-probe.json").write_text(json.dumps(initial, indent=2) + "\n", encoding="utf-8")
            if case.active:
                if case.policy == "http2":
                    require(variant == "included" and http2_client is not None,
                            "Native HTTP/2 requires the actual included binary and compiled managed client")
                    http2_contract(client, case, commands, java, osv, http2_client)
                else:
                    positive_contract(client, case, assets, commands, java, cli, fixture, osv)
                client.json(HOST_PATH + "/flags/off-production", "POST", {})
                require(client.json(APP_ROOT + case.api + "/overview")["applicationName"] == "bootui-native-acceptance",
                        "Native activation changed after startup")
                host_traffic(client, fixture)
                require(any(leases.iterdir()), "Active native instance discovery did not write a lease")
            else:
                assert_dormant(initial)
                deny_surfaces(client, case, assets)
                host_traffic(client, fixture)
                client.json(HOST_PATH + "/flags/on-local", "POST", {})
                deny_surfaces(client, case, assets)
                host_traffic(client, fixture)
                assert_dormant(client.probe())
                assert_no_startup_console(commands, case, leases)
                require(not osv.requests, "A denied native console performed an OSV request")
            final = client.probe()
            (folder / "final-probe.json").write_text(json.dumps(final, indent=2) + "\n", encoding="utf-8")
            require(not Commands.exited(process), "The native host died during acceptance")
        shutdown_contract(folder / "shutdown-probe.json", case.active)
        require(not any(leases.iterdir()), "Shutdown failed to clean up an instance lease")
        require(sha256(binary) == entry["binarySha256"], "The executed binary changed during acceptance")
        entry["status"] = "passed"
    except (AcceptanceFailure, OSError, ValueError, KeyError, http.client.HTTPException) as failure:
        entry["status"], entry["error"] = "failed", str(failure)
    finally:
        commands.journal.write()
    return entry


def execute(args: argparse.Namespace, journal: Journal) -> None:
    journal.data["fixture"] = args.fixture
    java_home = Path(os.environ.get("JAVA_HOME", "")).resolve()
    require(bool(os.environ.get("JAVA_HOME")), "JAVA_HOME must identify the native toolchain")
    java = java_home / "bin/java"
    native_image = java_home / "bin/native-image"
    require(java.is_file() and native_image.is_file(), "JAVA_HOME must contain java AND native-image")
    environment = safe_environment(journal.output / "build-home", java_home, args.npm_cache)
    environment["MAVEN_OPTS"] = (
        f"-Dmaven.repo.local={args.maven_repo} -Duser.home={journal.output / 'build-home'} "
        f"-Djava.io.tmpdir={journal.output / 'build-home/scratch'}")
    commands = Commands(journal, environment)
    collect_host(commands, args.target)
    java_identity = commands.run("jdk-identity", [str(java), "-XshowSettings:properties", "-version"])
    native_identity = commands.run("native-image-identity", [str(native_image), "--version"])
    compiler_identity = commands.run("c-compiler-identity", ["cc", "--version"])
    require(re.search(r"java\.specification\.version\s*=\s*25\b", java_identity) is not None,
            "Use the supported Quarkus 4 GraalVM JDK 25 toolchain")
    require("native-image" in native_identity and "25" in native_identity, "Native compiler identity is missing")
    journal.data["toolchain"] = {"javaHome": str(java_home), "java": java_identity,
                                 "nativeImage": native_identity, "cCompiler": compiler_identity}
    journal.write()
    commands.run("install-extension", maven_command(
        args.maven_repo, "-pl", "bootui-quarkus-4,bootui-quarkus-4-deployment", "-am", "install"),
        timeout=args.build_timeout)
    commands.run("install-fixture-parent", maven_command(
        args.maven_repo, "-f", str(FIXTURE.parent / "pom.xml"), "-N", "install"), timeout=300)
    assets = bundled_assets(args.maven_repo)
    journal.data["uiAssets"] = assets
    journal.write()
    cli = obtain_cli(commands, args.maven_repo)
    binaries = {variant: compile_fixture(commands, args.maven_repo, args.target, variant, args.build_timeout, args.fixture)
                for variant in ("included", "excluded", "jvm")}
    http2_client = compile_http2_client(commands, binaries["included"], java_home)
    require(journal.data["builds"]["included"]["sha256"] != journal.data["builds"]["excluded"]["sha256"],
            "Included/excluded variants are the same binary")
    included_cases = activation_cases()
    expected_cases = {("included", case.name) for case in included_cases}
    for case in included_cases:
        run_case(commands, case, binaries["included"], java, cli, assets, "included", args.fixture)
    multiplexed = http2_case()
    expected_cases.add(("included", multiplexed.name))
    run_case(commands, multiplexed, binaries["included"], java, cli, assets, "included", args.fixture, http2_client)
    for variant in ("excluded", "jvm"):
        for case in (Case(variant + "-system-opt-in-spoof",
                          properties=(("bootui.enabled", "ON"), ("quarkus.profile", "local"),
                                      ("bootui.native-console.enabled", "true"),
                                      ("org.graalvm.nativeimage.imagecode", "runtime"))),
                     Case(variant + "-environment-opt-in-spoof",
                          environment=(("BOOTUI_ENABLED", "ON"), ("QUARKUS_PROFILE", "local"),
                                       ("BOOTUI_NATIVE_CONSOLE_ENABLED", "true")))):
            expected_cases.add((variant, case.name))
            run_case(commands, case, binaries[variant], java, cli, assets, variant, args.fixture)
    require({(case["variant"], case["name"]) for case in journal.data["cases"]} == expected_cases,
            "The compiled/executed native acceptance matrix is incomplete")
    require(journal.data["cases"] and all(case["status"] == "passed" for case in journal.data["cases"]),
            "One or more compiled/executed native acceptance gates failed")


def parse_arguments(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=TARGETS)
    parser.add_argument("--fixture", choices=("jdbc", "hibernate"), default="jdbc",
                        help="Exercise optional ORM/Validation and host listener preservation, or the API-absent fixture")
    parser.add_argument("--maven-repo", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--npm-cache", type=Path,
                        help="Use an existing absolute npm dependency cache offline; never inherit npm configuration")
    parser.add_argument("--build-timeout", type=int, default=3600,
                        help="Bound each local Maven/native compile (seconds, 60..7200)")
    args = parser.parse_args(argv)
    if not args.maven_repo.is_absolute():
        parser.error("--maven-repo must be an absolute isolated repository path")
    if args.npm_cache is not None:
        if not args.npm_cache.is_absolute() or not args.npm_cache.is_dir():
            parser.error("--npm-cache must be an existing absolute directory")
        args.npm_cache = args.npm_cache.resolve()
    if not 60 <= args.build_timeout <= 7200:
        parser.error("--build-timeout must be between 60 and 7200 seconds")
    args.maven_repo = args.maven_repo.resolve()
    args.output = args.output.resolve()
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_arguments(argv)
    # Never erase/reuse evidence or arbitrary files from an earlier run.
    if args.output.exists() and any(args.output.iterdir()):
        print("Refusing a nonempty artifact directory; use a fresh --output", file=sys.stderr)
        return 2
    args.output.mkdir(parents=True, exist_ok=True)
    journal = Journal(args.output, args.target, args.maven_repo)
    try:
        execute(args, journal)
        journal.data["status"] = "passed"
        status = 0
    except (AcceptanceFailure, OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError,
            http.client.HTTPException) as failure:
        journal.data["status"], journal.data["error"] = "failed", str(failure)
        status = 1
    except KeyboardInterrupt:
        journal.data["status"], journal.data["error"] = "interrupted", "Acceptance interrupted; no success recorded"
        status = 130
    finally:
        journal.data["completedAt"] = time.time()
        journal.write()
    print(f"Quarkus 4 native acceptance: {journal.data['status']}; {args.output / 'acceptance-result.json'}")
    return status


if __name__ == "__main__":
    def interrupted(_number, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
