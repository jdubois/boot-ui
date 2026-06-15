# Changelog

All notable changes to BootUI are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.5.0] - 2026-06-15

Feature release headlined by a new **Live Activity** panel — a diagnostics "home base" that merges BootUI's already
captured signals into one reverse-chronological stream and adds a Symfony-style per-request profiler — and a move to
**Server-Sent Events** for the event-driven panels so they update the moment something happens instead of polling on a
timer. It also adds keyboard shortcuts and number-key navigation to the command palette, a fourth Ahead-of-Time
sample-app Docker image (Spring AOT + JDK AOT cache), simplifies the Overview panel, upgrades the sample app to Spring AI
2.0.0 GA, and quiets the tracing libraries' DEBUG noise.

### Added

- **Live Activity panel** — a new Overview panel that is the diagnostics home base: one reverse-chronological stream of
  everything the application just did plus a per-request profiler. It adds no new instrumentation, instead reusing the
  controllers and DTOs that back the HTTP Exchanges, SQL Trace, Exceptions, and Security Logs panels, so every value is
  already masked, self-filtered, and bounded. The stream merges `REQUEST`, `SQL`, `EXCEPTION`, and `SECURITY` entries
  with a colour-coded severity, a latency heat scale, a requests-over-time sparkline, a KPI strip (requests/min, error
  rate, p50/p95 latency, SQL rate, slowest endpoint, active exceptions, health, heap), and client-side filters that
  persist in the browser. Correlated SQL, exceptions, and security events are nested chronologically under the request
  that produced them — pinned by trace id, by the request's serving thread, or by method/path — and the per-request
  profiler correlates one request's signals with a tiered join that degrades gracefully and labels approximate matches
  rather than fabricating links, flags likely N+1 access patterns, and offers a **Copy profile** action. The merged feed
  is pushed over **Server-Sent Events** (`GET /bootui/api/activity/stream`) and can be paused and resumed. The panel is
  read-only and inherits BootUI's full safety model. Configurable under `bootui.activity.*` (#388).
- **Server-Sent Events live updates** for the Exceptions, SQL Trace, and Security Logs panels. Each panel subscribes to a
  per-panel `/stream` endpoint and re-fetches the moment a signal is captured (or the buffer is cleared or
  paused/resumed) instead of polling on a fixed interval. The push carries no data — masking, truncation, and
  value-exposure rules still apply through the regular endpoint — bursts are coalesced into a single refresh, the stream
  is closed when the auto-refresh toggle is off or the tab is hidden, and the panels fall back to their initial load when
  Server-Sent Events are unavailable (#386).
- **Command palette keyboard shortcuts and number-key navigation** — each panel now has a two-letter shortcut that the
  palette matches and displays, and the unfiltered palette can be navigated with the number keys `1`–`9` (#381).
- **AOT-optimized sample-app Docker image** (`Dockerfile-aot`, `docker-compose-aot.yml`, published as
  `jdubois/bootui-sample-app-aot`) — a fourth startup-optimization option that combines Spring AOT processing with a JDK
  AOT (Ahead-of-Time) cache on the plain JVM image for significantly faster startup, documented in
  [`docs/TRY-SAMPLE-APP.md`](docs/TRY-SAMPLE-APP.md) (#383, #387).
- Sample-app **action-lab buttons** that exercise more BootUI panels for richer demos and integration coverage (#389).
- New `bootui.activity.*` configuration properties, catalogued in [`docs/PROPERTIES.md`](docs/PROPERTIES.md).

### Changed

- **Simplified the Overview panel** by removing the live Health and Memory cards. The panel now opens with the hero
  banner and quick links and leads straight into the on-demand security & health scoring dashboard; the dedicated Health,
  Live Memory, and Heap Dump panels (also reachable from the Live Activity KPI strip) remain the home for that detail
  (#396).
- **Quieted the tracing libraries' DEBUG noise at full sampling.** Because BootUI raises
  `management.tracing.sampling.probability` to `1.0` for local development, the OpenTelemetry SDK and Micrometer Tracing
  span/propagation code runs on every request and floods the console with low-value lines when the host's root logger is
  at `DEBUG`. BootUI now pins `logging.level.io.opentelemetry` and `logging.level.io.micrometer.tracing` to `INFO` as
  overridable defaults while the Traces panel is active; set either key yourself to opt back in (#394).
- Upgraded the sample app to **Spring AI 2.0.0 GA** (from `2.0.0-RC1`) (#378).
- Bumped `esbuild` from `0.28.0` to `0.28.1` (#380).

### Fixed

- **`NoSuchMethodError` opening the Configuration or MCP Server panel on older Jackson 3** — `ConfigMetadataCatalog` now
  binds to the stable `ObjectMapper.treeToValue(TreeNode, Class)` overload instead of `treeToValue(JsonNode, Class)`,
  which was only added in jackson-databind 3.1. Host applications whose classpath resolves an earlier Jackson 3 (for
  example 3.0.x dragged in by a transitive dependency) no longer crash `ConfigController` start-up with
  `java.lang.NoSuchMethodError: 'java.lang.Object tools.jackson.databind.ObjectMapper.treeToValue(...)'` (#384).
- **SQL Trace no longer fails under Spring Boot DevTools' class-loader split.** When DevTools loads the application on its
  restart class loader, the data source's own loader (the base loader, where the driver/pool jar lives) cannot see
  BootUI's `SqlTracedDataSource` marker, so creating the JDBC tracing proxy threw. BootUI now defines the proxy with its
  own class loader — a descendant of the data source's that can see both the marker and the JDK's JDBC interfaces — so
  SQL tracing keeps working during DevTools-powered development (#395).
- Updated the Claude Code panel icon (#392).
- Fixed the documentation site's home-page `<title>` tag (#382) and a duplicate `@vuepress/plugin-markdown-tab` warning
  during the docs build (#379).

## [1.4.0] - 2026-06-12

Feature release headlined by three new panels — a **SQL Trace** panel that records executed SQL through a hand-written
JDBC proxy and flags slow queries and likely N+1 access patterns, an **Exceptions** diagnostics panel that captures and
groups runtime exceptions, and an opt-in, local-only **MCP server** (with a Developer Tools panel) that exposes BootUI's
advisors and read-only diagnostics to local AI coding agents. It also sharpens the Traces panel, strengthens the CRaC
advisor, fixes two GraalVM native-image issues in the starter, hardens the sample app's JVM and native Docker images to
zero OS-package CVEs, shrinks the packaged UI, and polishes the console shell.

### Added

- **SQL Trace panel** — a new Database panel showing the SQL statements the application recently executed, captured by a
  hand-written JDBC tracing proxy built on the JDK's own dynamic-proxy support (no third-party database-proxy library).
  It transparently wraps each `DataSource` and records SQL text, statement/category, wall-clock duration, affected rows,
  batch size, connection, thread, and failures into a bounded in-memory ring buffer; groups identical statements; flags
  likely **N+1** access patterns and slow queries; and offers local-only Pause/Resume and Clear actions. Parameter
  capture is off by default and masked when enabled, wrapping fails open, and the JDK proxies are registered for GraalVM
  native images. Configurable under `bootui.sql-trace.*` (#359).
- **Exceptions panel** — a new Diagnostics panel that captures, groups, and surfaces exceptions thrown by the host
  application. Capture uses two observe-only sources (a `HandlerExceptionResolver` for MVC handler exceptions with
  request context, and a Logback root appender for anything logged with a throwable), deduplicated by `Throwable`
  identity and grouped by a SHA-256 fingerprint of the class and top stack frames, with bounded recent occurrences and
  the cause chain. Messages are masked, request paths are captured without query strings, and the clear action honors
  read-only. Configurable under `bootui.exceptions.*` (#358).
- **MCP server for AI agents** — BootUI can optionally expose its advisors and read-only diagnostics to local AI coding
  agents (GitHub Copilot, Claude Code) through an opt-in, local-only
  [Model Context Protocol](https://modelcontextprotocol.io) server. It is a hand-rolled JSON-RPC 2.0 endpoint at
  `POST /bootui/api/mcp`, disabled by default (`bootui.mcp.enabled=OFF`), that reuses the existing controllers and DTOs
  so every tool returns the same masked, bounded shape as the REST API — advisor scans as action tools, plus diagnostics
  and core-context read tools — and inherits BootUI's full safety model. A new **MCP Server** panel (top of Developer
  Tools) documents the exposed tools, shows connection details and a copyable client-configuration JSON, and toggles the
  server on or off at runtime, overriding the configured mode. Configurable under `bootui.mcp.*` (#368, #370).
- Three new **CRaC readiness checks** — `CRAC-FILE-001` (direct file-handle opens), `CRAC-CACHE-001` (live
  `CacheManager` beans), and `CRAC-CONFIG-001` (static initializers capturing env/properties) — plus broadened
  `CRAC-RES-001`, `CRAC-SECRET-001`, and `CRAC-POOL-001` coverage, all catalogued in
  [`docs/CRAC-READINESS-CHECKS.md`](docs/CRAC-READINESS-CHECKS.md) (#355).
- New `bootui.sql-trace.*`, `bootui.exceptions.*`, and `bootui.mcp.*` configuration properties, all catalogued in
  [`docs/PROPERTIES.md`](docs/PROPERTIES.md).
- A brand favicon for the console UI, the sample app, and the VuePress documentation site (#364).

### Changed

- **Traces panel now shows the HTTP request path** a trace served (falling back to the root span name when no path
  attribute is present) instead of labelling traces with generic root spans, and **fully excludes BootUI's own
  traffic**: self-span filtering is now trace-level, so once any span identifies a trace as BootUI's own the whole trace
  — and sibling spans exported in other OTLP batches — is dropped. Applied to both the in-process span exporter and the
  `/bootui/api/otlp` receiver (#375).
- **Polished the console shell** — collapsed-sidebar hover flyouts, a mobile overlay drawer and responsive topbar, a
  pulsing "live" dot on the auto-refresh toggle, recently visited panels floated to the top of the command palette, and
  human-readable byte sizes in Health details (#362).
- **Shrank the packaged UI JAR** by subsetting the Bootstrap Icons font and CSS to only the icons BootUI uses (#363).
- **Hardened the sample app's JVM Docker image** (published as `jdubois/bootui-sample-app`) to carry no known OS-package
  CVEs. Its runtime stage now uses Google's distroless glibc base (`gcr.io/distroless/base-debian12:nonroot`, the same
  base `Dockerfile-native` uses) instead of Alpine, which removes the vulnerable `openssl` (`libssl3`/`libcrypto3`) and
  `busybox` packages a scanner previously flagged. The `jlink` runtime is now assembled on the glibc JDK to match the
  base; because distroless ships no shell, JVM flags moved from a `sh -c $JAVA_OPTS` entrypoint to `JAVA_TOOL_OPTIONS`,
  the entrypoint is exec-form, and the Docker `HEALTHCHECK` was dropped (probe `/actuator/health` from your orchestrator
  instead, as the native image already documents) (#365).
- **Hardened the sample app's GraalVM native Docker image** by building a mostly-static binary and switching the runtime
  stage to `gcr.io/distroless/base-debian12:nonroot`, taking it (and the BootUI-generated `Dockerfile-native`) to zero
  CVEs; the curl-based `HEALTHCHECK` was dropped (#356).
- **Shrank the sample app's JVM Docker image from 739MB to 338MB** by exploding the repackaged Spring Boot jar into
  layers, assembling a curated `jlink` runtime, and using a minimal Alpine final stage (#361).
- Refactored the sample app into clean feature packages for clearer demos and integration tests (#371).

### Fixed

- **GraalVM native image: the Mappings panel no longer fails.** `GET /bootui/api/mappings` (the compatibility
  endpoint that returns Actuator's raw mappings descriptor) threw a `MissingReflectionRegistrationError` in a native
  image because Jackson reflectively instantiates the array forms of Actuator's nested `MediaTypeExpressionDescription`
  / `NameValueExpressionDescription` types while serializing them. BootUI's `RuntimeHints` now register those types and
  their array forms, so consumers of the starter no longer need to declare the hints themselves (#367).
- **GraalVM native image: BootUI no longer self-reports as "Disabled" while running.** In a native image the activation
  condition is frozen at AOT build time, but the `BootUiActivation` bean recomputed it against the live runtime
  environment (where the build-time `dev` profile / `bootui.enabled=ON` no longer apply), so the Overview panel and the
  startup log claimed BootUI was disabled even though it was serving. When running AOT-generated artifacts, BootUI now
  trusts the frozen build-time decision and reports the accurate enabled state (#367).
- Exempted the `/bootui/api/mcp` endpoint from Spring Security's SPA CSRF token so non-browser MCP clients (e.g. VS
  Code) can connect without a token, while `LocalhostOnlyFilter`'s loopback, `Host` allow-list, and cross-site
  defenses still apply (#370).
- Fixed VuePress documentation anchor links landing on the wrong section (#374).

## [1.3.0] - 2026-06-11

Feature release headlined by two new GraalVM/CRaC capabilities — a new **CRaC (Coordinated Restore at Checkpoint)
readiness panel** and **GraalVM reachability-metadata** support (repository lookup plus an install-into-source-tree
action) — alongside an upgrade to **Spring Boot 4.1.0** and a second wave of advisor improvements (Memory, REST API,
GraalVM) authored with Anthropic's new **Claude Fable 5** model, on top of the 1.2.0 hardening pass. It also makes BootUI
reachable from inside containers through narrow, fail-closed opt-ins (`bootui.trusted-proxies` and
`bootui.trust-container-gateway`), runs the sample app Docker-free by default, and adds a cross-platform CI build matrix.

### Added

- **CRaC readiness panel** — a new Runtime panel that reviews the host application's
  [Coordinated Restore at Checkpoint](https://docs.spring.io/spring-framework/reference/integration/checkpoint-restore.html)
  readiness. It reports whether the `org.crac` API is on the classpath, whether the running JVM is a CRaC-capable JDK
  (detected via the real CRaC implementation rather than the no-op shim), whether `spring.context.checkpoint=onRefresh`
  is set, and any `-XX:CRaCCheckpointTo` / `-XX:CRaCRestoreFrom` JVM arguments. It scans the host application's own
  classes against a curated set of `CRaC-*` checks (including the `CRAC-POOL-001` connection-pool readiness check) for
  constructs that complicate checkpoint/restore, and generates ready-to-use container assets — a multi-stage
  `Dockerfile-crac` plus a `checkpoint-and-run.sh` entrypoint. The full catalogue lives in
  [`docs/CRAC-READINESS-CHECKS.md`](docs/CRAC-READINESS-CHECKS.md) (#322).
- **GraalVM reachability-metadata lookup** — the GraalVM panel queries the reachability-metadata repository for the
  host's dependencies and adds an _install into source tree_ action that writes the metadata into the project; the
  "Include dependencies" toggle now defaults to on (#324, #331).
- A second wave of advisor rules on top of the 1.2.0 hardening pass: **7 new GraalVM checks** (plus the new
  `GRAAL-FFM-001` Foreign Function & Memory check, replacing the AWT check), **7 new Memory rules**, and **9 new REST API
  rules**, all catalogued in the refreshed `docs/*-CHECKS.md`. This wave of advisor work was authored with Anthropic's
  new Claude Fable 5 model.
- **`bootui.trusted-proxies`** — an opt-in list of source IP ranges (CIDR notation, e.g. `172.16.0.0/12`) trusted in
  addition to loopback by the safety filter. Lets local Docker-bridge callers reach BootUI without the blunt
  `bootui.allow-non-localhost=true`: it relaxes only the source-address check and keeps the `Host` allow-list
  (DNS-rebinding) and cross-site write (CSRF) protections in force. Pair it with `bootui.allowed-hosts` for the hostname
  the browser uses.
- **`bootui.trust-container-gateway`** (`OFF` / `AUTO` / `ON`, default `OFF`) — a one-flag opt-in to trust the
  auto-detected container gateway as a single `/32`, so BootUI can be reached inside a container with a published port
  without knowing the subnet or setting a broad `bootui.trusted-proxies` CIDR. Detection covers both the Linux Docker
  Engine bridge gateway (from `/proc/net/route`) and the Docker Desktop gateway (via the `gateway.docker.internal` DNS
  name). Like `bootui.trusted-proxies`, it relaxes only the source-address check and keeps the `Host` allow-list and
  CSRF protections in force.
- **Cross-platform CI** — a build workflow matrix that runs the full build on Linux, Windows, and macOS.
- A "Docker container access" section in [`docs/SETUP.md`](docs/SETUP.md) covering the trusted-proxies and
  container-gateway options.

### Changed

- **Upgraded to Spring Boot 4.1.0** (from 4.0.x), including aligning the Hibernate advisor end-to-end tests with
  Hibernate 7.4 collection-fetch behavior (#326).
- Marked the **JVM Tuning, GraalVM, Architecture, REST API, and CRaC** panels unavailable in GraalVM native images,
  where their underlying JVM/bytecode analysis cannot run. The startup-timeline buffer is now installed for AOT images
  even when BootUI is inactive, so timeline data is captured if BootUI is later enabled.
- **`bootui-sample-app` now runs Docker-free by default** — the `dev` Spring profile (the default via
  `spring.profiles.default=dev`) swaps the Docker Compose PostgreSQL, Redis, and Ollama services for an in-memory H2
  database, a simple in-memory cache, and disabled Spring AI, so a bare `spring-boot:run` (and the Playwright e2e suite)
  starts offline with no Docker engine. A new `docker` profile (`-Dspring-boot.run.profiles=docker`) restores the full
  Docker experience (PostgreSQL, Redis, Ollama, and the `qwen2.5:0.5b` chat model).
- **"Try the sample app" now uses the published Docker image** — [`docs/TRY-SAMPLE-APP.md`](docs/TRY-SAMPLE-APP.md) runs
  `jdubois/bootui-sample-app` (the JVM image), with `docker run` command lines for the CRaC
  (`jdubois/bootui-sample-app-crac`) and GraalVM native (`jdubois/bootui-sample-app-native`) images too. The
  `scripts/run-sample.sh` and `scripts/run-sample.ps1` helper scripts, which cloned and built the repository locally,
  were removed.
- The sample app's three Dockerfiles (JVM, CRaC, and native) now default to the `dev` profile with Flyway/Liquibase
  disabled for fast startup, and the JVM and CRaC images set explicit JVM tuning flags.
- Shared a single source-tree writer and build-system detection routine across the GraalVM and CRaC config generators.

### Fixed

- BootUI now loads correctly when the host application sets a non-root `server.servlet.context-path` (#332).
- Fixed the generated `Dockerfile-native` Maven build for plain Spring Boot applications (#325).
- Normalized install display paths to forward slashes on Windows so the setup snippets render correctly.
- Removed the ArchUnit gate from CRaC availability — only running in a native image disables the panel.
- Fixed broken documentation links and aligned the VuePress navbar logo with the sidebar toggle.

## [1.2.0] - 2026-06-09

Feature release headlined by a **sweeping hardening pass across all eight rule-based advisors** — recalibrated
severities, far fewer false positives and negatives, and a wave of new high-signal checks — backed by the new ability to
**dismiss and restore advisor findings**. It also makes the console load even when the host disables Spring's
static-resource mappings, and fixes scheduled-task and sample-app native-image regressions.

### Added

- **New high-signal advisor checks** added across the rule advisors during the hardening pass, including Architecture
  (`ARCH-SPRING-017`, `ARCH-SPRING-018`, `ARCH-SPRING-019`, `ARCH-SPRING-021`, `ARCH-MOD-001`), REST API (`RAPI-MAP-008`,
  `RAPI-RESP-008`, `RAPI-VER-005`), and GraalVM (`GRAAL-CLASSGEN-001`, `GRAAL-INIT-002`, `GRAAL-SER-002`,
  `GRAAL-SCAN-001`, `SPRING-AOT-001`, `SPRING-AOT-002`, plus new class-generation, classpath-scanning, and Spring-AOT
  categories), with further new Spring, Hibernate, Memory, Security, and Pentesting rules catalogued in the refreshed
  `docs/*-CHECKS.md`.
- Added `CRITICAL` severity to the Architecture, REST API, Spring, Hibernate, Security, Pentesting, and GraalVM advisors;
  official "learn more" links to the Architecture, Pentesting, and GraalVM panels; and a muted analysis-error channel on
  the Architecture, Spring, Security, and Memory panels that surfaces rules which throw during evaluation.
- **Dismiss / restore advisor findings** — every finding surfaced by the seven Overview-scored rule advisors
  (Architecture, REST API, Spring, Hibernate, Memory, Security, Pentesting) now carries a _Dismiss_ button. Dismissed
  rules collapse into a "Dismissed rules" list at the bottom of the panel and are excluded from the panel's finding
  count, severity bars, the panel's own advisor score, and the weighted Overview score; they can be restored at any time.
  Dismissals are keyed by the globally unique rule IDs, applied server-side, and persisted under the `dismissedRules`
  node of a developer-local `.bootui/boot-ui.yml` file (next to the runtime overrides file), so they survive restarts and
  stay consistent between each panel and the Overview dashboard.
- Per-advisor **0–100 score** now shown on each of those advisor panels (100 minus the weighted finding penalty), always
  matching the value the Overview dashboard computes for that advisor, rendered through a shared `AdvisorScoreCard`.

### Changed

- **Hardened every rule-based advisor (Phases 0–8)** — Architecture, REST API, Spring, Hibernate, Memory, Security,
  Pentesting, and GraalVM — with context- and profile-aware dynamic severity, recalibrated thresholds, canonical Spring
  Boot 4 property names, and fewer false positives/negatives. The matching `docs/*-CHECKS.md` catalogues were refreshed.
- Reworked Memory heap-pressure detection to measure pressure from a post-GC dual snapshot and to track GC-overhead
  trend across scans rather than within a single forced GC.
- Extracted shared UI building blocks (`FlashBanner`, `SpinnerButton`, `ReadOnlyNotice`, `AdvisorScoreCard`) and a shared
  `AgentSessionController` base for the Copilot and Claude Code panels.
- Changed the Spring panel icon from a lightbulb to a leaf.
- Restructured the documentation site: install the starter via a dedicated dev Maven/Gradle profile, render the
  Maven/Gradle setup as VuePress tabs, reordered the setup sections, and removed the README badges.

### Fixed

- Serve the BootUI console assets even when the host sets `spring.web.resources.add-mappings=false`: a dedicated
  `WebMvcConfigurer` maps `/bootui/**` to the bundled SPA without re-exposing the host's own static resources, and
  disabled static-resource mappings are now logged at `WARN` with a troubleshooting note (#291).
- Scheduled Tasks panel no longer returns HTTP 500 when the host application registers its own `ScheduledTaskHolder`
  bean; tasks are aggregated across every holder so programmatically registered timers appear alongside `@Scheduled`
  tasks (#288).
- Fixed the sample app's native-image smoke test for its new `@Inheritance(TABLE_PER_CLASS)` and `UUID` `@Id` demo
  entities by registering reflection hints for the `UnionSubclassEntityPersister` constructor and the `UUID[]` multi-id
  loader array in the sample app's native-hints configuration.
- Fixed a Liquibase connection leak and a brittle cache type check flagged by static analysis, and stabilized the
  Hibernate advisor end-to-end tests against cached scan state and duplicated paged collection-fetch detail rendering.

## [1.1.0] - 2026-06-07

Feature release that introduces a dedicated **Advisors** workspace with three new rule-based panels (Spring, REST API,
Memory), expands every existing advisor catalogue, lets the console run from non-web applications, and reorganizes the
runtime memory panels — while hardening the safety filter and correcting the Actuator-defaults precedence.

### Added

- **Spring panel** — new advisor that inspects the running application for Spring and Spring Boot 4 best-practice issues,
  shipping 31 curated rules documented in `docs/SPRING-CHECKS.md`.
- **REST API panel** — new advisor that audits controller/handler mappings against 36 curated REST design rules,
  documented in `docs/REST-API-CHECKS.md`.
- **Memory panel** — new advisor with 22 heap, native, GC, and finalizer checks documented in `docs/MEMORY-CHECKS.md`.
- **Live Memory** runtime panel showing live JVM memory-pool usage, split out from the previous runtime Memory panel.
- Support for serving the BootUI console from **non-web (command-line) Spring Boot applications**, not just servlet web
  apps.
- GitHub panel **open-issues drawer** that lists open repository issues with bounded refreshes.
- Five new Spring Security checks — BCrypt work-factor floor (`SEC-AUTH-006`), Referrer-Policy and Permissions-Policy
  headers (`SEC-HEAD-005`/`SEC-HEAD-006`), concurrent session control (`SEC-SESSION-007`), and HTTPS enforcement in
  production (`SEC-CONFIG-006`), documented in `docs/SECURITY-CHECKS.md`.
- Expanded advisor coverage across the board: GraalVM readiness grows from 5 to 12 checks, plus new Architecture,
  Hibernate, and Memory rules, all reflected in their `docs/*-CHECKS.md` catalogues.

### Changed

- Grouped the rule-based panels under a dedicated **Advisors** navigation group (Architecture, REST API, Spring,
  Hibernate, Memory, Security) alongside Pentesting and Vulnerabilities, and wired the new advisors into the Overview
  security & health scoring dashboard.
- Renamed panels for clearer URLs and class names: dropped the "Advisor" suffix from the rule-based panels, renamed
  Tuning Advisor to **JVM Tuning** and Dependencies to **Vulnerabilities**, and split the runtime Memory panel into
  **Live Memory** and **JVM Tuning**. Legacy routes (`/security-advisor`, `/hibernate-advisor`, `/tuning-advisor`,
  `/pentest`, `/dependencies`, `/rest-advisor`, `/spring-advisor`, `/memory-advisor`, `/profiles`) redirect to the new
  paths.
- Aligned endpoint, controller, and DTO naming for Log Tail, HTTP Probe, Database Connection Pools, and Profile Diff,
  and fixed the Database Connection Pools component pluralization.
- Standardized the empty/unavailable panel state behind a shared `UnavailableState` component and consolidated shared
  time/number formatting helpers across views.
- Renamed the advisor catalogue docs from `*-ADVISOR-CHECKS.md` to `*-CHECKS.md`, closed rule-ID numbering gaps so every
  sequence is continuous, corrected drifted Hibernate doc entries (`HIB-CONFIG-016`, `HIB-CONFIG-017`, `HIB-MAP-018`,
  `HIB-MAP-019`), and rewrote `docs/REST-API-CHECKS.md` in the shared `### ID - Title` format. Renumbered rule IDs:
  `MEM-GC-002` → `MEM-GC-001`, `MEM-GC-003` → `MEM-GC-002`, `RAPI-VALID-003` → `RAPI-VALID-002`, `RAPI-VALID-004` →
  `RAPI-VALID-003`, and `HIB-FETCH-007` → `HIB-FETCH-006`.
- Gave AI Usage, Copilot, and Claude Code their own documentation sections and synced `docs/FEATURES.md`,
  `docs/PROPERTIES.md`, the README feature table, and the screenshots with the Advisors regrouping.
- Scoped the Maven Central release secrets to a protected `maven-central` GitHub environment.

### Fixed

- BootUI's Actuator defaults are now contributed as true lowest-priority defaults so a host application's
  `EnvironmentPostProcessor` settings always win (#246).
- Hardened the localhost-only safety filter and added value-based secret masking for browser-visible property values.
- Fixed dark-mode contrast on Bootstrap contextual utilities and on the GitHub quota metric cards.
- Made the developer tooling more robust: `run-sample.sh` no longer fails on macOS Bash 3.2, the getting-started scripts
  can target `main` or one of the last five tags, the Maven offline setup primes `spring-boot-maven-plugin`, and the
  Copilot dev server self-heals on a cold worktree `.m2`.
- Fixed documentation-site build failures (GitHub Pages Node 24 configuration, backticked angle-bracket type fragments,
  and the Hibernate advisor image) and pinned the Ollama Docker Compose port to stabilize the e2e startup.

## [1.0.0] - 2026-06-05

First stable BootUI release, focused on promoting the current local developer-console surface to `1.0.0`, adding the
Spring Security Advisor, and publishing the redesigned documentation site.

### Added

- Security Advisor panel with explicit Spring Security hardening checks for authentication, authorization, CSRF, sessions,
  headers, CORS, method security, actuator exposure, OAuth2 resource-server validation, and security configuration hygiene,
  plus the `docs/SECURITY-ADVISOR-CHECKS.md` rule catalogue.
- Overview security & health scoring dashboard that can run the available Architecture, Hibernate Advisor, Security
  Advisor, Vulnerabilities, Pentesting, and GitHub scanners individually or together.
- VuePress documentation site, GitHub Pages workflow, repository documentation, and setup/sample-app pages for the public
  docs at `julien-dubois.com/boot-ui`.

### Changed

- Copilot and Claude Code dashboards now emphasize input/output token usage charts while retaining event and failure
  views for sanitized local agent activity.
- Refreshed release-facing screenshots for the 1.0 surface, including Overview, Security Advisor, Copilot, and Claude Code,
  and verified the screenshot set against the routed panel list.
- Updated the implementation roadmap so completed 1.0 work is separated from the next workstream for trace/log/request
  correlation, bean graph visualization, and an e-mail viewer.

### Fixed

- Detected proxied Hikari data sources in the Database Connection Pools panel.
- Corrected Spring Modulith Flyway migration reporting so module-specific history tables remain visible and read-only.
- Fixed VuePress markdown links, homepage setup navigation, GitHub Pages Node 24 configuration, and sample quick-start
  script UI bundling.

## [0.5.1] - 2026-06-04

Patch release focused on preserving BootUI startup in applications that do not include Spring Security Core while
keeping Security Logs support available when Spring Security authentication events are present.

### Fixed

- Guarded BootUI's auto-configured Spring Security audit event repository behind Spring Security authentication event
  classes so applications without `spring-security-core` no longer fail at startup.

## [0.5.0] - 2026-06-04

Fifth BootUI release, focused on repository context, servlet session inspection, database migration/advisor tooling, and
release-facing documentation for the expanded 0.5.0 panel surface.

### Added

- GitHub dashboard panel under Overview, with local repository detection, bounded refreshes for pull requests, issues,
  latest GitHub Actions executions, dynamic rate-limit/quota drawers, security signals, and Copilot usage report metadata.
- HTTP Sessions panel backed by embedded Tomcat session metadata, with masked session identifiers and attributes by
  default plus confirmation-gated clear/destroy actions.
- Database navigation group with read-mostly Flyway and Liquibase panels, including migration/change-set inventory and
  confirmation-gated `migrate`, `clean`, and `update` actions.
- Hibernate Advisor panel with explicit Hibernate/JPA mapping, configuration, caching, and repository-query checks, plus
  the `docs/HIBERNATE-CHECKS.md` rule catalogue.
- Auto-configured an in-memory Spring Boot `AuditEventRepository` for Security Logs when BootUI is active, audit events are
  enabled, and the host app has not provided its own repository.
- Sample-app quick-start scripts for macOS/Linux and Windows PowerShell.

### Changed

- Standardized panel auto-refresh controls and visibility-aware refresh behaviour across live panels.
- Updated the GitHub Actions drawer to show latest execution details and count only the latest run per workflow when
  reporting workflow failures.
- Expanded the sample app with Flyway/Liquibase schemas, richer Hibernate/JPA sample mappings, HTTP session data, security
  events, and release screenshots for the current sidebar surface.
- Reworked the implementation roadmap so already-shipped database/security/runtime panels moved out of the plan and the
  next workstream focuses on trace/log/request correlation, bean graph visualization, and an e-mail viewer.

### Fixed

- Corrected AI Usage telemetry KPIs and summary calculations.
- Matched the exact BootUI root paths in BootUI's highest-priority Spring Security chain so host SPA fallback filters do
  not intercept `/bootui` before BootUI can redirect to its console.
- Tightened security diagnostics, value exposure handling, panel availability wiring, and release documentation for the
  0.5.0 panel surface.

## [0.4.0] - 2026-06-03

Fourth BootUI release, focused on new local runtime/security diagnostics, native-image readiness tooling, and the
current grouped sidebar surface.

### Added

- GraalVM native-image readiness panel with on-demand host-application checks for reflection, dynamic proxies, resources,
  serialization, native access, dependency reachability metadata, and a reviewable `reachability-metadata.json` scaffold.
- Threads panel backed by in-process `ThreadMXBean` snapshots, with state counts, deadlock detection, virtual-thread
  context, server-side filtering/paging, stack expansion, and confirmation-gated raw dump download.
- HTTP Exchanges panel for recent inbound application requests, including bounded recording, server-side filtering,
  masked headers/query data, trace identifiers, and drawer-style request/response details.
- Security Logs panel and Security navigation group for recent Spring Boot audit events, bounded retention, masking,
  filters, and visibility-aware auto-refresh.
- Weekly GraalVM native-image build workflow and native-image sample-app Docker assets/readiness documentation.

### Changed

- Renamed the Cache surface to Spring Cache across the route metadata, docs, and release-facing screenshots.
- Updated HTTP Exchanges and Security Logs to use standard visibility-aware auto-refresh instead of manual refresh
  buttons.
- Updated the next-feature roadmap so already-shipped panels moved out of the plan and the next workstream is focused on
  migrations, trace/log/request correlation, and bean graph visualization.
- Bumped build and dependency plumbing, including Spring AI 2.0.0-M8, the GraalVM native build tools plugin, Sonatype
  Central publishing plugin, Maven plugins, `actions/checkout`, and the frontend/API error-handling utilities.

### Fixed

- Added Spring AOT runtime hints and sample native-image wiring so BootUI resources, DTOs, heap-dump/security reflective
  calls, and Maven metadata survive native-image builds.
- Updated feature documentation, sample-app walkthroughs, security policy, Playwright docs, and screenshots for the
  current sidebar grouping and full 0.4.0 panel surface.
- Completed Java/frontend audit follow-ups around nullability, shared helpers, frontend API normalization, and duplicate
  utility removal.

## [0.3.0] - 2026-06-02

Third BootUI release, focused on the new JVM Tuning Advisor, Java 17 baseline, stronger AI telemetry guidance, and
release-facing documentation/screenshots for the updated menu surface.

### Added

- Tuning Advisor panel split out from Memory, with fixed bare-metal JVM options, percentage-based Kubernetes
  `JAVA_TOOL_OPTIONS`, optional Burstable request sizing, Actuator probe YAML, and virtual-thread sizing guidance based on
  the running JVM context.
- LangChain4j support in the AI Usage panel. BootUI now detects Spring AI and/or LangChain4j, shows the selected
  framework with header badges, and offers side-by-side Spring AI and LangChain4j telemetry setup guides explaining the
  dependency and configuration each needs to emit GenAI spans (including optional prompt/completion content capture).
- Health panel setup guidance: a disabled state with guidance when no Actuator `HealthEndpoint` is available, and
  guidance (without changing reported statuses) when a health tree contains only Spring Boot's default indicators.
- Frontend test coverage for the shared auto-refresh and refresh-state utilities, the Health view, and the panel header
  component.

### Changed

- ArchUnit is now bundled transitively through `bootui-spring-boot-starter`, so the Architecture panel works out of the
  box without an extra application dependency; the sample app's redundant direct dependency was removed.
- Architecture checks were expanded with additional coding-practice and Spring proxy/stereotype heuristics.
- Pentesting checks now align their local-only hygiene catalogue with OWASP Top 10 2025.
- Release preparation (Maven module versions, README install snippet, release commit, and tag) and Maven Central
  publishing are unified into a single `Release` workflow, replacing the separate `Prepare Release` workflow.
- Lowered the build baseline from Java 25 to Java 17, updating the Maven compiler release, the CI build matrix, and
  CodeQL analysis.
- The Data menu item is now Spring Data, and the database pool view is consistently named Database Connection Pools.
- AI Usage content-capture guidance and documentation now cover LangChain4j alongside Spring AI.
- Copilot and Claude Code agent panels now share the standard panel refresh behavior.

### Fixed

- Removed auto-refresh flicker by showing panel skeletons only on first load and sharing refresh state across panels.
- Normalized frontend errors when the backend is offline or unavailable.
- Removed duplicated `formatDuration`/`formatTime` helpers in the Traces panel in favor of the shared format utilities.
- Updated the footer GitHub link, release docs, and regenerated feature screenshots for the current sidebar menu.

## [0.2.0] - 2026-06-01

Second BootUI release, focused on local security diagnostics, three new local-only diagnostics panels — Architecture
(ArchUnit), Heap Dump (value-free class histogram), and Database Connection Pools — and safer defaults around
host-application security, plus release/documentation hardening for the full visible panel surface.

### Added

- Architecture panel that runs a curated, zero-config ArchUnit ruleset against the host application's own classes for
  package-cycle, coding-practice, and Spring-stereotype hygiene, with an on-demand scan and the latest report.
- Heap Dump panel that captures local JVM heap dumps on demand and analyzes a value-free class histogram, including a
  `max-classes` memory cap plus big-objects and collection-bloat smart filters. Raw `.hprof` download stays disabled by
  default because dumps contain plaintext secrets.
- Database Connection Pools panel that surfaces read-only database pool sizing, masked JDBC metadata, and a live
  active/idle/total/pending saturation chart, failing closed when pool support is unavailable.
- Pentesting panel with explicit, local-only OWASP-aligned hygiene checks for security headers, CORS behavior, cookie
  flags, verbose errors, Spring Security wiring, actuator exposure, DevTools, H2 console, and risky configuration values.
- BootUI Spring Security integration that keeps `/bootui/**` and `/bootui/api/**` reachable in local applications using
  Spring Security while preserving the localhost-only safety filter.
- Automatic local application trace capture so Traces and AI Usage can populate from the host app without requiring a
  separate local OTLP exporter setup.
- CI test report publishing for Maven/JUnit and Playwright runs.
- Playwright end-to-end coverage for the Pentesting panel.

### Changed

- Monitoring panels now hide BootUI's own beans, mappings, loggers, metrics, traces, and related runtime data by default
  through `bootui.monitoring.exclude-self=true`.
- Request-driven BootUI controllers and agent session stores are lazy-loaded, and agent session parsing is bounded to
  avoid unnecessary startup work.
- Vulnerability findings are sorted by severity/importance first, with stable ordering inside each severity group.
- The app shell, panel headers, skeleton states, auto-refresh controls, and command palette/navigation were polished for a
  faster and more consistent UI.
- Architecture, Pentesting, and Vulnerabilities now share clearer scan status messaging.
- Startup Timeline configuration, panel read-only controls, application property reference docs, pentest catalogue docs,
  feature docs, screenshots, and E2E documentation were reconciled with the implemented `0.2.0` behavior.
- Refreshed and reorganized `SECURITY.md`.
- Regenerated feature screenshots and extended the docs screenshot script to cover the Architecture, Heap Dump, and
  Database Connection Pools panels.

### Fixed

- Fixed sample-app and BootUI security audit findings, including enabling CSRF protection in the sample app.
- Fixed hidden-BootUI-internals assumptions in Beans E2E coverage after self-data filtering became the default.
- Removed duplicated panel headings and restored Overview/Metrics heading behavior.
- Fixed BootUI navigation controls, including theme persistence and command palette shortcut behavior.
- Fixed the Claude Code sidebar icon.
- Added registry-level coverage so global read-only mode is checked for every action-capable panel.

## [0.1.0] - 2026-05-29

First final BootUI release. This promotes the alpha line to the final `0.1.x` coordinate while keeping the local-only,
developer-console safety model and the full visible panel surface.

### Added

- Copilot and Claude Code panels for sanitized local activity dashboards, including session summaries, activity trends,
  tool and model usage, failures, and bounded live refresh behavior.
- Vitest, Vue Test Utils, and jsdom coverage for reusable frontend behavior, wired into the Maven test phase.

### Changed

- README, feature documentation, the release plan, and generated feature screenshots are aligned with the final `0.1.0`
  panel surface and install coordinates.
- Beans, Conditions, Mappings, Configuration, and Loggers use bounded server-side filtering and pagination for
  high-cardinality applications.
- The full visible route set is promoted from the supported alpha surface to the supported `0.1.0` release surface.

### Fixed

- Dev Services discovery and controls handle prototype-scoped Testcontainers beans, stopped containers, null log output,
  restart failures, metadata-only connection detail masking, and abstract bean definitions more defensively.

## [0.1.0-alpha.5] - 2026-05-27

Latest tagged alpha with the expanded panel surface, telemetry features, and release hardening.

### Added

- Backend test coverage for `BootUiProperties` binding, additional activation rules
  (devtools activation, custom disabled profiles, invalid `bootui.enabled` failing closed),
  controller mappings and DTO serialization for every `/bootui/api/**` endpoint,
  Config controller HTTP CRUD with masking modes and restart warnings, logger level
  mutation/clear, broader secret masking coverage, and panel edge cases
  (Data, Scheduled, HTTP Probe, Log Tail, Profile Diff, Security, Metrics, DevTools,
  Dev Services, Memory).
- `CHANGELOG.md` and a sample-app walkthrough at `bootui-sample-app/README.md`.
- Spring Cache panel for cache managers, known caches, safe local sizes, Micrometer cache metrics,
  cache annotations, and confirmation-gated clear actions.
- Embedded OTLP/HTTP trace receiver at `/bootui/api/otlp/v1/traces`, plus Traces and AI Usage panels
  for local trace waterfalls, Spring AI observations, token usage, tool calls, and bounded in-memory
  telemetry.
- Optional panel availability metadata so the sidebar can dim panels whose backing classpath,
  Actuator endpoint, or local infrastructure is unavailable.
- GitHub project links in the UI and sample-app AI prompt helpers for exercising telemetry locally.

### Changed

- Documentation reconciled with the implemented `AUTO|ON|OFF` activation model,
  persisted runtime overrides, plain-JavaScript Vue 3 frontend, and the full
  visible panel set as supported alpha functionality.
- Dev Services, Vulnerabilities, Traces, and AI Usage now have stronger empty/disabled states,
  bounded data handling, and focused Playwright coverage.
- Vulnerability scan results are retained in memory after an explicit scan so the panel can keep
  showing the latest local results.
- Sample app PostgreSQL JDBC driver updated to 42.7.11.
- Repository formatting checks and release documentation now cover the alpha release workflow,
  README version synchronization, and Maven Central signing constraints.

### Fixed

- Corrected the sample Redis service port mapping used by the Cache panel tests.
- Fixed GitHub code scanning workflow permissions and an incomplete string escaping/encoding finding.

### Security

- Enabled CSRF protection in the sample app.

## [0.1.0-alpha.4] - 2026

First successful Maven Central publication of the alpha line.

### Fixed

- Source-less modules (`bootui-ui`, `bootui-spring-boot-starter`) now attach an
  empty `javadoc.jar` at the `package` phase so the release-profile `gpg-sign`
  binding (running at `verify`) signs it. Without this, Sonatype Central
  rejected the deployment.

## [0.1.0-alpha.3] - 2026

Release attempt blocked by missing javadoc signatures (see alpha.4 fix).

## [0.1.0-alpha.2] - 2026

Initial public alpha attempt. Sonatype Central deployment failed; subsequent
attempts re-used the GAV coordinate and required version bumps because
Sonatype consumes a coordinate even on failure.

## [0.1.0-alpha.1] - 2026

First tagged BootUI alpha. Highlights of the harden-all-visible-panels scope:

### Added

- Spring Boot 4 starter (`bootui-spring-boot-starter`) and auto-configuration
  (`bootui-autoconfigure`) packaged with a Vue 3 / Vite UI shell served from
  `/bootui` and `/bootui/api/**`.
- `bootui.enabled=AUTO|ON|OFF` activation model with profile-based enablement
  (`dev`, `local`) and disablement (`prod`, `production`); fail-closed on
  invalid values; auto-activation when Spring Boot DevTools is on the classpath.
- Localhost-only safety filter with explicit `bootui.allow-non-localhost` opt-out.
- Secret-masking for browser-visible property names and values, with three
  exposure modes: `MASKED` (default), `METADATA_ONLY`, and `FULL`.
- Runtime configuration overrides persisted to
  `.bootui/application-bootui.properties` and applied at high precedence on the
  next start; restart/rebind caveats surfaced for every override mutation.
- Internal Actuator bridge that returns stable BootUI DTOs even when the
  underlying Actuator endpoint or Spring module is absent.
- Panels: Overview, Beans, Conditions, Configuration, Mappings, Health, Loggers,
  Startup Timeline, JVM Memory (with suggested options), Spring Data,
  Scheduled Tasks, HTTP Probe (loopback-only), Log Tail, Profile Diff,
  Spring Security, Micrometer Metrics, Dependency inventory + OSV vulnerability
  scan, Spring Boot DevTools reload/restart, and Dev Services for Docker Compose,
  Testcontainers beans, and service connection metadata.
- Sample application (`bootui-sample-app`) and a Playwright end-to-end suite
  exercising every visible browser route.

### Notes

- Spring Boot 3.x support, Gradle plugin, CLI, extension SPI, hosted features,
  request history, distributed tracing, multi-service orchestration, and live
  Docker Compose lifecycle control are intentionally out of scope for the alpha.

[1.5.0]: https://github.com/jdubois/boot-ui/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/jdubois/boot-ui/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/jdubois/boot-ui/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/jdubois/boot-ui/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/jdubois/boot-ui/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/jdubois/boot-ui/compare/v0.5.1...v1.0.0
[0.5.1]: https://github.com/jdubois/boot-ui/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/jdubois/boot-ui/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/jdubois/boot-ui/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/jdubois/boot-ui/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/jdubois/boot-ui/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/jdubois/boot-ui/compare/v0.1.0-alpha.5...v0.1.0
[0.1.0-alpha.5]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.5
[0.1.0-alpha.4]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.4
[0.1.0-alpha.3]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.3
[0.1.0-alpha.2]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.2
[0.1.0-alpha.1]: https://github.com/jdubois/boot-ui/releases/tag/v0.1.0-alpha.1
