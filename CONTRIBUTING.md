# Contributing to BootUI

Thanks for your interest in improving BootUI! This document explains how to
get a working development environment and submit changes.

## Code of conduct

This project adheres to the [Contributor Covenant](CODE_OF_CONDUCT.md). By
participating you are expected to uphold this code.

## Prerequisites

- **Java 17** (or newer). The reference toolchain is OpenJDK 17.
- **Maven Wrapper**. Use the committed `./mvnw` script; no system Maven
  installation is required.
- **Node.js 24+** and npm 11+ are downloaded automatically by the
  `frontend-maven-plugin` when you run the build. You do not need to install
  Node manually.
- **Framework targets**. The Spring MVC and WebFlux adapters target Spring Boot
  4.0+; the Quarkus adapter targets the LTS version declared by
  `quarkus.platform.version` in the root `pom.xml`. BootUI does not support
  Spring Boot 3.x.

## Project layout

```
bootui-core/                         Shared DTOs, secret masking, and core helpers
bootui-engine/                       Framework-neutral services/advisors and SPI ports
bootui-spring-autoconfigure/         Spring MVC + WebFlux adapter (auto-config, endpoints, safety)
bootui-spring-boot-starter/          Spring MVC starter
bootui-spring-boot-starter-reactive/ Spring WebFlux starter
bootui-ui/                           Vue 3 SPA bundled into META-INF/resources/bootui
bootui-conformance/                  Shared HTTP contract suite + golden manifests for all adapters
bootui-coverage/                     Aggregated coverage report (coverage profile only)
bootui-client/                       Dependency-free client for the command-line endpoint
bootui-cli/                          The `bootui` CLI, projected from the engine's MCP tool catalog
bootui-spring-sample-app/            Reference Spring MVC app + Playwright e2e
bootui-spring-webflux-sample-app/    Reference Spring WebFlux app
bootui-quarkus-parent/               Shared Quarkus LTS BOM and plugin management
bootui-quarkus/                      Quarkus runtime adapter
bootui-quarkus-deployment/           Quarkus deployment/build-time wiring
bootui-quarkus-integration-tests/    Quarkus @QuarkusTest suites
bootui-quarkus-sample-app/           Reference Quarkus app
docs/                                Public documentation source (VuePress)
```

## Keeping framework-version references in sync

Use the root Maven properties as the source of truth for the published adapters and public compatibility documentation:

```bash
./mvnw -q -DforceStdout help:evaluate -Dexpression=spring-boot.version
./mvnw -q -DforceStdout help:evaluate -Dexpression=quarkus.platform.version
```

When updating compatibility text in docs (README, `docs/SETUP.md`, `docs/features/`,
`.github/copilot-instructions.md`, and `.github/instructions/{spring-adapter,quarkus-adapter}.instructions.md`),
reference those properties and refresh any explicit version strings in the same PR.
All Quarkus modules, including the non-published sample app, inherit the Quarkus platform through
`bootui-quarkus-parent`; keep its LangChain4j BOM compatible with that shared LTS line.

## Build

```bash
./mvnw clean install
```

This downloads Node + npm, runs `npm install`, runs the Vue unit tests with Vitest,
builds the Vue UI with Vite, and packages every module. A full clean build takes
about a minute on a warm cache.

For a faster local build, add `-T 1C` (one reactor thread per CPU core) to build modules in parallel:

```bash
./mvnw -T 1C clean install
```

This is safe: every module that triggers Quarkus build-time augmentation declares an explicit
`provided`-scope dependency on `bootui-quarkus-deployment` so the reactor always orders it first, and every
`@QuarkusTest`-bearing module pins its own Surefire `forkCount` so parallel forks never race Quarkus's shared
test-bootstrap cache (see the `maven-surefire-plugin` comments in the root `pom.xml` and in
`bootui-quarkus-integration-tests/base/pom.xml`). `-T` is a personal preference, not a project default, so it
is not baked into `.mvn/maven.config` — add it to your own shell alias or a personal, git-ignored
`.mvn/maven.config` if you want it every time (that file is also used for personal, per-worktree overrides such
as `-Dmaven.repo.local`; see "Parallel worktrees" in `.github/copilot-instructions.md`). CI always
builds with `-T 1C`.

For an adapter-focused Java iteration, select the corresponding sample or
integration-test module and let Maven build its dependencies:

```bash
# Spring MVC
./mvnw -B -ntp -pl bootui-spring-sample-app -am install

# Spring WebFlux
./mvnw -B -ntp -pl bootui-spring-webflux-sample-app -am install

# Quarkus extension plus Docker-free integration suites
./mvnw -B -ntp -pl bootui-quarkus-integration-tests -am install
```

### Docker builds behind a corporate npm registry

Standard Docker builds remain the default and need no npm configuration:

```bash
docker build -t bootui-sample-app .
```

Only if your network requires a corporate npm registry and you already have a
working `~/.npmrc`, pass that file as an optional BuildKit secret:

```bash
docker build --secret id=npmrc,src="$HOME/.npmrc" -t bootui-sample-app .
```

The same optional secret works with `-f Dockerfile-aot`, `-f Dockerfile-crac`,
`-f Dockerfile-native`, `-f Dockerfile-webflux`, and `-f Dockerfile-quarkus`.
For example:

```bash
docker build --secret id=npmrc,src="$HOME/.npmrc" -f Dockerfile-native -t bootui-sample-app-native .
```

BuildKit mounts the file in the build user's home only for the Maven step:
`/root/.npmrc` for the Spring images and `/home/bootui/.npmrc` for Quarkus.
The secret file is not copied into image layers. `.dockerignore` intentionally
excludes all `.npmrc` files, including project-level files, from the build context;
use the secret rather than copying registry credentials into the project.
Without `--secret`, npm keeps its default registry configuration. There is no
new requirement for normal developers or CI, and no secret is needed at runtime.

### Docker Hub image retention

The **Publish Docker images** workflow retains seven days of sample images, measured
from their last push, not their last pull. `latest` and every manifest it references
are always preserved, even if builds have failed for more than a week. Each
smoke-tested platform image also receives a unique `build-<run>-<attempt>-<platform>`
tag. The combined image index receives a `build-<run>-<attempt>-index` tag before
release tags move. These internal retention tags make images and indexes from
failed or interrupted publishes discoverable, including repeated builds of the
same commit or day.

Cleanup runs after the build jobs regardless of their outcome. It inventories all
tags before deleting any, resolves image indexes recursively, and preserves every
manifest reachable from retained tags. It then deletes expired tags, unreferenced
indexes, and their unreferenced platform manifests, in that order. Layers are never
deleted directly: Docker Hub reclaims unreferenced layers asynchronously and keeps
layers shared with retained images. Registry permission failures and unconfirmed
deletions fail the job rather than silently skipping cleanup.
An HTTP 403 for an individual manifest is reported immediately without stopping
independent deletions in the other images and repositories. The job still fails
at the end with the confirmed/blocked counts; blocked digests stay in the saved
inventory. Other API failures still stop execution.

The `docker-retention-inventory-*` Actions artifact is a write-ahead journal, uploaded
**before** any tags are removed. The next run restores it, including from a failed
cleanup, so a partially deleted image remains discoverable. Do not manually remove
these artifacts. Each run attempt creates a new journal with 90-day retention
without overwriting the previous one. On first use, the workflow recovers legacy
digests from available Docker publish logs; expired or
missing logs limit that recovery and are reported explicitly. Content that predates
available history may still need manual inventory through Docker Hub Image Management.
History recovery includes the source wrapper indexes that older attested builds
used before flattening their platform and attestation manifests into release
indexes. Journals created before this recovery fix trigger a one-time history
refresh, merged with the existing retry records, so those wrappers are deleted
before their children rather than leaving the children blocked by references.

Use **Run workflow** with `cleanup_only=true` to run retention without rebuilding.
Set `prune_dry_run=true` to preview **both tag and manifest deletion** without deleting
anything. A dry run still preserves an inventory artifact. Destructive cleanup runs
only on `main`; other branches may preview. All publish runs are serialized across
branches to prevent concurrent registry changes. `DOCKERHUB_TOKEN` must have
**Read, Write, Delete** permissions; `DOCKERHUB_UNTAGGED_PRUNE` is no longer used.

The scripts use Python's standard library. Run their offline regression suite with:

```bash
python3 -B -m unittest discover -s .github/scripts -p 'test_docker_retention*.py'
```

The build also validates GitHub Actions expressions with pinned `actionlint`.
Plain YAML parsing does not catch contexts used outside their allowed scope:

```bash
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 \
  -shellcheck= -pyflakes= \
  .github/workflows/docker-publish.yml .github/workflows/build.yml
```

### Software Bill of Materials (SBOM)

Generate a CycloneDX SBOM covering every dependency across the whole reactor after an install:

```bash
./mvnw clean install
./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
```

This writes `target/bootui-sbom.json` (CycloneDX 1.6, JSON). The `cyclonedx-maven-plugin` version and default
configuration live in the root `pom.xml`'s `pluginManagement`, but the goal is deliberately not bound to any
lifecycle phase there: bound, it would attach the BOM as an extra artifact during `install`/`deploy` and get
swept into Maven Central publication by the `release` profile, counting against Central's per-file monthly
quota for every published module (see the `central-publishing-maven-plugin` checksums comment in `pom.xml`).
CI (`.github/workflows/build.yml`) runs the same standalone command after the main build and uploads the result
as the `bootui-sbom` workflow artifact on every push and pull request.

## Testing

Use the CI-equivalent build before opening or updating a pull request:

```bash
./mvnw -B -ntp clean install
```

For frontend-only unit test iteration:

```bash
cd bootui-ui/src/main/frontend
npm install
npm test
```

### Coverage

Run the same instrumented Maven/Vitest coverage build as CI with:

```bash
./mvnw -B -ntp -Pcoverage clean verify
```

The `coverage` profile leaves the normal and `release` profiles unchanged. It writes per-module JaCoCo HTML/XML/CSV
reports under `*/target/site/jacoco/`, the cross-module report under
`bootui-coverage/target/site/jacoco-aggregate/`, and Vitest HTML/Cobertura/LCOV/JSON reports under
`bootui-ui/src/main/frontend/coverage/`. For a frontend-only run:

```bash
(cd bootui-ui/src/main/frontend && npm run test:coverage)
```

Coverage gates deliberately protect critical contracts rather than a repository-wide percentage:

| Scope | Measured baseline | Initial gate |
| --- | ---: | ---: |
| `SecretMasker` | 75.0% lines | 70% lines |
| `BootUiPathNormalizer` | 100% lines / 100% branches | 100% / 100% |
| Engine safety package | 92.2% lines / 80.3% branches | 90% / 75% |
| Spring exposure/MCP policy | 100% lines / 75.0% branches | 80% / 60% |
| Quarkus exposure/MCP policy | 86.4% lines / 100% branches | 80% / 60% |
| Frontend path utility | 84.5% statements / 82.4% branches / 100% functions / 89.6% lines | 80% / 75% / 95% / 85% |
| Shared frontend state primitives | 97.2% statements / 88.2% branches / 100% functions / 100% lines | 95% / 85% / 95% / 95% |
| Shared accessible UI components | 94.5% statements / 93.2% branches / 92.9% functions / 96.8% lines | 90% / 85% / 85% / 90% |

The margins absorb harmless compiler/provider shifts while still rejecting meaningful regressions. DTO serialization is
reported in the aggregate and protected behaviorally by the shared Spring/Quarkus conformance suites; generated record
methods are not assigned a vanity percentage gate. CI publishes the human-readable reports, JaCoCo XML, Cobertura XML,
LCOV, and the aggregate job summary for comparison across runs.

The shared HTTP contract has one adapter-specific runner per platform. After
installing the reactor dependencies, run the affected conformance class:

```bash
# Spring MVC
./mvnw -B -ntp -pl bootui-spring-sample-app test -Dtest=SpringApiConformanceTest

# Spring WebFlux
./mvnw -B -ntp -pl bootui-spring-webflux-sample-app test -Dtest=WebFluxApiConformanceTest

# Quarkus
./mvnw -B -ntp -pl bootui-quarkus-integration-tests/base test -Dtest=BootUiQuarkusApiConformanceTest
```

### Panel metadata workflow

Backend panel metadata (`id`, manifest title/order, action capability, and guarded
API prefixes) is centrally tracked in
`bootui-engine/src/main/java/io/github/jdubois/bootui/engine/panel/BootUiPanels.java`.
The Vue route list remains the independent source of truth for sidebar titles,
groups, and navigation order.

When adding or renaming a panel, update `BootUiPanels`, `routes.js`, the conformance
manifests, and the directly related docs. When moving a sidebar entry, update
`routes.js` and the docs without reordering the backend manifest. CI validates
that the backend catalog, UI routes, conformance manifests, and
`docs/features/` stay aligned.

Run the browser end-to-end suite for every affected adapter when you change the
UI, browser-facing API responses, or sample-app behavior:

```bash
# Spring MVC and WebFlux share one Playwright project
(cd bootui-spring-sample-app/e2e && npm ci && npx playwright install chromium)
(cd bootui-spring-sample-app/e2e && npm test)
(cd bootui-spring-sample-app/e2e && npm run test:webflux)

# Quarkus (requires JDK 17, 21, or 25 and Docker/Podman for Dev Services)
(cd bootui-quarkus-sample-app/e2e && npm ci && npx playwright install chromium)
(cd bootui-quarkus-sample-app/e2e && npm test)
```

Playwright starts the relevant sample app automatically and reuses an existing
server on port `8080` (Spring MVC), `8081` (Spring WebFlux), or `8082`
(Quarkus).

## Formatting

Use Spotless for Java and repository whitespace checks:

```bash
./mvnw spotless:apply
./mvnw spotless:check
```

Use Prettier for the Vue app and Playwright tests:

```bash
(cd bootui-ui/src/main/frontend && npm run format)
(cd bootui-spring-sample-app/e2e && npm run format)
(cd bootui-quarkus-sample-app/e2e && npm run format)
```

Before pushing, run the matching checks:

```bash
./mvnw -B -ntp spotless:check
(cd bootui-ui/src/main/frontend && npm run format:check)
(cd bootui-spring-sample-app/e2e && npm run format:check)
(cd bootui-quarkus-sample-app/e2e && npm run format:check)
```

## GitHub Actions dependencies

Remote GitHub Actions default to a full 40-character commit SHA with an inline release comment:

```yaml
uses: dorny/test-reporter@a43b3a5f7366b97d083190328d2c652e1a8b6aa2 # v3.0.0
```

The following explicitly trusted actions may instead use a mutable major-version tag:

- GitHub: `actions/checkout`, `actions/configure-pages`, `actions/deploy-pages`, `actions/download-artifact`,
  `actions/setup-java`, `actions/setup-node`, `actions/upload-artifact`, `actions/upload-pages-artifact`, and
  `github/codeql-action`
- Docker: `docker/build-push-action`, `docker/login-action`, `docker/metadata-action`, and
  `docker/setup-buildx-action`

New remote actions remain SHA-pinned unless this allowlist is deliberately extended. Local actions continue to use
relative paths. Dependabot checks action references weekly: major tags receive compatible updates automatically,
Dependabot proposes new major tags, and SHA-pinned actions receive pull requests for newer release SHAs.

Trusted actions may also remain SHA-pinned, as in generated GitHub Agentic Workflow lock files. Every SHA pin requires
a release comment beginning with a version; explanatory text such as `# v9.0.0 (source v9)` is allowed. Generated
`.lock.yml` workflows are checked by the same policy and should be regenerated with `gh aw compile`, not edited manually.

Run the policy check and its regression suite locally with:

```bash
bash .github/scripts/check-action-references.sh
python3 -B -m unittest discover -s .github/scripts -p 'test_action_references.py'
```

## Run the sample app

Build the selected adapter first (see the adapter-focused commands above), then
run its sample without `-am`:

| Adapter | Command | Console |
| ------- | ------- | ------- |
| Spring MVC | `./mvnw -pl bootui-spring-sample-app spring-boot:run` | <http://localhost:8080/bootui> |
| Spring WebFlux | `./mvnw -pl bootui-spring-webflux-sample-app spring-boot:run` | <http://localhost:8081/bootui> |
| Quarkus | `./mvnw -pl bootui-quarkus-sample-app quarkus:dev` | <http://localhost:8082/bootui> |

The Quarkus sample requires JDK 17, 21, or 25 for augmentation and uses Dev
Services, so Docker or Podman must be available.

## Front-end development

The Vue source lives in `bootui-ui/src/main/frontend`. For a fast inner loop:

```bash
cd bootui-ui/src/main/frontend
npm install
npm run dev
```

This starts Vite with hot-module reload on a separate port and proxies
`/bootui/api/*` to a locally running sample app. Open the **Vite** URL
(<http://localhost:5173/bootui/>) to see your edits live — the Maven-served
console at <http://localhost:8080/bootui> serves the pre-built bundle baked into
the JAR and does **not** hot-reload source changes. If the sample app runs on a
non-default port, point the proxy at it with `BOOTUI_API_PROXY_TARGET`. Use
`npm run test:watch` for Vitest watch mode while iterating. When you are done,
run `./mvnw install -pl bootui-ui` once to re-bundle the assets into the JAR.

To develop against custom BootUI mounts, set `BOOTUI_DEV_PATH` for the Vite shell base and, when the API path is not
`<BOOTUI_DEV_PATH>/api`, set `BOOTUI_DEV_API_PATH` independently. When the host application has a non-root context,
also set `BOOTUI_DEV_APPLICATION_PATH` so the Overview link targets that application root:

```bash
BOOTUI_DEV_PATH=/host/dev-console \
  BOOTUI_DEV_API_PATH=/host/internal/bootui-api \
  BOOTUI_DEV_APPLICATION_PATH=/host \
  BOOTUI_API_PROXY_TARGET=http://localhost:8083 \
  npm run dev
```

Then open <http://localhost:5173/host/dev-console/>. These values affect only Vite development; the packaged build uses
relative asset URLs and receives the actual UI/API paths from the backend at runtime.

### Bootstrap Icons subsetting

To keep the packaged JAR small, the build does not ship the full Bootstrap Icons
pack. A Vite plugin (`scripts/generate-icon-subset.mjs`) scans the front-end
sources for the `bi-*` classes that are actually used and emits a subset font plus
a trimmed stylesheet into `src/generated/` (git-ignored), which `main.js` imports.
This runs automatically on build, dev-server start, and Vitest, so using a new
icon needs no extra steps — just reference its `bi-*` class. If you add an icon
while `npm run dev` is already running, restart the dev server so the subset is
regenerated.

## Publishing

Maven Central publication uses the `release` Maven profile:

```bash
./mvnw -B -ntp -Prelease clean deploy
```

The release profile attaches source and Javadoc JARs, signs artifacts with GPG,
and publishes through the Sonatype Central Publishing plugin using the `central`
server from `~/.m2/settings.xml`. The sample app is not deployed. By default,
Central uploads are published automatically; set `-Dcentral.autoPublish=false`
to stage for manual publishing instead.

To prepare and publish a release, run the **Release** GitHub Actions workflow
from the branch you want to release, usually `main`, and enter the target version
without the leading `v`. The target must be exactly the next patch, minor, or major
version after the latest stable release tag. For example, after `v1.13.1`, the
workflow accepts only `1.13.2`, `1.14.0`, or `2.0.0`. The workflow updates all
Maven module versions and refreshes the documentation dependency examples in the
working tree and optionally verifies with the `release` Maven profile. Before any
Maven Central upload, it commits those exact contents, creates a GPG-signed
annotated version tag, and atomically pushes the source branch plus tag. If the
source branch advanced during preparation, the workflow aborts; it never rebases
released contents. The selected branch must allow
`github-actions[bot]` to push the release commit and tag.

The workflow then resolves the remote tag to its peeled commit SHA, checks out that
SHA in detached state, rechecks the Maven/npm/tag identity, and publishes exactly
that checkout. After auto-publication it polls every published coordinate, runs the
Spring MVC, Spring WebFlux, and Quarkus consumer smoke tests, and dispatches the
Pages workflow at the immutable tag rather than at a branch that may have advanced.

The same workflow (`.github/workflows/release.yml`) also runs on manually pushed
`v*` tags, or manually with an empty version when the selected ref is already
tagged with the Maven project version. Publication entry points require a signed
annotated tag whose version matches the Maven project. Configure these repository
or environment secrets before running it:

| Secret                   | Value                                            |
| ------------------------ | ------------------------------------------------ |
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal user token username      |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal user token password      |
| `GPG_PRIVATE_KEY`        | ASCII-armored private key used to sign artifacts |
| `MAVEN_GPG_PASSPHRASE`   | Passphrase for the GPG private key               |

Manual runs publish automatically by default; disable `auto_publish` when you
want to review and publish the deployment in the Central Portal. The release commit
and signed tag are already pushed before that upload. After publishing the staged
deployment in the Portal, rerun **Release** at the existing tag with an empty
version, `auto_publish` enabled, and `resume_after_publish` enabled. This skips a
duplicate Maven deploy and performs availability polling, consumer smoke tests, and
tag-pinned documentation deployment.

Never move or recreate a release tag after a failure. If deployment failed before
Central accepted an upload, rerun the workflow at the existing tag. If Central
created a failed deployment, drop that deployment in the Portal before rerunning
the same tagged SHA; Central does not accept a duplicate GAV while the failed
deployment remains. If upload or publication succeeded but a later poll, smoke
test, or documentation step failed, rerun at the existing tag with
`resume_after_publish` enabled so the workflow does not upload the coordinates
again.

CI pins these ordering and immutability rules. Run the focused policy check locally
with:

```bash
bash .github/scripts/check-release-integrity.sh
```

## Submitting a change

1. Open or claim an issue describing the change before you write code.
2. Create a topic branch off `main`. Branch names should start with your
   GitHub username (e.g. `jdubois/improve-config-ui`).
3. Keep PRs small and focused. Update `docs/` whenever public behaviour
   changes.
4. Run `./mvnw -B -ntp clean install` before pushing.
5. Run the affected Spring MVC, Spring WebFlux, and/or Quarkus conformance and
   Playwright commands when you change the UI, browser-facing API responses, or
   sample-app behavior.
6. Use the pull request template — it links to the verifications we expect.

### Keeping a PR reviewable

"Small and focused" is about how much *judgement* a reviewer has to apply, not
about the raw diff size. A few habits keep that cost down:

- **One decision per PR.** A change that alters a scoring or availability policy,
  reshapes a JSON contract, and restyles the UI asks the reviewer to hold three
  unrelated arguments at once. Land the policy change first, then build the UI on
  top of the contract it settled.
- **Separate mechanical churn from behaviour.** `spotless:apply` reflows, import
  reordering, and large renames should be their own commit, ideally their own PR.
  Mixed into a behaviour change they hide the handful of lines that actually
  matter, and `git log -p` stops being useful later.
- **Land the wide, repetitive part on its own.** When a change forces the same
  edit across many rules, panels, or adapters, split it: one commit introduces the
  mechanism with tests, a second applies it everywhere. The second commit is then
  safe to skim.
- **Prefer a guard test over a convention.** If a change makes it possible to
  break something silently — a rule that forgets to record evidence, a panel that
  forgets to publish availability — add the test that fails instead of writing the
  rule down and hoping. See `HibernateRuleCatalogueTests` for the pattern.
- **Say what you rejected.** A short note in the PR description about the
  alternatives you discarded saves the reviewer from re-deriving them.

## Reporting bugs and security issues

- **Bugs**: open an issue using the _Bug report_ template.
- **Security vulnerabilities**: do **not** open a public issue. Use GitHub's
  private security advisory flow: see [SECURITY.md](SECURITY.md).

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
