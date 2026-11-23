from fnmatch import fnmatchcase
import os
from pathlib import Path
import re
import subprocess
import tempfile
from textwrap import dedent
import unittest


WORKFLOW = (Path(__file__).resolve().parents[1] / "workflows/build.yml").read_text()
TARGET = Path("bootui-spring-sample-app/target")
AGENT_XML = "TEST-io.github.jdubois.bootui.sample.AgentOverheadBenchmarkIT.xml"
JOURNAL_XML = "TEST-io.github.jdubois.bootui.sample.JournalOverheadBenchmarkIT.xml"


def step(name):
    return WORKFLOW.split(f"\n      - name: {name}\n", 1)[1].split("\n      - ", 1)[0]


def value(step_text, key):
    match = re.search(rf"^          {key}: (.+)$", step_text, re.MULTILINE)
    return match[1] if match else None


def block(step_text, key, indent):
    return dedent(re.search(rf"^ +{key}: \|\n((?:^ {{{indent}}}.*\n?)+)", step_text, re.MULTILINE)[1])


def write_files(root, files):
    for relative, content in files.items():
        file = root / relative
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)


class AgentOverheadReportsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.summary_job = self.root / "summary-job"
        self.summary_job.mkdir()
        self.summary = self.summary_job / "summary.md"
        self.summary.touch()

    def run_step(self, name, cwd=None, **env):
        return subprocess.run(
            ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", block(step(name), "run", 10)],
            cwd=cwd or self.summary_job,
            env={"PATH": os.environ["PATH"], "GITHUB_STEP_SUMMARY": str(self.summary), **env},
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_passed(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def upload(self, directory, leg, files):
        producer = self.root / leg
        write_files(producer / TARGET, files)
        (producer / TARGET / directory).mkdir(parents=True, exist_ok=True)
        matrix = {
            "leg.reports": directory,
            "leg.id": leg,
            "leg.it": "JournalOverheadBenchmarkIT" if directory == "journal-overhead" else "AgentOverheadBenchmarkIT",
        }

        def expand(expression):
            return re.sub(r"\$\{\{ matrix\.(leg\.[\w-]+) \}\}", lambda match: matrix[match[1]], expression)

        staging = step("Stage the leg's report")
        self.assert_passed(self.run_step(
            "Stage the leg's report", cwd=producer,
            **{key: expand(value(staging, key)) for key in ("LEG_ARTIFACT", "LEG_REPORTS", "LEG_TEST")},
        ))
        upload = step("Upload the leg's report")
        archive_root = producer / value(upload, "path")
        contents = {file.relative_to(archive_root): file.read_text() for file in archive_root.rglob("*") if file.is_file()}
        return expand(value(upload, "name")), contents

    def download(self, *uploads):
        # Model only the actions' layout: a directory upload archives relative to that directory, and a singleton
        # pattern match (or merge-multiple) extracts directly to path. Empty uploads never create an archive.
        download = step("Download the legs' reports")
        archives = [(name, files) for name, files in uploads if files and fnmatchcase(name, value(download, "pattern"))]
        root = self.summary_job / value(download, "path")
        for name, files in archives:
            folder = root if len(archives) == 1 or value(download, "merge-multiple") == "true" else root / name
            write_files(folder, files)

    def published_files(self, name):
        files = set()
        for glob in block(step(name), "path", 12).splitlines():
            for path in self.summary_job.glob(glob):
                files.update(path.rglob("*") if path.is_dir() else [path])
        return {file.relative_to(self.summary_job): file.read_text() for file in files if file.is_file()}

    def gate(self, required, extras=False, extra_result="skipped"):
        return self.run_step(
            "Check that every leg passed",
            LEGS_RESULT=required, EXTRAS=str(extras).lower(), EXTRA_LEGS_RESULT=extra_result,
        )

    def test_single_survivor_still_summarized_when_another_required_leg_failed(self):
        report = "# Surviving I/O report\n"
        props = "overheadPercent=12\nbudgetPercent=10\n"
        self.download(self.upload("agent-overhead", "spring-mvc-agent-io", {
            "agent-overhead/spring-mvc-agent-io.md": report,
            "agent-overhead/spring-mvc-agent-io.properties": props,
            f"failsafe-reports/{AGENT_XML}": "<io/>",
            f"failsafe-reports/{JOURNAL_XML}": "<unrelated/>",
        }))
        self.assert_passed(self.run_step("Gather the legs' reports"))
        summary = self.run_step("Publish the overhead report")
        self.assert_passed(summary)
        self.assertEqual(self.summary.read_text(), report)
        self.assertIn("above its 10 % limit", summary.stdout)
        files = self.published_files("Upload the overhead report")
        leg = "agent-overhead-legs/agent-overhead-leg-agent-overhead--spring-mvc-agent-io"
        self.assertEqual(files, {
            TARGET / f"{leg}/failsafe-reports/{AGENT_XML}": "<io/>",
            TARGET / "agent-overhead/spring-mvc-agent-io.md": report,
            TARGET / "agent-overhead/spring-mvc-agent-io.properties": props,
        })
        failure = self.gate("failure")
        self.assertEqual(failure.returncode, 1)
        self.assertIn("At least one agent overhead leg did not pass (failure)", failure.stdout)
        self.assert_passed(self.gate("success"))

    def test_multiple_legs_keep_report_categories_and_diagnostic_sources_separate(self):
        self.download(
            self.upload("agent-overhead", "spring-mvc-agent", {
                "agent-overhead/spring-mvc-agent.md": "# Default\n",
                "agent-overhead/summary.properties": "overheadPercent=8\nbudgetPercent=10\n",
                f"failsafe-reports/{AGENT_XML}": "<default/>",
            }),
            self.upload("agent-overhead", "spring-mvc-agent-io", {f"failsafe-reports/{AGENT_XML}": "<io/>"}),
            self.upload("journal-overhead", "spring-mvc-journal", {
                "journal-overhead/spring-mvc-journal.md": "# Journal\n",
                "journal-overhead/spring-mvc-journal.properties": "verdict=FAIL\n",
                f"failsafe-reports/{JOURNAL_XML}": "<journal/>",
            }),
        )
        self.assert_passed(self.run_step("Gather the legs' reports"))
        self.assert_passed(self.run_step("Publish the overhead report"))
        journal = self.run_step("Publish the journal overhead report")
        self.assert_passed(journal)
        self.assertIn("Report-only.", journal.stdout)
        self.assertIn("# Default\n# Journal\n", self.summary.read_text())
        agent_files = self.published_files("Upload the overhead report")
        journal_files = self.published_files("Upload the journal overhead report")
        self.assertEqual(sorted(content for file, content in agent_files.items() if file.name == AGENT_XML), ["<default/>", "<io/>"])
        self.assertIn("<journal/>", journal_files.values())
        self.assertNotIn("<journal/>", agent_files.values())
        self.assertTrue(agent_files.keys().isdisjoint(journal_files))
        self.assert_passed(self.gate("success", extras=True, extra_result="success"))
        self.assertEqual(self.gate("success", extras=True, extra_result="failure").returncode, 1)

    def test_duplicate_report_still_fails_collection(self):
        self.download(*[
            self.upload("agent-overhead", leg, {"agent-overhead/summary.properties": leg})
            for leg in ("spring-mvc-agent", "spring-mvc-agent-io")
        ])
        result = self.run_step("Gather the legs' reports")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Two legs wrote agent-overhead/summary.properties", result.stdout)

    def test_no_usable_report_warns_and_retains_any_diagnostics(self):
        for diagnostics in ({}, {f"failsafe-reports/{JOURNAL_XML}": "<journal/>"}):
            with self.subTest(files=diagnostics):
                self.download(self.upload("journal-overhead", "spring-mvc-journal", diagnostics))
                self.assert_passed(self.run_step("Gather the legs' reports"))
                summary = self.run_step("Publish the journal overhead report")
                self.assert_passed(summary)
                self.assertIn("wrote no report", summary.stdout)
                self.assertEqual(self.summary.read_text(), "")
                self.assertEqual(list(self.published_files("Upload the journal overhead report").values()), list(diagnostics.values()))
