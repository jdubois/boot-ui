import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("retry.sh")


class RetryTests(unittest.TestCase):
    def run_retry(self, attempts, *command):
        return subprocess.run(
            ["bash", str(SCRIPT), str(attempts), "0", *command],
            capture_output=True,
            text=True,
            check=False,
        )

    def counting_command(self, directory, succeed_on):
        counter = Path(directory) / "count"
        script = (
            f'n=$(( $(cat "{counter}" 2>/dev/null || echo 0) + 1 )); echo $n > "{counter}"; '
            f'[ "$n" -ge {succeed_on} ]'
        )
        return counter, ["bash", "-c", script]

    def test_success_runs_once(self):
        with tempfile.TemporaryDirectory() as directory:
            counter, command = self.counting_command(directory, 1)
            result = self.run_retry(2, *command)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(counter.read_text().strip(), "1")

    def test_one_transient_failure_is_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            counter, command = self.counting_command(directory, 2)
            result = self.run_retry(2, *command)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(counter.read_text().strip(), "2")
            self.assertIn("retrying", result.stderr)

    def test_persistent_failure_fails_after_the_last_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            counter, command = self.counting_command(directory, 99)
            result = self.run_retry(2, *command)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertEqual(counter.read_text().strip(), "2")
            self.assertIn("after 2 attempts", result.stderr)

    def test_exit_status_of_the_command_is_preserved(self):
        result = self.run_retry(2, "bash", "-c", "exit 7")
        self.assertEqual(result.returncode, 7, result.stderr)

    def test_invalid_arguments_are_rejected(self):
        for args in (["0", "0", "true"], ["x", "0", "true"], ["2", "0"]):
            with self.subTest(args=args):
                result = subprocess.run(
                    ["bash", str(SCRIPT), *args], capture_output=True, text=True, check=False
                )
                self.assertEqual(result.returncode, 2, result.stderr)


if __name__ == "__main__":
    unittest.main()
