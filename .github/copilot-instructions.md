# BootUI — Copilot instructions

BootUI is a local-only developer console (Vue 3 SPA + REST API at `/bootui`) that drops into a host application. It
targets **two frameworks from one codebase**: **Spring Boot 4** and **Quarkus**. Both adapters serve the **same Vue UI**,
the **same `/bootui/api/**` JSON contract**, and reuse the **same framework-neutral engine** — so a panel should look and
behave identically on either runtime. The authoritative scope/behavior lives in `docs/SPECIFICATION.md`, `docs/PLAN.md`,
`docs/FEATURES.md`, and `docs/QUARKUS-SUPPORT.md` — read those before changing public behavior or visible panel behavior.

Maturity, stated honestly so you don't assume parity: the **Spring Boot adapter is complete** (all panels). The **Quarkus
adapter is being built out** — panels light up as the shared engine grows. Treat "make it work on both" as the default
design constraint for new shared code, but check `QuarkusPanelAvailability` / `docs/QUARKUS-SUPPORT.md` for what is actually
live on Quarkus today rather than assuming a panel already works there.

## Dual-framework architecture (read first)

The core idea: **push behavior down into a framework-neutral engine, keep each framework as a thin adapter.**

```text
bootui-core    DTO records (core/dto/*), SecretMasker, BootUiInfo, ValueExposure — zero framework deps
bootui-spi     Small neutral interfaces (ExposurePolicy, MemoryRuntimeConfig, BasePackageProvider, …) over
               core DTOs / jakarta.* / Micrometer only
bootui-engine  Framework-neutral services + advisor engines (depends on core + spi); no framework/DI annotations
bootui-conformance  Test-support: one abstract HTTP contract suite + golden panel fixtures both adapters run
bootui-ui      Vue 3 + Vite SPA, built once into META-INF/resources/bootui/

# Spring Boot adapter
bootui-autoconfigure        Thin @RestController endpoints + SPI impls + safety filters + EnvironmentPostProcessors
bootui-spring-boot-starter  Drop-in dependency (autoconfigure + ui + spring-boot-starter-web + actuator)
bootui-sample-app           Reference Spring Boot 4 app for demos + Playwright e2e

# Quarkus adapter
bootui-quarkus              Runtime: JAX-RS resources + SPI impls + CDI @Produces + Vert.x safety filter
bootui-quarkus-deployment   Build-time wiring (build steps, bean registration, prod gating)
bootui-quarkus-integration-tests   @QuarkusTest conformance + smoke (Docker-free)
bootui-quarkus-sample-app   Reference Quarkus app (wired via a JDK-gated reactor profile — see below)
```

Load-bearing rules:

- **Dependency direction is one-way: `core ← spi ← engine`, and each adapter depends on the trio.** Shared modules
  (`core`, `spi`, `engine`, `conformance`, `ui`) must **never** depend on a framework. This is enforced at build time by
  `EngineBoundaryArchitectureTests` (ArchUnit), which bans `org.springframework..`, `jakarta.servlet..`, `jakarta.ws.rs..`,
  `io.quarkus..`, `io.vertx..`, `org.jboss..`, **and both JSON libraries** (`tools.jackson..`, `com.fasterxml.jackson..`)
  from `bootui-engine`. A leak fails the build.
- **The engine is JSON-free on purpose.** Spring Boot 4 ships Jackson 3 (`tools.jackson.*`); Quarkus ships Jackson 2
  (`com.fasterxml.jackson.*`) — incompatible artifact *and* package. So JSON parsing/serialization lives in the adapter,
  which feeds the engine **already-parsed neutral records** and serializes the engine's DTO records back out. The DTO
  records carry no Jackson annotations, so they serialize correctly under both.
- **Engine classes carry no framework or DI annotations and are wired by an explicit factory per adapter.** Spring uses
  `@Bean` methods in `BootUiEngineConfiguration` (`@Lazy`, `@ConditionalOnMissingBean`) that commonly construct the engine
  service inline from its SPI impl (e.g. `new MemoryReportProvider(new SpringMemoryRuntimeConfig(environment))`); Quarkus
  uses `@Produces` in `BootUiEngineProducer`. When an adapter actually injects an SPI impl (notably the Quarkus producer),
  inject the **concrete** impl (e.g. `QuarkusExposurePolicy`), not the SPI interface, so adding another impl later can't
  make CDI resolution ambiguous.
- **The optional-dependency classloading trap (R2).** `<optional>true</optional>` stops transitive propagation but does
  **not** make classloading safe. An engine class must not statically import an optional type (Flyway/Liquibase/Hikari/
  Hibernate/servlet/security). Keep the *presence decision* in the adapter (`@ConditionalOnClass` / a CDI build step),
  construct the engine service only when the dependency is present, and inject the already-resolved handle into its
  constructor. Where one optional API is unavoidable, concentrate it in a single engine reader pinned by an ArchUnit rule
  (precedent: `jakarta.persistence` only in `JpaMetamodelReader`).
- **Two repeating extraction templates** (the choice recurs for every panel moved into the engine):
  - **Live-policy SPI interface** — for config the operator can change at runtime (exposure/masking, virtual-threads,
    health-probe presence). The engine takes an SPI interface and re-reads it per request; each adapter implements it over
    its own config (`Environment` for Spring, MicroProfile `Config` for Quarkus).
  - **Static settings record** — for config read once and never rebound (no UI/override path). The engine takes an
    immutable record; the adapter factory maps its properties onto it inline.
  - Decision rule: does the property have a live-override / UI-toggle path? Yes → policy interface. No → settings record.
- **How a panel is served on each side, given a shared engine service:**
  - Spring: a thin `@RestController` under `...autoconfigure.web` injects the engine bean and maps query params; the
    controller is added to `@Import(...)` on `BootUiAutoConfiguration`.
  - Quarkus: a thin JAX-RS `@Path("/bootui/api/xxx")` resource under `...quarkus.web` injects the engine bean; the SPI
    impl bean is pinned unremovable in `BootUiQuarkusProcessor`, and the panel id is added to `QuarkusPanelAvailability`.

## Toolchain

- Java 17 (compiler `release` 17, `-parameters`). Maven Wrapper (`./mvnw`), Maven 3.9.16; do not require a system Maven.
- Spring Boot 4.1.x (`spring-boot.version` in root `pom.xml`; currently 4.1.0).
- Quarkus 3.20.x LTS (`quarkus.platform.version` in the Quarkus modules; currently 3.20.0).
- Published Maven coordinates use `com.julien-dubois.bootui:*`; Java packages remain `io.github.jdubois.bootui.*`.
- Node.js / npm for the packaged Vue app are downloaded automatically by the `frontend-maven-plugin` (`node.version` /
  `npm.version` in root `pom.xml`); do not add a manual Node install step for the Maven build.
- **JDK caveat for the Quarkus sample app:** Hibernate ORM's build-time ByteBuddy enhancement (via the Quarkus 3.20
  platform) cannot read class files newer than the JDKs that platform supports (17 and 21). So `bootui-quarkus-sample-app`
  is wired into the reactor only on JDK 17/21 via the root-pom `quarkus-sample-app` profile (`<jdk>[17,22)</jdk>`). CI and
  releases run Java 17 and build it; a local `./mvnw install` on a newer JDK (e.g. 25/26) stays green by skipping it. The
  extension's own modules (`bootui-quarkus*`, integration tests) have no Hibernate dependency and build on every JDK.

## Build, run, test

```bash
# CI-equivalent multi-module build (downloads Node, tests/builds Vue UI, runs both adapters' suites, packages all JARs).
# On Java 17 this also builds + augments the Quarkus sample app via the JDK-gated profile.
./mvnw -B -ntp clean install

# Maven Central release path for non-SNAPSHOT versions.
./mvnw -B -ntp -Prelease clean deploy
```

### Spring Boot adapter

```bash
# Backend + UI iteration loop. NOTE: -am pulls in bootui-ui (the starter depends on it), so the Vue app IS rebuilt.
./mvnw -pl bootui-core,bootui-autoconfigure,bootui-spring-boot-starter,bootui-sample-app -am install
# To genuinely skip the Vue rebuild, drop -am (works once bootui-ui has been installed at least once).
./mvnw -pl bootui-core,bootui-autoconfigure,bootui-spring-boot-starter,bootui-sample-app install

# Fastest sample app launch (smoke-test path: http://localhost:8080/bootui).
./mvnw -o -ntp -pl bootui-sample-app -Dmaven.test.skip=true spring-boot:run -Dspring-boot.run.profiles=dev
# If offline mode misses a dependency, drop -o once.

# Single test class / single test method.
./mvnw -pl bootui-core test -Dtest=SecretMaskerTests
./mvnw -pl bootui-core test -Dtest=SecretMaskerTests#detectsCommonSecretKeys
```

Do **not** add `-am` to `spring-boot:run`: Maven applies the goal to every selected reactor project, including the
parent/core/UI modules, and those modules have no main class. Use `-am` for build/test reactor work instead
(`./mvnw -pl bootui-sample-app -am test`).

### Quarkus adapter

```bash
# Build + test the Quarkus extension and its Docker-free @QuarkusTest conformance suite (works on any JDK).
./mvnw -B -ntp -pl bootui-quarkus,bootui-quarkus-deployment,bootui-quarkus-integration-tests -am install

# Build/run the Quarkus sample app — requires JDK 17/21 (see the JDK caveat above). Point JAVA_HOME at a 17/21 JDK.
JAVA_HOME=/path/to/jdk-17 ./mvnw -pl bootui-quarkus-sample-app -am install
JAVA_HOME=/path/to/jdk-17 ./mvnw -pl bootui-quarkus-sample-app -am quarkus:dev   # console at http://localhost:8080/bootui/
```

The Quarkus sample app uses Dev Services (Postgres) and LangChain4j/Ollama, so a full `quarkus:dev` run needs Docker
and/or Ollama; the reactor `install` only *augments* it (no runtime, no Docker). The `@QuarkusTest` integration suite is
deliberately Docker-free.

### Front-end

```bash
# Inner loop (Vite dev server with HMR; proxies /bootui/api/* to a running sample app — Spring or Quarkus).
(cd bootui-ui/src/main/frontend && npm install && npm run dev)
# Unit tests (Vitest + Vue Test Utils + jsdom; also run by Maven's test phase unless -DskipTests is set).
(cd bootui-ui/src/main/frontend && npm test)
# After changing UI code that needs to be re-bundled into the JAR:
./mvnw -pl bootui-ui install

# Browser end-to-end suite (required for UI, browser-facing API, or sample-app changes).
(cd bootui-sample-app/e2e && npm ci && npx playwright install --with-deps chromium && npm test)
```

### Isolating parallel worktrees

When working from **multiple git worktrees in parallel**, avoid a shared `install`: every worktree builds the same
`com.julien-dubois.bootui:*` version, so installing overwrites the others in the shared `~/.m2/repository`. Install into an
isolated local repository and run from that same repo (per-invocation, or via a per-worktree git-ignored
`.mvn/maven.config`):

```bash
./mvnw -Dmaven.repo.local=.m2 -ntp -pl bootui-sample-app -am -DskipTests install
./mvnw -Dmaven.repo.local=.m2 -o -ntp -pl bootui-sample-app -Dmaven.test.skip=true spring-boot:run -Dspring-boot.run.profiles=dev
```

### Live UI iteration (hot reload)

Editing Vue source does **not** hot-reload the Maven-served console: `spring-boot:run` / `quarkus:dev` (and the app's
`/bootui` URL) serve the **pre-built** bundle copied into the classpath at build time, so UI edits only appear after
`./mvnw -pl bootui-ui install` plus a restart. For a fast loop with hot-module reload (HMR) — including live/visual
iteration in the in-app browser (e.g. when using the Impeccable skill) — run two processes and open the **Vite** URL:

1. **Backend (REST API).** Run a sample app (Spring `spring-boot:run` or Quarkus `quarkus:dev`), or the `Dev server`
   script in `.github/github-app.yml`. Serves `http://localhost:8080/bootui` (or `$COPILOT_PORT` in the Copilot app).
2. **Frontend (HMR).** Run `(cd bootui-ui/src/main/frontend && npm install && npm run dev)`. Serves
   `http://localhost:5173/bootui/` and proxies `/bootui/api/*` to the backend.

Open **`http://localhost:5173/bootui/`** (the Vite server) to see edits live. Point the proxy at a non-default backend
port with `BOOTUI_API_PROXY_TARGET` (the Copilot app's `Vite UI dev server` script sets this to `$COPILOT_PORT`). State-
changing panel actions work through the proxy because both adapters compare the `Origin`/`Host` **host only** (not port),
so the browser's `:5173` origin is accepted against the `:8080`/`$COPILOT_PORT` host.

CI (`.github/workflows/build.yml`) runs `./mvnw -B -ntp clean install` on Java 17 — which builds both adapters, runs the
shared conformance suite against both, runs the frontend Vitest suite through Maven, builds + augments the Quarkus sample
app (JDK-gated profile), installs Playwright Chromium, and runs `bootui-sample-app/e2e` with `npm test`. CodeQL covers
Java/Kotlin and JavaScript/TypeScript when code scanning is enabled. The release workflow publishes `v*` tags to Maven
Central through the `release` profile and the Sonatype Central Publishing plugin.

## Formatting before commits and PRs

- Before committing, pushing, creating/updating a PR, or marking a PR ready, run the formatters for the areas touched by
  the change; after broad AI-generated edits, run all of them:

```bash
./mvnw -B -ntp spotless:apply
(cd bootui-ui/src/main/frontend && npm run format)
(cd bootui-sample-app/e2e && npm run format)
```

- Do not commit or create/update a PR until the same formatting checks CI uses pass:

```bash
./mvnw -B -ntp spotless:check
(cd bootui-ui/src/main/frontend && npm run format:check)
(cd bootui-sample-app/e2e && npm run format:check)
```

- **`spotless:check` runs over the whole reactor.** On Java 17 that includes `bootui-quarkus-sample-app` (the JDK-gated
  module); a newly added Quarkus-side file that isn't formatted will fail CI even if a JDK-26 local run skipped it.

## Activation & safety model (critical)

Both adapters **fail closed at activation**: when activation is ambiguous, BootUI stays **disabled and silent**. The
request-time access policy is **shared**: both adapters are thin bindings over the framework-neutral
`io.github.jdubois.bootui.engine.safety.LocalhostGuard`, which is the single source of truth for the local-only policy
(loopback-source trust on the raw TCP peer, `Host` allow-list / DNS-rebinding defense, and cross-site-write / CSRF
defense), the check order, and the **exact canonical 403 messages**. The Spring `LocalhostOnlyFilter` and the Quarkus
`BootUiQuarkusSafetyFilter` only translate their native request/config into the guard's neutral
`LocalhostGuardRequest`/`LocalhostGuardConfig` and render the `LocalhostGuardDecision`, so the two adapters reject
identically (same JSON `{"error":"…"}` body). The pure policy is pinned by `LocalhostGuardTests` in `bootui-engine`;
each binding adds thin per-adapter tests that it feeds the guard correctly. One gap remains: Quarkus has no equivalent of
Spring's `PanelAccessFilter` yet, so per-panel enable / read-only gating is Spring-only for now.

### Spring Boot

- Two autoconfigurations are registered (not component-scanned) in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: `BootUiAutoConfiguration` and
  `BootUiSpringSecurityAutoConfiguration` (the latter permits BootUI routes and wires SPA-friendly CSRF when Spring
  Security is on the classpath). `BootUiAutoConfiguration` is gated by `BootUiActivationCondition`,
  `@ConditionalOnWebApplication(SERVLET)`, and `@ConditionalOnClass(DispatcherServlet)`. New controllers must be added to
  `@Import(...)` on `BootUiAutoConfiguration`.
- Activation (`BootUiActivationCondition.resolve`): `bootui.enabled=ON|OFF` wins, otherwise an active profile in
  `bootui.enabled-profiles` (`dev`, `local` by default) or `spring-boot-devtools` on the classpath turns BootUI on.
  `bootui.disabled-profiles` (`prod`, `production`) force-off unless `bootui.enabled=ON`.
- `LocalhostOnlyFilter` (order `Integer.MIN_VALUE`, on `/bootui/*` and `/bootui/api/*`) is a **thin binding over the engine
  `LocalhostGuard`**. It fails closed for non-loopback callers; beyond the loopback source check the guard validates the
  `Host` header against the built-in loopback names plus `bootui.allowed-hosts` (DNS-rebinding defense) and rejects
  cross-site state-changing requests via `Origin`/`Sec-Fetch-Site` (CSRF defense that works without Spring Security). The
  only opt-out is `bootui.allow-non-localhost=true`. The filter owns only the Spring-side plumbing (the trusted-proxies
  parse cache, the resolve-once container-gateway snapshot, per-reason logging, the JSON 403 render); the policy lives in
  the engine.
- `PanelAccessFilter` runs after it and enforces per-panel `bootui.panels.*` settings via the `BootUiPanels` registry:
  it blocks requests to disabled panels and rejects state-changing methods on action-capable panels marked read-only.
  Register new action endpoints in `BootUiPanels` so these toggles apply.
- Four `EnvironmentPostProcessor`s are registered in `META-INF/spring.factories` and run while BootUI is active:
  `BootUiOverridesEnvironmentPostProcessor` (runtime config overrides), `BootUiActuatorDefaultsEnvironmentPostProcessor`
  (local Actuator/tracing defaults; host `management.*` settings still win), `BootUiStartupEnvironmentPostProcessor`
  (startup-timeline buffer), and `BootUiWebApplicationTypeEnvironmentPostProcessor` (forces servlet web type when active
  and otherwise non-web). Add new post-processors to that same file.
- **All four EPPs are Spring-bootstrap-only and stay in the Spring adapter** (R8 classification): none encodes a semantic
  default the shared DTO contract needs both adapters to apply identically, so there is no engine-level equivalent and no
  Quarkus EPP. Each compensates for a Spring-bootstrap concept Quarkus does not have, or its target panel is reached
  differently on Quarkus: web-type (`spring.main.web-application-type`) has no Vert.x analog; the Actuator/tracing defaults
  are Spring Actuator + Micrometer keys with no Quarkus counterpart (the Health panel reads SmallRye directly, and Traces/AI
  capture is the in-process OTel exporter); the startup buffer is `BufferingApplicationStartup` (the Startup Timeline panel
  is a separate, still-unported Quarkus capture concern); and the overrides property source is Spring precedence (the
  Quarkus equivalent would be a SmallRye `ConfigSource`, deferred until the Configuration panel is ported). Note the
  overrides EPP is the one that does **not** gate on `BootUiActivationCondition` — it always loads the overrides file. On
  Quarkus, local Traces/AI full-fidelity capture relies on Quarkus OpenTelemetry's own default sampler
  (`parentbased_always_on`) rather than a BootUI-contributed `quarkus.otel.traces.sampler` default; this is at parity with
  Spring's parent-based `probability=1.0` for the typical host-unconfigured dev case, and the host's own sampler still wins.

### Quarkus

- Activation is decided at **build time by launch mode** in `BootUiQuarkusProcessor.registerConsole`: in
  `LaunchMode.NORMAL` (production) the data-bearing `/bootui/api/**` endpoints, CDI beans, and Vert.x safety filter are
  **not wired at all** (prod-dark, fail-closed); `DEV` and `TEST` wire them (so `quarkus:dev` and `@QuarkusTest` exercise
  the console). Caveat: the static Vue shell under `META-INF/resources/bootui/` is still served by Quarkus' static-resource
  handler in every launch mode (suppressing even the empty shell in production is a known follow-up), but with the API dark
  it has no data to show. The build step indexes the extension runtime jar so Arc/RESTEasy discover the beans and `@Path`
  resources, and pins the SPI-backed beans unremovable. New SPI impl beans must be added to the `addBeanClasses(...)` list
  there; new `@Path` resources are auto-discovered and need no processor change.
- `BootUiQuarkusSafetyFilter` is a **global Vert.x HTTP route filter** (registered via the `@Observes Filters` event), not
  a JAX-RS `@PreMatching` filter — a Vert.x filter runs for every request before routing, including unmatched paths (an
  unmatched `POST /bootui/api/overview` is 404'd by the Vert.x router before the RESTEasy chain, which a pre-matching
  filter would miss). It is a **thin binding over the same engine `LocalhostGuard`** as the Spring filter, so it enforces
  the full policy at parity over the whole `/bootui` surface: loopback-source trust on the raw Vert.x TCP peer
  (`remoteAddress()`, never a forwarded header), the `Host` allow-list (sourced from the `Host` header, falling back to
  the Vert.x `authority()` for HTTP/2 `:authority`), and cross-site-write rejection — rendering the **same** canonical
  `{"error":"…"}` JSON 403. The binding owns the Quarkus plumbing: it reads `bootui.allow-non-localhost` /
  `allowed-hosts` / `trusted-proxies` / `trust-container-gateway` live from MicroProfile `Config` (fail-closed), and
  resolves the container-gateway snapshot **eagerly at startup, off the Vert.x event loop** (the detector does blocking
  `/proc`/DNS work; lazy first-request resolution would trip the `BlockedThreadChecker`), and only when gateway trust is
  not `OFF`. When you change the policy, change the engine guard (not a binding) and keep the **exact 403 reason strings**
  identical across adapters (the SPA / e2e may key on them). Per-panel read-only gating is still Spring-only (no Quarkus
  `PanelAccessFilter` yet).
- Config is read live from MicroProfile `Config` in the SPI impls (e.g. `QuarkusExposurePolicy`) and the safety filter,
  fail-closed on missing/invalid values.

## API & DTO conventions

- All endpoints live under `/bootui/api/**`. The browser UI is at `/bootui/` (Vite `base: '/bootui/'`, hash router).
- **DTOs are immutable Java `record`s in `io.github.jdubois.bootui.core.dto`**, one record per file, no Jackson
  annotations — they must serialize identically under Spring Boot's Jackson 3 and Quarkus' Jackson 2. The UI binds to this
  stable shape; never return raw Actuator descriptors or framework objects. Map them to DTOs (see `ConfigController.toDto`).
- **Never let the engine touch JSON or a framework type.** If a panel needs to parse an external JSON response (OSV,
  GraalVM reachability metadata, GitHub), parse it in the adapter and hand the engine a neutral record; the engine owns the
  policy/shaping, the adapter owns transport + parsing.
- Spring consumes Actuator endpoints in-process via `ObjectProvider<XxxEndpoint>` (see `BeansController`,
  `LoggersController`); always handle `getIfAvailable() == null` by returning an empty DTO — Actuator may be partially
  disabled. The Quarkus equivalents read the corresponding Quarkus/SmallRye/Micrometer sources.
- Any code path that surfaces a property name/value to the browser **must** route it through `SecretMasker` (behind the
  `ExposurePolicy` SPI: `BootUiExposure` on Spring, `QuarkusExposurePolicy` on Quarkus) before serialization. See
  `ConfigController.toDto` and `ConfigOverrideService.displayValue` for the pattern.
- Do not perform dependency vulnerability lookups on page load. The Vulnerabilities panel lists local dependency inventory
  first and calls OSV.dev only after the explicit `/bootui/api/vulnerabilities/scan` action; honor
  `bootui.vulnerabilities.osv-enabled=false`.
- Keep external/network behavior explicit and bounded: scans are user-initiated, timeouts and max package/advisory limits
  come from config, and failures return a clear error status while preserving local data.

## Conformance harness (both adapters share one contract)

- `bootui-conformance` holds the **abstract HTTP contract suite** (`AbstractBootUiApiConformanceTest`) + a small probe
  (`BootUiHttpProbe`) + golden manifests (`expected-panels-spring.json`, `expected-panels-quarkus.json`). The Spring side
  runs it via `SpringApiConformanceTest` (boots the sample app); the Quarkus side via `BootUiQuarkusApiConformanceTest`
  (`@QuarkusTest`).
- The suite is **availability-driven**: every panel that is in `DATA_PANEL_ROOT_GETS` **and** reported `available:true`
  is auto-asserted to answer `GET /bootui/api/<id>` with 200 + JSON. So lighting up a panel on a new adapter automatically
  adds black-box coverage — no fixture edit needed (the manifest lists all panels; availability is computed per adapter).
- Run conformance at the **end of every refactor/extraction step**, on both adapters, before committing.

## Configuration overrides flow (Spring)

Runtime config edits use two cooperating components:

1. `BootUiOverridesEnvironmentPostProcessor` (wired via `META-INF/spring.factories`) runs at `HIGHEST_PRECEDENCE + 10`
   and `addFirst`s a `BootUiOverridesPropertySource` populated from `.bootui/application-bootui.properties` (path
   configurable via `bootui.overrides-file`).
2. `ConfigOverrideService` mutates that same property source at runtime and persists via `ConfigOverridesFileStore`.

Because already-bound `@ConfigurationProperties` beans won't auto-rebind, every mutation returns a `ConfigOverrideResult`
whose `message` warns about restart caveats. Preserve that contract when adding new override paths. (The Quarkus override
equivalent reads MicroProfile `Config`; design it to the same restart-caveat contract when it lands.)

## Current panel surface

`bootui-ui/src/main/frontend/src/routes.js` is the source of truth for routes and sidebar grouping — `App.vue` renders the
sidebar from `router.options.routes`, and both adapters render the same sidebar because they serve the same Vue bundle.
`docs/FEATURES.md` mirrors the route list. The separate `BootUiPanels` registry (in `bootui-engine`) is the **backend**
catalogue of panel ids used by the API access filters and per-adapter availability — not the sidebar/title source.
**Availability is per-adapter**: Spring's `PanelsController` (over Actuator/bean presence) and Quarkus'
`QuarkusPanelAvailability`; `docs/QUARKUS-SUPPORT.md` is the prose source for Quarkus-live status (`docs/FEATURES.md` does
not yet carry a per-platform column). The `/bootui/api/panels` manifest (`PanelsReport`) also carries a top-level
`platform` discriminator (`spring-boot` | `quarkus`, set by each adapter's manifest builder) so the shared Vue UI can
render framework-correct setup/empty-state copy (e.g. Traces and AI Usage point Quarkus users at in-process
`quarkus-opentelemetry` capture instead of the embedded OTLP receiver). The UI reads it via `inject('panels')` and
**defaults to `spring-boot` when absent** — keep that field populated in both adapters and in the conformance fixtures
(`expected-panels-spring.json` / `expected-panels-quarkus.json`). The stance is to harden **all visible panels**, not
hide newer ones. Keep API, UI,
`docs/FEATURES.md`, and e2e coverage aligned across these nine groups (current order):

- **Overview**: Overview, Live Activity, GitHub
- **Advisors**: Architecture, REST API, Spring, Hibernate, Memory, Security, Pentesting, Vulnerabilities
- **Runtime**: Health, HTTP Sessions, Metrics, Live Memory, JVM Tuning, Heap Dump, Threads, Startup Timeline, GraalVM, CRaC
- **Configuration**: Configuration, Profile Diff, Loggers, Beans, Conditions, Mappings
- **Database**: Database Connection Pools, SQL Trace, Spring Data, Flyway, Liquibase
- **Security**: Spring Security, Security Logs
- **Services**: Scheduled Tasks, Spring Cache, AI Usage
- **Diagnostics**: Traces, Log Tail, Exceptions, HTTP Exchanges, HTTP Probe
- **Developer Tools**: MCP Server, DevTools, Dev Services, Copilot, Claude Code

- Some panels are framework-specific. The **plan** (not yet implemented — tracked in `docs/QUARKUS-SUPPORT.md`) is to
  replace the **Spring advisor** with a **Quarkus advisor** and **Spring Cache** with a **Quarkus Cache** panel on the
  Quarkus adapter; a few Spring-only panels (e.g. DevTools, Conditions) have no Quarkus equivalent and stay unavailable
  there. The **GraalVM** and **CRaC** advisors are deliberately **not** ported to Quarkus (they are not "not yet" — they
  have no meaningful Quarkus equivalent): Quarkus compiles native images itself and generates its own reachability
  metadata at build time, and CRaC targets the Spring startup model, so both report a panel-specific "not applicable on
  Quarkus" reason (`QuarkusPanelAvailability.NOT_APPLICABLE`) rather than the generic "not yet" message. Use the shared
  registry + per-adapter availability rather than forking the route list.
- As of today the Quarkus adapter reports these panels **available**: Architecture, Hibernate, Pentesting,
  Vulnerabilities, Threads, Heap Dump, Live Memory, JVM Tuning, Metrics, Loggers, Health, HTTP Probe, Traces, AI Usage,
  and GitHub. Architecture is the first **advisor** lit up on
  Quarkus: the shared engine `ArchitectureScanner` runs the curated ArchUnit ruleset against the application's own
  classes, bounded to base packages discovered at **build time** from the Jandex application index by a
  `registerBasePackages` build step (the runtime `AutoConfigurationPackages` lookup the Spring adapter uses has no Quarkus
  analogue, and runtime package scanning isn't reliable under the Quarkus classloader). The build step reduces the indexed
  classes to an antichain of root packages (`BasePackageRoots`, dropping default/single-segment packages that would scan
  the whole classpath) and emits them as a `RunTimeConfigurationDefaultBuildItem` that `QuarkusBasePackageProvider` reads
  back. `POST /bootui/api/architecture/scan` triggers the scan and dismissals persist to `.bootui/boot-ui.yml` via
  `DismissedRulesResource`, exactly as on Spring. Discovery is single-module today (sibling modules in a multi-module build
  aren't auto-discovered; override with the `bootui.internal.base-packages` config key — a comma-separated package list —
  if needed). Loggers is served over the JBoss LogManager that Quarkus uses at
  runtime (the Spring adapter uses Actuator's loggers endpoint) and was the first Quarkus panel with a state-changing
  action: `POST /bootui/api/loggers/{name}` sets a level, guarded by the shared engine `LocalhostGuard` write floor and
  the engine's refusal to mutate BootUI's own loggers (per-panel read-only gating is still Spring-only). HTTP Probe is
  also action-capable (`POST /bootui/api/http-probe`): it issues a local-only request to the application's *own* loopback
  port — resolved per-probe by `QuarkusServerPortSupplier`, which selects `quarkus.http.test-port` vs `quarkus.http.port`
  by `LaunchMode` so the probe targets the actually-bound port (incl. a random `=0` port, which Quarkus rewrites the
  property to after binding) — behind the same `LocalhostGuard` write floor. Metrics is read-only and always available
  on Quarkus, but reports real meters only when the application adds a `quarkus-micrometer` registry: the engine
  `MetricsReportProvider` resolves the live composite `MeterRegistry` through a CDI `Instance` (absent → renders
  unavailable), and the meter-visibility predicate is the shared engine `MeterSelfFilter` (so BootUI's own `/bootui/**`
  meters stay hidden, exactly as the Spring adapter feeds `BootUiSelfDataFilter::shouldIncludeMeter`). Traces and AI Usage
  reuse the engine telemetry services; their read endpoints are always wired (so the panels render even with no data), but spans
  are only captured when the application adds `quarkus-opentelemetry` (in-process via a CDI `SpanProcessor`, no OTLP
  receiver). Hibernate is the second **advisor** on Quarkus and the first **optional-dependency** advisor port: the
  shared engine `HibernateScanner` runs the same mapping/identifier/fetch ruleset, but the optional `jakarta.persistence`
  API is confined to two runtime classes (`BootUiHibernateProducer`, `QuarkusEntityDiscovery`) that the deployment
  `registerHibernateAdvisor` build step **excludes** (`ExcludedTypeBuildItem`, referenced by string name) unless
  `Capability.HIBERNATE_ORM` is present and the launch mode is non-prod — so Arc never links the JPA API in an
  ORM-absent app (the capability gate, not `@Lazy`, is the safety mechanism, mirroring the SmallRye-Health/OpenTelemetry
  producers). When present, entities are discovered from the live `EntityManagerFactory` metamodel (all persistence
  units, de-duplicated by identity); when absent the scanner is fed an empty-discovery supplier so `POST /scan` renders
  DISABLED rather than failing, and only the panel's *availability* tracks the build-time `bootui.internal.hibernate-present`
  flag. Persistence configuration is read through `QuarkusHibernatePropertyLookup`, which maps the Spring property names
  the engine rules expect onto their `quarkus.hibernate-orm.*` equivalents (`ddl-auto` → `database.generation` with the
  `drop-and-create` ↔ `create-drop` value alias, `show-sql`/`format_sql`/`batch_size`); the Open-Session-in-View rule is
  correctly inert (Quarkus has no OSIV, so the lookup returns the effective-disabled constant and the rule never fires),
  and Spring Data repository hints are Spring-only. GitHub is the first **Overview** panel lit up on Quarkus: the shared
  engine `GitHubDashboardService` is framework- and JSON-library-free, so the Quarkus adapter only supplies a Jackson 2
  (`com.fasterxml.jackson.*`, from `quarkus-rest-jackson`) `GitHubClient` impl in `...quarkus.web.GitHubApiClient` (the
  Spring adapter's is the Jackson 3 twin), reuses the framework-free `DefaultGitHubTokenProvider` (env + `gh` CLI), and
  `@Produces` the engine service from `BootUiEngineProducer` over the same `bootui.github.*` MicroProfile Config keys and
  defaults (null-safe `Arrays.asList` for the host allow-list). Availability is **dynamic** like Spring's
  `PanelsController.githubAvailable()` — `QuarkusPanelAvailability` special-cases it through
  `GitHubRepositoryDetector.detect(user.dir, allowed-hosts)` rather than the static `AVAILABLE_PANELS` set. The thin
  `GitHubResource` mirrors `GitHubController`: `GET /bootui/api/github` renders network-free, only the explicit
  `POST /bootui/api/github/refresh` calls GitHub (gated by `api-enabled` + host allow-list); cache-on-success and the
  api-disabled `DISABLED` short-circuit live in the shared engine, so both adapters behave identically. Pentesting is the
  third **advisor** on Quarkus and the first **action-capable** advisor port (`POST /bootui/api/pentesting/scan`, behind
  the shared engine `LocalhostGuard` write floor): the shared engine `PentestingScanner` owns the bounded
  synthetic-loopback probe methodology unchanged, so `QuarkusPentestingObservationCollector` supplies only a
  framework-neutral `PentestingObservation` — the live server port (reusing the `QuarkusServerPortSupplier` launch-mode
  resolution the HTTP Probe panel uses) and the `quarkus.http.root-path` context path the probes need, plus a
  deliberately neutral endpoint/security/config snapshot. The empty endpoint inventory is load-bearing, not lazy: a
  non-zero `applicationMappings` would fire the engine's `PT-A07-001` (HIGH, "spring-security-web not present") false
  positive on every Quarkus app, so the inventory stays empty and the Spring-Security/Actuator checks short-circuit. No
  deployment build step is needed (the `@Produces PentestingScanner` rides on the already-registered `BootUiEngineProducer`
  and the `PentestingResource` is auto-discovered from the indexed runtime jar). One honesty caveat: the engine-owned
  OWASP coverage matrix copy is Spring-worded, so a no-finding category renders a Spring-flavored `PASS`/`REVIEW` even on
  Quarkus. Vulnerabilities is the first **non-advisor data panel with a
  user-initiated network action** on Quarkus: the local dependency inventory is captured at **build time** from
  `CurateOutcomeBuildItem.getApplicationModel().getRuntimeDependencies()` (jar-filtered) by a `registerDependencyInventory`
  build step and surfaced as the runtime config default `bootui.internal.dependencies` (comma-joined
  `groupId:artifactId:version`, each coordinate defensively skipped if it contains a comma/`$`/whitespace so the
  comma channel can't be corrupted nor trip SmallRye `${...}` expansion), read back by `QuarkusDependencyProvider` — the
  Quarkus analogue of Spring's `DependencyCatalog` classpath scan, which is unreliable under the Quarkus classloader (this
  mirrors the Architecture base-package discovery exactly). `GET /bootui/api/vulnerabilities` lists that inventory and
  **never** calls OSV on render; only the user-initiated `POST /bootui/api/vulnerabilities/scan` invokes the adapter-side
  `OsvVulnerabilityScanner` (a Jackson-2 port — `com.fasterxml.jackson`, from `quarkus-rest-jackson` — of the Spring scanner,
  delegating all aggregation/ordering to the engine `DependencyReports`), behind the shared `LocalhostGuard` write floor.
  It honors `bootui.vulnerabilities.osv-enabled=false` (→ `DISABLED`, no network call) and the `request-timeout` /
  `max-packages` / `max-advisories` limits; failures return an `ERROR` status while preserving the local inventory. No
  optional-dependency `Capability`/`ExcludedTypeBuildItem` gate is needed (inventory is build-time; OSV uses the JDK
  `HttpClient` + always-present Jackson 2), so the panel is statically available. Everything else is reported unavailable with a clear reason until its Quarkus backing lands.
- **Advisors** read their backing analysis rules from `docs/*-CHECKS.md` (`ARCHITECTURE-CHECKS.md`, `SPRING-CHECKS.md`,
  `HIBERNATE-CHECKS.md`, `MEMORY-CHECKS.md`, `SECURITY-CHECKS.md`, `PENTEST-CHECKS.md`, `REST-API-CHECKS.md`,
  `GRAALVM-READINESS-CHECKS.md`; a `QUARKUS-CHECKS.md` will back the Quarkus advisor). Update the matching doc when changing
  advisor logic.
- The Claude Code panel reuses `views/Copilot.vue` (`component: Copilot`); there is no separate `ClaudeCode.vue`.
- The route order and `meta.group` keys in `routes.js` (each `group` must exist in its `groups` map), `docs/FEATURES.md`,
  and the sample-app navigation test (`bootui-sample-app/e2e/tests/app-shell.spec.js`) must stay consistent when panels are
  added, renamed, hidden, or reordered. When renaming a route path, add a redirect from the old path (see the redirect
  block at the end of `routes.js`).
- New browser-facing behavior usually needs a stable DTO, controller tests where practical, Vue route/view updates,
  `docs/FEATURES.md` updates, a conformance check, and an e2e spec when the UI or sample app behavior changes.
- Feature screenshots in `docs/images/bootui-*.webp` stay at 1600x900 px; they are captured as PNG and re-encoded to WebP
  (quality 80) by the screenshot script. Seed realistic non-sensitive sample data instead of capturing empty states.
  **Always scroll to the top before capturing** — reset both the window and the `.bootui-workspace` scroll container
  (`page.evaluate(() => { window.scrollTo(0, 0); document.querySelector('.bootui-workspace')?.scrollTo(0, 0) })`) after the
  prepare step and before `page.screenshot()`. The main content scrolls inside `.bootui-workspace` (not the document), and
  `waitFor` / `waitForText` helpers scroll matching elements into view, otherwise leaving the viewport at the bottom of the
  panel.

## Java conventions

- Package root `io.github.jdubois.bootui.<module>`. In `bootui-engine`, services + advisor engines live in feature
  sub-packages (`engine.memory`, `engine.architecture`, `engine.pentesting`, …); neutral interfaces live in `bootui-spi`.
  In the **Spring adapter**, simple controllers live in `...autoconfigure.web`, complex features in dedicated sub-packages
  (`...autoconfigure.architecture`, `...autoconfigure.config`), safety code in `...autoconfigure.safety`. In the **Quarkus
  adapter**, JAX-RS resources live in `...quarkus.web`, SPI impls + producers + the safety filter in `...quarkus`.
- DTOs are immutable Java `record`s (annotation-free; Jackson-friendly under both adapters).
- Compiler is configured with `-parameters` (`<parameters>true</parameters>`); rely on it for `@RequestParam`/
  `@PathVariable` / `@QueryParam` binding without explicit `value=` attributes.
- 4-space indent for Java/XML, 2-space for JS/Vue/JSON/YAML/MD (`.editorconfig`). UTF-8, LF, trailing newline. Formatting
  is enforced by Spotless (palantir-java-format) across the whole reactor.
- **Tests move with the class.** Pure unit tests follow a service into `bootui-engine` (with package-private fakes in the
  same package); the adapter's controller/resource test mocks the engine service and asserts only wiring; a factory test
  pins each adapter factory's property→record mapping. Keep the conformance suite green on both adapters at every step.

## Frontend conventions

- Vue 3 **Composition API with `<script setup>`**, Vue Router 5 with `createWebHashHistory()`, Bootstrap 5.3 +
  bootstrap-icons. Plain JavaScript (`.js` / `.vue`); no TypeScript.
- API calls use **relative** paths (`fetch('api/overview')`) so they resolve against the `/bootui/` SPA base — do not
  hardcode `/bootui/api/...`. **The UI is framework-agnostic:** it talks only to the shared `/bootui/api/**` contract, so a
  view must never assume Spring- or Quarkus-specific response shapes. If a panel needs framework-specific copy (e.g.
  `/actuator/health` vs `/q/health`), drive it from a value in the DTO, not hardcoded text.
- New panel = add a `views/Xxx.vue`, register the route in `src/main/frontend/src/routes.js` with an `icon`, `title`, and
  `group` in `meta`; the sidebar in `App.vue` renders from `router.options.routes`.
- Frontend unit tests use Vitest with Vue Test Utils and jsdom. Add focused `*.test.js` coverage for reusable composables/
  components and UI logic where Playwright would be too broad or slow.
- The Vite dev server proxies `/bootui/api/*` to a running sample app (Spring or Quarkus); packaged assets must work from
  `/bootui/` without requiring consumers to install Node.

## Contribution conventions (from `CONTRIBUTING.md`)

- Branch names start with the GitHub username, e.g. `jdubois/improve-config-ui`.
- Keep PRs small and update `docs/` whenever public behavior changes. The PR template's checklist (`./mvnw clean install`,
  sample-app smoke test, no committed secrets) is enforced in review.
- Run focused Vitest tests for changed Vue composables/components, and run the Playwright suite when changing browser
  flows, browser-facing API response shapes, visible routes, or sample-app behavior.
- Both sample apps are for demos/integration tests and set `<maven.deploy.skip>true</maven.deploy.skip>`; do not publish
  them in Maven Central releases.
- Spring Boot 3.x compatibility is **out of scope** — don't add compatibility shims.

## Release plumbing (Maven Central)

Subtle constraints that have burned past releases — preserve them when touching `pom.xml` files or the release profile.
The published artifacts are the shared modules and both adapters (`bootui-core`, `-spi`, `-engine`, `-ui`,
`-autoconfigure`, `-spring-boot-starter`, `-quarkus`, `-quarkus-deployment`); the demo/test modules (`bootui-sample-app`,
`bootui-quarkus-sample-app`, `bootui-quarkus-integration-tests`, `bootui-conformance`) set
`<maven.deploy.skip>true</maven.deploy.skip>` — they must still build so the publishing plugin sees the full reactor, but
must not be published.

- **Source-less modules (`bootui-ui`, `bootui-spring-boot-starter`) must attach their empty `javadoc.jar` at phase
  `package`, not `verify`.** The parent's `release` profile binds `maven-source-plugin`, `maven-javadoc-plugin`, and
  `maven-gpg-plugin:sign` all to `verify`, and gpg runs before any child-pom executions in the same phase. If the empty
  javadoc is attached at `verify`, gpg signs everything *except* the javadoc.jar, and Sonatype Central rejects the
  deployment with `Missing signature for file: ...-javadoc.jar`. If you add another source-less module, copy the existing
  `attach-empty-javadocs` execution (phase `package`, classifier `javadoc`, `skipIfEmpty=false`, fed from
  `target/empty-javadocs`).
- **The signing GPG public key must be queryable by fingerprint on `keys.openpgp.org` and/or `keyserver.ubuntu.com`.**
  Sonatype Central validates signatures against those keyservers; if the public key isn't there, every signature comes back
  `Invalid signature ... Could not find a public key by the key fingerprint`. After rotating the `GPG_PRIVATE_KEY` secret,
  re-publish the matching public key. On macOS, `gpg --send-keys` often fails with `Invalid argument` from dirmngr; the
  reliable fallback is the HTTPS upload APIs (`POST https://keys.openpgp.org/vks/v1/upload` with a JSON `{"keytext": ...}`
  body, and `POST https://keyserver.ubuntu.com/pks/add` with form field `keytext`).
- **A failed deployment still consumes the version coordinate in Central.** Once the publishing plugin uploads
  `com.julien-dubois.bootui:<artifact>:<version>` — even if validation rejects it — you cannot re-upload that exact GAV
  without dropping the failed deployment from the Sonatype Central Portal first. The default path is to run the `Release`
  workflow with a new version so it bumps the codebase, commits, tags, and publishes in one run.
- **Use the `Release` workflow (`.github/workflows/release.yml`) to bump versions** rather than running `versions:set` by
  hand. It runs `versions:set`, updates the `docs/SETUP.md` install snippet (the public `README.md` only links to the docs
  site), *and* bumps every npm `package.json` / `package-lock.json` (root docs site, `bootui-ui` frontend, the
  `bootui-sample-app/e2e` suite) via `npm version --no-git-tag-version`, then verify-builds, commits, tags, and publishes.
  Manual `versions:set` skips the install-snippet rewrite and the npm bumps, leaving them pointing at a stale version. When
  touching version strings, keep the Quarkus modules' `quarkus.platform.version` independent of the project version.

## Design context

BootUI's design system is documented at the **repo root** (kept out of `docs/` so it isn't published to the docs site).
Read and honor both before changing anything the user sees in `bootui-ui`:

- **`PRODUCT.md`** — strategic context: register (`product`), users/personas, purpose, brand personality, anti-references,
  the five design principles, and the accessibility bar.
- **`DESIGN.md`** — the visual system in Stitch DESIGN.md format, with `.impeccable/design.json` as its machine sidecar:
  design tokens, typography, elevation, and component specs. North star: **"The Calm Control Room."**

Load-bearing rules when touching the UI:

- **Accessibility is WCAG 2.1 AA in *both* light and dark themes.** Verify contrast for semantic status colors (log levels,
  severities) and code/identifier text on tinted or selected backgrounds — Bootstrap's contextual colors are tuned for
  light backgrounds only. Every interactive control needs a visible, branded focus ring.
- **Use Bootstrap, never look like default Bootstrap** — no AdminLTE / SB-Admin admin-template look, default blue, or
  untouched utility-class styling.
- **The green→blue gradient means "active / selected" only** — never a hero backdrop or heading fill. Backgrounds stay
  cool (no cream/sand). Machine output is monospace; BootUI's own explanation is sans.
- **Never surprise the user** — no network calls, scans, or mutations on render; honor `prefers-reduced-motion`.

These files are also read by the `impeccable` design skill; re-run `/impeccable document` if the visual system drifts from
the code.
