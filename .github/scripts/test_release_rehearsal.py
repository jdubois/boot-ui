import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from rehearse_v2_merge import (  # noqa: E402
    GATE,
    FakeCentral,
    TriggerParseError,
    gate_sentinels,
    parse_triggers,
    push_runs_on,
    runs_on_main,
)

ROOT = Path(__file__).resolve().parents[2]


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


if __name__ == "__main__":
    unittest.main()
