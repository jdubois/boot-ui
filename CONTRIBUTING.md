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
bootui-spring-boot-starter/          Spring MVC + WebFlux adapter and starter (auto-config, endpoints, safety)
bootui-ui/                           Vue 3 SPA bundled into META-INF/resources/bootui
bootui-conformance/                  Shared HTTP contract suite + golden manifests for all adapters
bootui-coverage/                     Aggregated coverage report (coverage profile only)
bootui-cli/                          The `bootui` CLI, projected from the engine's MCP tool catalog, and its
                                     dependency-free client package
bootui-agent-bridge/                 JDK-only agent/engine contract, shaded into bootui-agent (never published)
bootui-agent/                        The optional `-javaagent` jar, dormant until BootUI claims it
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
`AGENTS.md`, and `.github/instructions/{spring-adapter,quarkus-adapter}.instructions.md`),
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
as `-Dmaven.repo.local`; see "Parallel worktrees" in `AGENTS.md`). CI always
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
anything. A dry run still preserves an inventory artifact. Run it from `main`: the
`docker-hub` environment accepts only `main`, so a preview from another branch is
refused. The workflow also publishes only through its release-line gate
([Releasing 2.0](docs/V2-RELEASE.md)). All publish runs are serialized across
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

Surefire and Failsafe write each test class's standard output and error to
`target/surefire-reports/<class>-output.txt` (`target/failsafe-reports/` for the Spring sample app's integration
tests) instead of the console, so look there for application logs and stack traces printed by tests. Failures and
errors are still reported in the console. CI uploads these files as the `test-output` artifacts.

CI splits that build across parallel jobs: the main job runs the reactor with
`-Dbootui.skipQuarkusExtensionIts`, which leaves out the per-extension Quarkus integration-test modules (every
`bootui-quarkus-integration-tests` child except `base`, plus the JDK-gated Hibernate module), and a separate job tests
those modules. Without the property, a local build includes them.

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

The correlation coverage scenario (`AbstractCorrelationCoverageTest`, [PLAN-v2.md](docs/PLAN-v2.md) §5.1) measures
how much request-thread work Live Activity nests under its request when identical requests are paced, sent
back-to-back, or sent simultaneously. It fails when a floor is missed, when a child is nested under a request that was
not running when it happened, when work on an executor the application did not wrap is nested under a request, or
when a request-thread profile reads as approximate. The Spring MVC and Quarkus runners also run with tracing off, and
the Spring MVC runners send Kafka messages to an in-JVM broker. Each runner writes its report and the raw feed to
`target/correlation-coverage/`:

```bash
./mvnw -B -ntp -pl bootui-spring-sample-app test -Dtest='SpringCorrelationCoverage*'
./mvnw -B -ntp -pl bootui-spring-webflux-sample-app test -Dtest=WebFluxCorrelationCoverageTest
./mvnw -B -ntp -pl bootui-quarkus-integration-tests/otel test -Dtest='BootUiQuarkusCorrelationCoverage*'
```

The engine, the Spring adapter, and the Quarkus adapter run every test under `CorrelationLeakGuard`, registered in
each module's `junit-platform.properties`. It fails a test that leaves a BootUI correlation scope open on its thread,
because that scope would leak into whichever test the thread runs next. Close every scope a test opens, for example
with try-with-resources.

The capture overhead benchmark (`CaptureOverheadBenchmarkTest`) compares the Spring MVC sample app's throughput and
latency with BootUI on and off. Timings depend on the machine, so it is opt-in and never a CI gate. It takes about
three minutes and writes its report to `target/capture-overhead/`:

```bash
./mvnw -B -ntp -pl bootui-spring-sample-app test -Dtest=CaptureOverheadBenchmarkTest -Dbootui.benchmark=true
```

The agent overhead benchmark (`AgentOverheadBenchmarkIT`) measures the BootUI agent's cumulative cost against
[PLAN-v2.md](docs/PLAN-v2.md) §8's budget: the Spring sample's executable jar, BootUI on in both configurations, without
the agent and with every default sensor claimed (`executors`, `inventory`, `code-paths`), on the same route and load as
the capture overhead benchmark. It runs the configurations in pairs whose order alternates and reports each pair's
throughput ratio and their median to `target/agent-overhead/`. The budget is 10 %. `bootui.benchmark.passes` sets the
number of pairs (3 by default), `bootui.benchmark.agent.sensors` claims other sensors than the defaults (for example
`executors` alone, to measure one sensor's share or a new sensor's cost), and
`bootui.benchmark.agent.fail-above-percent` makes it fail when the median paired overhead exceeds that value; without it
the benchmark only reports. `bootui.benchmark.route=io` drives `/api/side-effects/benchmark-io` instead, the same search
plus one outbound connect to a stub server the benchmark runs and one file read per request, so the side-effect sensors
that hook connects and files are measured on a route that exercises them; `bootui.benchmark.route=threads` drives
`/api/thread-activity/benchmark`, the same search plus one thread started and joined and one executor created and shut
down per request, for the `thread-activity` sensor's A/B; `bootui.benchmark.agent.baseline-sensors`
runs the other arm with the agent and those sensors instead of without the agent, an A/B of the sensors it leaves out;
and `bootui.benchmark.report` names the report (`spring-mvc-agent` by default):

```bash
./mvnw -B -ntp -pl bootui-spring-sample-app verify -Dbootui.benchmark=true -Dit.test=AgentOverheadBenchmarkIT \
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

CI runs it in `build.yml`'s `agent-overhead` job with nine pairs on a four-processor runner, then on the I/O route with
nine pairs: every default sensor against no agent (`spring-mvc-agent-io`) on every agent run, and, on pushes, manual
runs, and pull requests labelled `agent` only, every default sensor against the same agent without `network`
(`spring-mvc-network-ab`), the default sensors plus the opt-in `files` against the default sensors, fifteen pairs
(`spring-mvc-files-ab`), and the default sensors plus `files` against no agent (`spring-mvc-agent-io-files`). The load generator
shares the processors with the sample and single pairs vary by more than ten points. That job records the report in its
summary, warns above the 10 % budget, and fails only above 30 %: a clear regression, not noise. The first CI runs measured
a median of about 17 % (pairs from 9 to 22 %), above the budget, so a gate at the budget, or at twice it, would fail
on noise alone; tighten the gate once the overhead is back under budget.

The journal capture budget benchmark (`JournalCaptureBudgetBenchmarkTest`) times the application thread's path into
the runtime journal, with one and eight producers, the stack walk that keeps a statement's application frames, and the
dispatcher's sustained rate, against [PLAN-v2.md](docs/PLAN-v2.md) §8's budgets. It is opt-in too, takes about a
minute, and writes its report to `target/capture-budgets/`:

```bash
./mvnw -B -ntp -pl bootui-engine test -Dtest=JournalCaptureBudgetBenchmarkTest -Dbootui.benchmark=true
```

The Spring sample's agent integration tests start the sample in a JVM of its own with the BootUI agent attached:
`SpringAgentScenarioIT` on the test class path, and `SpringAgentExecutableJarIT` on the repackaged executable jar, run
with `java -javaagent:... -jar`, whose classes and libraries load from `jar:nested:` URLs. The latter asserts the claim
arms with the default sensors, Code Inventory scans `BOOT-INF/classes` and maps `BOOT-INF/lib` to its artifacts, and
Code Paths times the seeded slow route. Both run at `verify` in the Java 17 build and on the Java 21, 25, and 27 lanes of
`jdk-compatibility.yml`:

```bash
./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" -pl bootui-spring-sample-app -am verify -Dit.test='SpringAgent*IT' \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

The Architecture ThreadFactory exemption also has packaged-runtime regressions. The Spring check runs at
`verify`, after the executable jar is repackaged; it scans nested resources and Java 27 bytecode with an intentionally
older host ASM alongside ArchUnit's embedded reader, and verifies that the engine does not bundle another ASM copy.
The Quarkus check launches a standalone fast-jar scanner probe,
without enabling BootUI's production HTTP surface:

```bash
./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" -pl bootui-spring-sample-app -am verify \
  -Dit.test=ThreadFactoryExecutableJarIT
./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" -pl bootui-quarkus-integration-tests/prod-shell-guard test \
  -Dtest=ThreadFactoryFastJarTest
```

Use an absolute isolated-repository path for the packaged Quarkus test: its fork resolves Maven dependencies from a
different working directory, so a relative `.m2` would point at a different repository.

ThreadFactory analysis deliberately uses `com.tngtech.archunit.thirdparty.org.objectweb.asm`, an internal package in
the existing ArchUnit dependency, rather than shipping another reader or depending on a host framework's ASM version.
When upgrading ArchUnit, verify this internal API with `NoDirectThreadInstantiationRuleTests`,
`ThreadFactoryLambdaAnalysisTests`, and `ThreadFactoryReviewTests`, then run both packaged-runtime checks above.
The Spring fixture's ASM 9.8 dependency must remain older than the reader so it continues to exercise isolation.

### Live MySQL validation

MySQL diagnostics require **real Oracle MySQL 8.4** evidence, not the existing MariaDB advisor tests or mocked JDBC
rows. The tested fixture image is `mysql:8.4.6`. Spring uses Connector/J 9.7.0 with HikariCP 7.0.2; Quarkus uses
Connector/J 9.6.0 with Agroal 3.0.1.
MariaDB reached through MySQL Connector/J has no automated live coverage by design: the panel labels it unsupported, and
it was checked manually on MariaDB 11.4 and 11.8. When a change touches collector SQL or session guards, recheck MariaDB
manually against a disposable `mariadb` container started with `--performance-schema=ON`.
Docker (or a Testcontainers-compatible runtime) must be available. Use Java 17 for the delivery baseline:
JDK 28+ silently skips Quarkus augmentation.

From the repository root, install the current reactor dependencies into one worktree-local repository before running
the Spring live suites and Quarkus `mysql-live` profile:

```bash
./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" -Pcoverage clean install
```

Then run the live collectors and opt-in Quarkus HTTP fixture:

```bash
./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" \
  -pl bootui-spring-boot-starter test -Dtest='MySql*LiveTests'

./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" \
  -pl bootui-quarkus-integration-tests/datasource \
  -Pmysql-live test -Dtest=BootUiQuarkusMySqlLiveTest
```

The Spring wildcard deliberately includes the full real-server suite set, not only execution/permissions smoke tests:

| Suites | Evidence |
| --- | --- |
| `MySqlExecutionLiveTests`, `MySqlPermissionsLiveTests` | Transaction/timeout/cleanup safety and restricted-account/active-role behavior. |
| `MySqlCollectorsLiveTests`, `MySqlPerformanceSchemaOffLiveTests` | All eight collectors and independently readable evidence with Performance Schema disabled. |
| `MySqlDigestOverflowLiveTests`, `MySqlReplicationLiveTests` | Real digest-capacity overflow, running/stopped/errored channels, and channel visibility with Performance Schema disabled. |
| `MySqlInstrumentationLiveTests`, `MySqlIdentifierCaseLiveTests` | Global/handler/object collection and timing, denied configuration, real metadata waits, and server-side identifier case normalization. |
| `MySqlHttpLiveTests` | Four MVC/WebFlux × default/custom-mount live HTTP cases. |

Do not infer minimum grants from the status-fallback fixture: MySQL 8.4.6 lets its restricted account read
`performance_schema.global_status` without an explicit table grant. The fallback test deliberately injects a
test-only join to denied `setup_actors` to produce a real permission error before the permitted, fixed-name SHOW.
It proves fallback handling and the network guard, not that ordinary status reads require that extra grant.

The `mysql-live` profile isolates optional MySQL dependencies and sources under `src/mysql-live/java`; the normal
datasource module stays H2-based and Docker-free. Docker is mandatory for this opt-in fixture.
`MySqlHttpLiveTests` supplies the Spring MVC/WebFlux live HTTP coverage. All three stacks need MySQL-available
REST/MCP/CLI contracts. Existing availability-driven H2/no-datasource conformance can pass while never
reading MySQL. Mocked browser responses prove interaction and rendering, not SQL, privileges, or adapter wiring.

Live HTTP fixtures must explicitly call `MySqlReportContract.verify(BootUiHttpProbe, origin, apiPath)` from
`bootui-conformance`, starting with a fresh service in `NOT_READ` and `bootui.mcp.enabled=ON`. The helper covers the
available manifest, REST collection/cache, CLI/MCP cached equality and action publication, and cross-site rejection;
`assertRead(JsonNode)` checks a collected report. Generic REST/MCP/CLI baseline runners do not invoke this positive
helper automatically.

Required live evidence includes all eight collectors with restricted accounts and active roles; Performance Schema,
digests, or timing disabled; denied tables and optional `PROCESS`; named/mixed datasources; no default schema;
blocking/metadata waits; basic running/stopped/errored replication; row-cap edges; timeout/cancellation;
manual-commit refusal; and pooled state restoration or safe discard. A driver/pool cleanup failure is not a reason
to weaken safety assertions.

Inspect Surefire XML totals: required suites must exist, execute tests, and contain no skipped required scenarios.
CI uses `.github/scripts/check-mysql-live-tests.py` for its expected-suite/no-skip gate. Keep its required suite list
aligned with every Spring suite above and `BootUiQuarkusMySqlLiveTest`; execution, permissions, and HTTP smoke tests
alone do not prove the collector, disabled-instrumentation, overflow, or replication requirements.
Record the actual pinned MySQL patch image, Connector/J and pool versions. A green build with all container tests
skipped is not MySQL validation. Complete all four browser suites and `npm install && npm run docs:build` as part of
the integrated change; capture the feature screenshot only after the final runtime/fixture contract agrees.
See the [MySQL feature contract](docs/features/database.md#mysql).

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
# The whole MVC suite with the BootUI agent attached, after ./mvnw install -pl bootui-spring-sample-app -am
(cd bootui-spring-sample-app/e2e && npm run test:agent)
# The agent's evidence specs beside the OpenTelemetry Java agent (both orders) and JaCoCo's agent
(cd bootui-spring-sample-app/e2e && npm run test:agent:opentelemetry-first)
(cd bootui-spring-sample-app/e2e && npm run test:agent:opentelemetry-last)
(cd bootui-spring-sample-app/e2e && npm run test:agent:jacoco)
# The whole WebFlux suite with the BootUI agent attached
(cd bootui-spring-sample-app/e2e && npm run test:webflux:agent)

# Quarkus (requires JDK 17 to 27 and Docker/Podman for Dev Services)
(cd bootui-quarkus-sample-app/e2e && npm ci && npx playwright install chromium)
(cd bootui-quarkus-sample-app/e2e && npm test)
# The whole Quarkus suite with the BootUI agent attached, after ./mvnw install -pl bootui-agent -am
(cd bootui-quarkus-sample-app/e2e && npm run test:agent)
```

Playwright starts the relevant sample app automatically and reuses an existing
server on port `8080` (Spring MVC), `8081` (Spring WebFlux), or `8082`
(Quarkus).

The agent suites start their sample with `-javaagent:` and the jar `./mvnw install` built in `bootui-agent/target`, or
`BOOTUI_AGENT_JAR`, and set the `agentAttached` fixture option, so the Java Agent, Code Inventory, and Code Paths specs
assert the armed claim and the recorded run instead of the not-attached state. The Quarkus agent suite passes the jar to
`quarkus:dev` as `-Djvm.args`. To run one beside another sample, set `SERVER_PORT` with `BOOTUI_AGENT_SAMPLE_PORT`
(Spring MVC), `BOOTUI_WEBFLUX_AGENT_PORT` (WebFlux), or `BOOTUI_SAMPLE_PORT` with `BOOTUI_OSV_FIXTURE_PORT` (Quarkus).
The companion suites attach the OpenTelemetry Java agent or JaCoCo's agent beside BootUI's, from the jars the sample's
build copies from Maven Central to `bootui-spring-sample-app/target/agent-companions` (versions pinned in the root POM);
set `BOOTUI_JACOCO_PORT` to move JaCoCo's TCP server off port `6300`.
CI runs each as its own leg of `build.yml`'s `spring-e2e` and `quarkus-e2e` jobs, on Java 17, and the whole MVC agent
suite again on Java 21 and 25 in `jdk-compatibility.yml`'s `agent-e2e` job. These agent legs, the
`agent-overhead` job, and the Java 21, 25, and 27 lanes run on every push to `main` and `v2`, every pull request into
`main`, and nightly. A pull request into `v2` runs them only when it changes an agent-related path, listed in
`.github/scripts/agent-changes.sh`, or carries the `agent` label; add the label to run them on any other change.

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

The Quarkus sample requires JDK 17 to 27 for augmentation and uses Dev
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
also set `BOOTUI_DEV_APPLICATION_PATH` so the Scorecard's homepage link targets that application root:

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

The release profile attaches source JARs and an empty placeholder Javadoc JAR
(Maven Central requires the file but not its content; BootUI's public surface is
its HTTP, MCP, and CLI contract rather than a Java API), signs artifacts with GPG,
and publishes through the Sonatype Central Publishing plugin using the `central`
server from `~/.m2/settings.xml`. The sample app is not deployed. By default,
Central uploads are published automatically; set `-Dcentral.autoPublish=false`
to stage for manual publishing instead.

To prepare and publish a release, run the **Release** GitHub Actions workflow
from `main`, or from an older major's maintenance branch such as `1.x` (no other
branch is accepted, and a tag is published only when its commit is on one of
them), and enter the target version
without the leading `v`. Versions advance within a major version, so an older major
can still receive patches after a newer major ships. The target must be exactly the
next patch or minor after the latest stable tag of its own major, or `MAJOR.0.0`
directly above the highest existing major, and its major must match the source
branch's project version (or be one above it when opening a new major). For example,
with only `v1.13.1` tagged, the workflow accepts `1.13.2`, `1.14.0`, or `2.0.0`. With
`v1.20.0` and `v2.0.0` tagged, a branch on 1.x accepts `1.20.1` or `1.21.0`, a branch
on 2.x accepts `2.0.1`, `2.1.0`, or `3.0.0`, and `1.19.5` or a second `2.0.0` is
rejected. `.github/scripts/release-version-policy.sh` holds this rule.

Every branch also declares its release line, the major its contents belong to, in
`.github/release-line`, and the workflow releases only versions of that line. `v2`
declares `2` while it still carries a 1.x project version, so once it is merged,
`main` releases 2.0.0 and never another 1.x version, and a `1.x` maintenance branch
releases 1.x patches and never 2.0.0. The documentation site and the Docker Hub
sample images build from a branch rather than a tag, so they follow the same line:
`.github/scripts/release-line-gate.sh` lets `pages.yml` and `docker-publish.yml`
publish only when the branch's line has a release on Maven Central and no newer
major does. [Releasing 2.0](docs/V2-RELEASE.md) is the runbook for the `v2` merge,
its rehearsal, and the `1.x` maintenance branch.

The workflow updates all Maven module versions and refreshes the documentation
dependency examples in the working tree and optionally verifies with the `release`
Maven profile. Before any
Maven Central upload, it commits those exact contents, creates a GPG-signed
annotated version tag, and atomically pushes the source branch plus tag. If the
source branch advanced during preparation, the workflow aborts; it never rebases
released contents. The selected branch must allow
`github-actions[bot]` to push the release commit and tag.

Before dispatching, cut `CHANGELOG.md`'s `[Unreleased]` heading to
`## [VERSION] - YYYY-MM-DD`, complete the notes, and land them on the source branch
as their own commit. Keep the notes short: a one- or two-sentence summary, then
one- or two-line bullets for the main changes, with related small items merged
and details left to the linked docs and pull requests. Use the intended release date and recheck it if publication
is delayed. The exact source SHA must be green on `build.yml`; avoid merging other
changes during the release run. For preparation-only work, stop before dispatch:
leave Maven/npm versions and install coordinates for the workflow, and do not
create a tag or publish locally. The workflow checks release integrity, including
the non-distribution artifact exclusions, before importing signing credentials.

The workflow then resolves the remote tag to its peeled commit SHA, checks out that
SHA in detached state, rechecks the Maven/npm/tag identity, and publishes exactly
that checkout. After auto-publication it polls every published coordinate, runs the
Spring MVC, Spring WebFlux, and Quarkus consumer smoke tests and the Java agent smoke
test (a consumer resolves `bootui-agent` with no dependency, and a JVM started with it
as its `-javaagent` reports it dormant), and dispatches the
Pages workflow at the immutable tag rather than at a branch that may have advanced.
The documentation site follows the newest major only: the workflow redeploys it when
the release's major is at least the highest major among the stable tags on origin,
and skips the redeploy, with a notice, for a patch to an older major.

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
tag-pinned documentation deployment for a newest-major release.

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
python3 -B -m unittest discover -s .github/scripts -p 'test_release_*.py'
```

The second command tests the per-major version, release-line, and newest-major
documentation rules, the release-line gate against a local stand-in for Maven
Central, and the integrity guard itself, which also pins the gates in `pages.yml`
and `docker-publish.yml`.

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
