import functools
import http.server
import os
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / ".github/scripts/release-line-gate.sh"
GROUP = "com/julien-dubois/bootui"

POM = """<project>
    <groupId>com.julien-dubois.bootui</groupId>
    <artifactId>bootui-parent</artifactId>
    <version>{version}</version>
</project>
"""


class _QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *arguments):
        pass


class _BrokenHandler(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.send_response(503)
        self.end_headers()

    def log_message(self, *arguments):
        pass


class FakeCentral:
    """A local Maven repository serving only the versions it is told are published."""

    def __init__(self, handler=None):
        self.directory = tempfile.TemporaryDirectory()
        handler = handler or functools.partial(_QuietHandler, directory=self.directory.name)
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def publish(self, version, artifacts=("parent", "starter")):
        root = Path(self.directory.name) / GROUP
        if "parent" in artifacts:
            parent = root / "bootui-parent" / version / f"bootui-parent-{version}.pom"
            parent.parent.mkdir(parents=True, exist_ok=True)
            parent.write_text("<project/>", encoding="utf-8")
        if "starter" in artifacts:
            name = f"bootui-spring-boot-starter-{version}.jar"
            starter = root / "bootui-spring-boot-starter" / version / name
            starter.parent.mkdir(parents=True, exist_ok=True)
            starter.write_bytes(b"jar")

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.directory.cleanup()


class ReleaseLineGateTests(unittest.TestCase):
    def setUp(self):
        self.central = FakeCentral()
        self.addCleanup(self.central.close)

    def gate(self, project_version, release_line, tags, central_url=None):
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            (checkout / "pom.xml").write_text(POM.format(version=project_version), encoding="utf-8")
            if release_line is not None:
                (checkout / ".github").mkdir()
                (checkout / ".github/release-line").write_text(f"# test\n{release_line}\n", encoding="utf-8")
            tags_file = checkout / "tags"
            tags_file.write_text("".join(f"{tag}\n" for tag in tags), encoding="utf-8")
            output = checkout / "output"
            output.touch()
            environment = {
                **os.environ,
                "BOOTUI_RELEASE_TAGS_FILE": str(tags_file),
                "BOOTUI_CENTRAL_URL": central_url or self.central.url,
                "GITHUB_OUTPUT": str(output),
            }
            result = subprocess.run(
                ["bash", str(GATE)],
                cwd=checkout,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            values = dict(
                line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines() if "=" in line
            )
            return result, values

    def assert_decision(self, expected, project_version, release_line, tags, reason=None):
        result, values = self.gate(project_version, release_line, tags)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(values.get("publish"), expected, result.stdout + result.stderr)
        self.assertEqual(values.get("release-line"), str(release_line))
        if reason:
            self.assertIn(reason, values.get("reason", ""))

    def test_1x_main_keeps_publishing_until_the_merge(self):
        self.central.publish("1.0.0")
        self.central.publish("1.19.0")
        self.assert_decision("true", "1.19.0", 1, ["v1.0.0", "v1.19.0"], "v1.0.0 is on Maven Central")

    def test_merging_v2_into_main_publishes_nothing_before_2_0_0(self):
        self.central.publish("1.19.0")
        self.assert_decision("false", "1.19.0", 2, ["v1.19.0", "v2.0.0-RC1"], "no release tag yet")

    def test_a_2_0_0_tag_without_its_artifacts_publishes_nothing(self):
        self.central.publish("1.19.0")
        self.assert_decision("false", "1.19.0", 2, ["v1.19.0", "v2.0.0"], "none with its artifacts")
        self.assert_decision("false", "2.0.0", 2, ["v1.19.0", "v2.0.0"], "none with its artifacts")

    def test_a_partially_visible_release_publishes_nothing(self):
        self.central.publish("2.0.0", artifacts=("parent",))
        self.assert_decision("false", "2.0.0", 2, ["v1.19.0", "v2.0.0"])

    def test_2_0_0_on_central_publishes_the_2x_line(self):
        self.central.publish("1.19.0")
        self.central.publish("2.0.0")
        self.assert_decision("true", "2.0.0", 2, ["v1.19.0", "v2.0.0"], "v2.0.0 is on Maven Central")

    def test_a_failed_later_patch_does_not_block_the_released_line(self):
        self.central.publish("2.0.0")
        self.assert_decision("true", "2.0.1", 2, ["v1.19.0", "v2.0.0", "v2.0.1"])

    def test_maintenance_branch_stops_once_a_newer_major_is_released(self):
        self.central.publish("1.19.0")
        self.central.publish("1.19.1")
        self.central.publish("2.0.0")
        self.assert_decision(
            "false", "1.19.1", 1, ["v1.19.0", "v1.19.1", "v2.0.0"], "superseded by v2.0.0"
        )

    def test_an_unpublished_newer_tag_does_not_retire_the_released_line(self):
        self.central.publish("1.19.0")
        self.assert_decision("true", "1.19.0", 1, ["v1.19.0", "v2.0.0"])

    def test_unreachable_central_fails_closed(self):
        result, values = self.gate("1.19.0", 1, ["v1.19.0"], central_url="http://127.0.0.1:9")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertNotIn("publish", values)

    def test_unexpected_central_answer_fails_closed(self):
        broken = FakeCentral(handler=_BrokenHandler)
        self.addCleanup(broken.close)
        for project_version, line, tags in (("1.19.0", 1, ["v1.19.0"]), ("1.19.0", 1, ["v1.19.0", "v2.0.0"])):
            with self.subTest(tags=tags):
                result, values = self.gate(project_version, line, tags, central_url=broken.url)
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertIn("refusing to decide", result.stderr)
                self.assertNotIn("publish", values)

    def test_missing_or_inconsistent_release_line_fails_closed(self):
        self.central.publish("1.19.0")
        for project_version, line in (("1.19.0", None), ("1.19.0", 3), ("2.0.0", 1)):
            with self.subTest(project_version=project_version, line=line):
                result, values = self.gate(project_version, line, ["v1.19.0"])
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertNotIn("publish", values)


if __name__ == "__main__":
    unittest.main()
