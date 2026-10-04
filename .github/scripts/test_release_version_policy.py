import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/release-version-policy.sh"

# The situation once 2.0.0 ships while 1.x is still maintained.
AFTER_TWO_ZERO = ["v1.19.0", "v1.20.0", "v2.0.0"]


def run_policy(*arguments, tags=()):
    return subprocess.run(
        ["bash", str(SCRIPT), *arguments],
        input="".join(f"{tag}\n" for tag in tags),
        capture_output=True,
        text=True,
        check=False,
    )


class NextVersionTests(unittest.TestCase):
    def assert_accepted(self, version, current, tags):
        result = run_policy("next-version", version, current, tags=tags)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result

    def assert_rejected(self, version, current, tags):
        result = run_policy("next-version", version, current, tags=tags)
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("::error::", result.stderr)
        return result

    def test_older_major_patch_is_accepted_after_a_newer_major(self):
        result = self.assert_accepted("1.20.1", "1.20.0", AFTER_TWO_ZERO)
        self.assertIn("v1.20.0", result.stdout)

    def test_older_major_minor_is_accepted_after_a_newer_major(self):
        self.assert_accepted("1.21.0", "1.20.0", AFTER_TWO_ZERO)

    def test_newest_major_patch_and_minor_are_accepted(self):
        for version in ("2.0.1", "2.1.0"):
            for current in ("2.0.0", "2.1.0-SNAPSHOT"):
                with self.subTest(version=version, current=current):
                    self.assert_accepted(version, current, AFTER_TWO_ZERO)

    def test_versions_must_strictly_increase_within_a_major(self):
        for version, current in (
            ("1.19.5", "1.20.0"),
            ("1.20.0", "1.20.0"),
            ("1.20.2", "1.20.0"),
            ("1.22.0", "1.20.0"),
            ("2.0.0", "2.0.0"),
            ("2.0.2", "2.0.0"),
            ("2.2.0", "2.0.0"),
        ):
            with self.subTest(version=version):
                self.assert_rejected(version, current, AFTER_TWO_ZERO)

    def test_new_major_must_sit_directly_above_every_existing_major(self):
        self.assert_accepted("3.0.0", "2.0.0", AFTER_TWO_ZERO)
        for version in ("3.0.1", "3.1.0", "4.0.0"):
            with self.subTest(version=version):
                self.assert_rejected(version, "2.0.0", AFTER_TWO_ZERO)

    def test_skipped_older_major_cannot_be_opened_later(self):
        self.assert_rejected("2.0.0", "2.0.0-SNAPSHOT", ["v1.20.0", "v3.0.0"])

    def test_first_release_of_a_new_major_from_the_previous_major_branch(self):
        # 2.0.0 is released from `main` after `v2` is merged, whatever that branch's version was.
        for current in ("1.20.0", "2.0.0-SNAPSHOT"):
            with self.subTest(current=current):
                self.assert_accepted("2.0.0", current, ["v1.19.0", "v1.20.0"])
        self.assert_rejected("2.0.0", "0.9.0", ["v1.19.0", "v1.20.0"])

    def test_release_must_match_the_source_branch_major(self):
        # A 1.x patch from a 2.x branch, or a 2.x patch from a 1.x branch, would downgrade or
        # mislabel the released contents.
        self.assert_rejected("1.20.1", "2.0.0", AFTER_TWO_ZERO)
        self.assert_rejected("2.0.1", "1.20.0", AFTER_TWO_ZERO)

    def test_previous_single_major_behaviour_is_preserved(self):
        tags = ["v1.13.0", "v1.13.1"]
        for version in ("1.13.2", "1.14.0", "2.0.0"):
            with self.subTest(version=version):
                self.assert_accepted(version, "1.13.1", tags)
        for version in ("1.13.1", "1.13.3", "1.15.0", "3.0.0"):
            with self.subTest(version=version):
                self.assert_rejected(version, "1.13.1", tags)

    def test_numeric_ordering_does_not_depend_on_tag_order(self):
        tags = ["v1.10.0", "v1.9.9", "v1.10.1", "v1.2.30"]
        self.assert_accepted("1.10.2", "1.10.1", tags)
        self.assert_rejected("1.9.10", "1.10.1", tags)

    def test_only_stable_tags_count(self):
        tags = ["v1.20.0", "v2.0.0-RC1", "v1.21.0-SNAPSHOT", "1.30.0", "v1.20", "nightly", ""]
        self.assert_accepted("1.20.1", "1.20.0", tags)
        self.assert_accepted("2.0.0", "1.20.0", tags)

    def test_missing_stable_tags_are_rejected(self):
        self.assert_rejected("1.0.0", "1.0.0-SNAPSHOT", ["v2.0.0-RC1"])

    def test_only_major_minor_patch_versions_are_accepted(self):
        for version in ("v1.20.1", "1.20", "1.20.1-RC1", "1.20.1.1", "01.20.1", "1.020.1", ""):
            with self.subTest(version=version):
                self.assert_rejected(version, "1.20.0", AFTER_TWO_ZERO)

    def test_unreadable_branch_version_is_rejected(self):
        self.assert_rejected("1.20.1", "${project.version}", AFTER_TWO_ZERO)


class NewestMajorTests(unittest.TestCase):
    def decide(self, version, tags):
        result = run_policy("newest-major", version, tags=tags)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def test_newest_major_release_redeploys_documentation(self):
        self.assertEqual(self.decide("2.0.1", AFTER_TWO_ZERO + ["v2.0.1"]), "true")

    def test_older_major_release_does_not_redeploy_documentation(self):
        self.assertEqual(self.decide("1.20.1", AFTER_TWO_ZERO + ["v1.20.1"]), "false")

    def test_release_counts_as_its_own_major(self):
        # The first release of a new major redeploys even before its own tag is listed.
        self.assertEqual(self.decide("2.0.0", ["v1.19.0", "v1.20.0"]), "true")
        self.assertEqual(self.decide("1.20.1", []), "true")

    def test_prerelease_tags_of_a_newer_major_do_not_hide_documentation(self):
        self.assertEqual(self.decide("1.20.1", ["v1.20.0", "v2.0.0-RC1"]), "true")

    def test_invalid_release_version_is_rejected(self):
        result = run_policy("newest-major", "2.0", tags=AFTER_TWO_ZERO)
        self.assertEqual(result.returncode, 2)


class AcceptanceScenarioTests(unittest.TestCase):
    """Tags v1.20.0 and v2.0.0 exist: the M4-16 acceptance scenario."""

    TAGS = ["v1.20.0", "v2.0.0"]

    def test_scenario(self):
        for version, current, accepted, redeploys in (
            ("1.20.1", "1.20.0", True, "false"),
            ("2.0.1", "2.0.0", True, "true"),
            ("1.19.5", "1.20.0", False, None),
            ("2.0.0", "2.0.0", False, None),
        ):
            with self.subTest(version=version):
                result = run_policy("next-version", version, current, tags=self.TAGS)
                self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)
                if accepted:
                    # The documentation decision runs after the release tag has been pushed.
                    decision = run_policy("newest-major", version, tags=self.TAGS + [f"v{version}"])
                    self.assertEqual(decision.returncode, 0, decision.stderr)
                    self.assertEqual(decision.stdout.strip(), redeploys)


class ReleaseLineTests(unittest.TestCase):
    """The branch's release line: `.github/release-line` (M4-23, D40)."""

    def line(self, project_version, content):
        with tempfile.TemporaryDirectory() as directory:
            line_file = Path(directory) / "release-line"
            if content is not None:
                line_file.write_text(content, encoding="utf-8")
            return run_policy("line-major", project_version, str(line_file))

    def test_declared_line_is_read_past_comments(self):
        for project_version, content, expected in (
            ("1.19.0", "1\n", "1"),
            ("1.19.0", "# v2 prepares 2.0.0 on a 1.x version\n\n2  # the next major\n", "2"),
            ("2.0.0", "2", "2"),
            ("2.1.0-SNAPSHOT", "3\n", "3"),
        ):
            with self.subTest(project_version=project_version, content=content):
                result = self.line(project_version, content)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), expected)

    def test_line_must_match_the_project_version_or_the_next_major(self):
        for project_version, content in (("1.19.0", "3\n"), ("2.0.0", "1\n"), ("3.0.0", "2\n")):
            with self.subTest(project_version=project_version, content=content):
                result = self.line(project_version, content)
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn("does not match project version", result.stderr)

    def test_missing_or_malformed_line_fails_closed(self):
        for content in (None, "", "# only a comment\n", "2\n3\n", "v2\n", "2.0\n", "02\n"):
            with self.subTest(content=content):
                result = self.line("1.19.0", content)
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn("::error::", result.stderr)

    def test_a_branch_with_the_java_agent_declares_at_least_line_2(self):
        # A main-to-v2 sync that resolves the release-line conflict to 1 must fail, though 1 matches
        # the 1.x project version: bootui-agent exists only from 2.0.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github").mkdir()
            line_file = root / ".github/release-line"
            for agent, content, expected in ((False, "1\n", 0), (True, "1\n", 2), (True, "2\n", 0)):
                with self.subTest(agent=agent, content=content):
                    if agent:
                        (root / "bootui-agent").mkdir(exist_ok=True)
                        (root / "bootui-agent/pom.xml").write_text("<project/>", encoding="utf-8")
                    line_file.write_text(content, encoding="utf-8")
                    result = run_policy("line-major", "1.19.0", str(line_file))
                    self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                    if expected:
                        self.assertIn("contains bootui-agent", result.stderr)

    def test_main_after_the_v2_merge_releases_only_2_0_0(self):
        tags = ["v1.18.0", "v1.19.0"]
        self.assertEqual(run_policy("next-version", "2.0.0", "1.19.0", "2", tags=tags).returncode, 0)
        for version in ("1.19.1", "1.20.0"):
            with self.subTest(version=version):
                result = run_policy("next-version", version, "1.19.0", "2", tags=tags)
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn("release line 2", result.stderr)

    def test_maintenance_branch_releases_patches_but_never_opens_2_0_0(self):
        before = ["v1.19.0"]
        after = ["v1.19.0", "v2.0.0"]
        for tags in (before, after):
            with self.subTest(tags=tags):
                self.assertEqual(run_policy("next-version", "1.19.1", "1.19.0", "1", tags=tags).returncode, 0)
        result = run_policy("next-version", "2.0.0", "1.19.0", "1", tags=before)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("release line 1", result.stderr)

    def test_main_before_the_merge_cannot_open_2_0_0(self):
        result = run_policy("next-version", "2.0.0", "1.19.0", "1", tags=["v1.19.0"])
        self.assertEqual(result.returncode, 2, result.stdout)

    def test_invalid_release_line_argument_is_rejected(self):
        for line in ("", "x", "1.0", "-1"):
            with self.subTest(line=line):
                self.assertEqual(
                    run_policy("next-version", "1.19.1", "1.19.0", line, tags=["v1.19.0"]).returncode, 2
                )


class LineTagsTests(unittest.TestCase):
    def tags(self, line, tags):
        result = run_policy("line-tags", line, tags=tags)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.splitlines()

    def test_lists_the_line_oldest_first_then_newer_majors(self):
        tags = ["v2.0.0", "v1.10.0", "v0.9.0", "v1.9.9", "v3.0.0", "v2.0.0-RC1", "v1.2.0", "nightly", "v1.10.0"]
        self.assertEqual(
            self.tags("1", tags),
            ["line v1.2.0", "line v1.9.9", "line v1.10.0", "newer v2.0.0", "newer v3.0.0"],
        )
        self.assertEqual(self.tags("2", tags), ["line v2.0.0", "newer v3.0.0"])
        self.assertEqual(self.tags("4", tags), [])

    def test_invalid_line_is_rejected(self):
        self.assertEqual(run_policy("line-tags", "v2", tags=["v2.0.0"]).returncode, 2)


class UsageTests(unittest.TestCase):
    def test_unknown_or_incomplete_commands_are_refused(self):
        for arguments in (
            (),
            ("next-version", "1.0.0"),
            ("next-version", "1.0.0", "1.0.0", "1", "extra"),
            ("newest-major",),
            ("line-major", "1.0.0"),
            ("line-tags",),
            ("latest",),
        ):
            with self.subTest(arguments=arguments):
                result = run_policy(*arguments)
                self.assertEqual(result.returncode, 64, result.stderr)


if __name__ == "__main__":
    unittest.main()
