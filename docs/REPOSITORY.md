# Repository and documentation

## Modules

- `bootui-engine`: the shared DTOs, secret masking, and core helpers (package `io.github.jdubois.bootui.core`), and the
  framework-neutral services/advisors and SPI ports built on them. The core package depends only on the JDK and never
  on the engine, the SPI, or an adapter; `CoreBoundaryArchitectureTests` enforces it.
- `bootui-spring-boot-starter`: the Spring MVC/WebFlux adapter and its starter (auto-configuration, endpoints, safety,
  and the bundled UI). It brings no web stack; the application's own web starter decides.
- `bootui-ui`: Vue 3 frontend packaged into `META-INF/resources/bootui/`.
- `bootui-conformance`: non-published HTTP test support and golden panel manifests for all adapters. Its reusable
  runners use JDK `HttpClient`, Jackson 2, JUnit and AssertJ without any host-framework dependency.
- `bootui-coverage`: aggregated coverage report (built by the `coverage` profile only).
- `bootui-cli`: the `bootui` command-line interface, generated from the engine's MCP tool catalog, and the
  dependency-free client it is built on (package `io.github.jdubois.bootui.client`, nothing outside the JDK; picocli is
  optional, and the runnable artifact is the shaded `all` classifier).
- `bootui-agent-bridge`: the JDK-only contract between the engine and the Java agent, loaded by the bootstrap class
  loader. Built and shaded into `bootui-agent`, never published on its own.
- `bootui-agent`: the optional, development-time `-javaagent` jar, published to Maven Central with no dependency for its
  consumers; it stays dormant until a BootUI application claims it.
- `bootui-spring-sample-app`: Spring MVC sample app + Playwright e2e coverage.
- `bootui-spring-webflux-sample-app`: Spring WebFlux sample app.
- `bootui-quarkus-parent`: Quarkus 3 platform BOM and plugin management.
- `bootui-quarkus`: Quarkus 3 runtime adapter.
- `bootui-quarkus-deployment`: Quarkus 3 build-time wiring module.
- `bootui-quarkus-integration-tests`: Quarkus 3 `@QuarkusTest` suites.
- `bootui-quarkus-sample-app`: Quarkus 3 sample app.
- `bootui-quarkus-4-parent`, `bootui-quarkus-4`, `bootui-quarkus-4-deployment`: the independent Java 21 / Quarkus 4
  subtree, using the distinct `io.github.jdubois.bootui.quarkus4` namespace and no Quarkus 3 adapter dependency.
- `bootui-quarkus-4-integration-tests`, `bootui-quarkus-4-sample-app`: non-published Quarkus 4 validation and sample
  modules. The `quarkus-4` reactor profile activates on JDK 21+ without changing the existing Java 17 modules.

## Compatibility version source of truth

Spring Boot and Quarkus compatibility references for the published adapters should follow the root `pom.xml` properties:

- `spring-boot.version`
- `quarkus.platform.version`
- `quarkus4.platform.version` (independent Quarkus 4 baseline)

When these are updated, refresh matching documentation references in the same pull request (`README.md`,
`docs/SETUP.md`, `docs/features/`, `AGENTS.md`, and
`.github/instructions/{spring-adapter,quarkus-adapter}.instructions.md`). The Quarkus 3 modules inherit
`bootui-quarkus-parent`, which imports the Quarkus BOM closer than the root parent imports Spring Boot's BOM. This keeps
the two frameworks' shared transitive dependencies isolated while giving the extension, tests, and sample app one
Quarkus build baseline. The published extension also supports the older Quarkus 3.33 LTS line: its
`requiresQuarkusCore` range and `build.yml`'s `quarkus-lts` job pin that separately. The root build currently uses
Quarkus 3.40.1.2; it is not the name of the older LTS compatibility target.

The Quarkus 4 subtree imports its nearer BOM through `bootui-quarkus-4-parent`. Its baseline is **4.0.1**,
its declared minimum is **core 4.0.0**, and its class-file target is **Java 21**. Existing modules keep Java 17.
Quarkus 3 keeps its LTS policy; Quarkus 4 is initially non-LTS. See [Framework support](FRAMEWORK-SUPPORT.md)
for generation selection, and [Quarkus support](QUARKUS-SUPPORT.md#quarkus-4) for execution evidence.

## Published artifacts

The historical **2.5.0** release illustrates the pre-Quarkus-4 distribution: seven coordinates under
`com.julien-dubois.bootui`: `bootui-engine`, `bootui-ui`,
`bootui-spring-boot-starter`, `bootui-quarkus`, `bootui-quarkus-deployment`, `bootui-cli` (with its `all` classifier),
and `bootui-agent`.

<!-- bootui-quarkus-4:next-release:start -->
> **Next release:** Releases before the first Quarkus 4-carrying BootUI release do not include the new coordinates. Select a published release that contains this adapter; until then, using Quarkus 4 requires an explicit source build.
<!-- bootui-quarkus-4:next-release:end -->

The additive distribution target is **nine** coordinates: those seven plus `bootui-quarkus-4` and
`bootui-quarkus-4-deployment`. No release number is assigned here. Only the new runtime and deployment are published;
`bootui-parent`, both Quarkus parents, samples, fixtures, conformance, coverage, and the standalone agent bridge remain
unpublished. Distribution POMs are flattened by `flatten-maven-plugin`: no `<parent>`, resolved dependency versions,
and inlined project metadata.

The release installs the publication reactor and uploads the allow-listed bundle assembled by
`.github/scripts/assemble_central_bundle.py`. Historical release manifests retain their own artifact set; adding
Quarkus 4 must not make a published seven-coordinate release appear incomplete.
`.github/scripts/stage-release-candidate.sh` builds the same bundle, unsigned, into a local file repository, and `.github/scripts/consumer-smoke-tests.sh <version> <directory>` runs the release's
consumer smoke tests against it. `.github/scripts/published-cli-smoke.sh <spring-sample-jar>` runs the newest published
`bootui-cli`, pinned by SHA-256, against the Spring sample built from the checkout, so a server change that breaks the
CLI users already have fails the build.

Complete candidate/release builds use JDK 25 without raising the Java 17/21 class-file targets. Set
`SMOKE_JAVA17_HOME` for actual legacy consumer execution and `SMOKE_JAVA21_HOME` for Quarkus 4 consumers;
a newer build JDK with `--release` is not a substitute for the baseline runtime checks.

## Documentation website

The public documentation website is <https://www.julien-dubois.com/boot-ui/>. It is built with VuePress from the
markdown files in `docs/`, so repository documentation stays the source of truth for the published site.

```bash
npm install
npm run docs:dev
```

The local development server runs at <http://127.0.0.1:8090>. Before pushing documentation changes, run:

```bash
npm run docs:build
node --test docs/.vuepress/workshop.test.mjs
python3 -B -m unittest discover -s .github/scripts -p 'test_docs_links.py'
```

The workshop check verifies the 180-minute agenda, ordered navigation, and participant source links. The generated-link
regression check reads the HTML for the AI agents, Security checks, Pentesting checks, and all workshop pages,
including their heading IDs and workshop cross-page targets. Neither check contacts external sites.

The [three-hour workshop](workshop/README.md) is published from `docs/workshop/`. Its navbar entry follows Features,
and its ordered sidebar group follows Get started. Planning, facilitator instructions, and reference exercise assets
stay in the repository-only `workshop/` directory; `workshop/PLAN.md` preserves the original curriculum plan.

GitHub Pages is deployed by `.github/workflows/pages.yml` from the `main` branch. In the repository settings, set
**Pages > Build and deployment > Source** to **GitHub Actions**. The workflow builds VuePress with the `/boot-ui/` base
path and publishes the site at <https://www.julien-dubois.com/boot-ui/> only when the release-line gate permits it.
The Release workflow also dispatches it at the immutable tag of a newest-major release. Merging the unreleased v2 line
into `main` builds the site but does not upload or deploy it before that line is published; older-major maintenance
releases do not replace the newest-major site. See [Releasing 2.0](V2-RELEASE.md).
