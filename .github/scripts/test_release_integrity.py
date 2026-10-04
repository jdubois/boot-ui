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
PUBLICATION_MODULES = "bootui-client,bootui-cli,bootui-agent-bridge,bootui-agent \\\n"
AGENT_AVAILABILITY = '"bootui-agent/${VERSION}/bootui-agent-${VERSION}.jar"\n'
AGENT_CONSUMER = '-f "$AGENT_SMOKE_DIR/pom.xml"'
AGENT_ATTACH = 'java -javaagent:"$AGENT_JAR" -version'
AGENT_DORMANT = (
    'AGENT_DORMANT_LINE="[BootUI agent] BootUI agent ${VERSION} attached (javaagent); '
    'dormant until BootUI claims it"'
)
AGENT_DEPENDENCY_FREE = 'if [[ "$AGENT_CLASSPATH" != "bootui-agent-${VERSION}.jar" ]]; then'


class ReleaseIntegrityTests(unittest.TestCase):
    def check(self, content=None, pages=None, docker=None):
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for name, text, source in (
                ("release.yml", content, WORKFLOW),
                ("pages.yml", pages, PAGES),
                ("docker-publish.yml", docker, DOCKER),
            ):
                path = Path(directory) / name
                path.write_text(source.read_text(encoding="utf-8") if text is None else text, encoding="utf-8")
                paths.append(str(path))
            return subprocess.run(
                ["bash", str(SCRIPT), *paths],
                capture_output=True,
                text=True,
                check=False,
            )

    def mutate(self, old, new, count=1):
        content = WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(content.count(old), count, f"fixture drifted: {old!r}")
        return content.replace(old, new)

    def mutate_file(self, source, old, new, count=1):
        content = source.read_text(encoding="utf-8")
        self.assertEqual(content.count(old), count, f"fixture drifted: {old!r}")
        return content.replace(old, new)

    def assert_rejected(self, content, message, pages=None, docker=None):
        result = self.check(content, pages, docker)
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
            "bootui-client,bootui-cli \\\n",
            "bootui-client,bootui-cli,bootui-agent \\\n",
            "bootui-client,bootui-cli,bootui-agent-bridge \\\n",
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
                self.assert_rejected(self.mutate(old, new), message)

    def test_java_agent_smoke_test_resolves_from_central(self):
        content = WORKFLOW.read_text(encoding="utf-8")
        purge = '          rm -rf "$HOME/.m2/repository/com/julien-dubois/bootui"\n'
        self.assertEqual(content.count(purge), 1, "fixture drifted: local artifact purge")
        content = content.replace(purge, "")
        content = content.replace(
            '          echo "Java agent smoke test passed."\n',
            '          echo "Java agent smoke test passed."\n' + purge,
            1,
        )
        self.assert_rejected(content, "must resolve bootui-agent from Maven Central")

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
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github").mkdir()
            (root / "pom.xml").write_text((ROOT / "pom.xml").read_text(encoding="utf-8"), encoding="utf-8")
            (root / "bootui-agent").mkdir()
            (root / "bootui-agent/pom.xml").write_text("<project/>", encoding="utf-8")
            for line, expected in (("2", 0), ("1", 1)):
                with self.subTest(line=line):
                    (root / ".github/release-line").write_text(f"# test\n{line}\n", encoding="utf-8")
                    result = subprocess.run(
                        ["bash", str(SCRIPT), str(WORKFLOW), str(PAGES), str(DOCKER)],
                        capture_output=True,
                        text=True,
                        check=False,
                        env={**os.environ, "RELEASE_INTEGRITY_ROOT": str(root)},
                    )
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
