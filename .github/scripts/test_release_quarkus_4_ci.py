import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SETUP = ROOT / ".github/scripts/setup-quarkus-4-graalvm.sh"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class Quarkus4CiTests(unittest.TestCase):
    def test_native_fixture_matrix_and_evidence_cannot_silently_drop_orm_coverage(self):
        from test_release_integrity import ReleaseIntegrityTests

        guard = ReleaseIntegrityTests("test_release_workflow_passes")
        source = (ROOT / ".github/workflows/build.yml").read_text()
        native = re.search(r"(?ms)^  quarkus-4-native:\n.*?(?=^  [a-z0-9-]+:\n|\Z)", source)[0]
        baseline = guard.check(root_files={})
        self.assertEqual(baseline.returncode, 0, baseline.stdout + baseline.stderr)
        for old, new in (
            ("        target: [ linux-x86_64, linux-arm64, macos-arm64 ]\n", ""),
            ("        fixture: [ jdbc, hibernate ]", "        fixture: [ jdbc ]"),
            ("    runs-on: ${{ matrix.runner }}", "    runs-on: ubuntu-24.04"),
            ('--fixture "$NATIVE_FIXTURE" ', ""),
            ('--fixture "$NATIVE_FIXTURE"', "--fixture jdbc"),
            ("name: quarkus-4-native-${{ matrix.target }}-${{ matrix.fixture }}",
             "name: quarkus-4-native-${{ matrix.target }}"),
            ("$NATIVE_TARGET/$NATIVE_FIXTURE", "$NATIVE_TARGET"),
            ("        fixture: [ jdbc, hibernate ]",
             "        fixture: [ jdbc, hibernate ]\n        exclude:\n"
             "          - target: linux-arm64\n            fixture: hibernate"),
            ("      - name: Compile and execute the actual native console\n",
             "      - name: Compile and execute the actual native console\n"
             "        if: matrix.fixture == 'jdbc'\n"),
        ):
            with self.subTest(mutation=old):
                self.assertEqual(native.count(old), 1, f"fixture drifted: {old!r}")
                mutated = source.replace(native, native.replace(old, new), 1)
                result = guard.check(root_files={".github/workflows/build.yml": mutated})
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("native acceptance", result.stderr)

    def test_all_q4_non_distribution_modules_are_excluded(self):
        root = ET.parse(ROOT / "pom.xml").getroot()
        excluded = {
            item.text for item in root.findall(".//m:excludeArtifacts/m:excludeArtifact", NS)
        }
        for pom in ROOT.glob("bootui-quarkus-4*/**/pom.xml"):
            if any(part in {"target", ".m2", "node_modules"} for part in pom.parts):
                continue
            artifact = ET.parse(pom).getroot().findtext("m:artifactId", namespaces=NS)
            with self.subTest(artifact=artifact):
                if artifact in {"bootui-quarkus-4", "bootui-quarkus-4-deployment"}:
                    self.assertNotIn(artifact, excluded)
                else:
                    self.assertIn(artifact, excluded)

    def test_q4_critical_policy_and_aggregate_coverage_are_included_without_changing_java17(self):
        root = ET.parse(ROOT / "pom.xml").getroot()
        self.assertEqual(root.findtext("m:properties/m:java.version", namespaces=NS), "17")
        includes = {item.text for item in root.findall(".//m:rules/m:rule/m:includes/m:include", NS)}
        self.assertIn("io.github.jdubois.bootui.quarkus4.QuarkusExposurePolicy", includes)
        self.assertIn("io.github.jdubois.bootui.quarkus4.mcp.QuarkusMcpPanelPolicy", includes)
        coverage = ET.parse(ROOT / "bootui-coverage/pom.xml").getroot()
        profile = coverage.find("m:profiles/m:profile", NS)
        self.assertEqual(profile.findtext("m:activation/m:jdk", namespaces=NS), "[21,)")
        self.assertEqual(
            {item.text for item in profile.findall("m:dependencies/m:dependency/m:artifactId", NS)},
            {"bootui-quarkus-4", "bootui-quarkus-4-deployment", "bootui-quarkus-4-integration-tests"},
        )

    def setup_compiler(self, target, *, machine, checksum):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        tools = root / "bin"
        tools.mkdir()
        system = "Darwin" if target.startswith("macos-") else "Linux"
        home = root / "compiler"
        if system == "Darwin":
            home /= "Contents/Home"
        for name, content in {
            "uname": f'#!/bin/sh\ncase "$1" in -s) echo {system};; -m) echo {machine};; esac\n',
            "curl": '#!/bin/sh\nprintf invoked > "$PROBE_DIR/download"\n'
                    'while [ "$#" -gt 0 ]; do if [ "$1" = "-o" ]; then shift; : > "$1"; fi; shift; done\n',
            "sha256sum": f'#!/bin/sh\necho "{checksum} archive"\n',
            "tar": '#!/bin/sh\nprintf invoked > "$PROBE_DIR/extract"\n'
                   f'mkdir -p "{home}/bin"\n'
                   f'printf "#!/bin/sh\\necho fixture compiler\\n" > "{home}/bin/java"\n'
                   f'cp "{home}/bin/java" "{home}/bin/native-image"\n'
                   f'chmod +x "{home}/bin/java" "{home}/bin/native-image"\n',
        }.items():
            path = tools / name
            path.write_text(content, encoding="utf-8")
            path.chmod(0o755)
        result = subprocess.run(
            ["bash", str(SETUP), target, str(root / "compiler")],
            env={**os.environ, "PATH": str(tools) + os.pathsep + os.environ["PATH"], "PROBE_DIR": str(root),
                 "GITHUB_ENV": str(root / "environment"), "GITHUB_PATH": str(root / "github-path")},
            capture_output=True, text=True,
        )
        return result, root

    def test_target_architecture_mismatch_stops_before_download(self):
        result, root = self.setup_compiler("linux-x86_64", machine="aarch64", checksum="wrong")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("actual Linux/x86_64 runner", result.stderr)
        self.assertFalse((root / "download").exists())

    def test_checksum_mismatch_stops_before_archive_extraction(self):
        result, root = self.setup_compiler("linux-arm64", machine="aarch64", checksum="wrong")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("checksum mismatch", result.stderr)
        self.assertTrue((root / "download").is_file())
        self.assertFalse((root / "extract").exists())

    def test_verified_compiler_home_and_path_follow_the_actual_archive_layout(self):
        script = SETUP.read_text()
        for target, machine, suffix in (
            ("linux-x86_64", "x86_64", ""),
            ("macos-arm64", "arm64", "/Contents/Home"),
        ):
            with self.subTest(target=target):
                checksum = re.search(rf"  {target}\)\n.*?\n    sha256=([a-f0-9]+)", script)[1]
                result, root = self.setup_compiler(target, machine=machine, checksum=checksum)
                self.assertEqual(result.returncode, 0, result.stderr)
                home = f"{root}/compiler{suffix}"
                self.assertEqual((root / "environment").read_text(), f"JAVA_HOME={home}\nGRAALVM_HOME={home}\n")
                self.assertEqual((root / "github-path").read_text(), f"{home}/bin\n")
                self.assertEqual(result.stdout.count("fixture compiler"), 2)
                self.assertFalse((root / "compiler/compiler.tar.gz").exists())


if __name__ == "__main__":
    unittest.main()
