import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("agent-changes.sh")
ROOT = SCRIPT.parents[2]
ENGINE = "bootui-engine/src/main/java/io/github/jdubois/bootui/engine/"

# Files whose changes can change what the agent legs observe (the agent ITs, the agent-attached Playwright legs, and the
# overhead benchmark). Each must exist, so a rename fails here instead of silently leaving the path list behind.
AGENT_EVIDENCE = [
    "bootui-agent/pom.xml",
    "bootui-agent-bridge/pom.xml",
    ENGINE + "javaagent/AgentSensorSettings.java",
    ENGINE + "journal/AgentEvidence.java",
    ENGINE + "journal/AsyncHandoffPayload.java",
    ENGINE + "journal/JournalActivityCapture.java",
    ENGINE + "journal/RunSideEffects.java",
    ENGINE + "journal/RunSummary.java",
    ENGINE + "journal/RunSummaryCodec.java",
    ENGINE + "model/RunEdgeDiff.java",
    ENGINE + "model/SideEffectAccess.java",
    ENGINE + "insights/ChangeImpactService.java",
    ENGINE + "insights/RuntimeInsightsAgentView.java",
    ENGINE + "insights/WorkAfterResponse.java",
    ENGINE + "exceptions/CaughtExceptionsReader.java",
    ENGINE + "web/LiveActivityAssembler.java",
    ENGINE + "mcp/McpAgentViews.java",
    "bootui-spring-boot-starter/src/main/java/io/github/jdubois/bootui/autoconfigure/BootUiEngineConfiguration.java",
    "bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/BootUiEngineProducer.java",
    "bootui-ui/src/main/frontend/src/views/JavaAgent.vue",
    "bootui-ui/src/main/frontend/src/views/components/CaughtInCode.vue",
    "bootui-spring-sample-app/e2e/tests-agent/work-after-response.spec.js",
    "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/AgentOverheadBenchmarkIT.java",
    "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SampleExecutableJar.java",
    "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SampleExecutableJarReadinessTests.java",
    "bootui-quarkus-deployment/src/test/java/io/github/jdubois/bootui/quarkus/deployment/devmode/BootUiAgentLiveReloadLeakIT.java",
    "bootui-quarkus/pom.xml",
    "bootui-quarkus-parent/pom.xml",
    "bootui-quarkus-deployment/pom.xml",
    "bootui-spring-webflux-sample-app/pom.xml",
    "bootui-quarkus-sample-app/e2e/tests-agent/work-after-response.spec.js",
    ".github/workflows/build.yml",
    ".github/workflows/jdk-compatibility.yml",
    ".github/scripts/agent-changes.sh",
    ".github/scripts/test_agent_changes.py",
]

# Files the agent legs don't depend on: a pull request changing only these must not queue them.
UNRELATED = [
    ENGINE + "flyway/FlywayService.java",
    ENGINE + "web/HttpProbeService.java",
    ENGINE + "mcp/McpDispatcher.java",
    "bootui-ui/src/main/frontend/src/views/Flyway.vue",
    "docs/features/java-agent.md",
    "CHANGELOG.md",
    ".github/scripts/check-release-integrity.sh",
    "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SampleExecutableJarOtherTests.java",
    "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SampleExecutableJar.java.bak",
]

OVERHEAD_EVIDENCE = [
    "bootui-agent/pom.xml",
    "bootui-agent-bridge/pom.xml",
    ENGINE + "javaagent/AgentSensorSettings.java",
    ENGINE + "journal/JournalActivityCapture.java",
    "bootui-spring-boot-starter/src/main/java/io/github/jdubois/bootui/autoconfigure/BootUiEngineConfiguration.java",
    "bootui-spring-sample-app/src/main/java/io/github/jdubois/bootui/sample/sideeffects/BenchmarkIo.java",
    "bootui-spring-sample-app/src/main/java/io/github/jdubois/bootui/sample/caught/CaughtBenchmark.java",
    "bootui-spring-sample-app/src/main/resources/application.properties",
    *[
        "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/" + name + ".java"
        for name in [
            "AgentOverheadBenchmarkIT",
            "JournalOverheadBenchmarkIT",
            "CaptureOverheadBenchmarkTest",
            "SampleExecutableJar",
            "SampleExecutableJarReadinessTests",
        ]
    ],
    "pom.xml",
    "bootui-engine/pom.xml",
    "bootui-spring-boot-starter/pom.xml",
    "bootui-spring-sample-app/pom.xml",
    "bootui-ui/pom.xml",
    "mvnw",
    ".mvn/wrapper/maven-wrapper.properties",
    ".github/workflows/build.yml",
    ".github/workflows/jdk-compatibility.yml",
    ".github/scripts/agent-changes.sh",
    ".github/scripts/test_agent_changes.py",
]

INTEGRATION_ONLY = [
    ENGINE + "insights/ChangeImpactService.java",
    ENGINE + "mcp/McpAgentViews.java",
    "bootui-ui/src/main/frontend/src/views/JavaAgent.vue",
    "bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/BootUiEngineProducer.java",
    "bootui-spring-sample-app/e2e/tests-agent/work-after-response.spec.js",
    "bootui-spring-sample-app/src/test/java/io/github/jdubois/bootui/sample/SpringAgentScenarioIT.java",
    "bootui-quarkus-deployment/src/test/java/io/github/jdubois/bootui/quarkus/deployment/devmode/BootUiAgentLiveReloadLeakIT.java",
]

NONE = {"agent": "false", "overhead": "false", "extras": "false"}
INTEGRATION = {"agent": "true", "overhead": "false", "extras": "false"}
OVERHEAD = {"agent": "true", "overhead": "true", "extras": "false"}
ALL = {"agent": "true", "overhead": "true", "extras": "true"}


def run(*arguments, stdin="", env=None, cwd=None):
    return subprocess.run(
        ["bash", str(SCRIPT), *arguments],
        input=stdin,
        capture_output=True,
        text=True,
        env={"PATH": os.environ["PATH"], **(env or {})},
        cwd=cwd,
        check=False,
    )


class AgentChangesTests(unittest.TestCase):
    def test_agent_evidence_files_exist(self):
        missing = [path for path in [*AGENT_EVIDENCE, *OVERHEAD_EVIDENCE, *INTEGRATION_ONLY] if not (ROOT / path).is_file()]
        self.assertEqual(missing, [])

    def test_every_agent_evidence_file_runs_the_agent_legs(self):
        for path in [*AGENT_EVIDENCE, *OVERHEAD_EVIDENCE]:
            with self.subTest(path=path):
                result = run("--match", stdin=path + "\n")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), path)

    def test_unrelated_files_skip_the_agent_legs(self):
        for path in UNRELATED:
            with self.subTest(path=path):
                self.assertEqual(run("--match", stdin=path + "\n").returncode, 1)

    def test_one_agent_file_among_others_runs_them(self):
        changed = "\n".join([*UNRELATED, ENGINE + "journal/RunSummary.java"]) + "\n"
        result = run("--match", stdin=changed)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), ENGINE + "journal/RunSummary.java")

    def test_overhead_inputs_run_the_benchmarks(self):
        for path in OVERHEAD_EVIDENCE:
            with self.subTest(path=path):
                self.assertEqual(run("--match-overhead", stdin=path + "\n").returncode, 0)

    def test_agent_consumers_do_not_run_the_benchmarks(self):
        for path in [*INTEGRATION_ONLY, *UNRELATED]:
            with self.subTest(path=path):
                self.assertEqual(run("--match-overhead", stdin=path + "\n").returncode, 1)

    def outputs(self, env, cwd=None):
        with tempfile.NamedTemporaryFile("r", suffix=".out") as output:
            result = run(env={"GITHUB_OUTPUT": output.name, **env}, cwd=cwd)
            self.assertEqual(result.returncode, 0, result.stderr)
            return dict(line.split("=", 1) for line in output.read().split())

    def pull_request(self, paths, labels=""):
        with tempfile.TemporaryDirectory() as directory:
            gh = Path(directory) / "gh"
            gh.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$TEST_CHANGED_PATHS"\n')
            gh.chmod(0o755)
            return self.outputs({
                "GITHUB_EVENT_NAME": "pull_request",
                "GITHUB_REPOSITORY": "jdubois/boot-ui",
                "PR_NUMBER": "1",
                "PR_LABELS": labels,
                "PATH": directory + os.pathsep + os.environ["PATH"],
                "TEST_CHANGED_PATHS": "\n".join(paths),
            })

    def test_manual_and_scheduled_runs_run_everything(self):
        for event in ["workflow_dispatch", "schedule"]:
            with self.subTest(event=event):
                self.assertEqual(self.outputs({"GITHUB_EVENT_NAME": event}), ALL)

    def test_the_agent_label_runs_the_extras(self):
        self.assertEqual(
            self.outputs({"GITHUB_EVENT_NAME": "pull_request", "PR_LABELS": "documentation,agent"}),
            ALL,
        )

    def test_unrelated_pull_requests_skip_everything(self):
        self.assertEqual(self.pull_request(UNRELATED, labels="agentless"), NONE)
        self.assertEqual(self.pull_request([]), NONE)

    def test_integration_changes_skip_all_overhead_legs(self):
        for path in INTEGRATION_ONLY:
            with self.subTest(path=path):
                self.assertEqual(self.pull_request([path]), INTEGRATION)

    def test_overhead_changes_run_basic_benchmarks_without_extras_on_pull_requests(self):
        for path in OVERHEAD_EVIDENCE:
            with self.subTest(path=path):
                self.assertEqual(self.pull_request([*UNRELATED, path]), OVERHEAD)

    def test_main_and_v2_pull_requests_use_the_same_path_policy(self):
        # A stale FORCE_ALL must not restore the old all-lanes-on-main policy.
        for branch in ["main", "v2"]:
            with self.subTest(branch=branch):
                with tempfile.TemporaryDirectory() as directory:
                    gh = Path(directory) / "gh"
                    gh.write_text('#!/usr/bin/env bash\nprintf "README.md\\n"\n')
                    gh.chmod(0o755)
                    self.assertEqual(self.outputs({
                        "GITHUB_EVENT_NAME": "pull_request",
                        "GITHUB_REPOSITORY": "jdubois/boot-ui",
                        "PR_NUMBER": "1",
                        "GITHUB_BASE_REF": branch,
                        "FORCE_ALL": "true",
                        "PATH": directory + os.pathsep + os.environ["PATH"],
                    }), NONE)

    def git(self, directory, *arguments):
        result = subprocess.run(
            ["git", "-c", "user.name=CI Test", "-c", "user.email=ci@example.invalid",
             "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *arguments],
            cwd=directory, capture_output=True, text=True, check=True,
        )
        return result.stdout.strip()

    def commit_file(self, directory, path):
        file = Path(directory) / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text("fixture\n")
        self.git(directory, "add", "--", path)
        self.git(directory, "commit", "-qm", "fixture")
        return self.git(directory, "rev-parse", "HEAD")

    def test_pushes_use_all_commits_in_the_push(self):
        for paths, expected in [
            (["README.md"], NONE),
            ([INTEGRATION_ONLY[0]], INTEGRATION),
            (["bootui-agent/src/main/java/Agent.java", "README.md"], ALL),
            ([OVERHEAD_EVIDENCE[3]], ALL),
        ]:
            with self.subTest(paths=paths), tempfile.TemporaryDirectory() as directory:
                self.git(directory, "init", "-q")
                before = self.commit_file(directory, "initial.txt")
                for path in paths:
                    after = self.commit_file(directory, path)
                self.assertEqual(self.outputs({
                    "GITHUB_EVENT_NAME": "push",
                    "PUSH_BEFORE": before,
                    "PUSH_AFTER": after,
                }, cwd=directory), expected)

    def test_push_renames_and_deletions_cannot_bypass_the_gate(self):
        for delete in [False, True]:
            with self.subTest(delete=delete), tempfile.TemporaryDirectory() as directory:
                self.git(directory, "init", "-q")
                path = "bootui-agent/fixture.txt"
                before = self.commit_file(directory, path)
                if delete:
                    self.git(directory, "rm", "--", path)
                else:
                    self.git(directory, "mv", "--", path, "README.md")
                self.git(directory, "commit", "-qm", "remove agent path")
                self.assertEqual(self.outputs({
                    "GITHUB_EVENT_NAME": "push",
                    "PUSH_BEFORE": before,
                    "PUSH_AFTER": self.git(directory, "rev-parse", "HEAD"),
                }, cwd=directory), ALL)

    def test_new_branches_run_everything(self):
        self.assertEqual(self.outputs({
            "GITHUB_EVENT_NAME": "push", "PUSH_BEFORE": "0" * 40, "PUSH_AFTER": "1" * 40,
        }), ALL)

    def test_missing_or_invalid_push_commits_fail(self):
        for env in [{}, {"PUSH_BEFORE": "not-a-commit", "PUSH_AFTER": "also-not-a-commit"}]:
            with self.subTest(env=env):
                result = run(env={"GITHUB_EVENT_NAME": "push", **env}, cwd=ROOT)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("agent=false", result.stdout)

    def test_failed_changed_file_lookup_does_not_skip_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            gh = Path(directory) / "gh"
            gh.write_text('#!/usr/bin/env bash\necho "API unavailable" >&2\nexit 1\n')
            gh.chmod(0o755)
            result = run(env={
                "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REPOSITORY": "jdubois/boot-ui", "PR_NUMBER": "1",
                "PATH": directory + os.pathsep + os.environ["PATH"],
            })
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("API unavailable", result.stderr)
            self.assertNotIn("agent=false", result.stdout)

    def job(self, workflow, name):
        source = (ROOT / ".github/workflows" / workflow).read_text()
        match = re.search(r"(?ms)^  " + re.escape(name) + r":\n.*?(?=^  [\w-]+:|\Z)", source)
        self.assertIsNotNone(match, name)
        return match.group()

    def test_workflows_supply_the_whole_push_range(self):
        for workflow in ["build.yml", "jdk-compatibility.yml"]:
            with self.subTest(workflow=workflow):
                changes = self.job(workflow, "changes")
                self.assertIn("fetch-depth: 0", changes)
                self.assertIn("PUSH_BEFORE: ${{ github.event.before }}", changes)
                self.assertIn("PUSH_AFTER: ${{ github.sha }}", changes)
                self.assertNotIn("FORCE_ALL", changes)

    def test_overhead_matrices_and_aggregator_use_the_narrower_gate(self):
        changes = self.job("build.yml", "changes")
        self.assertIn("overhead: ${{ steps.agent.outputs.overhead }}", changes)
        for name in ["agent-overhead-legs", "agent-overhead-extra-legs", "agent-overhead"]:
            with self.subTest(job=name):
                job = self.job("build.yml", name)
                header = job.split("    steps:", 1)[0]
                self.assertIn("needs.changes.outputs.overhead == 'true'", header)
                self.assertNotIn("needs.changes.outputs.agent", header)
        self.assertIn("needs.changes.outputs.extras == 'true'", self.job("build.yml", "agent-overhead-extra-legs"))

    def test_agent_jobs_remain_gated(self):
        for workflow, name in [
            ("build.yml", "agent-integration-tests"),
            ("jdk-compatibility.yml", "agent-e2e"),
        ]:
            with self.subTest(workflow=workflow, job=name):
                self.assertIn("if: needs.changes.outputs.agent == 'true'", self.job(workflow, name))

    def test_general_jdk_checks_are_not_agent_gated(self):
        job = self.job("jdk-compatibility.yml", "compatibility")
        self.assertIn(
            "if: github.event_name != 'pull_request' || github.base_ref == 'main' || needs.changes.outputs.agent == 'true'",
            job,
        )
        for step in ["Agent forked-JVM tests", "Spring sample with the agent attached"]:
            self.assertIn("- name: " + step + "\n        if: needs.changes.outputs.agent == 'true'", job)

    def test_non_agent_packaged_regression_runs_outside_the_agent_gate(self):
        build = self.job("build.yml", "build")
        self.assertIn("-Dit.test=ThreadFactoryExecutableJarIT", build)
        self.assertIn("dependency:properties failsafe:integration-test failsafe:verify", build)
        self.assertIn("**/target/failsafe-reports/TEST-*.xml", build)
        self.assertNotRegex(build, r"(?m)^    (if|needs):")
        self.assertIn("-Dit.test='*IT,!ThreadFactoryExecutableJarIT'", self.job("build.yml", "agent-integration-tests"))


if __name__ == "__main__":
    unittest.main()
