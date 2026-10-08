"""Tests assemble_central_bundle.py and publish_central_bundle.py, which build and upload the Maven Central
bundle. check-central-bundle.py, which checks that bundle, is tested by test_release_central_bundle.py."""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

from assemble_central_bundle import ARTIFACT_IDS, BundleError, assemble  # noqa: E402

VERSION = "1.2.3"
# Installed with the publication reactor, never published: bootui-agent shades the bridge, and every published
# module carries a flattened, parentless POM.
UNPUBLISHED = ("bootui-parent", "bootui-quarkus-parent", "bootui-agent-bridge")
POM_ONLY = {"bootui-parent", "bootui-quarkus-parent"}


def install(repository, artifact_id, extra=(), signed=True):
    directory = repository / "com/julien-dubois/bootui" / artifact_id / VERSION
    directory.mkdir(parents=True)
    prefix = f"{artifact_id}-{VERSION}"
    names = [f"{prefix}.pom"]
    if artifact_id not in POM_ONLY:
        names += [f"{prefix}.jar", f"{prefix}-sources.jar", f"{prefix}-javadoc.jar"]
    for name in [*names, *extra]:
        (directory / name).write_text(name)
        if signed:
            (directory / f"{name}.asc").write_text("signature")
    (directory / "_remote.repositories").write_text("bookkeeping")
    (directory.parent / "maven-metadata-local.xml").write_text("<metadata/>")
    return directory


class AssembleCentralBundleTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repository = Path(self.directory.name) / "repository"
        self.output = Path(self.directory.name) / "bundle.zip"
        for artifact_id in ARTIFACT_IDS + UNPUBLISHED:
            install(self.repository, artifact_id, ("bootui-cli-1.2.3-all.jar",) if artifact_id == "bootui-cli" else ())

    def test_published_coordinates_are_the_eight_of_bootui_2(self):
        self.assertEqual(
            set(ARTIFACT_IDS),
            {
                "bootui-core",
                "bootui-engine",
                "bootui-ui",
                "bootui-spring-boot-starter",
                "bootui-quarkus",
                "bootui-quarkus-deployment",
                "bootui-cli",
                "bootui-agent",
            },
        )

    def test_the_agent_bridge_and_the_parents_are_never_bundled(self):
        names = assemble(self.repository, VERSION, self.output)
        for artifact_id in UNPUBLISHED:
            with self.subTest(artifact_id=artifact_id):
                self.assertFalse([n for n in names if f"/{artifact_id}/" in n])
        self.assertIn("com/julien-dubois/bootui/bootui-agent/1.2.3/bootui-agent-1.2.3.jar", names)

    def test_an_unsigned_bundle_is_assembled_only_on_request(self):
        unsigned = Path(self.directory.name) / "unsigned"
        for artifact_id in ARTIFACT_IDS:
            install(unsigned, artifact_id, ("bootui-cli-1.2.3-all.jar",) if artifact_id == "bootui-cli" else (), signed=False)
        with self.assertRaisesRegex(BundleError, "unsigned file"):
            assemble(unsigned, VERSION, self.output)
        names = assemble(unsigned, VERSION, self.output, signed=False)
        self.assertFalse([n for n in names if n.endswith(".asc")])
        self.assertIn("com/julien-dubois/bootui/bootui-core/1.2.3/bootui-core-1.2.3.jar.sha1", names)
        with self.assertRaisesRegex(BundleError, "assembled as unsigned"):
            assemble(self.repository, VERSION, self.output, signed=False)

    def test_unsigned_mode_is_a_command_line_flag(self):
        unsigned = Path(self.directory.name) / "unsigned"
        for artifact_id in ARTIFACT_IDS:
            install(unsigned, artifact_id, ("bootui-cli-1.2.3-all.jar",) if artifact_id == "bootui-cli" else (), signed=False)
        script = str(SCRIPTS / "assemble_central_bundle.py")
        refused = subprocess.run([sys.executable, script, str(unsigned), VERSION, str(self.output)], capture_output=True, text=True)
        self.assertEqual(refused.returncode, 1, refused.stderr)
        accepted = subprocess.run(
            [sys.executable, script, "--unsigned", str(unsigned), VERSION, str(self.output)], capture_output=True, text=True
        )
        self.assertEqual(accepted.returncode, 0, accepted.stderr)
        self.assertIn("for 8 artifacts", accepted.stdout)

    def test_bundle_holds_only_signed_artifacts_and_their_checksums(self):
        assemble(self.repository, VERSION, self.output)
        with zipfile.ZipFile(self.output) as bundle:
            names = bundle.namelist()
            pom = "com/julien-dubois/bootui/bootui-core/1.2.3/bootui-core-1.2.3.pom"
            self.assertEqual(bundle.read(pom + ".sha1").decode(), hashlib.sha1(pom.rsplit("/", 1)[1].encode()).hexdigest())
            self.assertEqual(bundle.read(pom + ".md5").decode(), hashlib.md5(pom.rsplit("/", 1)[1].encode()).hexdigest())
        directories = {name.rsplit("/", 1)[0] for name in names}
        self.assertEqual(directories, {f"com/julien-dubois/bootui/{a}/{VERSION}" for a in ARTIFACT_IDS})
        for directory in directories:
            artifact_id = directory.split("/")[-2]
            self.assertIn(f"{directory}/{artifact_id}-{VERSION}.pom", names)
        self.assertFalse([n for n in names if "_remote" in n or "maven-metadata" in n])
        self.assertFalse([n for n in names if n.endswith((".asc.md5", ".asc.sha1"))])
        self.assertIn("com/julien-dubois/bootui/bootui-cli/1.2.3/bootui-cli-1.2.3-all.jar.asc", names)

    def test_stale_checksums_are_regenerated(self):
        directory = self.repository / "com/julien-dubois/bootui/bootui-core" / VERSION
        (directory / "bootui-core-1.2.3.pom.sha1").write_text("stale")
        assemble(self.repository, VERSION, self.output)
        with zipfile.ZipFile(self.output) as bundle:
            self.assertNotEqual(bundle.read("com/julien-dubois/bootui/bootui-core/1.2.3/bootui-core-1.2.3.pom.sha1"), b"stale")

    def test_missing_module_is_refused(self):
        directory = self.repository / "com/julien-dubois/bootui/bootui-cli" / VERSION
        for path in directory.iterdir():
            path.unlink()
        directory.rmdir()
        with self.assertRaisesRegex(BundleError, "bootui-cli 1.2.3 is not installed"):
            assemble(self.repository, VERSION, self.output)

    def test_missing_pom_is_refused(self):
        (self.repository / "com/julien-dubois/bootui/bootui-engine/1.2.3/bootui-engine-1.2.3.pom").unlink()
        with self.assertRaisesRegex(BundleError, "bootui-engine 1.2.3 has no POM"):
            assemble(self.repository, VERSION, self.output)

    def test_missing_javadoc_is_refused(self):
        (self.repository / "com/julien-dubois/bootui/bootui-ui/1.2.3/bootui-ui-1.2.3-javadoc.jar").unlink()
        with self.assertRaisesRegex(BundleError, "bootui-ui 1.2.3 has no javadoc jar"):
            assemble(self.repository, VERSION, self.output)

    def test_unsigned_file_is_refused(self):
        (self.repository / "com/julien-dubois/bootui/bootui-core/1.2.3/bootui-core-1.2.3.jar.asc").unlink()
        with self.assertRaisesRegex(BundleError, "unsigned file: bootui-core-1.2.3.jar"):
            assemble(self.repository, VERSION, self.output)

    def test_foreign_file_is_refused(self):
        (self.repository / "com/julien-dubois/bootui/bootui-core/1.2.3/notes.txt").write_text("?")
        with self.assertRaisesRegex(BundleError, "unexpected file"):
            assemble(self.repository, VERSION, self.output)


class FakePortal(BaseHTTPRequestHandler):
    states = []
    requests = []

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        FakePortal.requests.append((self.path, self.headers.get("Authorization"), self.headers.get("Content-Type"), body))
        if self.path.startswith("/api/v1/publisher/upload"):
            self._answer(201, b"deployment-1")
        elif self.path.startswith("/api/v1/publisher/status"):
            state = FakePortal.states.pop(0) if len(FakePortal.states) > 1 else FakePortal.states[0]
            if state == 503:
                self._answer(503, b"busy")
                return
            payload = {"deploymentId": "deployment-1", "deploymentState": state}
            if state == "FAILED":
                payload["errors"] = {"pkg:maven/x": ["Missing signature"]}
            self._answer(200, json.dumps(payload).encode())
        else:
            self._answer(404, b"")

    def _answer(self, code, body):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class PublishCentralBundleTests(unittest.TestCase):
    def setUp(self):
        self.server = HTTPServer(("127.0.0.1", 0), FakePortal)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        FakePortal.requests = []
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.bundle = Path(directory.name) / "central-bundle.zip"
        self.bundle.write_bytes(b"zip-content")

    def publish(self, states, auto_publish="true", username="user", timeout="5"):
        FakePortal.states = list(states)
        env = {
            **os.environ,
            "MAVEN_CENTRAL_USERNAME": username,
            "MAVEN_CENTRAL_PASSWORD": "s3cret",
            "BOOTUI_CENTRAL_PORTAL_URL": f"http://127.0.0.1:{self.server.server_port}",
            "BOOTUI_CENTRAL_POLL_SECONDS": "0.01",
            "BOOTUI_CENTRAL_TIMEOUT_SECONDS": timeout,
        }
        return subprocess.run(
            [sys.executable, str(SCRIPTS / "publish_central_bundle.py"), str(self.bundle), "bootui-1.2.3", auto_publish],
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )

    def test_automatic_publication_waits_until_publishing(self):
        result = self.publish(["PENDING", 503, "VALIDATING", "PUBLISHING"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("deployment-1 is PUBLISHING", result.stdout)
        path, authorization, content_type, body = FakePortal.requests[0]
        self.assertEqual(path, "/api/v1/publisher/upload?name=bootui-1.2.3&publishingType=AUTOMATIC")
        self.assertEqual(authorization, "Bearer dXNlcjpzM2NyZXQ=")
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))
        self.assertIn(b'name="bundle"; filename="central-bundle.zip"', body)
        self.assertIn(b"zip-content", body)
        self.assertNotIn("s3cret", result.stdout + result.stderr)

    def test_user_managed_publication_stops_at_validated(self):
        result = self.publish(["VALIDATING", "VALIDATED"], auto_publish="false")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("publishingType=USER_MANAGED", FakePortal.requests[0][0])
        self.assertIn("publish it in the Central Portal", result.stdout)

    def test_failed_validation_reports_the_errors(self):
        result = self.publish(["VALIDATING", "FAILED"])
        self.assertEqual(result.returncode, 1)
        self.assertIn("failed validation", result.stderr)
        self.assertIn("Missing signature", result.stderr)

    def test_timeout_is_reported(self):
        result = self.publish(["VALIDATING"], timeout="0.05")
        self.assertEqual(result.returncode, 1)
        self.assertIn("did not reach PUBLISHED or PUBLISHING in time", result.stderr)

    def test_missing_credentials_are_refused_before_upload(self):
        result = self.publish(["PUBLISHED"], username="")
        self.assertEqual(result.returncode, 1)
        self.assertIn("MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD must be set", result.stderr)
        self.assertEqual(FakePortal.requests, [])


if __name__ == "__main__":
    unittest.main()
