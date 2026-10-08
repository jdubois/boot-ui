---
applyTo: ".github/workflows/**,.github/scripts/**,.github/dependabot.yml,Dockerfile*,docker-compose*.yml"
---

# CI, workflows, and container images

- Remote GitHub Actions are pinned to a full 40-character commit SHA with an inline release comment. Only the actions in
  the `is_trusted_action` allow-list of `.github/scripts/check-action-references.sh` may use a mutable major-version tag,
  and extending that list is a deliberate decision. Local actions keep relative paths. Check locally with
  `bash .github/scripts/check-action-references.sh`; `build.yml` runs it on every build.
- `.github/scripts/check-release-integrity.sh` pins literal strings and their ordering inside `release.yml` — the
  publication-only reactor, signed-tag verification, atomic push, the CLI uber-jar check, the per-major and
  release-line version policy, and the newest-major documentation gate among them. It also pins the release-line gate
  (`release-line-gate.sh`) in `pages.yml` and ahead of every publishing job in `docker-publish.yml`. Changing any of
  these files without the guard fails the build, so update the workflow and its guard in the same change, and run
  `python3 -B -m unittest discover -s .github/scripts -p 'test_release_*.py'`, which `build.yml` also runs.
- `pages.yml` runs `.github/scripts/check-docs-downloads.sh` against the built site. Every install-script URL referenced
  from `README.md` or `docs/` must exist in `docs/.vuepress/public/` and in the built output, and the installers must
  stay version-free: they resolve the version at run time, and a literal version would not be rewritten by a release.
  `install.sh` must also pass `shellcheck -s sh`.
- `build.yml` is the Java 17 baseline: it is the gate for formatting, the full reactor with coverage, the SBOM, and the
  Spring and Quarkus Playwright suites. To keep it fast, the per-extension Quarkus integration-test modules run in a
  parallel `quarkus-extension-its` job (the main build passes `-Dbootui.skipQuarkusExtensionIts`; the `base` module
  stays in the main build because it feeds the coverage aggregate), and each Spring and Quarkus Playwright suite,
  including the agent-attached ones and the companion legs that attach the OpenTelemetry Java agent (both orders) or
  JaCoCo's agent beside BootUI's, is its own matrix leg. Companion agent jars come from Maven Central through the
  sample's build (`target/agent-companions`), never from a download in a workflow step. The `agent-overhead` job records the BootUI agent's
  overhead benchmark, warns above its 10 % budget, and fails only above 30 %. The `journal-overhead` job records the
  runtime journal's on-versus-off throughput A/B, BootUI on in both arms, for the 2.0 sign-off; it is report-only and
  never fails the build. The agent-attached legs, `agent-overhead`,
  and the JDK lanes run on every push to `main` and `v2`, every pull request into `main`, and the nightly schedule; a
  pull request into `v2` runs them only when it changes a path listed in `.github/scripts/agent-changes.sh` or carries
  the `agent` label (otherwise the agent legs pass without starting anything). Keep that path list in step with new
  agent-backed code. `jdk-compatibility.yml` covers
  Java 21 and 25 with a focused build, the BootUI agent's forked-JVM tests, the Spring sample's agent integration
  tests, and the whole Spring MVC browser suite with the agent attached (`agent-e2e`), plus a non-blocking Java 27 early-warning lane that stays `continue-on-error` until Spring Boot and Quarkus
  document support for it. Keep new checks on the baseline workflow unless they are genuinely JDK-specific.
- Quarkus/Hibernate build-time augmentation is gated to the JDKs the shared Quarkus LTS platform supports. Preserve the
  JDK skip profile and the matrix gating rather than widening a job onto an unsupported JDK.
- `build.yml`'s `quarkus-lts` job runs the extension, built on the pinned platform, through every Quarkus integration
  module on the older supported LTS release, then the extension's own tests compiled against it. Its matrix version,
  `requiresQuarkusCore` in `bootui-quarkus/pom.xml`, and the release `docs/setup/quarkus.md` names change together; a
  step checks them.
- `build.yml`'s `published-cli` job runs `.github/scripts/published-cli-smoke.sh`: the newest published `bootui-cli`
  `all` jar, pinned by SHA-256, against the Spring sample built from the checkout. After a release, bump its version
  and checksum together.
- Keep workflow permissions least-privilege and never echo secrets into command arguments or logs.
- The `Dockerfile*` variants (JVM, AOT, CRaC, native, WebFlux, Quarkus) and their `docker-compose*.yml` files ship the
  sample apps only. They are demonstration surfaces, not published artifacts; keep them building from the same reactor
  modules and do not let them become a second source of truth for versions.
