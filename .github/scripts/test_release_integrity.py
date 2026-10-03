import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/check-release-integrity.sh"
WORKFLOW = ROOT / ".github/workflows/release.yml"

NEXT_VERSION_CALL = (
    'bash .github/scripts/release-version-policy.sh next-version "$VERSION" "$CURRENT_VERSION"'
)
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
    def check(self, content):
        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "release.yml"
            workflow.write_text(content, encoding="utf-8")
            return subprocess.run(
                ["bash", str(SCRIPT), str(workflow)],
                capture_output=True,
                text=True,
                check=False,
            )

    def mutate(self, old, new, count=1):
        content = WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(content.count(old), count, f"fixture drifted: {old!r}")
        return content.replace(old, new)

    def assert_rejected(self, content, message):
        result = self.check(content)
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
