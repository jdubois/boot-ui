import os
from pathlib import Path
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
]


def run(*arguments, stdin="", env=None):
    return subprocess.run(
        ["bash", str(SCRIPT), *arguments],
        input=stdin,
        capture_output=True,
        text=True,
        env={"PATH": os.environ["PATH"], **(env or {})},
        check=False,
    )


class AgentChangesTests(unittest.TestCase):
    def test_agent_evidence_files_exist(self):
        missing = [path for path in AGENT_EVIDENCE if not (ROOT / path).is_file()]
        self.assertEqual(missing, [])

    def test_every_agent_evidence_file_runs_the_agent_legs(self):
        for path in AGENT_EVIDENCE:
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

    def outputs(self, env):
        with tempfile.NamedTemporaryFile("r", suffix=".out") as output:
            result = run(env={"GITHUB_OUTPUT": output.name, **env})
            self.assertEqual(result.returncode, 0, result.stderr)
            return dict(line.split("=", 1) for line in output.read().split())

    def test_pushes_run_everything(self):
        self.assertEqual(self.outputs({"GITHUB_EVENT_NAME": "push"}), {"agent": "true", "extras": "true"})

    def test_the_agent_label_runs_the_extras(self):
        self.assertEqual(
            self.outputs({"GITHUB_EVENT_NAME": "pull_request", "PR_LABELS": "documentation,agent"}),
            {"agent": "true", "extras": "true"},
        )


if __name__ == "__main__":
    unittest.main()
