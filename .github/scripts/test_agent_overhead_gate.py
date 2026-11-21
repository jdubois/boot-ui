import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
SENSORS = Path("bootui-engine/src/main/java/io/github/jdubois/bootui/engine/javaagent/AgentSensorSettings.java")
REPORTS = Path("bootui-spring-sample-app/target/agent-overhead")


def resources_gate():
    workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    step = workflow.split("      - name: Check the resources sensor's default\n", 1)[1]
    script = step.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0]
    return textwrap.dedent(script)


class ResourcesOverheadGateTests(unittest.TestCase):
    def run_gate(self, *, increment=None, cumulative=None, default=True):
        # The failing main run's A/B report: a noisy median above budget, not an interval wholly above it.
        own = {
            "overheadPercent": "5.6",
            "minOverheadPercent": "-16.7",
            "maxOverheadPercent": "26.2",
            "lowOverheadPercent": "1.5",
            "highOverheadPercent": "8.1",
        }
        total = {
            "overheadPercent": "7.3",
            "minOverheadPercent": "3.5",
            "maxOverheadPercent": "10.7",
            "lowOverheadPercent": "4.7",
            "highOverheadPercent": "7.9",
        }
        own.update(increment or {})
        total.update(cumulative or {})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / REPORTS).mkdir(parents=True)
            for name, values in [
                ("spring-mvc-resources-ab", own),
                ("spring-mvc-agent-io-resources", total),
            ]:
                (root / REPORTS / (name + ".properties")).write_text(
                    "".join(f"{key}={value}\n" for key, value in values.items() if value is not None),
                    encoding="utf-8",
                )
            (root / SENSORS).parent.mkdir(parents=True)
            (root / SENSORS).write_text(
                (ROOT / SENSORS).read_text(encoding="utf-8")
                if default
                else 'DEFAULT_SENSORS = Set.of("files");\n',
                encoding="utf-8",
            )
            summary = root / "summary.md"
            result = subprocess.run(
                ["bash", "-e", "-c", resources_gate()],
                cwd=root,
                env={**os.environ, "GITHUB_STEP_SUMMARY": str(summary)},
                capture_output=True,
                text=True,
                check=False,
            )
            return result, summary.read_text(encoding="utf-8") if summary.exists() else ""

    def test_observed_noisy_median_passes_with_interval_reported(self):
        result, summary = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Resources sensor overhead: PASS", result.stdout)
        self.assertIn("Increment median 5.6 %", summary)
        self.assertIn("95 % interval 1.5 to 8.1 %", summary)
        self.assertIn("On by default: yes", summary)

    def test_interval_above_budget_fails_for_default_sensor(self):
        result, _ = self.run_gate(increment={"lowOverheadPercent": "3.1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::Resources sensor overhead: FAIL", result.stdout)

    def test_exact_budget_lower_bound_passes(self):
        result, _ = self.run_gate(increment={"lowOverheadPercent": "3.0"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_invalid_or_missing_interval_fails_closed(self):
        for key in ["lowOverheadPercent", "highOverheadPercent"]:
            for value in [None, "", "NaN", "invalid", "1.5\n" + key + "=2.0"]:
                with self.subTest(key=key, value=value):
                    result, _ = self.run_gate(increment={key: value})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("::error::The resources sensor's default cannot be checked", result.stdout)

    def test_inconsistent_interval_fails_closed(self):
        for values in [
            {"lowOverheadPercent": "9.0"},
            {"highOverheadPercent": "1.0"},
            {"lowOverheadPercent": "6.0", "highOverheadPercent": "8.1"},
        ]:
            with self.subTest(values=values):
                result, _ = self.run_gate(increment=values)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("::error::The resources sensor's default cannot be checked", result.stdout)

    def test_over_budget_opt_in_sensor_reports_without_failing(self):
        result, _ = self.run_gate(increment={"lowOverheadPercent": "3.1"}, default=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("::notice::Resources sensor overhead: FAIL", result.stdout)
        self.assertIn("It stays opt-in.", result.stdout)

    def test_cumulative_budget_remains_report_only(self):
        result, summary = self.run_gate(
            cumulative={"overheadPercent": "14.0", "lowOverheadPercent": "11.0", "highOverheadPercent": "17.0"}
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Report only, not enforced (D47)", summary)
        self.assertIn("D48's rule would fail", summary)


if __name__ == "__main__":
    unittest.main()
