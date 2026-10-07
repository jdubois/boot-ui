import hashlib
import os
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
STARTER_POM = "bootui-spring-boot-starter/pom.xml"
PUBLISHED = (
    "bootui-core",
    "bootui-engine",
    "bootui-ui",
    "bootui-spring-boot-starter",
    "bootui-quarkus",
    "bootui-quarkus-deployment",
    "bootui-cli",
    "bootui-agent",
)

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


class ReleaseIntegrityTests(unittest.TestCase):
    def check(self, content=None, pages=None, docker=None, smoke=None, stage=None, bundle=None, root_files=None):
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
        jar = "bootui-core-1.0.0.jar"
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
        self.assert_rejected(
            None,
            '-DcentralBaseUrl="$STUB_URL"',
            stage=self.mutate_file(STAGE, '  -DcentralBaseUrl="$STUB_URL" \\\n', ""),
        )
        self.assert_rejected(
            None,
            "-Dcentral.autoPublish=false",
            stage=self.mutate_file(STAGE, "  -Dcentral.autoPublish=false \\\n", ""),
        )

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


if __name__ == "__main__":
    unittest.main()
