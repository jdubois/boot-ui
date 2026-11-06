from contextlib import nullcontext, redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from test_release_line_gate import FakeCentral, _BrokenHandler


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("ci_docs_version", ROOT / ".github/scripts/ci-docs-version.py")
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class CiDocsVersionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q")
        self.git("config", "user.email", "ci-docs-test@example.invalid")
        self.git("config", "user.name", "CI docs test")
        action = self.root / HELPER.ACTION
        action.mkdir(parents=True)
        (action / "action.yml").write_text("runs:\n  using: composite\n", encoding="utf-8")
        (action / "invoke.sh").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        (action / "invoke.sh").chmod(0o755)
        self.set_project("2.3.0")
        self.git("add", ".")
        self.git("commit", "-qm", "Reviewed Action")
        self.ref = self.git("rev-parse", "HEAD")
        self.manifest = {"schemaVersion": 1, "available": False, "version": None, "actionRef": self.ref}
        self.write_manifest()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True).strip()

    def set_project(self, version):
        (self.root / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            f"<modelVersion>4.0.0</modelVersion><version>{version}</version></project>",
            encoding="utf-8",
        )

    def write_manifest(self):
        path = self.root / HELPER.MANIFEST
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.manifest, indent=2) + "\n", encoding="utf-8")

    def synchronize(self, version=None, check=True):
        return HELPER.synchronize(self.root, version, check=check)

    def test_initial_source_is_unavailable_without_modification(self):
        before = (self.root / HELPER.MANIFEST).read_bytes()
        self.assertFalse(self.synchronize()["available"])
        self.assertEqual((self.root / HELPER.MANIFEST).read_bytes(), before)

    def test_published_230_is_never_advertised(self):
        for mode in (False, True):
            with self.assertRaisesRegex(ValueError, "do not support"):
                self.synchronize("2.3.0", check=mode)
        self.manifest.update(available=True, version="2.3.0")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "do not support"):
            self.synchronize()

    def test_first_and_subsequent_releases_pin_committed_source(self):
        for version in ("2.3.1", "2.4.0", "3.0.0"):
            self.set_project(version)
            result = self.synchronize(version, check=False)
            self.assertTrue(result["available"])
            self.assertEqual(result["version"], version)
            self.assertEqual(result["actionRef"], self.ref)
            before = (self.root / HELPER.MANIFEST).read_bytes()
            self.synchronize(version, check=False)
            self.synchronize(version)
            self.synchronize()
            self.assertEqual((self.root / HELPER.MANIFEST).read_bytes(), before)

    def test_first_release_replaces_a_squashed_pr_pin_with_the_source_commit(self):
        tree = self.git("rev-parse", "HEAD^{tree}")
        old_pr_ref = self.git("commit-tree", tree, "-m", "Original squashed PR commit")
        self.manifest["actionRef"] = old_pr_ref
        self.write_manifest()
        self.set_project("2.3.1")
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.synchronize("2.3.1")
        result = self.synchronize("2.3.1", check=False)
        self.assertEqual(result["actionRef"], self.ref)
        self.git("add", ".")
        self.git("commit", "-qm", "Seal first release")
        self.synchronize("2.3.1")

    def test_later_release_pins_the_new_committed_action_automatically(self):
        self.set_project("2.3.1")
        self.synchronize("2.3.1", check=False)
        self.git("add", ".")
        self.git("commit", "-qm", "Seal first release")
        (self.root / HELPER.ACTION / "invoke.sh").write_text(
            "#!/bin/sh\necho reviewed change\n", encoding="utf-8"
        )
        self.git("add", HELPER.ACTION)
        self.git("commit", "-qm", "Reviewed Action update")
        source_ref = self.git("rev-parse", "HEAD")
        self.set_project("2.4.0")
        result = self.synchronize("2.4.0", check=False)
        self.assertEqual(result["actionRef"], source_ref)
        self.assertNotEqual(result["actionRef"], self.ref)
        self.git("add", ".")
        self.git("commit", "-qm", "Seal later release")
        self.synchronize("2.4.0")

    def test_baseline_detects_stale_or_unavailable_later_release(self):
        self.set_project("2.3.1")
        with self.assertRaisesRegex(ValueError, "unavailable"):
            self.synchronize()
        self.synchronize("2.3.1", check=False)
        self.set_project("2.4.0")
        with self.assertRaisesRegex(ValueError, "Stale"):
            self.synchronize()

    def test_invalid_versions_and_mismatched_project_do_not_write(self):
        before = (self.root / HELPER.MANIFEST).read_bytes()
        for version in ("v2.3.1", "02.3.1", "2.3.1-SNAPSHOT", "2.3", "2.3.1+build", "", "2.4.0"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.synchronize(version, check=False)
        self.assertEqual((self.root / HELPER.MANIFEST).read_bytes(), before)

    def test_release_version_cannot_regress(self):
        self.set_project("2.4.0")
        self.synchronize("2.4.0", check=False)
        self.set_project("2.3.1")
        with self.assertRaisesRegex(ValueError, "regress"):
            self.synchronize("2.3.1", check=False)

    def test_tag_entry_and_resume_only_check_and_never_repair(self):
        self.set_project("2.3.1")
        before = (self.root / HELPER.MANIFEST).read_bytes()
        with self.assertRaisesRegex(ValueError, "Signed CI manifest"):
            self.synchronize("2.3.1")
        self.assertEqual((self.root / HELPER.MANIFEST).read_bytes(), before)
        self.synchronize("2.3.1", check=False)
        self.git("add", ".")
        self.git("commit", "-qm", "Seal test contents")
        sealed = self.git("rev-parse", "HEAD")
        self.assertNotEqual(sealed, self.ref)
        self.git("checkout", "--detach", "-q", sealed)
        before = (self.root / HELPER.MANIFEST).read_bytes()
        for _ in range(2):
            self.synchronize("2.3.1")
            self.assertEqual(self.git("rev-parse", "HEAD"), sealed)
            self.assertEqual((self.root / HELPER.MANIFEST).read_bytes(), before)
            self.assertEqual(self.git("status", "--porcelain"), "")
        self.manifest.update(available=True, version="2.4.0")
        self.write_manifest()
        before = (self.root / HELPER.MANIFEST).read_bytes()
        with self.assertRaisesRegex(ValueError, "Signed CI manifest"):
            self.synchronize("2.3.1")
        self.assertEqual((self.root / HELPER.MANIFEST).read_bytes(), before)

    def test_action_content_inventory_and_permissions_must_be_unchanged(self):
        self.set_project("2.3.1")
        script = self.root / HELPER.ACTION / "invoke.sh"
        original = script.read_bytes()
        script.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
        for check in (False, True):
            with self.assertRaisesRegex(ValueError, "differs"):
                self.synchronize("2.3.1", check=check)
        script.write_bytes(original)
        script.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "differs"):
            self.synchronize("2.3.1", check=False)
        script.chmod(0o755)
        extra = script.parent / "untracked.sh"
        extra.write_text("echo extra", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differs"):
            self.synchronize("2.3.1", check=False)
        extra.unlink()
        script.unlink()
        with self.assertRaisesRegex(ValueError, "differs"):
            self.synchronize("2.3.1", check=False)
        script.symlink_to("action.yml")
        with self.assertRaisesRegex(ValueError, "symlinks"):
            self.synchronize("2.3.1", check=False)

    def test_signed_action_ref_must_resolve_to_ancestor_commit_containing_action(self):
        self.set_project("2.3.1")
        self.manifest["actionRef"] = "0" * 40
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.synchronize("2.3.1")
        self.manifest["actionRef"] = self.git("rev-parse", "HEAD^{tree}")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "commit"):
            self.synchronize("2.3.1")
        self.manifest["actionRef"] = self.ref
        self.write_manifest()
        self.git("add", ".")
        self.git("commit", "-qm", "Later source")
        future = self.git("rev-parse", "HEAD")
        self.git("checkout", "--detach", "-q", self.ref)
        self.set_project("2.3.1")
        self.manifest["actionRef"] = future
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.synchronize("2.3.1")

    def test_strict_manifest_schema(self):
        for key, value in (
            ("schemaVersion", True), ("schemaVersion", 2), ("available", "false"),
            ("version", "2.3.1"), ("actionRef", "main"), ("actionRef", "A" * 40),
            ("unexpected", 1),
        ):
            original = self.manifest.copy()
            self.manifest[key] = value
            self.write_manifest()
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.synchronize()
            self.manifest = original
        (self.root / HELPER.MANIFEST).write_text('{"available":false,"available":true}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.synchronize()

    def test_repository_baseline_is_release_aware_and_git_free(self):
        with patch.object(HELPER, "git", side_effect=AssertionError("Baseline must remain Git-free")):
            HELPER.synchronize(ROOT, check=True)
            self.synchronize()

    def test_missing_update_version_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "requires a release version"):
            self.synchronize(check=False)

    def test_shallow_history_rejected_even_when_action_pin_is_head(self):
        shallow = self.root / ".git/shallow"
        shallow.write_text(self.ref + "\n", encoding="utf-8")
        self.set_project("2.3.1")
        for check in (False, True):
            with self.assertRaisesRegex(ValueError, "shallow"):
                self.synchronize("2.3.1", check=check)

    def test_unavailable_manifest_does_not_query_central(self):
        with patch.object(HELPER.urllib.request, "build_opener", side_effect=AssertionError("No network")):
            self.assertTrue(HELPER.publication_ready(self.root))

    def test_exact_publication_gate_blocks_same_major_prepublication_and_partial_release(self):
        central = FakeCentral()
        self.addCleanup(central.close)
        central.publish("2.3.0")
        self.set_project("2.3.1")
        self.synchronize("2.3.1", check=False)
        self.assertFalse(HELPER.publication_ready(self.root, central.url))
        central.publish("2.3.1")
        self.assertFalse(HELPER.publication_ready(self.root, central.url))
        repository = Path(central.directory.name) / "com/julien-dubois/bootui"
        for module in ("bootui-engine", "bootui-ui", "bootui-spring-boot-starter", "bootui-quarkus",
                       "bootui-quarkus-deployment", "bootui-cli", "bootui-agent"):
            for extension in ("pom", "jar"):
                path = repository / module / "2.3.1" / f"{module}-2.3.1.{extension}"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"published")
        self.assertFalse(HELPER.publication_ready(self.root, central.url))
        (repository / "bootui-cli/2.3.1/bootui-cli-2.3.1-all.jar").write_bytes(b"uberjar")
        self.assertFalse(HELPER.publication_ready(self.root, central.url))
        (repository / "bootui-cli/2.3.1/bootui-cli-2.3.1-all.jar.asc").write_bytes(b"signature")
        self.assertTrue(HELPER.publication_ready(self.root, central.url))

    def test_unreadable_publication_state_fails_closed(self):
        self.set_project("2.3.1")
        self.synchronize("2.3.1", check=False)
        broken = FakeCentral(handler=_BrokenHandler)
        self.addCleanup(broken.close)
        with patch.object(HELPER.time, "sleep"), self.assertRaisesRegex(ValueError, "HTTP 503"):
            HELPER.publication_ready(self.root, broken.url)
        with patch.object(HELPER.time, "sleep"), \
                patch.object(HELPER.urllib.request.OpenerDirector, "open", side_effect=TimeoutError):
            with self.assertRaisesRegex(ValueError, "Cannot determine"):
                HELPER.publication_ready(self.root)

    def test_publication_gate_retries_transient_failures_but_not_missing_artifacts(self):
        self.set_project("2.3.1")
        self.synchronize("2.3.1", check=False)
        attempts = 0

        def eventually_available(request, *, timeout):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                raise TimeoutError("temporary network failure")
            return nullcontext(SimpleNamespace(status=503 if attempts == 2 else 200))

        with patch.object(HELPER.time, "sleep") as sleep, patch.object(HELPER.urllib.request, "build_opener") as factory:
            factory.return_value.open.side_effect = eventually_available
            self.assertTrue(HELPER.publication_ready(self.root))
            self.assertEqual(attempts, 18)
            self.assertEqual(sleep.call_count, 2)
            factory.return_value.open.reset_mock(side_effect=True)
            factory.return_value.open.return_value = nullcontext(SimpleNamespace(status=404))
            self.assertFalse(HELPER.publication_ready(self.root))
            self.assertEqual(factory.return_value.open.call_count, 1)

    def test_every_coordinate_and_signed_cli_are_required_with_bounded_head_requests(self):
        self.set_project("2.3.1")
        self.synchronize("2.3.1", check=False)
        modules = ("bootui-engine", "bootui-ui", "bootui-spring-boot-starter", "bootui-quarkus",
                   "bootui-quarkus-deployment", "bootui-cli", "bootui-agent")
        base = "https://repo.maven.apache.org/maven2/com/julien-dubois/bootui/"
        expected = {
            f"{base}{module}/2.3.1/{module}-2.3.1.{extension}"
            for module in modules for extension in ("pom", "jar")
        } | {
            base + "bootui-cli/2.3.1/bootui-cli-2.3.1-all.jar",
            base + "bootui-cli/2.3.1/bootui-cli-2.3.1-all.jar.asc",
        }
        seen = set()
        missing = None

        def respond(request, *, timeout):
            self.assertEqual(request.get_method(), "HEAD")
            self.assertEqual(timeout, 10)
            seen.add(request.full_url)
            return nullcontext(SimpleNamespace(status=404 if request.full_url == missing else 200))

        with patch.object(HELPER.urllib.request, "build_opener") as factory:
            factory.return_value.open.side_effect = respond
            self.assertTrue(HELPER.publication_ready(self.root))
            self.assertEqual(seen, expected)
            self.assertEqual(factory.return_value.open.call_count, 16)
            for missing in expected:
                with self.subTest(missing=missing):
                    self.assertFalse(HELPER.publication_ready(self.root))

    def test_publication_gate_output_is_false_for_pending_and_absent_on_error(self):
        output = self.root / "output"
        output.touch()
        with patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), \
                patch.object(HELPER.sys, "argv", ["ci-docs-version.py", "publication-gate"]), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            for ready in (False, True):
                output.write_text("", encoding="utf-8")
                with patch.object(HELPER, "publication_ready", return_value=ready):
                    self.assertEqual(HELPER.main(), 0)
                self.assertEqual(output.read_text(encoding="utf-8"), f"publish={str(ready).lower()}\n")
            output.write_text("", encoding="utf-8")
            with patch.object(HELPER, "publication_ready", side_effect=ValueError("ambiguous response")):
                self.assertEqual(HELPER.main(), 1)
            self.assertEqual(output.read_text(encoding="utf-8"), "")

    def test_redirect_is_ambiguous_not_publication(self):
        import http.server

        class Redirect(http.server.BaseHTTPRequestHandler):
            def do_HEAD(self):
                self.send_response(302)
                self.send_header("Location", "https://example.invalid/")
                self.end_headers()

            def log_message(self, *args):
                pass

        central = FakeCentral(handler=Redirect)
        self.addCleanup(central.close)
        self.set_project("2.3.1")
        self.synchronize("2.3.1", check=False)
        with self.assertRaisesRegex(ValueError, "HTTP 302"):
            HELPER.publication_ready(self.root, central.url)


if __name__ == "__main__":
    unittest.main()
