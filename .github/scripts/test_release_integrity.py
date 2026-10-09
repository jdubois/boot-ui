import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/check-release-integrity.sh"
WORKFLOW = ROOT / ".github/workflows/release.yml"
PAGES = ROOT / ".github/workflows/pages.yml"
DOCKER = ROOT / ".github/workflows/docker-publish.yml"
SMOKE = ROOT / ".github/scripts/consumer-smoke-tests.sh"
STAGE = ROOT / ".github/scripts/stage-release-candidate.sh"
BUNDLE_CHECK = ROOT / ".github/scripts/check-central-bundle.py"
BUNDLE_ASSEMBLER = ROOT / ".github/scripts/assemble_central_bundle.py"
STARTER_POM = "bootui-spring-boot-starter/pom.xml"
_BUNDLE_CHECK_SPEC = importlib.util.spec_from_file_location("check_central_bundle", BUNDLE_CHECK)
_bundle_check = importlib.util.module_from_spec(_BUNDLE_CHECK_SPEC)
_BUNDLE_CHECK_SPEC.loader.exec_module(_bundle_check)
# The published coordinates, from the one list the assembler and the bundle check read.
PUBLISHED = _bundle_check.PUBLISHED

SIGNATURE_CHECK = ROOT / ".github/scripts/verify-release-signatures.sh"
BUNDLE_SIGNATURE_LINE = '          bash .github/scripts/verify-release-signatures.sh bundle "$BUNDLE_DIR"\n'
BUILD_SIGNATURE_LINE = "            bash .github/scripts/verify-release-signatures.sh build\n"
PINNED_RELEASE_KEY = "      RELEASE_KEY_FINGERPRINT: 7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9\n"
NEXT_VERSION_CALL = (
    'bash .github/scripts/release-version-policy.sh next-version "$VERSION" "$CURRENT_VERSION" "$RELEASE_LINE"'
)
TAGGED_LINE_CHECK = 'if [[ "$TAGGED_RELEASE_LINE" != "${VERSION%%.*}" ]]; then'
GATE_RUN = "run: bash .github/scripts/release-line-gate.sh"
PAGES_DEPLOY_CONDITION = "    if: github.event_name != 'pull_request' && needs.build.outputs.publish == 'true'\n"
PAGES_UPLOAD_CONDITION = "        if: github.event_name != 'pull_request' && steps.gate.outputs.publish == 'true'\n"
DOCKER_CONFIG_CONDITION = "    if: ${{ !inputs.cleanup_only && needs.gate.outputs.publish == 'true' }}\n"
NEWEST_MAJOR_CALL = 'bash .github/scripts/release-version-policy.sh newest-major "$RELEASE_VERSION"'
REDEPLOY_CONDITION = "if: env.CENTRAL_AUTO_PUBLISH == 'true' && env.REDEPLOY_DOCS == 'true'"
PUBLICATION_MODULES = "bootui-cli,bootui-agent-bridge,bootui-agent \\\n"
STAGED_SMOKE = 'bash .github/scripts/consumer-smoke-tests.sh "$VERSION" "$RUNNER_TEMP/bootui-candidate"'
CENTRAL_SMOKE = '          bash .github/scripts/consumer-smoke-tests.sh "$VERSION"\n'
WEBFLUX_REACTIVE = (
    'run_spring_smoke "Spring WebFlux" "$WEBFLUX_SMOKE_DIR" "$WEBFLUX_PORT" REACTIVE '
    "org.springframework.boot.reactor.netty.NettyWebServer"
)
AGENT_AVAILABILITY = '"bootui-agent/${VERSION}/bootui-agent-${VERSION}.jar"\n'
AGENT_CONSUMER = '-f "$AGENT_SMOKE_DIR/pom.xml"'
AGENT_ATTACH = 'java -javaagent:"$AGENT_JAR" -version'
AGENT_DORMANT = (
    'AGENT_DORMANT_LINE="[BootUI agent] BootUI agent ${VERSION} attached (javaagent); '
    'dormant until BootUI claims it"'
)
AGENT_DEPENDENCY_FREE = 'if [[ "$AGENT_CLASSPATH" != "bootui-agent-${VERSION}.jar" ]]; then'
PLUGIN_VERSION_UPDATE = "plugin.version = process.argv[1];"
PLUGIN_VERSION_CHECK = "if (plugin.version !== process.argv[1]) {"


class ReleaseIntegrityTests(unittest.TestCase):
    def check(
        self,
        content=None,
        pages=None,
        docker=None,
        smoke=None,
        stage=None,
        bundle=None,
        assembler=None,
        signature=None,
        root_files=None,
    ):
        """Runs the guard on mutated copies. root_files maps repository paths to replacement contents and
        runs the guard against a copy of the POMs and the release line it reads from the repository."""
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for name, text, source in (
                ("release.yml", content, WORKFLOW),
                ("pages.yml", pages, PAGES),
                ("docker-publish.yml", docker, DOCKER),
                ("consumer-smoke-tests.sh", smoke, SMOKE),
                ("stage-release-candidate.sh", stage, STAGE),
                ("check-central-bundle.py", bundle, BUNDLE_CHECK),
                ("assemble_central_bundle.py", assembler, BUNDLE_ASSEMBLER),
                ("verify-release-signatures.sh", signature, SIGNATURE_CHECK),
            ):
                path = Path(directory) / name
                path.write_text(source.read_text(encoding="utf-8") if text is None else text, encoding="utf-8")
                paths.append(str(path))
            env = dict(os.environ)
            if root_files is not None:
                root = Path(directory) / "repository"
                for relative in ["pom.xml", ".github/release-line"] + [f"{m}/pom.xml" for m in PUBLISHED]:
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text((ROOT / relative).read_text(encoding="utf-8"), encoding="utf-8")
                for relative, text in root_files.items():
                    (root / relative).write_text(text, encoding="utf-8")
                env["RELEASE_INTEGRITY_ROOT"] = str(root)
            return subprocess.run(
                ["bash", str(SCRIPT), *paths],
                capture_output=True,
                text=True,
                check=False,
                env=env,
            )

    def mutate(self, old, new, count=1):
        content = WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(content.count(old), count, f"fixture drifted: {old!r}")
        return content.replace(old, new)

    def mutate_file(self, source, old, new, count=1):
        content = source.read_text(encoding="utf-8")
        self.assertEqual(content.count(old), count, f"fixture drifted: {old!r}")
        return content.replace(old, new)

    def mutate_root(self, relative, old, new, count=1):
        return {relative: self.mutate_file(ROOT / relative, old, new, count)}

    def assert_rejected(self, content, message, pages=None, docker=None, **files):
        result = self.check(content, pages, docker, **files)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(message, result.stderr)

    def test_release_workflow_passes(self):
        result = self.check(WORKFLOW.read_text(encoding="utf-8"))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_plugin_version_update_and_verification_are_required(self):
        for old, new, message in (
            (PLUGIN_VERSION_UPDATE, "// no update", "portable plugin release version update"),
            (
                'fs.writeFileSync(path, JSON.stringify(plugin, null, 2) + "\\n");',
                "// no write",
                "portable plugin release version write",
            ),
            (PLUGIN_VERSION_CHECK, "if (false) {", "portable plugin release version verification"),
        ):
            with self.subTest(old=old):
                self.assert_rejected(self.mutate(old, new), message)

    def plugin_version_command(self, marker):
        content = WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(content.count(marker), 1, f"fixture drifted: {marker!r}")
        position = content.index(marker)
        start = content.rindex("node -e '", 0, position)
        suffix = "' \"$VERSION\""
        end = content.index(suffix, position) + len(suffix)
        return content[start:end]

    def test_plugin_version_commands_are_ordered_around_sealing(self):
        for marker, anchor, message in (
            (
                PLUGIN_VERSION_UPDATE,
                '          RELEASE_SHA="$(git rev-parse HEAD)"',
                "written before release verification and sealing",
            ),
            (
                PLUGIN_VERSION_CHECK,
                "          echo \"All BootUI ${VERSION} artifacts are available on Maven Central.\"",
                "verified before publication",
            ),
        ):
            with self.subTest(marker=marker):
                command = self.plugin_version_command(marker)
                content = WORKFLOW.read_text(encoding="utf-8").replace(command, "")
                content = content.replace(anchor, command + "\n" + anchor)
                self.assert_rejected(content, message)

    def test_plugin_version_commands_update_and_check_the_actual_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "plugins/bootui/plugin.json"
            manifest.parent.mkdir(parents=True)
            original = json.loads((ROOT / "plugins/bootui/plugin.json").read_text(encoding="utf-8"))
            manifest.write_text(json.dumps(original), encoding="utf-8")
            claude_manifest = manifest.parent / ".claude-plugin/plugin.json"
            claude_manifest.parent.mkdir()
            claude_original = (ROOT / "plugins/bootui/.claude-plugin/plugin.json").read_bytes()
            claude_manifest.write_bytes(claude_original)
            env = {**os.environ, "VERSION": "2.3.4"}

            def run(marker):
                return subprocess.run(
                    ["bash", "-c", self.plugin_version_command(marker)],
                    cwd=directory,
                    env=env,
                    capture_output=True,
                    text=True,
                    check=False,
                )

            result = run(PLUGIN_VERSION_CHECK)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("plugin version mismatch", result.stderr)
            result = run(PLUGIN_VERSION_UPDATE)
            self.assertEqual(result.returncode, 0, result.stderr)
            updated = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(updated, {**original, "version": "2.3.4"})
            self.assertEqual(claude_manifest.read_bytes(), claude_original)
            result = run(PLUGIN_VERSION_CHECK)
            self.assertEqual(result.returncode, 0, result.stderr)
            versioned_bytes = manifest.read_bytes()
            result = run(PLUGIN_VERSION_UPDATE)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(manifest.read_bytes(), versioned_bytes)

            del updated["version"]
            manifest.write_text(json.dumps(updated), encoding="utf-8")
            result = run(PLUGIN_VERSION_CHECK)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("plugin version mismatch", result.stderr)
            result = run(PLUGIN_VERSION_UPDATE)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = run(PLUGIN_VERSION_CHECK)
            self.assertEqual(result.returncode, 0, result.stderr)

            manifest.write_text("{invalid", encoding="utf-8")
            for marker in (PLUGIN_VERSION_CHECK, PLUGIN_VERSION_UPDATE):
                with self.subTest(marker=marker):
                    self.assertNotEqual(run(marker).returncode, 0)

    def test_per_major_version_policy_is_required(self):
        self.assert_rejected(
            self.mutate(NEXT_VERSION_CALL, 'echo "$VERSION"'), "per-major next-version policy"
        )

    def test_version_policy_must_run_before_project_files_change(self):
        content = self.mutate(
            "git tag --list 'v*' |\n            " + NEXT_VERSION_CALL + "\n", ""
        )
        content = content.replace(
            "            -DgenerateBackupPoms=false\n",
            "            -DgenerateBackupPoms=false\n          git tag --list 'v*' | "
            + NEXT_VERSION_CALL
            + "\n",
            1,
        )
        self.assert_rejected(content, "validated against its major")

    def test_newest_tag_overall_cannot_return(self):
        content = self.mutate(
            "git tag --list 'v*' |\n",
            "LATEST_TAG=\"$(git tag --list 'v*' | sort -V | tail -n 1)\"\n          git tag --list 'v*' |\n",
        )
        self.assert_rejected(content, "not from the newest tag overall")

    def test_documentation_redeploy_requires_the_newest_major_gate(self):
        self.assert_rejected(
            self.mutate(REDEPLOY_CONDITION, "if: env.CENTRAL_AUTO_PUBLISH == 'true'"),
            "restricted to the newest major",
        )

    def test_documentation_redeploy_decision_is_required(self):
        self.assert_rejected(
            self.mutate(NEWEST_MAJOR_CALL, "echo true"),
            "newest-major documentation redeploy decision",
        )

    def test_documentation_redeploy_decision_reads_origin(self):
        self.assert_rejected(
            self.mutate(
                "git ls-remote --tags --refs origin 'refs/tags/v*' |\n"
                "              awk '{ sub(\"^refs/tags/\", \"\", $2); print $2 }' |",
                "git tag --list 'v*' |",
            ),
            "from the tags on origin",
        )

    def test_documentation_cannot_be_dispatched_outside_the_gated_step(self):
        content = self.mutate(
            "      - name: Manual publish reminder\n",
            "      - name: Ungated documentation\n"
            "        run: gh workflow run pages.yml --ref \"$RELEASE_TAG\"\n\n"
            "      - name: Manual publish reminder\n",
        )
        self.assert_rejected(content, "exactly one newest-major-gated step")

    def test_documentation_decision_must_precede_deployment(self):
        content = WORKFLOW.read_text(encoding="utf-8")
        start = content.index("      - name: Decide documentation redeploy\n")
        middle = content.index("      - name: Redeploy documentation site\n")
        end = content.index("      - name: Manual publish reminder\n")
        reordered = content[:start] + content[middle:end] + content[start:middle] + content[end:]
        self.assert_rejected(reordered, "decision must precede the documentation deployment")

    def test_existing_immutability_rules_still_hold(self):
        for old, new, message in (
            ('git tag -s "$TAG"', 'git tag "$TAG"', "signed annotated release tag creation"),
            ('git verify-tag "$TAG"', "true", "new tag signature verification"),
            ("git push --atomic origin", "git push origin", "atomic branch and tag push"),
            ("ref: ${{ env.RELEASE_SHA }}", "ref: main", "immutable release checkout"),
        ):
            with self.subTest(old=old):
                self.assert_rejected(self.mutate(old, new), message)

    def test_java_agent_is_in_the_publication_reactor(self):
        for modules in (
            "bootui-cli \\\n",
            "bootui-cli,bootui-agent \\\n",
            "bootui-cli,bootui-agent-bridge \\\n",
        ):
            with self.subTest(modules=modules):
                self.assert_rejected(
                    self.mutate(PUBLICATION_MODULES, modules), "publication-only Maven reactor"
                )

    def test_java_agent_availability_is_polled(self):
        self.assert_rejected(
            self.mutate("            " + AGENT_AVAILABILITY, ""), "Java agent availability check"
        )

    def test_agent_bridge_is_never_polled(self):
        content = self.mutate(
            "            " + AGENT_AVAILABILITY,
            "            " + AGENT_AVAILABILITY
            + '            "bootui-agent-bridge/${VERSION}/bootui-agent-bridge-${VERSION}.jar"\n',
        )
        self.assert_rejected(content, "bootui-agent-bridge is never published")

    def test_java_agent_smoke_test_is_required(self):
        for old, new, message in (
            (AGENT_CONSUMER, '-f "$AGENT_POM"', "external Java agent consumer invocation"),
            (AGENT_DEPENDENCY_FREE, "if false; then", "consumer dependency-free assertion"),
            (AGENT_ATTACH, "java -version", "Java agent attach smoke test"),
            (AGENT_DORMANT, 'AGENT_DORMANT_LINE="attached"', "dormant startup assertion"),
        ):
            with self.subTest(old=old):
                self.assert_rejected(None, message, smoke=self.mutate_file(SMOKE, old, new))

    def test_every_consumer_resolves_bootui_after_the_local_artifacts_are_dropped(self):
        content = SMOKE.read_text(encoding="utf-8")
        purge = 'rm -rf "$LOCAL_REPO/com/julien-dubois/bootui"\n'
        self.assertEqual(content.count(purge), 1, "fixture drifted: local artifact purge")
        content = content.replace(purge, "")
        content = content.replace(
            'echo "Java agent smoke test passed."\n', 'echo "Java agent smoke test passed."\n' + purge, 1
        )
        self.assert_rejected(None, "after the local BootUI artifacts are dropped", smoke=content)

    def test_resolved_artifacts_must_come_from_the_source_under_test(self):
        for old, new, message in (
            ('if ! resolved_from_source_under_test "$file"; then', "if false; then", "per-file origin check"),
            ('-e "${name}>${EXPECTED_ORIGIN_KEY}="', '-e "${name}>"', "exact origin match"),
            ('-Fxq -e "${name}>${EXPECTED_ORIGIN}="', '-Fq -e "${name}>${EXPECTED_ORIGIN}"', "exact origin match"),
            ('readonly EXPECTED_ORIGIN="bootui-staged"', 'readonly EXPECTED_ORIGIN=""', "staged-candidate origin"),
            ('"file://${STAGED_REPOSITORY}"', '"file://"', "staged-candidate origin URL"),
            ('"https://repo.maven.apache.org/maven2"', '"https://"', "Maven Central origin URL"),
            ('"$EXPECTED_ORIGIN_URL" | sha1_hex', '"" | sha1_hex', "Maven Resolver 2 origin key"),
            (
                'if [[ "$RESOLVED_ARTIFACTS" != "$EXPECTED_ARTIFACTS" ]]; then',
                "if false; then",
                "resolved exactly the published coordinates",
            ),
        ):
            with self.subTest(old=old):
                self.assert_rejected(None, message, smoke=self.mutate_file(SMOKE, old, new))

    def test_origin_check_accepts_exactly_the_source_under_test_in_both_resolver_formats(self):
        content = SMOKE.read_text(encoding="utf-8")
        functions = []
        for name in ("sha1_hex", "resolved_from_source_under_test"):
            start = content.index(f"\n{name}() {{\n") + 1
            functions.append(content[start : content.index("\n}\n", start) + 3])
        key_line = next(line for line in content.splitlines() if line.startswith("EXPECTED_ORIGIN_KEY="))
        url = "file:///tmp/bootui-candidate"
        digest = hashlib.sha1(url.encode()).hexdigest()
        other = hashlib.sha1(b"file:///tmp/elsewhere").hexdigest()
        jar = "bootui-ui-1.0.0.jar"
        for origin, accepted in (
            (f"{jar}>bootui-staged=", True),
            (f"{jar}>bootui-staged-{digest}=", True),
            (f"{jar}>bootui-staged-{digest.upper()}=", False),
            (f"{jar}>bootui-staged-{other}=", False),
            (f"{jar}>bootui-staged-{digest}x=", False),
            (f"{jar}>central=", False),
            (f"{jar}>central-{digest}=", False),
            (f"{jar}>=", False),
            (f"bootui-engine-1.0.0.jar>bootui-staged-{digest}=", False),
        ):
            with self.subTest(origin=origin), tempfile.TemporaryDirectory() as directory:
                artifact = Path(directory) / jar
                artifact.write_bytes(b"")
                (Path(directory) / "_remote.repositories").write_text(origin + "\n", encoding="utf-8")
                program = "\n".join(
                    [*functions, 'EXPECTED_ORIGIN="bootui-staged"', f'EXPECTED_ORIGIN_URL="{url}"', key_line]
                ) + f'\nresolved_from_source_under_test "{artifact}"\n'
                result = subprocess.run(["bash", "-c", program], capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, accepted, result.stderr)

    def test_webflux_consumer_must_stay_reactive_on_netty(self):
        for old, new, message in (
            (WEBFLUX_REACTIVE, WEBFLUX_REACTIVE.replace("REACTIVE", "SERVLET"), "REACTIVE on Netty"),
            ("  jakarta.servlet:jakarta.servlet-api \\\n", "", "Servlet API kept off"),
            ("  org.apache.tomcat.embed:tomcat-embed-core \\\n", "", "Tomcat kept off"),
            ("  io.projectreactor.netty:reactor-netty-http \\\n", "", "Reactor Netty kept off"),
        ):
            with self.subTest(old=old):
                self.assert_rejected(None, message, smoke=self.mutate_file(SMOKE, old, new))

    def test_cli_client_consumer_must_get_no_dependency(self):
        self.assert_rejected(
            None,
            "dependency-free CLI client consumer assertion",
            smoke=self.mutate_file(
                SMOKE, 'if [[ "$CLI_CLASSPATH" != "com.julien-dubois.bootui:bootui-cli" ]]; then', "if false; then"
            ),
        )
        self.assert_rejected(
            None,
            "runnable CLI uber-jar smoke test",
            smoke=self.mutate_file(
                SMOKE,
                'CLI_VERSION_OUTPUT="$(java -jar "$CLI_ALL_JAR" --version)"',
                'CLI_VERSION_OUTPUT="bootui ${VERSION}"',
            ),
        )

    def test_staged_candidate_is_smoke_tested_before_the_tag(self):
        content = self.mutate(STAGED_SMOKE, "true")
        self.assert_rejected(content, "pre-tag consumer smoke tests")
        moved = self.mutate(
            "            ./mvnw -B -ntp -Prelease clean verify\n",
            "            ./mvnw -B -ntp -Prelease clean verify\n            git commit -m \"Release $TAG\"\n",
        )
        self.assert_rejected(moved, "before the release commit and tag exist")

    def test_staged_candidate_is_smoke_tested_before_publication(self):
        self.assert_rejected(
            self.mutate("      - name: Smoke test the staged release candidate\n", "      - name: Smoke test\n"),
            "pre-publication staged smoke test step",
        )
        content = WORKFLOW.read_text(encoding="utf-8")
        start = content.index("      - name: Smoke test the staged release candidate\n")
        middle = content.index("      - name: Publish to Maven Central\n")
        end = content.index("      - name: Wait for Maven Central availability\n")
        reordered = content[:start] + content[middle:end] + content[start:middle] + content[end:]
        self.assert_rejected(reordered, "before Maven Central publication")

    def test_pre_publication_smoke_is_skipped_only_after_a_passing_pre_tag_run(self):
        flag = '            echo "CANDIDATE_SMOKE_PASSED=true" >> "$GITHUB_ENV"\n'
        self.assert_rejected(
            self.mutate(flag, flag + '          echo "CANDIDATE_SMOKE_PASSED=true" >> "$GITHUB_ENV"\n'),
            "may be set only once",
        )
        content = self.mutate(flag, "")
        content = content.replace(
            "            ./mvnw -B -ntp -Prelease clean verify\n",
            "            ./mvnw -B -ntp -Prelease clean verify\n" + flag,
            1,
        )
        self.assert_rejected(content, "only a passing pre-tag staged smoke test")
        self.assert_rejected(
            self.mutate(
                "        if: env.RESUME_AFTER_PUBLISH != 'true' && env.CANDIDATE_SMOKE_PASSED != 'true'\n",
                "        if: false\n",
            ),
            "skipped only on a resumed run",
        )

    def test_published_distributions_are_smoke_tested_from_central(self):
        self.assert_rejected(self.mutate(CENTRAL_SMOKE, "          true\n"), "Maven Central consumer smoke tests")

    def test_exactly_the_published_coordinates_are_polled(self):
        cli = '            "bootui-cli/${VERSION}/bootui-cli-${VERSION}.jar"\n'
        ui = '            "bootui-ui/${VERSION}/bootui-ui-${VERSION}.jar"\n'
        self.assert_rejected(self.mutate(ui, ""), "poll exactly the published coordinates")
        for new, message in (
            (cli + '            "bootui-parent/${VERSION}/bootui-parent-${VERSION}.pom"\n', "neither parent POM"),
            (cli + '            "bootui-client/${VERSION}/bootui-client-${VERSION}.jar"\n', "poll exactly"),
        ):
            with self.subTest(new=new):
                self.assert_rejected(self.mutate(cli, new), message)

    def test_staging_uses_the_publication_reactor_and_the_bundle_check(self):
        self.assert_rejected(
            None, "must stage the publication-only Maven reactor", stage=self.mutate_file(STAGE, "bootui-cli,", "")
        )
        for old, new, message in (
            ("-Prelease clean install \\", "-Prelease clean deploy \\", "not run the Maven deploy phase"),
            ("-Prelease clean install \\", "-Prelease clean package \\", "clean install"),
            ("  -Dgpg.skip=true\n", "  -Dgpg.skip=true \\\n  deploy\n", "not run the Maven deploy phase"),
            ('--unsigned "$LOCAL_REPO"', '--unsigned "$HOME/.m2/repository"', "assemble_central_bundle.py"),
            ('check-central-bundle.py" "$OUTPUT" "$VERSION"', 'true" "$OUTPUT"', "check-central-bundle.py"),
            ('check-central-bundle.py" "$OUTPUT" "$VERSION"', 'check-central-bundle.py" "$OUTPUT" "$VERSION" || true', "check-central-bundle.py"),
            ('python3 "$REPOSITORY_ROOT/.github/scripts/check-central-bundle.py"', '# python3 "$REPOSITORY_ROOT/.github/scripts/check-central-bundle.py"', "check-central-bundle.py"),
            ('"$LOCAL_REPO" "$VERSION" "$BUNDLE"\n', '"$LOCAL_REPO" "$VERSION" "$BUNDLE" || true\n', "assemble_central_bundle.py"),
            ("set -euo pipefail\n", "set -uo pipefail\n", "set -euo pipefail"),
            ('unzip -q "$BUNDLE" -d "$OUTPUT"\n', "", "unzip -q"),
            ('unzip -q "$BUNDLE" -d "$OUTPUT"\n', 'unzip -q "$BUNDLE" -d "$OUTPUT" || true\n', "unzip -q"),
        ):
            with self.subTest(new=new):
                self.assert_rejected(None, message, stage=self.mutate_file(STAGE, old, new))

    def test_staging_runs_its_commands_in_publication_order(self):
        check = 'python3 "$REPOSITORY_ROOT/.github/scripts/check-central-bundle.py" "$OUTPUT" "$VERSION"\n'
        unzip = 'unzip -q "$BUNDLE" -d "$OUTPUT"\n'
        assemble = (
            'python3 "$REPOSITORY_ROOT/.github/scripts/assemble_central_bundle.py" --unsigned "$LOCAL_REPO" "$VERSION"'
            ' "$BUNDLE"\n'
        )
        install = "./mvnw -B -ntp -Prelease clean install \\\n"
        stage = STAGE.read_text(encoding="utf-8")
        for first, second in ((unzip, check), (assemble, unzip), (install, assemble)):
            with self.subTest(first=first):
                self.assertEqual(stage.count(first), 1, f"fixture drifted: {first!r}")
                swapped = stage.replace(first, "\0", 1).replace(second, first, 1).replace("\0", second, 1)
                self.assert_rejected(None, "after the previous staging command", stage=swapped)

    def test_every_bundle_signature_is_verified_before_the_upload(self):
        message = "verification of every bundle signature by the release key before its upload"
        for mutated in (
            "",
            BUNDLE_SIGNATURE_LINE.replace("bash ", "# bash ", 1),
            BUNDLE_SIGNATURE_LINE.rstrip("\n") + " || true\n",
        ):
            with self.subTest(mutated=mutated):
                self.assert_rejected(self.mutate(BUNDLE_SIGNATURE_LINE, mutated), message)
        # Verifying only after the upload is too late: the coordinate is already consumed.
        publish = (
            '          python3 .github/scripts/publish_central_bundle.py target/central-bundle.zip "bootui-$VERSION"'
            ' "$CENTRAL_AUTO_PUBLISH"\n'
        )
        moved = self.mutate(BUNDLE_SIGNATURE_LINE, "").replace(publish, publish + BUNDLE_SIGNATURE_LINE, 1)
        self.assert_rejected(moved, "after the previous bundle command")

    def test_the_build_signatures_are_verified_before_the_tag(self):
        for mutated in (
            "",
            BUILD_SIGNATURE_LINE.replace("bash ", "# bash ", 1),
            BUILD_SIGNATURE_LINE.rstrip("\n") + " || true\n",
        ):
            with self.subTest(mutated=mutated):
                self.assert_rejected(self.mutate(BUILD_SIGNATURE_LINE, mutated), "pre-tag verification")
        commit = '            git commit -m "Release $TAG"\n'
        workflow = self.mutate(BUILD_SIGNATURE_LINE, "")
        self.assertEqual(workflow.count(commit), 1, "fixture drifted: release commit")
        moved = workflow.replace(commit, commit + BUILD_SIGNATURE_LINE, 1)
        self.assert_rejected(moved, "after the verification build and before the release commit")

    def test_the_release_key_is_pinned_and_checked_before_anything_is_signed(self):
        pinned = "pin RELEASE_KEY_FINGERPRINT to 7B7C0BD038603E5A9F1476D0498BA5AC9BABBAF9"
        other = PINNED_RELEASE_KEY.replace("7B7C0BD0", "00000000")
        self.assert_rejected(self.mutate(PINNED_RELEASE_KEY, other), pinned)
        self.assert_rejected(self.mutate(PINNED_RELEASE_KEY, ""), pinned)
        override = "        env:\n          RELEASE_KEY_FINGERPRINT: 0000000000000000000000000000000000000000\n"
        step = "      - name: Prepare release version\n        if: github.event_name == 'workflow_dispatch' && inputs.version != ''\n"
        self.assert_rejected(self.mutate(step, step + override), "nothing may redefine it")
        workflow = WORKFLOW.read_text(encoding="utf-8")
        start = workflow.index("      - name: Check the release signing key\n")
        end = workflow.index("      - name: Configure Git author\n")
        check_step = workflow[start:end]
        guarded = [line for line in check_step.splitlines() if line.rstrip().endswith("exit 1; }")]
        self.assertEqual(len(guarded), 2, "fixture drifted: the release signing key check")
        for line in guarded:
            for mutated in (line.replace("; exit 1; }", "; }"), line.replace("          ", "          # ", 1), ""):
                with self.subTest(line=line, mutated=mutated):
                    self.assert_rejected(self.mutate(line + "\n", mutated + "\n"), "the release signing key check must")
        self.assert_rejected(self.mutate(check_step, ""), "the release signing key check must")
        moved = self.mutate(check_step, "").replace(
            "      - name: Decide documentation redeploy\n", check_step + "      - name: Decide documentation redeploy\n", 1
        )
        self.assert_rejected(moved, "before anything is signed or tagged")
        jdk = "      - name: Set up JDK 17\n"
        before_jdk = self.mutate(check_step, "").replace(jdk, check_step + jdk, 1)
        self.assert_rejected(before_jdk, "after it is imported")
        name = "      - name: Check the release signing key\n"
        for setting in ("        if: inputs.version != ''\n", "        continue-on-error: true\n"):
            with self.subTest(setting=setting):
                self.assert_rejected(self.mutate(name, name + setting), "the release signing key check must run unconditionally")
        build = "      - name: Configure Git author\n"
        self.assert_rejected(
            self.mutate(build, build + "        continue-on-error: true\n"), "no release step may set continue-on-error"
        )

    def test_the_signature_check_reads_the_pin_and_nothing_else(self):
        script = SIGNATURE_CHECK.read_text(encoding="utf-8")
        readonly = "readonly RELEASE_KEY_FINGERPRINT\n"
        derived = (
            "RELEASE_KEY_FINGERPRINT=\"$(gpg --batch --with-colons --list-secret-keys"
            " | awk -F: '$1 == \"fpr\" { print $10; exit }')\"\n"
        )
        for mutated in (derived + readonly, readonly + "export RELEASE_KEY_FINGERPRINT\n", ""):
            with self.subTest(mutated=mutated):
                self.assert_rejected(
                    None, "verify-release-signatures.sh must", signature=script.replace(readonly, mutated, 1)
                )

    def test_every_signature_check_failure_stops_the_script(self):
        script = SIGNATURE_CHECK.read_text(encoding="utf-8")
        guarded = [line for line in script.splitlines() if line.rstrip().endswith("exit 1; }")]
        self.assertEqual(len(guarded), 4, "fixture drifted: the guarded failure lines")
        for line in guarded:
            for mutated in (
                line.replace("; exit 1; }", "; }"),
                line.replace("; exit 1; }", "; # exit 1; }"),
                "# " + line.lstrip(),
                "",
            ):
                with self.subTest(line=line, mutated=mutated):
                    self.assertEqual(script.count(line + "\n"), 1, f"fixture drifted: {line!r}")
                    self.assert_rejected(
                        None, "verify-release-signatures.sh must", signature=script.replace(line + "\n", mutated + "\n", 1)
                    )
        for old, new in (
            ("set -euo pipefail\n", "set -uo pipefail\n"),
            ("-path '*/target/*.asc')", "-path '*/target/*.sig')"),
            ("(-name '*.asc')", "(-name '*.sig')"),
            ('"${FIND_FILTER[@]}" -type f -print0)', '-name nothing -print0)'),
        ):
            with self.subTest(new=new):
                self.assertEqual(script.count(old), 1, f"fixture drifted: {old!r}")
                self.assert_rejected(None, "verify-release-signatures.sh must", signature=script.replace(old, new, 1))

    def test_every_list_names_the_same_coordinates(self):
        self.assert_rejected(
            None,
            "must expect exactly the published coordinates",
            smoke=self.mutate_file(SMOKE, "  bootui-agent\n)", "  bootui-agent\n  bootui-client\n)"),
        )
        self.assert_rejected(
            None,
            "must expect exactly the published coordinates",
            bundle=self.mutate_file(BUNDLE_CHECK, '    "bootui-agent",\n)', ")"),
        )
        own_list = "ARTIFACT_IDS = _load_bundle_check().PUBLISHED"
        for new in (
            'ARTIFACT_IDS = ("bootui-engine", "bootui-agent-bridge")',
            own_list + ' + ("bootui-parent",)',
            own_list + '\nARTIFACT_IDS = ("bootui-parent",)',
        ):
            with self.subTest(new=new):
                self.assert_rejected(
                    None,
                    "must bundle check-central-bundle.py's PUBLISHED",
                    assembler=self.mutate_file(BUNDLE_ASSEMBLER, own_list, new),
                )

    def test_parents_stay_unpublished(self):
        for parent in ("bootui-parent", "bootui-quarkus-parent"):
            with self.subTest(parent=parent):
                self.assert_rejected(
                    None,
                    f"non-distribution artifact '{parent}'",
                    root_files=self.mutate_root(
                        "pom.xml", f"<excludeArtifact>{parent}</excludeArtifact>\n", ""
                    ),
                )

    def test_published_poms_are_flattened(self):
        for relative, old, new, message in (
            ("pom.xml", "<flattenMode>ossrh</flattenMode>", "<flattenMode>minimum</flattenMode>", "ossrh"),
            (
                "pom.xml",
                "<flattenMode>ossrh</flattenMode>",
                "<flattenMode>ossrh</flattenMode><outputDirectory>target</outputDirectory>",
                "must stay beside pom.xml",
            ),
            (
                "pom.xml",
                ' child.project.url.inherit.append.path="false"',
                "",
                "keep the root url and scm",
            ),
            ("bootui-agent/pom.xml", "<artifactId>flatten-maven-plugin</artifactId>", "", "must declare flatten"),
        ):
            with self.subTest(relative=relative, new=new):
                self.assert_rejected(None, message, root_files=self.mutate_root(relative, old, new))

    def test_merged_modules_cannot_return(self):
        self.assert_rejected(
            None,
            "bootui-client was merged",
            root_files=self.mutate_root(
                "pom.xml", "<module>bootui-cli</module>", "<module>bootui-cli</module><module>bootui-client</module>"
            ),
        )

    def test_starter_never_brings_a_web_stack(self):
        web = (
            "            <artifactId>spring-boot-starter-web</artifactId>\n"
            "            <scope>provided</scope>\n"
        )
        for old, new, message in (
            (web, "            <artifactId>spring-boot-starter-web</artifactId>\n", "only at provided scope"),
            (
                "            <artifactId>spring-boot-starter-webflux</artifactId>\n            <scope>provided</scope>\n",
                "            <artifactId>spring-boot-starter-webflux</artifactId>\n            <optional>true</optional>\n",
                "only at provided scope",
            ),
            ("<id>no-web-stack</id>", "<id>web-stack</id>", "no-web-stack enforcer rule"),
            (
                "<exclude>org.apache.tomcat.embed:*:*:*:runtime</exclude>\n",
                "",
                "org.apache.tomcat.embed:*:*:*:runtime",
            ),
        ):
            with self.subTest(old=old, new=new):
                self.assert_rejected(None, message, root_files=self.mutate_root(STARTER_POM, old, new))

    def test_release_line_is_required_for_the_next_version(self):
        self.assert_rejected(
            self.mutate(NEXT_VERSION_CALL, NEXT_VERSION_CALL.replace(' "$RELEASE_LINE"', "")),
            "per-major next-version policy",
        )
        self.assert_rejected(
            self.mutate(
                'line-major "$CURRENT_VERSION" .github/release-line)"',
                'line-major "$CURRENT_VERSION" .github/release-line || echo 1)"',
            ),
            "release line of the source branch",
        )

    def test_tagged_contents_must_match_their_release_line(self):
        self.assert_rejected(self.mutate(TAGGED_LINE_CHECK, "if false; then"), "restricted to their own release line")

    def test_tagged_release_line_is_checked_before_publication(self):
        content = WORKFLOW.read_text(encoding="utf-8")
        start = content.index("         # Whatever triggered this run, a tag push included")
        end = content.index("         for npm_dir in . bootui-ui/src/main/frontend", start)
        moved = content[start:end]
        content = content[:start] + content[end:]
        anchor = '          echo "All BootUI ${VERSION} artifacts are available on Maven Central."\n'
        self.assertEqual(content.count(anchor), 1, "fixture drifted: availability anchor")
        content = content.replace(anchor, anchor + moved)
        self.assert_rejected(content, "before Maven Central publication")

    def test_pages_deploys_only_through_the_release_line_gate(self):
        for old, new, message in (
            (GATE_RUN, "run: echo publish=true", "documentation site release-line gate"),
            (PAGES_DEPLOY_CONDITION, "    if: github.event_name != 'pull_request'\n", "deploy job must run only"),
            (
                PAGES_UPLOAD_CONDITION,
                "        if: github.event_name != 'pull_request'\n",
                "uploaded only when the release-line gate allows it",
            ),
            (
                "      publish: ${{ steps.gate.outputs.publish }}\n",
                "      publish: 'true'\n",
                "documentation site gate output",
            ),
        ):
            with self.subTest(old=old):
                self.assert_rejected(None, message, pages=self.mutate_file(PAGES, old, new))

    def test_pages_cannot_deploy_from_a_second_job(self):
        pages = PAGES.read_text(encoding="utf-8") + (
            "\n  sneaky:\n    runs-on: ubuntu-latest\n    steps:\n"
            "      - uses: actions/deploy-pages@v5\n"
        )
        self.assert_rejected(None, "exactly one deploy job", pages=pages)

    def test_docker_images_publish_only_through_the_release_line_gate(self):
        for old, new, message in (
            (DOCKER_CONFIG_CONDITION, "    if: ${{ !inputs.cleanup_only }}\n", "docker-config must run only"),
            ("    needs: gate\n", "", "docker-config must run only"),
            ("    needs: docker-config\n", "", "must follow the gated docker-config job"),
            (GATE_RUN, "run: echo publish=true", "missing the gate job"),
        ):
            with self.subTest(old=old):
                self.assert_rejected(None, message, docker=self.mutate_file(DOCKER, old, new))

    def test_docker_image_jobs_cannot_run_after_a_skipped_gate(self):
        condition = "    if: github.repository == 'jdubois/boot-ui'\n"
        for replacement in (
            "    if: ${{ always() && github.repository == 'jdubois/boot-ui' }}\n",
            "    if: ${{ !cancelled() }}\n",
            "",
        ):
            with self.subTest(replacement=replacement):
                content = DOCKER.read_text(encoding="utf-8")
                self.assertEqual(content.count(condition), 2, "fixture drifted: image job conditions")
                for occurrence in range(2):
                    mutated = content
                    start = -1
                    for _ in range(occurrence + 1):
                        start = mutated.index(condition, start + 1)
                    mutated = mutated[:start] + replacement + mutated[start + len(condition):]
                    self.assert_rejected(None, "never runs after a skipped docker-config", docker=mutated)

    def test_release_comes_only_from_main_or_its_maintenance_branch(self):
        self.assert_rejected(
            self.mutate(
                'if [[ "$SOURCE_BRANCH" != "main" && "$SOURCE_BRANCH" != "${VERSION%%.*}.x" ]]; then',
                "if false; then",
            ),
            "release preparation restricted to main",
        )
        self.assert_rejected(
            self.mutate(
                'git merge-base --is-ancestor "$RELEASE_SHA" "refs/remotes/origin/$candidate"',
                "true",
            ),
            "release restricted to main or its maintenance branch",
        )

    def test_documentation_deploy_job_must_have_succeeded(self):
        self.assert_rejected(
            self.mutate('if [[ "$DEPLOY_CONCLUSION" != "success" ]]; then', "if false; then"),
            "confirmed from the deploy job",
        )

    def test_docker_cannot_publish_from_an_ungated_job(self):
        docker = DOCKER.read_text(encoding="utf-8") + (
            "\n  sneaky:\n    runs-on: ubuntu-latest\n    steps:\n      - run: docker push example\n"
        )
        self.assert_rejected(None, "unexpected job 'sneaky'", docker=docker)

    def test_release_line_cannot_slip_back_to_1_on_2x_contents(self):
        for line, expected in (("2", 0), ("1", 1)):
            with self.subTest(line=line):
                result = self.check(root_files={".github/release-line": f"# test\n{line}\n"})
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                if expected:
                    self.assertIn("contains bootui-agent", result.stderr)

    def test_gate_steps_must_be_whole_lines(self):
        forced = GATE_RUN + "; echo publish=true >> \"$GITHUB_OUTPUT\""
        self.assert_rejected(None, "documentation site release-line gate", pages=self.mutate_file(PAGES, GATE_RUN, forced))
        self.assert_rejected(None, "missing the gate job", docker=self.mutate_file(DOCKER, GATE_RUN, forced))

    def test_docker_config_condition_must_be_the_whole_line(self):
        self.assert_rejected(
            None,
            "docker-config must run only",
            docker=self.mutate_file(
                # Text after the expression turns the condition into a non-empty, always-true string.
                DOCKER, DOCKER_CONFIG_CONDITION, DOCKER_CONFIG_CONDITION.replace(" }}\n", " }} || true\n")
            ),
        )

    def test_pages_deploy_job_keeps_the_name_release_yml_selects(self):
        self.assert_rejected(
            None,
            "keep the name 'Deploy documentation site'",
            pages=self.mutate_file(PAGES, "    name: Deploy documentation site\n", "    name: Deploy site\n"),
        )

    def test_gate_test_seams_cannot_be_set_by_a_workflow(self):
        seam = "    env:\n      BOOTUI_CENTRAL_URL: http://example.invalid\n"
        pages = self.mutate_file(PAGES, "    outputs:\n", seam + "    outputs:\n")
        self.assert_rejected(None, "test seams must never be set", pages=pages)

    def test_publication_uploads_the_assembled_bundle(self):
        self.assert_rejected(
            self.mutate("./mvnw -B -ntp -Prelease clean install \\\n", "./mvnw -B -ntp -Prelease clean deploy \\\n"),
            "not run the Maven deploy phase",
        )
        self.assert_rejected(
            self.mutate("            -am\n\n          # The bundle", "            -am \\\n            deploy\n\n          # The bundle"),
            "not run the Maven deploy phase",
        )
        self.assert_rejected(
            self.mutate("          python3 .github/scripts/assemble_central_bundle.py", "          true"),
            "Central bundle assembled from the installed release",
        )
        self.assert_rejected(
            self.mutate("          python3 .github/scripts/publish_central_bundle.py", "          true"),
            "Central Portal bundle upload",
        )

    def test_assembled_bundle_is_signed_and_checked_before_upload(self):
        assemble = '          python3 .github/scripts/assemble_central_bundle.py "$LOCAL_REPO"'
        check = '          python3 .github/scripts/check-central-bundle.py "$BUNDLE_DIR" "$VERSION"\n'
        publish = "          python3 .github/scripts/publish_central_bundle.py"
        self.assert_rejected(
            self.mutate(assemble, '          python3 .github/scripts/assemble_central_bundle.py --unsigned "$LOCAL_REPO"'),
            "never with --unsigned",
        )
        self.assert_rejected(self.mutate(check, ""), "check of the assembled Central bundle before its upload")
        publish_line = publish + ' target/central-bundle.zip "bootui-$VERSION" "$CENTRAL_AUTO_PUBLISH"\n'
        moved_upload = self.mutate(publish_line, "").replace(check, publish_line + check, 1)
        self.assert_rejected(moved_upload, "after the previous bundle command")
        unzip = '          unzip -q target/central-bundle.zip -d "$BUNDLE_DIR"\n'
        for line, message in (
            (assemble, "Central bundle assembled from the installed release"),
            (unzip.rstrip("\n"), "unpacked Central bundle"),
            (check.rstrip("\n"), "check of the assembled Central bundle before its upload"),
            (publish, "Central Portal bundle upload"),
        ):
            for mutated in (line.replace("          ", "          # ", 1), line.replace("          ", "          true || ", 1)):
                with self.subTest(mutated=mutated):
                    self.assert_rejected(self.mutate(line, mutated), message)
        full_check = check.rstrip("\n")
        self.assert_rejected(self.mutate(full_check + "\n", full_check + " || true\n"), "check of the assembled")
        self.assert_rejected(
            self.mutate(publish_line, publish_line.rstrip("\n") + " || true\n"), "Central Portal bundle upload"
        )
        errexit = (
            "          set -euo pipefail\n\n"
            '          VERSION="$(./mvnw -B -ntp -q -N -DforceStdout help:evaluate -Dexpression=project.version | tail -n 1)"\n'
            "          LOCAL_REPO="
        )
        self.assert_rejected(
            self.mutate(errexit, errexit.replace("set -euo pipefail", "set -uo pipefail")),
            "errexit for the Maven Central publication step",
        )

    def test_bundle_artifacts_match_the_availability_poll_list(self):
        import re
        import sys

        sys.path.insert(0, str(SCRIPT.parent))
        try:
            from assemble_central_bundle import ARTIFACT_IDS
        finally:
            sys.path.pop(0)
        content = WORKFLOW.read_text(encoding="utf-8")
        step = content.split("- name: Wait for Maven Central availability", 1)[1].split("- name: ", 1)[0]
        polled = set(re.findall(r'"(bootui-[a-z-]+)/\$\{VERSION\}/', step))
        self.assertEqual(polled, set(ARTIFACT_IDS))
        self.assertEqual(set(ARTIFACT_IDS), set(PUBLISHED))

    def test_rebase_and_passphrase_arguments_are_refused(self):
        self.assert_rejected(
            self.mutate("          git tag -s", "          git rebase origin/main\n          git tag -s"),
            "must never be rebased",
        )
        self.assert_rejected(
            self.mutate(
                "./mvnw -B -ntp -Prelease clean verify",
                './mvnw -B -ntp -Prelease clean verify -Dgpg.passphrase="$P"',
            ),
            "must not be exposed in process arguments",
        )


@unittest.skipUnless(shutil.which("gpg"), "gpg is not installed")
class BundleSignatureVerificationTests(unittest.TestCase):
    """The publication step uploads a bundle only when every file carries a valid signature by the release key."""

    def setUp(self):
        self.pinned = None
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.release_home = root / "release-gnupg"
        self.other_home = root / "other-gnupg"
        self.bundle = root / "bundle"
        self.bundle.mkdir()
        for home, name in ((self.release_home, "Release"), (self.other_home, "Other")):
            home.mkdir(mode=0o700)
            self.gpg(home, "--gen-key", stdin=(
                "%no-protection\nKey-Type: eddsa\nKey-Curve: ed25519\nKey-Usage: sign\n"
                f"Name-Real: {name}\nName-Email: {name.lower()}@invalid\nExpire-Date: 1d\n%commit\n"
            ))
        exported = self.gpg(self.other_home, "--export", "--armor").stdout
        self.gpg(self.release_home, "--import", stdin=exported)
        self.release_fingerprint = self.fingerprint(self.release_home)
        self.other_fingerprint = self.fingerprint(self.other_home)

    def fingerprint(self, home):
        listing = self.gpg(home, "--with-colons", "--list-secret-keys").stdout
        return next(line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:"))

    def tearDown(self):
        self.directory.cleanup()

    def gpg(self, home, *arguments, stdin=None):
        return subprocess.run(
            ["gpg", "--batch", "--quiet", *arguments],
            input=stdin, env={**os.environ, "GNUPGHOME": str(home)},
            text=True, capture_output=True, check=True,
        )

    def sign(self, home, name):
        path = self.bundle / name
        path.write_text(name)
        self.gpg(home, "--armor", "--detach-sign", "--output", f"{path}.asc", str(path))
        return path

    def verify(self, *arguments, cwd=None):
        return subprocess.run(
            ["bash", str(SIGNATURE_CHECK), *(arguments or ("bundle", str(self.bundle)))],
            env={
                **os.environ,
                "GNUPGHOME": str(self.release_home),
                "RELEASE_KEY_FINGERPRINT": self.release_fingerprint if self.pinned is None else self.pinned,
            },
            text=True, capture_output=True, cwd=cwd,
        )

    def test_signatures_by_the_release_key_pass(self):
        self.sign(self.release_home, "bootui-engine-2.0.0.pom")
        self.sign(self.release_home, "bootui-engine-2.0.0.jar")
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_valid_signature_by_another_key_is_refused(self):
        self.sign(self.release_home, "bootui-engine-2.0.0.jar")
        self.sign(self.other_home, "bootui-engine-2.0.0.pom")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bootui-engine-2.0.0.pom.asc is not a valid signature by the release key", result.stdout)

    def test_a_tampered_file_is_refused(self):
        pom = self.sign(self.release_home, "bootui-engine-2.0.0.pom")
        pom.write_text("tampered")
        self.assertNotEqual(self.verify().returncode, 0)

    def test_a_bundle_without_signatures_is_refused(self):
        (self.bundle / "bootui-engine-2.0.0.pom").write_text("unsigned")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Found no release signature to verify", result.stdout)

    def test_build_mode_checks_the_signatures_in_each_target_directory(self):
        workspace = Path(self.directory.name) / "workspace"
        for module in ("target", "bootui-engine/target"):
            (workspace / module).mkdir(parents=True)
        self.bundle = workspace / "bootui-engine/target"
        self.sign(self.release_home, "bootui-engine-2.0.0.pom")
        self.bundle = workspace / "target"
        self.sign(self.release_home, "bootui-parent-2.0.0.pom")
        result = self.verify("build", cwd=workspace)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Verified 2 release signatures", result.stdout)
        self.bundle = workspace / "bootui-engine/target"
        self.sign(self.other_home, "bootui-engine-2.0.0.jar")
        result = self.verify("build", cwd=workspace)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("is not a valid signature by the release key", result.stdout)

    def test_signatures_by_a_key_other_than_the_pinned_one_are_refused(self):
        # Both keys are in the keyring and both signatures are good: only the pinned fingerprint decides.
        self.sign(self.other_home, "bootui-engine-2.0.0.pom")
        self.pinned = self.other_fingerprint
        self.assertEqual(self.verify().returncode, 0)
        self.sign(self.release_home, "bootui-engine-2.0.0.jar")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bootui-engine-2.0.0.jar.asc is not a valid signature by the release key", result.stdout)

    def test_a_missing_or_malformed_pin_is_refused(self):
        self.sign(self.release_home, "bootui-engine-2.0.0.pom")
        for pinned in ("", self.release_fingerprint.lower(), self.release_fingerprint[:-1]):
            with self.subTest(pinned=pinned):
                self.pinned = pinned
                result = self.verify()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("RELEASE_KEY_FINGERPRINT must be the pinned", result.stdout)

    def run_key_check(self, home, pinned):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        start = workflow.index("      - name: Check the release signing key\n")
        body = workflow[workflow.index("        run: |\n", start) + len("        run: |\n") : workflow.index(
            "      - name: Configure Git author\n", start
        )]
        script = "".join(line[10:] if line.startswith("          ") else line for line in body.splitlines(True))
        return subprocess.run(
            ["bash", "-c", script],
            env={**os.environ, "GNUPGHOME": str(home), "RELEASE_KEY_FINGERPRINT": pinned},
            text=True, capture_output=True,
        )

    def test_the_workflow_key_check_accepts_only_the_pinned_key_alone(self):
        result = self.run_key_check(self.release_home, self.release_fingerprint)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = self.run_key_check(self.release_home, self.other_fingerprint)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("is not the pinned release key", result.stdout)
        secret = self.gpg(self.other_home, "--export-secret-keys", "--armor").stdout
        self.gpg(self.release_home, "--import", stdin=secret)
        result = self.run_key_check(self.release_home, self.release_fingerprint)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Exactly one secret signing key", result.stdout)

    def test_a_missing_bundle_directory_is_a_usage_error(self):
        self.assertEqual(self.verify("bundle", str(self.bundle / "missing")).returncode, 2)


if __name__ == "__main__":
    unittest.main()
