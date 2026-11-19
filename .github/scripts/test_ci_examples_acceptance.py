import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("ci-example-acceptance.py")
SPEC = importlib.util.spec_from_file_location("ci_example_acceptance", SCRIPT)
acceptance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acceptance)


class ExampleAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def write_reports(self, status="PASS", workload=0, assessment=0):
        (self.root / "result.json").write_text(json.dumps({
            "workloadExit": workload, "assessmentExit": assessment,
        }))
        reports = self.root / "artifacts/assessment"
        reports.mkdir(parents=True)
        (reports / "bundle.json").write_text(json.dumps({"status": status}))
        (reports / "summary.md").write_text("Assessment")
        (reports / "junit.xml").write_text("<testsuite/>")
        (reports / "sarif.json").write_text('{"version":"2.1.0"}')
        return reports

    def test_candidate_materialization_does_not_create_release_metadata(self):
        acceptance.materialize(self.root, "9.9.9")
        self.assertIn("<bootui.version>9.9.9</bootui.version>", (self.root / "spring/pom.xml").read_text())
        self.assertFalse((self.root / "ci-release.json").exists())
        self.assertTrue((self.root / ".mvn/wrapper/maven-wrapper.properties").is_file())

    def test_accepts_complete_reports_and_preserves_a_failed_workload(self):
        self.write_reports(workload=1)
        acceptance.verify_reports(self.root, ("assessment",), 1)

    def test_rejects_not_assessed_or_incomplete_reports(self):
        reports = self.write_reports(status="NOT_ASSESSED")
        with self.assertRaisesRegex(AssertionError, "Bundle did not pass"):
            acceptance.verify_reports(self.root, ("assessment",), 0)
        (reports / "junit.xml").unlink()
        with self.assertRaisesRegex(AssertionError, "Incomplete reports"):
            acceptance.verify_reports(self.root, ("assessment",), 0)

    def test_rejects_unexpected_artifacts(self):
        self.write_reports()
        (self.root / "artifacts/leaked-log").write_text("unexpected")
        with self.assertRaisesRegex(AssertionError, "Unexpected reports"):
            acceptance.verify_reports(self.root, ("assessment",), 0)


if __name__ == "__main__":
    unittest.main()
