# BootUI Implementation Plan

## 1. Strategy

Build BootUI as a **Spring Boot 4 starter** first, not as a standalone application or platform. The first release should
prove the core value: adding one starter dependency to a Spring Boot 4 application gives a safe local UI that explains
the running app.

The MVP priorities remain:

1. Safety.
2. Easy installation.
3. Useful runtime explanations.
4. A polished but simple UI.
5. Testable architecture.

## 2. Current implementation state

The project has moved beyond the original skeleton and the initial MVP panel set. The repository currently includes:

- Maven multi-module structure:
  - `bootui-core`
  - `bootui-autoconfigure`
  - `bootui-spring-boot-starter`
  - `bootui-ui`
  - `bootui-sample-app`
- Java 25 and Spring Boot 4.x baseline.
- CI workflows and project metadata.
- Core BootUI runtime:
  - activation properties and activation condition
  - servlet auto-configuration registered through Spring Boot auto-configuration imports
  - localhost-only safety filter
  - startup banner
  - `/bootui` UI forwarding
  - config override property source, environment post-processor, file store, and runtime service
  - secret masking helper and value exposure modes
- Stable BootUI DTOs in `bootui-core` for browser-facing API responses.
- `/bootui/api/**` controllers for:
  - overview
  - beans
  - conditions
  - configuration properties and overrides
  - mappings
  - health
  - loggers
  - startup
  - JVM memory
  - Spring Data repositories
  - Spring Cache managers, metrics, annotations, and confirmed clear controls
  - scheduled tasks
  - HTTP probe
  - Pentesting
  - log tail
  - profile diff
  - Spring Security
  - Micrometer metrics
  - dependency inventory and OSV vulnerability scan
  - DevTools reload/restart
  - Copilot session dashboard, session explorer, and live SSE stream
  - Claude Code project-log dashboard, session explorer, and live refresh stream
  - OTLP traces receiver (`/bootui/api/otlp/v1/traces`), Traces panel API, and AI Usage panel API
- Vue 3 UI shell with routes for:
  - Overview
  - Startup Timeline
  - Memory
  - Health
  - Metrics
  - Conditions
  - Beans
  - Mappings
  - Configuration
  - Profile Diff
  - Loggers
  - Log Tail
  - Traces
  - HTTP Probe
  - Pentesting
  - Copilot
  - Claude Code
  - DevTools
  - Dev Services
  - Scheduled Tasks
  - Data
  - Cache
  - AI Usage
  - Security
  - Vulnerabilities
- Maven-integrated frontend build that downloads Node/npm, builds the Vite app, and packages assets into
  `META-INF/resources/bootui`.
- Backend tests covering activation, auto-configuration activation cases, localhost filtering, config override
  persistence, override property sources, override file storage, environment post-processing, config metadata loading,
  selected web controllers, missing Actuator behavior, and sample-app integration.
- Playwright end-to-end tests in `bootui-sample-app/e2e` covering the sample API, every visible BootUI route including
  Traces, AI Usage, and Claude Code, and focused flows for panels such as Metrics, Redis-backed Cache, Vulnerabilities,
  DevTools, and Dev Services.

## 3. Milestone status

| Milestone                                | Status                                   | Notes                                                                                                                                                                                                                                                   |
| ---------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0. Project foundation                    | Done                                     | Multi-module build, Java/Spring baseline, sample app, docs, and CI exist.                                                                                                                                                                               |
| 1. Auto-configuration and safety         | Implemented and covered                  | Activation, auto-configuration, localhost filter, banner, and overview API exist. Activation, localhost behavior, and fail-closed edge cases have focused tests.                                                                                        |
| 2. Static UI shell                       | Implemented and smoke-tested             | Vue shell, Maven frontend build, classpath packaging, and routes exist; Playwright verifies sidebar navigation across every visible section.                                                                                                            |
| 3. Actuator bridge                       | Implemented and covered                  | Stable BootUI DTO endpoints exist for every visible panel; missing-Actuator behavior has explicit empty-DTO coverage.                                                                                                                                   |
| 4. Beans and Conditions panels           | Implemented and covered by sample e2e    | API and UI panels exist, with bounded server-side filtering and paging for large-app edge cases.                                                                                                                                                        |
| 5. Config, Mappings, Health, and Loggers | Implemented and covered by sample e2e    | Runtime config overrides, secret masking, mappings, health, and logger controls exist. Config override plumbing has focused backend tests.                                                                                                              |
| 6. Post-MVP diagnostic panels            | Implemented and covered                  | Startup, Memory, Spring Data, Spring Cache, Scheduled Tasks, HTTP Probe, Pentesting, Log Tail, Profile Diff, Security, Metrics, Vulnerabilities, DevTools, and Dev Services panels have API/UI slices plus backend edge-case tests and focused Playwright coverage. |
| 7. Documentation and release hardening   | Released as `0.1.0`; ongoing             | User-facing docs are reconciled with current behavior, the changelog is current through `0.1.0`, and the CI-equivalent build plus sample-app Playwright suite passed on 2026-05-29. Keep release checks current as later work lands.                   |
| 8. In-app OTLP sink + Traces + AI Usage  | Delivered for `0.1.0`                    | Adds an OTLP/HTTP receiver on `/bootui/api/otlp/v1/traces`, a Traces waterfall panel, an AI Usage panel for Spring AI observations, and a sample-app Ollama service started via `compose.yaml`.                                                         |

## 4. Current status and next work

### 4.1 Backend test coverage status

The harden-all-visible-panels release test expansion is complete. Coverage now includes:

1. `BootUiProperties` binding defaults for public properties, including value exposure, overrides file, and endpoint
   timeout settings.
2. Activation edge cases, including devtools-based activation, custom disabled profiles, and invalid `bootui.enabled`
   values failing closed.
3. Controller mappings and DTO serialization for every `/bootui/api/**` endpoint, including DevTools and Dev Services.
4. Missing or unavailable Actuator endpoints returning stable empty DTOs.
5. Config controller HTTP create, update, and delete behavior; persisted display state; masking; `METADATA_ONLY` /
   `FULL` value exposure; and restart-warning messages.
6. Logger level mutation and clearing through HTTP.
7. Secret masking for browser-visible property names and values.
8. Edge-case coverage for Spring Data, Spring Cache, Scheduled Tasks, HTTP Probe, Log Tail, Profile Diff masking, Spring
   Security, Micrometer Metrics, DevTools, Dev Services, JVM Memory, and OTLP trace ingestion.
9. Dev Services edge-case hardening (2026-05-28): prototype-scoped Testcontainers beans skipped with a warning,
   stopped container status reported as `STOPPED`, null `getLogs()` result yields an empty string, restart exception
   yields HTTP 500 with a non-null message (bug fix), `METADATA_ONLY` exposure hides connection detail values, and
   abstract bean definitions are silently excluded (documents actual Spring `getType` behavior). Testcontainers added
   as a `test`-scope dependency in `bootui-autoconfigure` so the `discoverTestcontainers` classpath guard does not
   short-circuit unit tests.
10. Large-app rendering hardening (2026-05-28): high-cardinality Beans, Conditions, Mappings, Configuration, and
    Loggers lists now render progressively instead of mounting every filtered row at once. Filters still search the full
    in-memory result set, and the Configuration override property picker bounds its datalist suggestions while narrowing
    against the full metadata catalog as the user types.
11. Frontend unit test setup (2026-05-28): Vitest, Vue Test Utils, and jsdom are wired into the Vite frontend. The
    first tests cover the progressive rendering composable and footer component, and Maven runs `npm run test` during the
    `test` phase unless `-DskipTests` is set.
12. Server-side filtering and pagination (2026-05-28): high-cardinality Beans, Conditions, Mappings, Configuration,
    and Loggers reports now accept bounded query parameters and return page metadata. The Vue panels request server-side
    pages instead of fetching every row up front, and the Mappings panel uses a stable BootUI DTO while the legacy raw
    Actuator descriptor remains available at `/bootui/api/mappings`.

Future backend test work should be incremental and tied to new or changed behavior.

### 4.2 UI and product parity status

The visible-route parity check is current. The sample-app Playwright suite covers the grouped navigation order:

1. Overview.
2. Runtime: Health, Metrics, Memory, Startup Timeline.
3. Configuration: Configuration, Profile Diff, Loggers, Beans, Conditions, Mappings.
4. Services: Scheduled Tasks, Data, Cache, Security, AI Usage.
5. Diagnostics: Traces, Log Tail, HTTP Probe, Pentesting, Vulnerabilities.
6. Developer tools: DevTools, Dev Services, Copilot, Claude Code.
7. Disabled / unavailable grouping for unavailable non-overview panels.

Startup, Memory, Spring Data, Spring Cache, HTTP Probe, Pentesting, Profile Diff, Log Tail, Traces, AI Usage, Copilot,
Claude Code, Scheduled Tasks, Security, Metrics, Vulnerabilities, DevTools, and Dev Services are implemented,
documented, covered by sample-app Playwright tests, and part of the supported `0.1.0` surface. Any new visible route or
browser-facing behavior should update the router, README feature table, `docs/FEATURES.md`, and Playwright coverage
together.

The large-app hardening pass is complete for the most obvious list and payload hotspots: Beans, Conditions,
Mappings, Configuration, and Loggers request bounded server-side pages with filter parameters and render only the rows
already returned by the API.

Optional UI gating is complete for the visible route set (2026-05-28). `/bootui/api/panels` reports classpath,
endpoint, and configuration availability for each sidebar route. The Vue shell keeps routes reachable, moves unavailable
non-overview panels into a collapsed Disabled / unavailable group, dims unavailable links, exposes the reason through
accessible labels/tooltips, and shows a page-level unavailable-state banner when a dimmed panel is opened.

Frontend unit test setup is complete for `0.1.0`. The Vue app now uses Vitest with Vue Test Utils and jsdom for fast
component/composable coverage that complements the browser-level Playwright suite.

### 4.3 User-facing documentation status

The `0.1.0` documentation is current. Keep these user-facing topics current as behavior changes:

1. Installation.
2. Activation rules, including `bootui.enabled=AUTO|ON|OFF`, enabled profiles, disabled profiles, and devtools
   activation.
3. Configuration properties.
4. Safety model and localhost-only behavior, including explicit opt-outs.
5. Secret masking and value exposure behavior.
6. Runtime configuration override behavior, persistence to `.bootui/application-bootui.properties`, and restart/rebind
   caveats.
7. Actuator requirements and degraded behavior when endpoints are unavailable.
8. Panel-by-panel feature guide for every visible route, including Metrics, Traces, AI Usage, Copilot, Claude Code,
   DevTools, Dev Services, and the JVM memory panel with its suggested JVM options.
9. Troubleshooting.
10. Sample app walkthrough.
11. Release notes.

Completed reconciliation points:

- `bootui.enabled` uses `AUTO|ON|OFF`.
- Runtime config overrides persist to the BootUI overrides file by default.
- The frontend is plain JavaScript Vue 3.
- Startup Timeline, Memory, Spring Data, Spring Cache, HTTP Probe, Pentesting, Profile Diff, Log Tail, Traces, AI Usage,
  Copilot, Claude Code, Scheduled Tasks, Security, Metrics, DevTools, Dev Services, and Vulnerabilities are implemented
  `0.1.0` surfaces, not deferred ideas.
- Dev Services / Docker Compose / Testcontainers behavior is documented: Docker Compose entries are startup snapshots,
  bean-backed Testcontainers services can expose bounded logs, and restart is disabled unless
  `bootui.dev-services.restart-enabled=true`.
- Maven Central publishing has completed for `0.1.0`; the release profile signs and stages artifacts through the Sonatype
  Central Publishing plugin, and release notes are current through `0.1.0`.

### 4.4 Release readiness validation

Last validation completed on 2026-05-29:

- `./mvnw -B -ntp clean install` passed.
- The sample-app Playwright suite passed all 57 tests.
- The working tree remained clean after validation.

Before each release, rerun the CI-equivalent build:

```bash
./mvnw -B -ntp clean install
```

Run the sample app:

```bash
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev
```

Manual smoke checks:

1. Open `http://localhost:8080/bootui`.
2. Confirm all visible panels load.
3. Confirm `/bootui/api/overview` and the other visible API endpoints return stable DTOs.
4. Confirm secret-like values remain masked by default.
5. Confirm `prod` and `production` profiles disable BootUI unless explicitly forced on.
6. Confirm non-local requests are rejected by default.
7. Confirm generated frontend assets are packaged in the UI artifact.
8. Run the Playwright end-to-end suite in `bootui-sample-app/e2e`.
9. Confirm no generated build output or secrets are accidentally committed.

## 5. `0.1.0` release scope

The codebase contains more than the original v0.1 MVP. The released `0.1.0` keeps the strategy selected for the alpha
line:

| Strategy                       | Scope                                                                                                                                         | Trade-off                                                                                                                             |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Harden all visible panels      | Ship every current route as supported `0.1.x` functionality.                                                                                 | Delivered for the alpha line and promoted to `0.1.0`; keep focused backend tests, docs, and release validation current.                |
| Mark newer panels experimental | Keep all routes visible but clearly label Data, Startup, Memory, Scheduled, HTTP Probe, Log Tail, Profile Diff, and Security as experimental. | Faster release, but docs must set expectations.                                                                                       |
| Hide unfinished panels         | Only expose the original MVP routes plus any fully hardened additions.                                                                        | Safest smaller surface, but requires UI gating work.                                                                                  |

Delivered release stance: **harden all visible panels**.

Included in the original MVP surface:

- Development-only activation.
- Localhost-only protection.
- Overview panel.
- Beans Explorer.
- Conditions Explorer.
- Configuration Explorer.
  - metadata descriptions and defaults where available.
  - known Spring Boot property suggestions when creating overrides.
  - runtime overrides persisted through BootUI's overrides file.
- Mappings Browser.
- Health Dashboard.
- Logger Controls.
- Static UI shell packaged into a Java artifact.
- Sample app.
- Documentation.
- Spring Boot 4 target only.

Already implemented beyond the original MVP surface:

- Startup Timeline.
- JVM memory panel.
- Spring Data repository explorer.
- Spring Cache panel with metrics, annotation discovery, and confirmed clear actions.
- Dev Services panel for Docker Compose snapshots, Testcontainers beans, and Spring Boot service connection metadata.
- Scheduled task inspector.
- HTTP probe panel.
- Live log tail.
- Profile diff.
- Spring Security panel.
- Micrometer metrics browser with live values.
- DevTools reload/restart controls.
- In-app OTLP/HTTP traces receiver, Traces panel, and AI Usage panel.
- Copilot panel for sanitized GitHub Copilot CLI session dashboarding and live SSE updates.
- Claude Code panel for sanitized local Claude Code project-log dashboarding and live refresh updates.

Added in the late alpha line and included in `0.1.0`:

- **In-app OTLP/HTTP receiver**. BootUI exposes `POST /bootui/api/otlp/v1/traces` and accepts protobuf-encoded
  `ExportTraceServiceRequest` payloads from the host JVM (and from any cooperating local service that points its OTLP
  exporter there). Traces are buffered in memory only; nothing is forwarded.
- **Traces panel**. Lists recent traces with a service swim-lane chip strip and per-trace waterfall, including span
  attributes and events.
- **AI Usage panel**. Aggregates Spring AI's `gen_ai.client.operation`, `spring.ai.tool`, `db.vector.client.operation`,
  and embedding spans. Shows recent completions, token usage, tool calls, and (when content capture is enabled) a
  conversation drawer with messages.
- **Multi-service dev orchestration (sink-only)**. Multiple cooperating local processes can each export OTLP to BootUI
  and their spans show up in the same waterfall. This is the dev-time equivalent of Aspire's distributed-tracing view;
  BootUI does not run, schedule, or restart the other services.

Added for the final `0.1.0` release after `0.1.0-alpha.5`:

- **Copilot panel**. Reads the session directories and `events.jsonl` files that GitHub Copilot CLI writes under
  `~/.copilot/session-state/` and presents a sanitized activity dashboard: session count, total events, failures,
  24-hour and 7-day activity charts, event category mix, top tools, and model usage. The session explorer lets you
  drill into individual sessions with a turn-by-turn story, recent events, and failure list, while a configurable
  parsed-session cap bounds heap usage for large local histories. Raw event JSON is opt-in and local-only. Live updates
  are pushed via SSE. Inspired by
  [copilot-mission-control](https://github.com/DanWahlin/copilot-mission-control).
- **Claude Code panel**. Reads Claude Code JSONL project logs under `~/.claude/projects/` and presents the same
  sanitized session dashboard shape for local Claude Code usage. Normal responses only expose tool names, categories,
  timestamps, model names, coarse status, and failure state; prompts, assistant text, tool inputs, command output, file
  contents, and tool-result content stay out of the browser payload. A configurable parsed-session cap bounds heap usage
  for large project-log directories. Raw JSONL reveal is disabled by default because Claude Code logs can inline
  sensitive local content, and the panel refreshes through bounded polling to handle per-project subdirectories.

Still excluded from the already released `0.1.0`:

- Live Docker Compose lifecycle control beyond the existing snapshot view.
- Production-grade tracing or APM replacement. The OTLP receiver is dev-only and bounded in memory.
- Request history.
- Service Extension SPI and modular service integrations, starting with an AI Usage panel refactor that adds
  LangChain4j OpenTelemetry support.
- Gradle plugin.
- Hosted features.
- Spring Boot 3.x compatibility.

## 6. Completed final-release hardening candidates

The candidate hardening items originally tracked for a later `v0.2` were pulled into the final `0.1.0` release:

- ~~Dev Services hardening for Docker Compose/Testcontainers edge cases.~~ Done (2026-05-28, PR #88).
- ~~Large-app edge-case hardening for current panels as real-world usage reveals gaps.~~ Done (2026-05-28): the first
  pass protects high-cardinality browser lists with progressive rendering and a bounded Configuration metadata datalist.
- ~~Optional UI gating based on classpath/endpoint availability so irrelevant panels can be hidden or clearly disabled.~~
  Done (2026-05-28): the panel availability API drives sidebar dimming, accessible unavailable labels/tooltips, and an
  active-panel reason banner while keeping routes navigable.
- ~~Frontend test setup if the project decides to add Vitest or another UI test runner.~~ Done (2026-05-28): Vitest,
  Vue Test Utils, and jsdom run through `npm run test` and the Maven `test` phase, with seed coverage for progressive
  list rendering.
- ~~Server-side filtering or pagination for Actuator-backed APIs if real-world large apps expose response-size
  bottlenecks that browser-side progressive rendering cannot solve.~~ Done (2026-05-28): Beans, Conditions, Mappings,
  Configuration, and Loggers now expose bounded server-side pages with filter-aware counts and page metadata.

## 7. Next release candidate: AI Usage service extension and Service Extension SPI

The next preferred workstream is to refactor the current **AI Usage** panel into the first BootUI service extension and
extend it beyond Spring AI to support LangChain4j. LangChain4j can emit OpenTelemetry spans, so BootUI
should normalize those spans through the same in-app OTLP path used for Spring AI while keeping the released `0.1.0`
Spring AI behavior intact. This should establish the Service Extension SPI before the remaining Services menu entries
move behind it.

Scope:

- Introduce a Service Extension SPI that lets a module contribute a service panel, route metadata, availability status,
  stable DTO endpoints, documentation metadata, and optional frontend assets.
- Extract **AI Usage** into `services/bootui-service-ai-usage` first. Preserve the existing Spring AI observation
  aggregation and add LangChain4j OpenTelemetry span support to the same panel and DTO shape where possible.
- Move the remaining current Services menu entries behind that SPI over time: Scheduled Tasks, Data, Cache, and
  Security.
- Add HikariCP connection-pool visibility as the first database-pool slice after the AI Usage extraction, plus
  Elasticsearch, Flyway/Liquibase, and MongoDB. The HikariCP work is intentionally implementation-specific for this
  release; generic JDBC/R2DBC pool support can be revisited after the Hikari UX and DTO shape are proven.
  - HikariCP connection-pool visibility shipped (read-only **Connection Pools** panel with a live saturation chart,
    masked JDBC metadata, and fail-closed behavior). It currently lives in `bootui-autoconfigure` and will move behind
    the `services/` SPI with the other Services entries. Elasticsearch, Flyway/Liquibase, and MongoDB remain pending.
- Put all first-party service extensions under a top-level `services/` Maven directory, with one Maven submodule per
  service, for example:
  - `services/bootui-service-scheduled`
  - `services/bootui-service-data`
  - `services/bootui-service-hikari`
  - `services/bootui-service-cache`
  - `services/bootui-service-security`
  - `services/bootui-service-ai-usage`
  - `services/bootui-service-elasticsearch`
  - `services/bootui-service-migrations`
  - `services/bootui-service-mongodb`
- Preserve the default starter's current batteries-included developer experience unless a separate lean starter or
  opt-in dependency model is explicitly introduced.

Design constraints:

- Service extensions remain local-only and fail closed when their required classes, Actuator endpoints, clients, or
  configuration are unavailable.
- Browser-facing service data still uses stable BootUI DTOs and must route any sensitive property names or values
  through the existing masking/value-exposure model.
- AI Usage support should stay OTLP-backed and framework-neutral at the DTO boundary. The service should recognize
  Spring AI and LangChain4j span conventions, preserve source/framework labels when they are known, aggregate chat,
  embedding, tool, token, latency, error, and vector-store signals when present, and degrade gracefully when a framework
  omits optional attributes. Prompt/response content must remain opt-in through captured trace attributes and continue
  to follow the existing masking/value-exposure rules.
- HikariCP support should be read-only at first and exposed as its own **Connection Pools** panel in the Services group,
  placed after **Data** and before **Cache**. It should discover `HikariDataSource` beans only, fail closed when HikariCP
  is absent, and show pool identity, masked JDBC connection metadata, current active/idle/total/pending counts, min/max
  sizing, timeout/lifetime settings, and unavailable reasons for closed or inaccessible pools. The panel should include a
  local live chart like the Metrics panel, polling bounded snapshots for active, idle, total, and pending connections so
  users can see saturation trends without leaving BootUI. It should not execute SQL, borrow connections, resize pools, or
  attempt generic JDBC/R2DBC abstraction in the first iteration.
- Elasticsearch support should be read-only at first: connection/status, cluster metadata where safely available,
  index inventory, health, and bounded sample diagnostics. Destructive index or document operations stay out of scope
  unless they are later designed with explicit confirmation and safety controls.
- Flyway/Liquibase support should be read-only at first: configured migration tools, current schema version, applied and
  pending migrations where available, validation/checksum state, and clear degraded states when either tool is absent.
- MongoDB support should be read-only at first: connection/status, database and collection inventory, index metadata,
  document counts, and bounded sample diagnostics without exposing document contents by default.
- Tests, docs, router ordering, `/bootui/api/panels` availability, and sample-app Playwright coverage should move in
  sync with each extracted or newly added service extension.

## 8. Technical decisions

### 8.1 Java and Spring Boot

Use Java 25 and Spring Boot 4.x for the initial codebase.

Reason:

- Aligns with the current Spring Boot forward-looking ecosystem.
- Keeps the project modern.
- Avoids spending v0.1 effort on older compatibility.

Risk:

- Spring Boot 3.x users are a large audience, but they are not the target of v0.1.

Mitigation:

- Keep abstractions clean enough to add a Boot 3.5 compatibility branch later.

### 8.2 Build tool

Use Maven.

Reason:

- Spring Boot library publishing and multi-module builds are straightforward.
- The project can add Gradle examples later.

### 8.3 Frontend

Use Vue 3 and Vite for the UI. The current implementation is plain JavaScript rather than TypeScript.

Reason:

- Fast development.
- Small enough for this UI.
- Good component model for data-heavy panels.
- Matches the local Spring Boot developer-console use case.

Packaging requirements:

- The Vue build must be wired into Maven.
- A root Maven build must run the frontend build automatically.
- Built assets must be included in the BootUI Java artifact and served from the classpath.
- Users of `bootui-spring-boot-starter` must not need Node.js, npm, or a separate frontend build in their own
  applications.

### 8.4 Data access

Use BootUI internal DTOs rather than binding the frontend directly to raw Actuator JSON.

Reason:

- Shields UI from Spring Boot response shape changes.
- Allows better explanations and safety masking.
- Allows reduced functionality when some endpoints are unavailable.

### 8.5 Actuator and in-process integration

Prefer internal endpoint invokers or Spring-managed metadata where practical.

Reason:

- Avoids requiring users to expose all Actuator endpoints over HTTP.
- Reduces security friction.
- Keeps BootUI local and internal.
- Lets panels such as Spring Data, Scheduled Tasks, and Security remain read-only metadata views instead of invoking
  application behavior, while keeping mutating controls such as Spring Cache clear actions explicit and
  confirmation-gated.

## 9. Suggested next steps

`0.1.0` has been released to Maven Central. The final version bump, release commit, tag, Maven module versions, and README
install snippet were kept synchronized through the `Prepare Release` workflow; keep using that workflow for future version
bumps.

Backend test coverage for the harden-all-visible-panels scope has been completed: `BootUiPropertiesTests`,
`BootUiActivationConditionAdditionalTests`, controller mapping and DTO serialization tests for every `/bootui/api/**`
endpoint, `AdditionalMissingActuatorEndpointsTests`, `ConfigControllerHttpCrudTests`, `ConfigControllerMaskingTests`,
`LoggersControllerMutationTests`, `CacheControllerTests`, `SecretMaskerBrowserVisibleSurfaceTests`, and edge-case tests
for Scheduled, HTTP Probe, Log Tail, Profile Diff masking, Security, Memory, and OTLP trace ingestion.

Dev Services edge-case hardening was completed on 2026-05-28 (PR #88). Large-app browser-rendering
hardening was completed next for high-cardinality lists. Optional UI gating was completed after that with the
`/bootui/api/panels` availability report, sidebar dimming, accessible unavailable labels/tooltips, and an active-panel
reason banner. Frontend unit test setup followed with Vitest, Vue Test Utils, jsdom, and Maven test-phase wiring.
Server-side filtering and pagination completed the final-release hardening set for high-cardinality list payloads.

User-facing documentation is reconciled with current behavior: `README.md`, `docs/FEATURES.md`, and
`docs/SPECIFICATION.md` use the `AUTO|ON|OFF` activation model, the persisted-overrides behavior, the plain-JavaScript
Vue 3 frontend, and the full visible panel set. The repository now ships a `CHANGELOG.md` (release notes through
`0.1.0`) and a sample-app walkthrough at `bootui-sample-app/README.md`.

On 2026-05-29, `./mvnw -B -ntp clean install` and the Playwright suite under `bootui-sample-app/e2e` both passed on the
current branch. The Playwright run covered all 57 sample-app browser tests.

The listed final-release hardening set is complete, `0.1.0` is released, and release validation was refreshed on
2026-05-29. The next workstream should be the AI Usage service extension and Service Extension SPI in §7: extract
AI Usage into `services/bootui-service-ai-usage`, add LangChain4j OpenTelemetry support alongside Spring AI, then move
the remaining Services menu entries behind first-party modules under `services/`. Keep tests, docs, router ordering,
`/bootui/api/panels` availability, sample-app Playwright coverage, and release validation in sync as behavior changes
land.

## 10. Validation checklist

Last completed on 2026-05-29:

- [x] `./mvnw -B -ntp clean install` passes.
- [x] The UI build is executed automatically by Maven.
- [x] Sample app starts with the `dev` profile through the Playwright web server.
- [x] `/bootui` loads.
- [x] `/bootui/api/overview` returns data.
- [x] Every visible panel loads and handles empty/unavailable data.
- [x] Configuration panel works and masks secrets.
- [x] Runtime config overrides persist through the BootUI overrides file and warn about restart/rebind caveats.
- [x] Logger changes work.
- [x] DevTools controls show unavailable states when Spring Boot DevTools is absent.
- [x] Newer diagnostic panels have release-grade coverage/docs for `0.1.0`.
- [x] BootUI is disabled with `prod` and `production` profiles unless `bootui.enabled=ON`.
- [x] Non-local requests are rejected by default.
- [x] Documentation matches actual behavior.

Before future releases, rerun this checklist after any release-facing code or documentation change.

## 11. Risks

| Risk                                 | Impact | Mitigation                                                                                                                                                         |
| ------------------------------------ | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Accidentally exposing sensitive data | High   | Localhost-only, dev-only activation, secret masking, value exposure controls, production fail-closed defaults, and focused tests for every panel surfacing values. |
| Regressing visible panel hardening   | High   | Keep the current route set as the supported `0.1.0` surface, and require focused tests, docs, and release validation for release-facing changes.                   |
| Actuator endpoints unavailable       | Medium | Internal bridge, stable empty DTOs, graceful UI states, setup guidance.                                                                                            |
| Optional Spring modules unavailable  | Medium | Classpath gating, empty DTOs, and clear UI empty states for Spring Data, Spring Cache, Security, scheduling, and startup data.                                     |
| Duplicating Spring Boot Admin        | Medium | Stay focused on embedded local single-app developer experience.                                                                                                    |
| Frontend bundle too large            | Medium | Avoid heavy dependencies and lazy-load later panels if needed.                                                                                                     |
| Boot 4 adoption slower than expected | Medium | Keep compatibility seams for a later Boot 3.5 branch.                                                                                                              |
| Scope creep                          | High   | Treat the current route set as the maximum near-term surface and move new ideas to later releases.                                                                 |
