import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("changelog", Path(__file__).with_name("check-changelog.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

HEADER = "# Changelog\n\n## [Unreleased]\n\n"
RELEASED = "\n## [1.0.0] - 2026-01-01\n\n### Fixed\n\n- **Same title.** Released.\n- **Same title.** Released (M4-20).\n"


class ChangelogTests(unittest.TestCase):
    def test_the_repository_changelog_is_clean(self):
        changelog = Path(__file__).resolve().parents[2] / "CHANGELOG.md"
        self.assertEqual(MODULE.check(changelog.read_text(encoding="utf-8")), [])

    def test_accepts_distinct_entries_and_ignores_released_sections(self):
        text = HEADER + "### Added\n\n- **One.** First.\n- **Two.** Second.\n\n### Fixed\n\n- **Three.** Third.\n"
        self.assertEqual(MODULE.check(text + RELEASED), [])

    def test_rejects_a_repeated_entry_title(self):
        text = HEADER + "### Fixed\n\n- **Same title.** First.\n  More.\n- **Other.** Text.\n- **Same title.** First.\n"
        problems = MODULE.check(text)
        self.assertEqual(len(problems), 1)
        self.assertIn("'Same title.' at lines 7, 10", problems[0])

    def test_rejects_a_repeated_subsection(self):
        text = HEADER + "### Fixed\n\n- **One.** First.\n\n### Fixed\n\n- **Two.** Second.\n"
        problems = MODULE.check(text)
        self.assertEqual(len(problems), 1)
        self.assertIn("'### Fixed' subsection 2 times", problems[0])

    def test_rejects_plan_identifiers_outside_link_targets(self):
        for jargon in ("(PLAN-v2 M5-6)", "(M4-22)", "(D37)", "(PLAN-v2 §5.14)", "(docs/PLAN.md §3.19)"):
            with self.subTest(jargon=jargon):
                problems = MODULE.check(HEADER + f"### Added\n\n- **One.** Text {jargon}.\n")
                self.assertEqual(len(problems), 1)
                self.assertIn("plan identifier", problems[0])

    def test_accepts_links_rfc_sections_and_pull_requests(self):
        text = HEADER + (
            "### Added\n\n- **One.** See [Run comparison](docs/PLAN-v2.md#58-run-comparison) and"
            " [report](docs/V2-VALIDATION-REPORT.md), RFC 9110 §10.2.3"
            " ([#1234](https://github.com/jdubois/boot-ui/pull/1234)).\n"
        )
        self.assertEqual(MODULE.check(text), [])


if __name__ == "__main__":
    unittest.main()
