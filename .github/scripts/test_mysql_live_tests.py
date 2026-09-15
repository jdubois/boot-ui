import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("mysql_live", Path(__file__).with_name("check-mysql-live-tests.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MySqlLiveEvidenceTests(unittest.TestCase):
    def check_report(self, content):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            if content is not None:
                (reports / "TEST-live.xml").write_text(content, encoding="utf-8")
            return MODULE.check(reports, ["MySqlLiveTests"])

    def test_requires_a_report(self):
        self.assertTrue(self.check_report(None))

    def test_requires_actual_cases(self):
        self.assertTrue(self.check_report('<testsuite name="MySqlLiveTests" tests="0"/>'))

    def test_accepts_passing_live_cases(self):
        self.assertEqual(
            self.check_report(
                '<testsuite name="example.MySqlLiveTests" tests="1" skipped="0" errors="0" failures="0">'
                '<testcase name="actualMysql"/></testsuite>'
            ),
            [],
        )

    def test_rejects_skips_even_if_summary_omits_them(self):
        self.assertTrue(
            self.check_report(
                '<testsuite name="MySqlLiveTests" tests="1">'
                '<testcase name="actualMysql"><skipped/></testcase></testsuite>'
            )
        )

    def test_rejects_failed_cases(self):
        self.assertTrue(
            self.check_report(
                '<testsuite name="MySqlLiveTests" tests="1" failures="1">'
                '<testcase name="actualMysql"><failure/></testcase></testsuite>'
            )
        )

    def test_rejects_inconsistent_and_invalid_counts(self):
        for count in ("2", "invalid"):
            with self.subTest(count=count):
                self.assertTrue(
                    self.check_report(
                        f'<testsuite name="MySqlLiveTests" tests="{count}">'
                        '<testcase name="actualMysql"/></testsuite>'
                    )
                )

    def test_rejects_unrelated_or_malformed_reports(self):
        self.assertTrue(self.check_report('<testsuite name="OtherTests" tests="1"><testcase/></testsuite>'))
        self.assertTrue(self.check_report("not XML"))


if __name__ == "__main__":
    unittest.main()
