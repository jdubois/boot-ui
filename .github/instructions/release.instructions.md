---
applyTo: ".github/workflows/release.yml,.github/workflows/build.yml,.github/scripts/check-release-integrity.sh,.github/scripts/release-version-policy.sh,.github/scripts/check-action-references.sh,pom.xml,**/pom.xml,README.md,docs/SETUP.md,docs/CLI.md,jbang-catalog.json,package.json,package-lock.json,**/package.json,**/package-lock.json"
---

# Release and publishing

- Use `.github/workflows/release.yml` for version bumps. It must update Maven versions, `README.md`, `docs/SETUP.md`,
  every npm package and lock file, and `plugins/bootui/plugin.json`. The portable plugin version is part of the signed
  release contents and must match the release version before publication. Leave `.claude-plugin/plugin.json` inside
  the plugin version-free so Claude Code retains commit-SHA updates.
- Keep `quarkus.platform.version` independent from the BootUI project version.
- Exactly seven coordinates are published: `bootui-engine` (with the core DTO package), `bootui-ui`,
  `bootui-spring-boot-starter` (the auto-configuration and the one Spring starter, for Spring MVC and WebFlux),
  `bootui-quarkus`, `bootui-quarkus-deployment`, `bootui-cli` (the CLI and its dependency-free client package, plus
  the shaded `all` classifier), and the `bootui-agent` `-javaagent` jar. The same list lives in `release.yml`'s
  availability poll,
  `check-central-bundle.py` (whose `PUBLISHED` the bundle assembler and the release tests read),
  `consumer-smoke-tests.sh`, and `check-release-integrity.sh`; change them together.
- Publication never runs Maven's `deploy` phase: under Maven 3.10, `central-publishing-maven-plugin` stages resolver
  bookkeeping that Central rejects. `release.yml` installs the publication-only reactor, signed, then
  `assemble_central_bundle.py` bundles exactly the published coordinates from the local repository,
  `check-central-bundle.py` checks the bundle, `verify-release-signatures.sh bundle` checks every signature in it, and
  `publish_central_bundle.py` uploads it through the Central Portal API. The assembler's list is an allow-list, so
  the parents and `bootui-agent-bridge` never reach the bundle.
- Neither `bootui-parent` nor `bootui-quarkus-parent` is published. Each published module declares
  `flatten-maven-plugin` (configured in the root POM, `ossrh` mode), so its installed and published POM has no
  `<parent>`, resolved dependency versions, scopes, optional flags and exclusions, and the root's Central metadata.
  The flattened POM stays beside `pom.xml`: Quarkus 3.33 breaks in-reactor builds when it lives under `target/`. A new
  published module must declare the plugin too. Both parents stay in the publication reactor and in `excludeArtifacts`.
- Sample apps, integration tests, coverage, and conformance must retain `maven.deploy.skip=true`, remain in the Central
  plugin's `excludeArtifacts` list, and stay outside the publication-only reactor in `release.yml`.
- The consumer smoke tests live in `.github/scripts/consumer-smoke-tests.sh` and run three times:
  `stage-release-candidate.sh` stages the exact Central bundle (assembled the same way, unsigned, never uploaded) as a file repository
  inside the pre-tag verification build and again from the immutable tagged checkout before publication, and the
  script then runs against Maven Central after publication. Every run requires each resolved BootUI file to come from
  the source under test and the consumers to resolve exactly the published coordinates. `build.yml`'s
  `release-candidate` job runs the staged smoke on every change.
- `bootui-agent-bridge` is built but never published: `bootui-agent` shades it in and declares it, like Byte Buddy,
  `<optional>`. It is the one non-distribution jar module inside the publication-only reactor, so it keeps
  `maven.deploy.skip=true` and its `excludeArtifacts` entry, and is never polled on Maven Central. The consumer smoke tests
  resolve `bootui-agent` in a standalone consumer project, fails unless its runtime
  classpath is the agent jar alone, and runs `java -javaagent:<jar> -version` on the Java 17 baseline, requiring exit
  code 0 and the `[BootUI agent] BootUI agent <version> attached (javaagent); dormant until BootUI claims it` line.
- The release also rewrites the `bootui-cli` coordinate in `jbang-catalog.json`, `README.md`, and `docs/CLI.md`, and
  fails when `jbang-catalog.json` still resolves the previous version, so `jbang bootui@jdubois/boot-ui` cannot install
  a stale release. The catalog alias points at the shaded `:all` classifier, so the CLI's shade execution and the alias
  must change together; `release.yml` verifies the `bootui-cli-${VERSION}-all.jar` is published.
- Every published jar module attaches an empty placeholder `javadoc.jar` during `package`, before release-profile
  signing at `verify`; Maven Central requires the file, not generated Javadoc. The release profile's
  `attach-empty-javadocs` execution covers modules with build output, and the source-less `bootui-ui` keeps its own
  execution with the same id.
- Preserve the immutable source-first workflow sequence: prepare and verify the versioned working tree; commit the exact
  release contents; refuse to continue if the source branch advanced; create and verify a GPG-signed annotated tag; then
  atomically push the release commit and tag before any publication. Publish, verify, smoke-test, and deploy documentation
  only from the commit peeled from that signed tag. Never rebase release contents, move or recreate a release tag, or
  publish from an untagged branch state.
- Versions advance per major, so an older major keeps receiving patches after a newer one ships.
  `.github/scripts/release-version-policy.sh` accepts only the next patch or minor after the newest stable tag of the
  release's own major, or `MAJOR.0.0` directly above the highest major, and requires the release's major to match the
  source branch's project version (one above it only when opening a new major). Never reintroduce a "newest tag
  overall" computation in `release.yml`; the integrity guard rejects it.
- Every branch declares its release line, the major its contents belong to, in `.github/release-line` (`v2`
  declares `2` while it still carries a 1.x version). `release.yml` passes it to `next-version`, so a branch releases
  only versions of its own line, and rechecks the tagged contents' line before publication. Releases are prepared only
  from `main` or the version's `N.x` maintenance branch, and a tag publishes only when its commit is on one of them. Never remove the file or
  make it optional; the first commit on a branch preparing a new major declares that major. A branch containing
  `bootui-agent` (2.0 and later) must declare at least `2`; keep `2` when a `main`-to-`v2` sync conflicts on the file.
- `pages.yml` and `docker-publish.yml` build from a branch, not a tag, so `.github/scripts/release-line-gate.sh`
  decides whether they publish: only when one tag of the branch's line has its artifacts on Maven Central and no newer
  major does, failing closed on any unreadable answer. This is what keeps merging `v2` into `main` from publishing the
  2.0 site or images early; `docs/V2-RELEASE.md` is the runbook, and `.github/scripts/rehearse_v2_merge.py` rehearses
  the merge. Workflow files cannot guard refs created before the gate (old tags, old branches); the GitHub environment
  deployment policies do.
- The documentation site follows the newest major only. `release.yml` dispatches `pages.yml` only when the release's
  major is at least the highest major among the stable tags on origin; an older-major patch skips the redeploy.
  `test_release_version_policy.py`, `test_release_line_gate.py`, and `test_release_integrity.py` cover these rules,
  the gate, and the guard.
- Maven Central requires the matching public signing key to be available by fingerprint. `release.yml` pins that key's
  primary fingerprint once, as the job's `RELEASE_KEY_FINGERPRINT`: right after the key is imported, a step refuses any
  other key before anything is signed or tagged, and `verify-release-signatures.sh` requires every signature of the
  verification build (before the tag, unless `skip_build`) and of the Central bundle (before the upload) to be by it.
  Change the value only on a deliberate key rotation, in `release.yml` and `check-release-integrity.sh` together, after
  publishing the new key. Never expose signing secrets in command arguments or logs. If macOS `gpg --send-keys` fails
  through dirmngr, use the HTTPS upload APIs for `keys.openpgp.org` and `keyserver.ubuntu.com`.
- A failed Central deployment may consume the coordinate. Drop the failed deployment before rerunning the existing
  signed tag. If publication succeeded but polling, smoke tests, or documentation failed, resume from that tag with
  `resume_after_publish=true` so Maven Central deployment is not repeated.
