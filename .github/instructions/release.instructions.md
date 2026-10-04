---
applyTo: ".github/workflows/release.yml,.github/workflows/build.yml,.github/scripts/check-release-integrity.sh,.github/scripts/release-version-policy.sh,.github/scripts/check-action-references.sh,pom.xml,**/pom.xml,README.md,docs/SETUP.md,docs/CLI.md,jbang-catalog.json,package.json,package-lock.json,**/package.json,**/package-lock.json"
---

# Release and publishing

- Use `.github/workflows/release.yml` for version bumps. It must update Maven versions, `README.md`, `docs/SETUP.md`,
  and every npm package and lock file.
- Keep `quarkus.platform.version` independent from the BootUI project version.
- Published artifacts are the parent POM, core, engine, UI, Spring autoconfigure, both Spring starters (MVC and
  reactive), Quarkus parent, Quarkus runtime, Quarkus deployment, `bootui-client`, and `bootui-cli`. Sample apps,
  integration tests, coverage, and conformance must retain `maven.deploy.skip=true`, remain in the Central plugin's
  `excludeArtifacts` list, and stay outside the publication-only reactor in `release.yml`.
- The release also rewrites the `bootui-cli` coordinate in `jbang-catalog.json`, `README.md`, and `docs/CLI.md`, and
  fails when `jbang-catalog.json` still resolves the previous version, so `jbang bootui@jdubois/boot-ui` cannot install
  a stale release. The catalog alias points at the shaded `:all` classifier, so the CLI's shade execution and the alias
  must change together; `release.yml` verifies the `bootui-cli-${VERSION}-all.jar` is published.
- Every published jar module attaches an empty placeholder `javadoc.jar` during `package`, before release-profile
  signing at `verify`; Maven Central requires the file, not generated Javadoc. The release profile's
  `attach-empty-javadocs` execution covers modules with build output, and the source-less modules (`bootui-ui`,
  `bootui-spring-boot-starter`, and `bootui-spring-boot-starter-reactive`) keep their own execution with the same id.
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
- Maven Central requires the matching public signing key to be available by fingerprint. Never expose signing secrets in
  command arguments or logs. If macOS `gpg --send-keys` fails through dirmngr, use the HTTPS upload APIs for
  `keys.openpgp.org` and `keyserver.ubuntu.com`.
- A failed Central deployment may consume the coordinate. Drop the failed deployment before rerunning the existing
  signed tag. If publication succeeded but polling, smoke tests, or documentation failed, resume from that tag with
  `resume_after_publish=true` so Maven Central deployment is not repeated.
