import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check-central-bundle.py"
SPEC = importlib.util.spec_from_file_location("check_central_bundle", SCRIPT)
bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bundle)

VERSION = "2.0.0"
GROUP_PATH = Path("com/julien-dubois/bootui")


def pom(artifact, dependencies="", extra=""):
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{VERSION}</version>
  <name>BootUI :: {artifact}</name>
  <description>The {artifact} module.</description>
  <url>https://github.com/jdubois/boot-ui</url>
  <licenses>
    <license>
      <name>Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>jdubois</id>
      <name>Julien Dubois</name>
    </developer>
  </developers>
  <scm>
    <connection>scm:git:https://github.com/jdubois/boot-ui.git</connection>
    <developerConnection>scm:git:ssh://git@github.com/jdubois/boot-ui.git</developerConnection>
    <url>https://github.com/jdubois/boot-ui</url>
  </scm>
  {extra}
  <dependencies>{dependencies}</dependencies>
</project>
"""


def dependency(group, artifact, version="1.0", scope=None, optional=False):
    scope_xml = f"<scope>{scope}</scope>" if scope else ""
    optional_xml = "<optional>true</optional>" if optional else ""
    return (
        f"<dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId>"
        f"<version>{version}</version>{scope_xml}{optional_xml}</dependency>"
    )


class CentralBundleTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        for artifact in bundle.PUBLISHED:
            self.write(artifact, pom(artifact))

    def tearDown(self):
        self.directory.cleanup()

    def write(self, artifact, content, files=None):
        artifact_dir = self.root / GROUP_PATH / artifact / VERSION
        artifact_dir.mkdir(parents=True, exist_ok=True)
        prefix = f"{artifact}-{VERSION}"
        names = files
        if names is None:
            names = [f"{prefix}.jar", f"{prefix}-sources.jar", f"{prefix}-javadoc.jar"]
            names += [f"{prefix}-{c}.jar" for c in bundle.EXTRA_FILES.get(artifact, ())]
        for name in names + [f"{prefix}.pom"]:
            for suffix in ("", ".md5", ".sha1"):
                (artifact_dir / (name + suffix)).write_text("x", encoding="utf-8")
        (artifact_dir / f"{prefix}.pom").write_text(content, encoding="utf-8")

    def errors(self):
        return bundle.check_bundle(self.root, VERSION)

    def assert_error(self, fragment):
        errors = self.errors()
        self.assertTrue(any(fragment in e for e in errors), errors)

    def test_a_well_formed_bundle_passes(self):
        self.assertEqual(self.errors(), [])

    def test_a_parent_pom_or_an_old_coordinate_is_rejected(self):
        for extra in ("bootui-parent", "bootui-spring-boot-starter-reactive", "bootui-client"):
            with self.subTest(extra=extra):
                self.write(extra, pom(extra))
                self.assert_error("the bundle must hold exactly")
                for child in (self.root / GROUP_PATH / extra).rglob("*"):
                    if child.is_file():
                        child.unlink()
                for child in sorted((self.root / GROUP_PATH / extra).rglob("*"), reverse=True):
                    child.rmdir()
                (self.root / GROUP_PATH / extra).rmdir()

    def test_a_missing_published_artifact_or_file_is_rejected(self):
        artifact_dir = self.root / GROUP_PATH / "bootui-cli" / VERSION
        (artifact_dir / f"bootui-cli-{VERSION}-all.jar").unlink()
        self.assert_error("missing bootui-cli-2.0.0-all.jar")
        (artifact_dir / f"bootui-cli-{VERSION}-sources.jar.sha1").unlink()
        self.assert_error("missing bootui-cli-2.0.0-sources.jar.sha1")

    def test_signatures_are_accepted_beside_their_files(self):
        artifact_dir = self.root / GROUP_PATH / "bootui-agent" / VERSION
        for name in (f"bootui-agent-{VERSION}.jar.asc", f"bootui-agent-{VERSION}.pom.asc"):
            (artifact_dir / name).write_text("signature", encoding="utf-8")
        self.assertEqual(self.errors(), [])

    def test_resolver_bookkeeping_and_foreign_files_are_rejected(self):
        artifact_dir = self.root / GROUP_PATH / "bootui-core"
        for path in (
            artifact_dir / "maven-metadata-local.xml",
            artifact_dir / VERSION / "_remote.repositories",
            artifact_dir / VERSION / f"bootui-core-{VERSION}-tests.jar",
            artifact_dir / VERSION / f"bootui-core-{VERSION}.jar.asc.md5",
        ):
            with self.subTest(path=path.name):
                path.write_text("x", encoding="utf-8")
                self.assert_error(f"unexpected file in the bundle: {path.relative_to(self.root).as_posix()}")
                path.unlink()
        self.assertEqual(self.errors(), [])

    def test_a_pom_with_a_parent_is_rejected(self):
        parent = (
            "<parent><groupId>com.julien-dubois.bootui</groupId><artifactId>bootui-parent</artifactId>"
            f"<version>{VERSION}</version></parent>"
        )
        self.write("bootui-core", pom("bootui-core", extra=parent))
        self.assert_error("must not declare <parent>")

    def test_inherited_build_sections_are_rejected(self):
        for element in ("dependencyManagement", "profiles", "build", "repositories"):
            with self.subTest(element=element):
                self.write("bootui-core", pom("bootui-core", extra=f"<{element}/>"))
                self.assert_error(f"must not declare <{element}>")

    def test_unresolved_properties_and_missing_versions_are_rejected(self):
        self.write("bootui-core", pom("bootui-core", dependency("org.example", "lib", "${lib.version}")))
        self.assert_error("unresolved ${...} property")
        self.write(
            "bootui-core",
            pom("bootui-core", "<dependency><groupId>org.example</groupId><artifactId>lib</artifactId></dependency>"),
        )
        self.assert_error("must declare groupId, artifactId and a literal version")

    def test_central_metadata_must_match_the_root(self):
        for old, new, fragment in (
            ("<url>https://github.com/jdubois/boot-ui</url>\n  <licenses>",
             "<url>https://github.com/jdubois/boot-ui/bootui-core</url>\n  <licenses>", "<url> must be"),
            ("<description>The bootui-core module.</description>", "", "non-empty <description>"),
            ("Apache License, Version 2.0", "MIT", "<licenses>"),
            ("<name>Julien Dubois</name>", "", "named developer"),
            ("scm:git:https://github.com/jdubois/boot-ui.git<", "scm:git:https://github.com/jdubois/boot-ui.git/core<",
             "<scm><connection>"),
        ):
            with self.subTest(new=new):
                self.write("bootui-core", pom("bootui-core").replace(old, new, 1))
                self.assert_error(fragment)

    def test_unpublished_bootui_dependencies_are_rejected_unless_optional(self):
        bridge = dependency("com.julien-dubois.bootui", "bootui-agent-bridge", VERSION)
        self.write("bootui-agent", pom("bootui-agent", bridge))
        self.assert_error("bootui-agent-bridge is not published")
        optional_bridge = dependency("com.julien-dubois.bootui", "bootui-agent-bridge", VERSION, optional=True)
        self.write("bootui-agent", pom("bootui-agent", optional_bridge))
        self.assertEqual(self.errors(), [])
        stale = dependency("com.julien-dubois.bootui", "bootui-core", "1.19.0")
        self.write("bootui-engine", pom("bootui-engine", stale))
        self.assert_error("must be version 2.0.0")

    def test_test_scope_dependencies_are_rejected(self):
        self.write("bootui-core", pom("bootui-core", dependency("org.junit.jupiter", "junit-jupiter", scope="test")))
        self.assert_error("scope test")

    def test_the_starter_brings_no_web_stack(self):
        starter = "bootui-spring-boot-starter"
        for group, artifact, optional in (
            ("org.springframework.boot", "spring-boot-starter-web", False),
            ("org.springframework.boot", "spring-boot-starter-webflux", True),
            ("org.apache.tomcat.embed", "tomcat-embed-core", False),
            ("jakarta.servlet", "jakarta.servlet-api", False),
            ("io.projectreactor.netty", "reactor-netty-http", False),
        ):
            with self.subTest(artifact=artifact):
                self.write(starter, pom(starter, dependency(group, artifact, optional=optional)))
                self.assert_error("must bring no web stack")
        provided = dependency("org.springframework.boot", "spring-boot-starter-web", scope="provided")
        self.write(starter, pom(starter, provided))
        self.assertEqual(self.errors(), [])


if __name__ == "__main__":
    unittest.main()
