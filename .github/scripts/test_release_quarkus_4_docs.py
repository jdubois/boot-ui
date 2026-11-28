import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("release_q4_docs", Path(__file__).with_name("release-quarkus-4-docs.py"))
docs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(docs)


class Quarkus4ReleaseDocsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.q3 = (
            "<artifactId>bootui-quarkus</artifactId>\n<version>2.5.0</version>\n"
            "com.julien-dubois.bootui:bootui-quarkus:2.5.0\n"
        )
        self.content = (
            self.q3 + docs.START + "\n> **Next release:** Not published in BootUI 2.5.0.\n" + docs.END + "\n"
            "<artifactId>bootui-quarkus-4</artifactId>\n<version>NEXT_BOOTUI_RELEASE</version>\n"
            "com.julien-dubois.bootui:bootui-quarkus-4:NEXT_BOOTUI_RELEASE\n"
            "com.julien-dubois.bootui:bootui-quarkus-4-deployment:NEXT_BOOTUI_RELEASE\n"
        )
        for relative in docs.DOCUMENTS:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(self.content, encoding="utf-8")

    def test_initial_and_later_releases_replace_only_exact_q4_versions(self):
        for version in ("3.0.0", "3.0.1"):
            docs.synchronize(self.root, version, check=False)
            docs.synchronize(self.root, version, check=True)
            for relative in docs.DOCUMENTS:
                result = (self.root / relative).read_text(encoding="utf-8")
                self.assertIn(self.q3, result)
                self.assertNotIn(docs.START, result)
                self.assertNotIn(docs.TOKEN, result)
                self.assertIn(f"<artifactId>bootui-quarkus-4</artifactId>\n<version>{version}</version>", result)
                self.assertIn(f"com.julien-dubois.bootui:bootui-quarkus-4-deployment:{version}", result)

    def test_checks_never_repair_signed_contents(self):
        with self.assertRaisesRegex(ValueError, "stale"):
            docs.synchronize(self.root, "3.0.0", check=True)
        self.assertEqual((self.root / "README.md").read_text(encoding="utf-8"), self.content)

    def test_malformed_blocks_or_unbound_markers_fail_before_any_write(self):
        for invalid in (self.content.replace(docs.END, ""), self.content + "\nNEXT_BOOTUI_RELEASE\n"):
            path = self.root / docs.DOCUMENTS[-1]
            path.write_text(invalid, encoding="utf-8")
            with self.assertRaises(ValueError):
                docs.synchronize(self.root, "3.0.0", check=False)
            self.assertEqual((self.root / "README.md").read_text(encoding="utf-8"), self.content)

    def test_version_matching_is_literal_and_not_an_artifact_prefix(self):
        path = self.root / "README.md"
        controls = (
            "com.julien-dubois.bootui:bootui-quarkus-40:2.5.0\n"
            "com.julien-dubois.bootui:bootui-quarkus-4:2x5x0\n"
            "com.julien-dubois.bootui:bootui-quarkus-4:2.5.0-SNAPSHOT\n"
        )
        path.write_text(self.content + controls, encoding="utf-8")
        docs.synchronize(self.root, "3.0.0", check=False)
        self.assertIn(controls, path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
