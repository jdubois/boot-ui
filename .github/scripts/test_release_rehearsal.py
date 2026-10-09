import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import rehearse_v2_merge as rehearsal  # noqa: E402
from rehearse_v2_merge import (  # noqa: E402
    GATE,
    FakeCentral,
    TriggerParseError,
    check_sign_off,
    gate_sentinels,
    parse_triggers,
    push_runs_on,
    runs_on_main,
)

ROOT = Path(__file__).resolve().parents[2]
SIGN_OFF = """## Release sign-off

| Field | Value |
| --- | --- |
| Release candidate | `0123456789012345678901234567890123456789` (version `1.21.0`) |
| Decision | APPROVE_RELEASE_2_0_0 |
| Decision rationale | Fixture only: the maintainer reviewed the evidence and exceptions. |

### Sign-off

| Role | Name | Date |
| --- | --- | --- |
| Maintainer | Fixture maintainer | 2026-01-01 |
"""


def runs(workflow):
    return runs_on_main(parse_triggers(workflow))


class TriggerTests(unittest.TestCase):
    """rehearse_v2_merge.py must recognize every way a workflow can run on a push to main."""

    def test_push_without_a_branch_filter_runs_on_main(self):
        for workflow in (
            "on: push\njobs: {}\n",
            "on: [push, pull_request]\njobs: {}\n",
            "on:\n  push:\n  workflow_dispatch:\njobs: {}\n",
            "on:\n  push: {}\njobs: {}\n",
            "'on':\n  push:\n    paths: [ 'docs/**' ]\njobs: {}\n",
        ):
            with self.subTest(workflow=workflow):
                self.assertTrue(runs(workflow))

    def test_flow_and_block_branch_lists(self):
        for workflow, expected in (
            ("on:\n  push:\n    branches: [ main, v2 ]\n", True),
            ("on:\n  push:\n    branches: [ 'v2' ]\n", False),
            ("on:\n  push:\n    branches:\n      - v2\n      - main  # default\n", True),
            ("on:\n  push:\n    branches:\n      - 'release/**'\n", False),
            ("on:\n  push:\n    branches:\n      - '**'\n      - '!main'\n", False),
            ("on:\n  push:\n    branches: [ 'ma*' ]\n", True),
            ("on:\n  push:\n    branches-ignore: [ v2 ]\n", True),
            ("on:\n  push:\n    branches-ignore:\n      - main\n", False),
            ("on:\n  push:\n    tags: [ 'v*' ]\n", False),
            ("on:\n  push:\n    tags: [ 'v*' ]\n    branches: [ main ]\n", True),
        ):
            with self.subTest(workflow=workflow):
                self.assertEqual(runs(workflow), expected)

    def test_default_branch_events_run_on_main(self):
        for workflow in (
            "on:\n  schedule:\n    - cron: '17 7 * * *'  # daily\n",
            "on:\n  workflow_run:\n    workflows: [ Build ]\n    types: [ completed ]\n",
            "on: repository_dispatch\n",
        ):
            with self.subTest(workflow=workflow):
                self.assertTrue(runs(workflow))

    def test_events_that_never_run_on_main_by_themselves(self):
        self.assertFalse(runs("on:\n  pull_request:\n    branches: [ main ]\n  workflow_dispatch:\n"))

    def test_release_workflow_only_runs_on_tags(self):
        release = (Path(__file__).resolve().parents[1] / "workflows/release.yml").read_text(encoding="utf-8")
        push = parse_triggers(release)["push"]
        for branch in ("main", "v2", "1.x"):
            self.assertFalse(push_runs_on(push, branch))

    def test_unreadable_triggers_are_reported_not_skipped(self):
        for workflow in (
            "name: no trigger\njobs: {}\n",
            "on: { push: { branches: [ main ] } }\n",
            "on:\n  push:\n    branches: main\n",
            "on:\n  push:\n    branches:\n      main\n",
        ):
            with self.subTest(workflow=workflow), self.assertRaises(TriggerParseError):
                parse_triggers(workflow)



class FakeCentralTests(unittest.TestCase):
    """The simulated futures publish exactly what the gate under test looks for on Maven Central."""

    def test_the_sentinels_come_from_the_gate(self):
        sentinels = gate_sentinels((ROOT / GATE).read_text(encoding="utf-8"))
        self.assertEqual(sentinels, ("bootui-engine", "bootui-spring-boot-starter"))
        self.assertNotIn("bootui-core", sentinels)

    def test_a_gate_without_sentinels_is_an_error(self):
        with self.assertRaises(ValueError):
            gate_sentinels("central_state() { :; }\n")

    def test_publish_writes_every_sentinel_jar(self):
        central = FakeCentral(("bootui-engine", "bootui-spring-boot-starter"))
        try:
            central.publish("2.0.0")
            root = Path(central.directory.name) / "com/julien-dubois/bootui"
            self.assertTrue((root / "bootui-engine/2.0.0/bootui-engine-2.0.0.jar").is_file())
            self.assertTrue((root / "bootui-spring-boot-starter/2.0.0/bootui-spring-boot-starter-2.0.0.jar").is_file())
            self.assertFalse((root / "bootui-core").exists())
        finally:
            central.close()


class SignOffTests(unittest.TestCase):
    """Only an explicit approval with complete candidate and maintainer metadata authorizes release."""

    def check_report(self, report, release_day, expected):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs").mkdir()
            if report is not None:
                (root / "docs/V2-VALIDATION-REPORT.md").write_text(report, encoding="utf-8")
            with patch("rehearse_v2_merge.record") as record:
                check_sign_off(root, release_day)
            self.assertEqual(record.call_count, 1)
            self.assertEqual(record.call_args.args[0], expected)

    def assert_not_authorized(self, report):
        for release_day, expected in ((False, "PENDING"), (True, "FAIL")):
            with self.subTest(release_day=release_day):
                self.check_report(report, release_day, expected)

    def test_complete_explicit_approval_passes(self):
        for release_day in (False, True):
            with self.subTest(release_day=release_day):
                self.check_report(SIGN_OFF, release_day, "PASS")
                for rationale in (
                    "Maintainer reviewed the evidence and accepted the documented exceptions.",
                    "None of the remaining limitations blocks release; documented exceptions accepted.",
                ):
                    self.check_report(
                        SIGN_OFF.replace(
                            "Fixture only: the maintainer reviewed the evidence and exceptions.", rationale
                        ),
                        release_day,
                        "PASS",
                    )

    def test_non_positive_and_ambiguous_decisions_never_authorize(self):
        for decision in (
            "HOLD",
            "PENDING",
            "NoRelease",
            "Do not release 2.0.0: further validation required",
            "Release 2.0.0?",
            "Release 2.0.0 if further validation passes",
            "APPROVE_RELEASE_2_0_0 if CI passes",
            "Not APPROVE_RELEASE_2_0_0",
            "APPROVE_RELEASE_2_0_0 / HOLD",
            "TODO",
            "",
        ):
            with self.subTest(decision=decision):
                self.assert_not_authorized(SIGN_OFF.replace("APPROVE_RELEASE_2_0_0", decision))

    def test_missing_or_duplicate_decision_never_authorizes(self):
        decision = "| Decision | APPROVE_RELEASE_2_0_0 |\n"
        for report in (SIGN_OFF.replace(decision, ""), SIGN_OFF.replace(decision, decision * 2)):
            self.assert_not_authorized(report)

    def test_empty_or_invalid_maintainer_metadata_never_authorizes(self):
        for name, date in (
            ("", "2026-01-01"),
            ("TODO", "2026-01-01"),
            ("TODO: maintainer", "2026-01-01"),
            ("Pending", "2026-01-01"),
            ("**PENDING**", "2026-01-01"),
            ("N/A", "2026-01-01"),
            ("-", "2026-01-01"),
            ("` `", "2026-01-01"),
            ("...", "2026-01-01"),
            ("<name>", "2026-01-01"),
            ("Fixture maintainer", ""),
            ("Fixture maintainer", "TODO"),
            ("Fixture maintainer", "2026-02-30"),
            ("Fixture maintainer", "2026-13-01"),
            ("Fixture maintainer", "20260101"),
            ("Fixture maintainer", "01/01/2026"),
            ("Fixture maintainer", "9999-12-31"),
        ):
            with self.subTest(name=name, date=date):
                self.assert_not_authorized(
                    SIGN_OFF.replace("Fixture maintainer | 2026-01-01", f"{name} | {date}")
                )
        signature = "| Maintainer | Fixture maintainer | 2026-01-01 |\n"
        for report in (SIGN_OFF.replace(signature, ""), SIGN_OFF.replace(signature, signature * 2)):
            self.assert_not_authorized(report)

    def test_missing_or_invalid_candidate_never_authorizes(self):
        candidate = "| Release candidate | `0123456789012345678901234567890123456789` (version `1.21.0`) |\n"
        for value in ("", "TODO", "`32f3a6142` (version `1.21.0`)", "`v2`", "not a candidate"):
            with self.subTest(candidate=value):
                self.assert_not_authorized(
                    SIGN_OFF.replace(candidate, f"| Release candidate | {value} |\n")
                )
        for report in (SIGN_OFF.replace(candidate, ""), SIGN_OFF.replace(candidate, candidate * 2)):
            self.assert_not_authorized(report)
        for version in ("", "v1.21.0", "01.21.0", "1.21", "1.21.0-SNAPSHOT", "1.2.3?"):
            with self.subTest(version=version):
                self.assert_not_authorized(SIGN_OFF.replace("version `1.21.0`", f"version `{version}`"))

    def test_missing_or_placeholder_rationale_never_authorizes(self):
        rationale = "| Decision rationale | Fixture only: the maintainer reviewed the evidence and exceptions. |\n"
        for replacement in (
            "",
            "| Decision rationale | |\n",
            "| Decision rationale | TODO |\n",
            "| Decision rationale | **PENDING** |\n",
            "| Decision rationale | ... |\n",
        ):
            self.assert_not_authorized(SIGN_OFF.replace(rationale, replacement))

    def test_unreadable_missing_or_duplicate_section_never_authorizes(self):
        for report in (None, "# Report\n", SIGN_OFF * 2):
            self.assert_not_authorized(report)

    def test_repository_report_remains_pending(self):
        self.assert_not_authorized((ROOT / "docs/V2-VALIDATION-REPORT.md").read_text(encoding="utf-8"))


class ReleaseRunbookTests(unittest.TestCase):
    def test_preflight_cut_permissions_and_strict_rehearsal_precede_merge(self):
        release_day = (ROOT / "docs/V2-RELEASE.md").read_text(encoding="utf-8").split("## Release day\n")[1]
        steps = (
            "**Read-only preflight.**",
            "**Cut `1.x` and apply its settings.**",
            "**Strict live rehearsal.**",
            "**Merge `v2` into `main`**",
        )
        positions = [release_day.index(step) if step in release_day else -1 for step in steps]
        self.assertTrue(all(position >= 0 for position in positions), positions)
        self.assertEqual(positions, sorted(positions))
        self.assertIn("main remains frozen", release_day.replace("`", ""))


class LiveAuthorizationTests(unittest.TestCase):
    """Exercise dispatch ordering without fetching, pushing, or contacting GitHub or Maven Central."""

    def test_live_dispatch_requires_no_failed_or_pending_prerequisite(self):
        for status, release_day in (("PENDING", False), ("FAIL", True), ("PASS", True)):
            with self.subTest(status=status, release_day=release_day):
                self.check_live(status, release_day)

    def check_live(self, status, release_day):
        def fake_git(*arguments, **kwargs):
            output = ""
            if arguments == ("rev-parse", "--show-toplevel"):
                output = str(ROOT)
            elif arguments == ("--version",):
                output = "git version 2.50.0"
            elif arguments[0] in ("rev-parse", "commit-tree", "merge-tree"):
                output = "a" * 40
            elif arguments[:2] == ("worktree", "add"):
                gate = Path(arguments[-2]) / GATE
                gate.parent.mkdir(parents=True)
                gate.write_text('"bootui-engine/${version}/bootui-engine-${version}.jar"', encoding="utf-8")
            return SimpleNamespace(returncode=0, stdout=output, stderr="")

        argv = ["rehearse_v2_merge.py", "--live"] + (["--release-day"] if release_day else [])
        with (
            patch.object(sys, "argv", argv),
            patch.object(rehearsal, "git", side_effect=fake_git),
            patch.object(rehearsal, "FakeCentral"),
            patch.object(rehearsal, "results", []),
            patch.object(
                rehearsal, "rehearse",
                side_effect=lambda *args: rehearsal.record(status, "fixture prerequisite"),
            ),
            patch.object(rehearsal, "live") as live,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            result = rehearsal.main()
            if status == "PASS":
                self.assertEqual(result, 0)
                live.assert_called_once()
            else:
                self.assertEqual(result, 1)
                live.assert_not_called()


if __name__ == "__main__":
    unittest.main()
