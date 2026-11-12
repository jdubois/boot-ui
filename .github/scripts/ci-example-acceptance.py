#!/usr/bin/env python3
"""Run the documented starter-kit recipes against the installed source candidate, not a published release."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / ".github/scripts" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


examples = load("ci_examples_generator", "ci-examples.py")
consumer = load("ci_consumer_acceptance", "ci-consumer-acceptance.py")


def stop(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        return process.communicate(timeout=45)[0]
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        return process.communicate(timeout=10)[0]


def run(command, *, cwd, env):
    process = subprocess.Popen(
        command, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, start_new_session=True,
    )
    try:
        output, _ = process.communicate(timeout=900)
    except subprocess.TimeoutExpired as error:
        output = stop(process)
        raise RuntimeError(f"Starter-kit recipe timed out:\n{output[-12000:]}") from error
    except BaseException:
        stop(process)
        raise
    return process.returncode, output


def materialize(kit, version):
    record = examples.release_record(ROOT)
    values = examples.substitutions(ROOT, record)
    values["@BOOTUI_VERSION@"] = version
    for name, content in examples.kit_entries(ROOT, values).items():
        target = kit / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        if name == "mvnw" or name.endswith(".sh"):
            target.chmod(0o755)


def verify_reports(state, names, expected_workload):
    result = json.loads((state / "result.json").read_text(encoding="utf-8"))
    consumer.require(result["workloadExit"] == expected_workload, f"Unexpected workload result in {state}")
    consumer.require(result["assessmentExit"] == 0, f"Assessment did not pass in {state}")
    artifacts = state / "artifacts"
    consumer.require({path.name for path in artifacts.iterdir()} == set(names), f"Unexpected reports in {artifacts}")
    for name in names:
        directory = artifacts / name
        consumer.require(
            {path.name for path in directory.iterdir()} == {"bundle.json", "summary.md", "junit.xml", "sarif.json"},
            f"Incomplete reports in {directory}",
        )
        bundle = json.loads((directory / "bundle.json").read_text(encoding="utf-8"))
        consumer.require(bundle["status"] == "PASS", f"Bundle did not pass in {directory}")
        consumer.require(bool((directory / "summary.md").read_text(encoding="utf-8").strip()),
                         f"Empty summary in {directory}")
        ET.parse(directory / "junit.xml")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-repository", type=Path, required=True)
    args = parser.parse_args()
    repository = args.maven_repository.resolve(strict=True)
    version, _, _ = consumer.project_versions()
    _, _, cli = consumer.candidate_artifacts(version, repository)
    cli = cli.resolve(strict=True)
    cases = (
        ("external-mvc", ("run-external.py", "--stack", "mvc"), ("assessment",), False),
        ("external-webflux", ("run-external.py", "--stack", "webflux"), ("assessment",), False),
        ("framework-mvc", ("run-framework.py", "spring", "--stack", "mvc"), ("orders", "catalog"), False),
        ("framework-webflux", ("run-framework.py", "spring", "--stack", "webflux"), ("orders", "catalog"), False),
        ("framework-quarkus", ("run-framework.py", "quarkus"), ("orders", "catalog"), False),
        ("failed-workload", ("run-external.py", "--stack", "mvc"), ("assessment",), True),
    )
    with tempfile.TemporaryDirectory(prefix="bootui-ci-kit-") as directory:
        root = Path(directory).resolve(strict=True)
        kit = root / "kit"
        kit.mkdir()
        runs = root / "runs"
        runs.mkdir()
        materialize(kit, version)
        for name, command, reports, workload_failure in cases:
            state = runs / name
            env = dict(os.environ)
            env.pop("CI_EXAMPLE_FAIL_WORKLOAD", None)
            env.update(
                BOOTUI_VERSION=version,
                BOOTUI_CLI_JAR=str(cli),
                BOOTUI_MAVEN_REPO_LOCAL=str(repository),
                BOOTUI_CI_ROOT=str(state),
                BOOTUI_ASSESSMENT_ID="candidate-" + name,
            )
            if workload_failure:
                env["CI_EXAMPLE_FAIL_WORKLOAD"] = "true"
            print(f"Testing source-candidate starter kit: {name}", flush=True)
            code, output = run([sys.executable, *command], cwd=kit, env=env)
            expected = 1 if workload_failure else 0
            if code != expected:
                logs = []
                for filename in ("maven.log", "application.log"):
                    path = state / filename
                    if path.is_file():
                        logs.append(f"{filename}:\n{path.read_text(encoding='utf-8', errors='replace')[-12000:]}")
                raise AssertionError(
                    f"{name} exited {code}, expected {expected}\n{output[-12000:]}\n" + "\n".join(logs)
                )
            verify_reports(state, reports, expected)
            gate, gate_output = run([sys.executable, "check-result.py"], cwd=kit, env=env)
            consumer.require(gate == expected, f"{name} produced the wrong final gate:\n{gate_output}")
    print("All starter-kit recipes passed against the source candidate; release downloads were not exercised.")


if __name__ == "__main__":
    raise SystemExit(main())
