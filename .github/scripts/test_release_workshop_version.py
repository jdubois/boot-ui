import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("workshop_version", ROOT / ".github/scripts/workshop-version.py")
workshop_version = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workshop_version)


class WorkshopVersionTests(unittest.TestCase):
    def copy_workshop(self, root):
        for directory in ("docs/workshop", "workshop"):
            (root / directory).mkdir(parents=True)
            for source in (ROOT / directory).glob("*.md"):
                (root / directory / source.name).write_bytes(source.read_bytes())

    def test_checked_in_workshop_matches_project_version(self):
        version = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))["version"]
        workshop_version.synchronize(ROOT, version, check=True)

    def test_command_exit_codes_gate_the_release_without_writing(self):
        version = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))["version"]
        before = {
            path: path.read_bytes()
            for directory in ("docs/workshop", "workshop")
            for path in (ROOT / directory).glob("*.md")
        }
        for mode, target, expected in (("check", version, 0), ("check", "9.9.9", 1), ("update", "v1.2.3", 1)):
            with self.subTest(mode=mode, target=target):
                result = subprocess.run(
                    [sys.executable, str(SPEC.origin), mode, target],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                self.assertEqual(before, {path: path.read_bytes() for path in before})
                if expected:
                    self.assertIn("Workshop version:", result.stderr)

    def test_next_release_updates_all_materials_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workshop(root)
            overview = (root / "docs/workshop/README.md").read_text(encoding="utf-8")
            previous = workshop_version.PIN.search(overview)[1]
            extra = root / "workshop/future-lab.md"
            extra.write_text(f"BootUI {previous}; loopback 127.0.0.1; library 9.8.7\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "expected 7.8.9"):
                workshop_version.synchronize(root, "7.8.9", check=True)
            workshop_version.synchronize(root, "7.8.9", check=False)
            workshop_version.synchronize(root, "7.8.9", check=True)
            before = {path: path.read_bytes() for path in root.rglob("*.md")}
            for path, content in before.items():
                self.assertNotIn(previous.encode(), content, str(path))
            setup = (root / "docs/workshop/01-setup-and-tooling.md").read_text(encoding="utf-8")
            for snippet in ("--branch v7.8.9", "--version 7.8.9", "-Version 7.8.9", "<version>7.8.9</version>"):
                self.assertIn(snippet, setup)
            for relative in ("docs/workshop/05-java-instrumentation.md", "docs/workshop/appendix-c-frameworks.md"):
                self.assertIn("bootui-agent-7.8.9.jar", (root / relative).read_text(encoding="utf-8"))
            self.assertEqual(extra.read_text(encoding="utf-8"), "BootUI 7.8.9; loopback 127.0.0.1; library 9.8.7\n")
            workshop_version.synchronize(root, "7.8.9", check=False)
            self.assertEqual(before, {path: path.read_bytes() for path in root.rglob("*.md")})

    def test_mixed_command_versions_fail_without_writing(self):
        for snippet in (
            "--branch v0.0.1",
            "--version 0.0.1",
            "-Version 0.0.1",
            "bootui-agent-0.0.1.jar",
            "<version>0.0.1</version>",
            "--version 7.8.9-SNAPSHOT",
            "<version>7.8.9-SNAPSHOT</version>",
            "--branch v07.8.9",
        ):
            with self.subTest(snippet=snippet), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_workshop(root)
                extra = root / "workshop/mixed.md"
                extra.write_text(snippet, encoding="utf-8")
                before = {path: path.read_bytes() for path in root.rglob("*.md")}
                for check in (True, False):
                    with self.assertRaisesRegex(ValueError, "found"):
                        workshop_version.synchronize(root, "7.8.9", check=check)
                    self.assertEqual(before, {path: path.read_bytes() for path in root.rglob("*.md")})

    def test_missing_edition_or_materials_and_invalid_versions_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_workshop(root)
            for version in ("", "v2.2.0", "2.2.0-SNAPSHOT", "02.2.0"):
                with self.subTest(version=version), self.assertRaisesRegex(ValueError, "Invalid"):
                    workshop_version.synchronize(root, version, check=False)
            overview = root / "docs/workshop/README.md"
            overview.write_text("No edition pin", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "exactly one"):
                workshop_version.synchronize(root, "7.8.9", check=False)
            overview.write_text("this edition pins **7.8.9**", encoding="utf-8")
            for path in (root / "workshop").glob("*.md"):
                path.unlink()
            with self.assertRaisesRegex(ValueError, "missing"):
                workshop_version.synchronize(root, "7.8.9", check=False)
