import importlib.util
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("ci_examples", Path(__file__).with_name("ci-examples.py"))
examples = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(examples)
ACTION = "0572c2f676a0d0ead3fa9ce29b8fc9354ab7622f"
SOURCE_ROOT = Path(__file__).resolve().parents[2]


class CiExampleDocumentationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for relative in examples.DOCUMENTS:
            self.write(relative, "<!-- bootui-ci:release:start -->\nstale\n<!-- bootui-ci:release:end -->\n")
        self.write(
            "pom.xml",
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>'
            "<spring-boot.version>4.1.1</spring-boot.version>"
            "<quarkus.platform.version>3.40.1</quarkus.platform.version></properties></project>",
        )
        self.write("mvnw", "#!/bin/sh\nexit 0\n")
        self.write(".mvn/wrapper/maven-wrapper.properties", "distributionUrl=https://example.invalid/maven.zip\n")
        self.write(
            "examples/ci/workflows/live.yml.template",
            "uses: jdubois/boot-ui@@BOOTUI_ACTION_REF@\n"
            "bootui-version: '@BOOTUI_VERSION@'\n",
        )
        self.record(available=False, version=None)

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def record(self, **values):
        self.write("docs/ci/release.json", json.dumps({"schemaVersion": 1, "actionRef": ACTION, **values}))

    def test_unpublished_notice_never_presents_old_cli_as_working(self):
        self.write(
            "docs/CI.md",
            "<!-- bootui-ci:release:start -->\nstale\n<!-- bootui-ci:release:end -->\n"
            "<!-- bootui-ci:source:workflows/live.yml.template:start -->\nstale\n"
            "<!-- bootui-ci:source:workflows/live.yml.template:end -->\n",
        )
        examples.synchronize(self.root, check=False)
        rendered = (self.root / "docs/CI.md").read_text()
        self.assertIn("not yet published", rendered)
        self.assertIn("bootui-version: 'UNRELEASED'", rendered)
        self.assertIn("uses: jdubois/boot-ui@vUNRELEASED", rendered)
        self.assertNotIn(ACTION, rendered)
        self.assertNotIn("bootui-version: '2.3.0'", rendered)
        examples.synchronize(self.root, check=True)
        with self.assertRaisesRegex(ValueError, "not published"):
            examples.kit_bytes(self.root)

    def test_first_and_subsequent_releases_replace_all_generated_regions(self):
        for version in ("2.3.1", "2.4.0", "3.0.0"):
            with self.subTest(version=version):
                self.record(available=True, version=version)
                examples.synchronize(self.root, check=False)
                examples.synchronize(self.root, check=True)
                for relative in examples.DOCUMENTS:
                    self.assertIn(f"BootUI {version}", (self.root / relative).read_text())

    def test_real_documentation_has_no_unreleased_placeholders_after_release(self):
        for path in examples.DOCUMENTS:
            self.write(path, (SOURCE_ROOT / path).read_text(encoding="utf-8"))
        self.write("pom.xml", (SOURCE_ROOT / "pom.xml").read_text(encoding="utf-8"))
        shutil.copytree(
            SOURCE_ROOT / "examples/ci", self.root / "examples/ci", dirs_exist_ok=True,
            ignore=shutil.ignore_patterns("target", "__pycache__", ".m2"),
        )
        for version, action_ref in (("2.3.1", "1" * 40), ("2.4.0", "2" * 40)):
            self.record(available=True, version=version, actionRef=action_ref)
            examples.synchronize(self.root, check=False)
            for path in examples.DOCUMENTS:
                with self.subTest(version=version, path=path):
                    self.assertNotIn("UNRELEASED", (self.root / path).read_text(encoding="utf-8"))
            guide = (self.root / "docs/CI.md").read_text(encoding="utf-8")
            self.assertIn(f"uses: jdubois/boot-ui@v{version}", guide)
            self.assertNotIn(f"uses: jdubois/boot-ui@{action_ref}", guide)
            if version == "2.4.0":
                self.assertIn(
                    "- name: BootUI diagnostics\n  uses: jdubois/boot-ui@v2.4.0",
                    guide,
                )
            self.assertIn("exactly one stable `bootui-version`", guide)

    def test_primary_guide_starts_with_the_simple_action_not_a_policy_or_sample_project(self):
        guide = (SOURCE_ROOT / "docs/CI.md").read_text(encoding="utf-8")
        quick_start = guide.split("## Add BootUI to GitHub Actions", 1)[1].split(
            "## What appears in GitHub Actions", 1
        )[0]
        self.assertIn("bootui-ci:source:snippets/action.yml.template:start", quick_start)
        self.assertIn("Adds a readable Markdown report", quick_start)
        self.assertIn("One Action step", guide)
        self.assertIn("Detects and starts your Spring Boot or Quarkus app", quick_start)
        self.assertIn("does not run it by default", guide)
        self.assertIn("six CI-capable local advisors", guide)
        self.assertIn("policy excludes the GitHub advisor", guide)
        self.assertIn("does not turn uploaded `junit.xml` into a Tests view", guide)
        self.assertIn("steps.bootui.outputs.report-dir", guide)
        self.assertIn("add `id: bootui`", guide)
        self.assertIn("::: details Display BootUI as a JUnit test/check", guide)
        self.assertIn("@SpringBootTest", guide)
        self.assertIn("@QuarkusTest", guide)
        self.assertIn("[framework-managed test recipe](ci/framework-tests.md)", guide)
        self.assertNotIn("bootui-ci:source:spring/pom.xml.template", guide)
        self.assertNotIn("bootui-ci:source:workflows/external.yml.template", guide)
        self.assertLess(len(guide.splitlines()), 150)
        source = (SOURCE_ROOT / "examples/ci/snippets/action.yml.template").read_text(encoding="utf-8")
        self.assertNotIn("id: bootui", source)
        self.assertNotIn("policy:", source)
        self.assertNotIn("phase:", source)
        self.assertNotIn("test-command:", source)
        self.assertNotIn("app-command:", source)

    def test_checked_in_generated_regions_are_current(self):
        examples.synchronize(SOURCE_ROOT, check=True)

    def test_ci_navigation_check_runs_in_both_documentation_workflows(self):
        command = "node --test docs/.vuepress/workshop.test.mjs docs/.vuepress/ci.test.mjs"
        for name in ("pages.yml", "docs-v2.yml"):
            workflow = (SOURCE_ROOT / ".github/workflows" / name).read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertRegex(workflow, re.compile(r"^\s+run: " + re.escape(command) + r"$", re.MULTILINE))
        workflow = (SOURCE_ROOT / ".github/workflows/docs-v2.yml").read_text(encoding="utf-8")
        self.assertIn("'examples/ci/**'", workflow)

    def test_stale_docs_fail_instead_of_being_silently_fixed_by_check(self):
        with self.assertRaisesRegex(ValueError, "stale"):
            examples.synchronize(self.root, check=True)
        self.assertIn("stale", (self.root / "docs/CI.md").read_text())

    def test_render_validates_every_source_before_writing(self):
        self.write(
            "docs/ci/reference.md",
            "<!-- bootui-ci:source:missing.yml.template:start -->\nstale\n"
            "<!-- bootui-ci:source:missing.yml.template:end -->\n",
        )
        with self.assertRaisesRegex(ValueError, "missing"):
            examples.synchronize(self.root, check=False)
        self.assertIn("stale", (self.root / "README.md").read_text())

    def test_unknown_tokens_and_escape_paths_are_rejected(self):
        self.write("examples/ci/unknown.txt.template", "@UNKNOWN_VERSION@")
        values = examples.substitutions(self.root, examples.release_record(self.root))
        with self.assertRaisesRegex(ValueError, "Unknown"):
            examples.render_source(self.root, "unknown.txt.template", values)
        with self.assertRaisesRegex(ValueError, "Invalid"):
            examples.render_source(self.root, "../../README.md", values)

    def test_malformed_and_duplicate_regions_fail(self):
        for content in (
            "<!-- bootui-ci:release:start -->\nmissing end",
            "<!-- bootui-ci:release:start -->\na\n<!-- bootui-ci:release:end -->\n" * 2,
        ):
            with self.subTest(content=content):
                self.write("docs/CI.md", content)
                with self.assertRaises(ValueError):
                    examples.synchronize(self.root, check=False)

    def test_invalid_or_unsupported_release_metadata_fails(self):
        for available, version in ((True, "2.3.0"), (True, "latest"), (True, "2.4.0-SNAPSHOT"), (False, "2.4.0")):
            with self.subTest(version=version, available=available):
                self.record(available=available, version=version)
                with self.assertRaises(ValueError):
                    examples.release_record(self.root)

    def test_released_kit_is_reproducible_and_preserves_executable_modes(self):
        self.record(available=True, version="2.3.1")
        self.write("examples/ci/scripts/start.sh", "#!/bin/sh\nexit 0\n")
        self.write("examples/ci/spring/pom.xml.template", "<version>@BOOTUI_VERSION@</version>")
        self.write("examples/ci/spring/pom.xml", "stale local build")
        self.write("examples/ci/spring/target/secret.log", "do not package")
        self.write("examples/ci/.env", "LOCAL_SECRET=do-not-package")
        self.write("examples/ci/spring/.bootui/local.json", '{"secret":"do-not-package"}')
        data = examples.kit_bytes(self.root)
        self.assertEqual(data, examples.kit_bytes(self.root))
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read("bootui-ci-examples/spring/pom.xml"), b"<version>2.3.1</version>")
            self.assertNotIn("secret.log", " ".join(archive.namelist()))
            self.assertNotIn(".env", " ".join(archive.namelist()))
            self.assertNotIn(".bootui", " ".join(archive.namelist()))
            self.assertTrue((archive.getinfo("bootui-ci-examples/mvnw").external_attr >> 16) & 0o111)
            self.assertTrue((archive.getinfo("bootui-ci-examples/scripts/start.sh").external_attr >> 16) & 0o111)
            self.assertIn(b"uses: jdubois/boot-ui@v2.3.1", archive.read("bootui-ci-examples/workflows/live.yml"))

    def test_unpublished_assets_remove_a_previous_stale_download(self):
        self.write(examples.ASSET, "old download")
        examples.write_asset(self.root)
        self.assertFalse((self.root / examples.ASSET).exists())

    def test_generation_cannot_overwrite_a_symlinked_document(self):
        document = self.root / "README.md"
        original = document.read_text()
        document.unlink()
        document.symlink_to(self.root / "outside.md")
        self.write("outside.md", original)
        with self.assertRaisesRegex(ValueError, "destination"):
            examples.synchronize(self.root, check=False)
        self.assertEqual((self.root / "outside.md").read_text(), original)

    def test_assets_reject_an_escaping_parent_directory(self):
        with tempfile.TemporaryDirectory() as outside:
            destination = self.root / examples.ASSET
            destination.parent.parent.mkdir(parents=True, exist_ok=True)
            destination.parent.symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symlink"):
                examples.write_asset(self.root)

    def test_published_download_check_requires_the_kit_in_the_built_site(self):
        script = self.root / ".github/scripts/check-docs-downloads.sh"
        script.parent.mkdir(parents=True)
        shutil.copy2(Path(__file__).with_name("check-docs-downloads.sh"), script)
        self.write(
            "README.md",
            "https://www.julien-dubois.com/boot-ui/install.sh\n"
            "https://www.julien-dubois.com/boot-ui/ci/bootui-ci-examples.zip\n",
        )
        self.write("docs/.vuepress/public/install.sh", "#!/bin/sh\nexit 0\n")
        self.write("docs/.vuepress/dist/install.sh", "#!/bin/sh\nexit 0\n")
        self.record(available=True, version="2.3.1")
        examples.write_asset(self.root)
        dist = self.root / "docs/.vuepress/dist"
        result = subprocess.run(["bash", str(script), str(dist)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ci/bootui-ci-examples.zip is not in the built site", result.stderr)
        target = dist / "ci/bootui-ci-examples.zip"
        target.parent.mkdir(parents=True)
        shutil.copy2(self.root / examples.ASSET, target)
        result = subprocess.run(["bash", str(script), str(dist)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
