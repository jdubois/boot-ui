---
applyTo: ".github/workflows/release.yml,.github/workflows/build.yml,.github/scripts/check-release-integrity.sh,pom.xml,**/pom.xml,README.md,docs/SETUP.md,package.json,package-lock.json,**/package.json,**/package-lock.json"
---

# Release and publishing

- Use `.github/workflows/release.yml` for version bumps. It must update Maven versions, `README.md`, `docs/SETUP.md`,
  and every npm package and lock file.
- Keep `quarkus.platform.version` independent from the BootUI project version.
- Published artifacts are the parent POM, core, engine, UI, Spring autoconfigure, both Spring starters (MVC and
  reactive), Quarkus parent, Quarkus runtime, and Quarkus deployment. Sample apps, integration tests, and conformance
  must retain `maven.deploy.skip=true`, remain in the Central plugin's `excludeArtifacts` list, and stay outside the
  publication-only reactor in `release.yml`.
- The source-less published modules (`bootui-ui`, `bootui-spring-boot-starter`, and
  `bootui-spring-boot-starter-reactive`) must attach their empty `javadoc.jar` during `package`, before release-profile
  signing at `verify`.
- Preserve the immutable source-first workflow sequence: prepare and verify the versioned working tree; commit the exact
  release contents; refuse to continue if the source branch advanced; create and verify a GPG-signed annotated tag; then
  atomically push the release commit and tag before any publication. Publish, verify, smoke-test, and deploy documentation
  only from the commit peeled from that signed tag. Never rebase release contents, move or recreate a release tag, or
  publish from an untagged branch state.
- Maven Central requires the matching public signing key to be available by fingerprint. Never expose signing secrets in
  command arguments or logs. If macOS `gpg --send-keys` fails through dirmngr, use the HTTPS upload APIs for
  `keys.openpgp.org` and `keyserver.ubuntu.com`.
- A failed Central deployment may consume the coordinate. Drop the failed deployment before rerunning the existing
  signed tag. If publication succeeded but polling, smoke tests, or documentation failed, resume from that tag with
  `resume_after_publish=true` so Maven Central deployment is not repeated.
