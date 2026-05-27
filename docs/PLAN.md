# BootUI Implementation Plan

## 1. Strategy

Build BootUI as a **Spring Boot 4 starter** first, not as a standalone application or platform. The first release should prove the core value: adding one starter dependency to a Spring Boot 4 application gives a safe local UI that explains the running app.

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
  - log tail
  - profile diff
  - Spring Security
  - Micrometer metrics
  - dependency inventory and OSV vulnerability scan
  - DevTools reload/restart
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
  - AI Usage
  - HTTP Probe
  - DevTools
  - Dev Services
  - Scheduled Tasks
  - Data
  - Cache
  - Security
  - Vulnerabilities
- Maven-integrated frontend build that downloads Node/npm, builds the Vite app, and packages assets into `META-INF/resources/bootui`.
- Backend tests covering activation, auto-configuration activation cases, localhost filtering, config override persistence, override property sources, override file storage, environment post-processing, config metadata loading, selected web controllers, missing Actuator behavior, and sample-app integration.
- Playwright end-to-end tests in `bootui-sample-app/e2e` covering the sample API, every visible BootUI route including Traces and AI Usage, and focused flows for panels such as Metrics, Redis-backed Cache, Vulnerabilities, DevTools, and Dev Services.

## 3. Milestone status

| Milestone | Status | Notes |
|---|---|---|
| 0. Project foundation | Done | Multi-module build, Java/Spring baseline, sample app, docs, and CI exist. |
| 1. Auto-configuration and safety | Implemented and covered | Activation, auto-configuration, localhost filter, banner, and overview API exist. Activation, localhost behavior, and fail-closed edge cases have focused tests. |
| 2. Static UI shell | Implemented and smoke-tested | Vue shell, Maven frontend build, classpath packaging, and routes exist; Playwright verifies sidebar navigation across every visible section. |
| 3. Actuator bridge | Implemented and covered | Stable BootUI DTO endpoints exist for every visible panel; missing-Actuator behavior has explicit empty-DTO coverage. |
| 4. Beans and Conditions panels | Implemented and covered by sample e2e | API and UI panels exist; large-app edge cases should be handled incrementally as v0.2 hardening work. |
| 5. Config, Mappings, Health, and Loggers | Implemented and covered by sample e2e | Runtime config overrides, secret masking, mappings, health, and logger controls exist. Config override plumbing has focused backend tests. |
| 6. Post-MVP diagnostic panels | Implemented and covered | Startup, Memory, Spring Data, Spring Cache, Scheduled Tasks, HTTP Probe, Log Tail, Profile Diff, Security, Metrics, Vulnerabilities, DevTools, and Dev Services panels have API/UI slices plus backend edge-case tests and focused Playwright coverage. |
| 7. Documentation and release hardening | Validated for the current alpha; ongoing | User-facing docs are reconciled with current behavior, and the CI-equivalent build plus sample-app Playwright suite passed on 2026-05-27. Keep release checks current as v0.2 work lands. |
| 8. In-app OTLP sink + Traces + AI Usage | In progress | Adds an OTLP/HTTP receiver on `/bootui/api/otlp/v1/traces`, a Traces waterfall panel, an AI Usage panel for Spring AI observations, and a sample-app Ollama service started via `compose.yaml`. |

## 4. Current status and next work

### 4.1 Backend test coverage status

The harden-all-visible-panels alpha test expansion is complete. Coverage now includes:

1. `BootUiProperties` binding defaults for public properties, including value exposure, overrides file, and endpoint timeout settings.
2. Activation edge cases, including devtools-based activation, custom disabled profiles, and invalid `bootui.enabled` values failing closed.
3. Controller mappings and DTO serialization for every `/bootui/api/**` endpoint, including DevTools and Dev Services.
4. Missing or unavailable Actuator endpoints returning stable empty DTOs.
5. Config controller HTTP create, update, and delete behavior; persisted display state; masking; `METADATA_ONLY` / `FULL` value exposure; and restart-warning messages.
6. Logger level mutation and clearing through HTTP.
7. Secret masking for browser-visible property names and values.
8. Edge-case coverage for Spring Data, Spring Cache, Scheduled Tasks, HTTP Probe, Log Tail, Profile Diff masking, Spring Security, Micrometer Metrics, DevTools, Dev Services, JVM Memory, and OTLP trace ingestion.

Future backend test work should be incremental and tied to new or changed behavior, especially v0.2 candidates such as Dev Services edge cases, WebFlux support, source-file links, and optional UI gating.

### 4.2 UI and product parity status

The visible-route parity check is current. On 2026-05-27, the sample-app Playwright suite passed all 43 tests, covering:

1. Overview.
2. Startup Timeline.
3. Memory.
4. Health.
5. Metrics.
6. Conditions.
7. Beans.
8. Mappings.
9. Configuration.
10. Profile Diff.
11. Loggers.
12. Log Tail.
13. Traces.
14. AI Usage.
15. HTTP Probe.
16. DevTools.
17. Dev Services.
18. Scheduled Tasks.
19. Data.
20. Cache.
21. Security.
22. Vulnerabilities.

Startup, Memory, Spring Data, Spring Cache, HTTP Probe, Profile Diff, Log Tail, Traces, AI Usage, Scheduled Tasks, Security, Metrics, Vulnerabilities, DevTools, and Dev Services are implemented, documented, covered by sample-app Playwright tests, and part of the supported alpha surface. Any new visible route or browser-facing behavior should update the router, README feature table, `docs/FEATURES.md`, and Playwright coverage together.

### 4.3 User-facing documentation status

The first-alpha documentation refresh is complete. Keep these user-facing topics current as behavior changes:

1. Installation.
2. Activation rules, including `bootui.enabled=AUTO|ON|OFF`, enabled profiles, disabled profiles, and devtools activation.
3. Configuration properties.
4. Safety model and localhost-only behavior, including explicit opt-outs.
5. Secret masking and value exposure behavior.
6. Runtime configuration override behavior, persistence to `.bootui/application-bootui.properties`, and restart/rebind caveats.
7. Actuator requirements and degraded behavior when endpoints are unavailable.
8. Panel-by-panel feature guide for every visible route, including Metrics, Traces, AI Usage, DevTools, Dev Services, and the JVM memory panel with its suggested JVM options.
9. Troubleshooting.
10. Sample app walkthrough.
11. Release notes.

Completed reconciliation points:

- `bootui.enabled` uses `AUTO|ON|OFF`.
- Runtime config overrides persist to the BootUI overrides file by default.
- The frontend is plain JavaScript Vue 3.
- Startup Timeline, Memory, Spring Data, Spring Cache, HTTP Probe, Profile Diff, Log Tail, Traces, AI Usage, Scheduled Tasks, Security, Metrics, DevTools, Dev Services, and Vulnerabilities are implemented alpha surfaces, not deferred ideas.
- Dev Services / Docker Compose / Testcontainers behavior is documented: Docker Compose entries are startup snapshots, bean-backed Testcontainers services can expose bounded logs, and restart is disabled unless `bootui.dev-services.restart-enabled=true`.
- Maven Central publishing has been exercised for the first alpha; the release profile signs and stages artifacts through the Sonatype Central Publishing plugin.

### 4.4 Release readiness validation

Last validation completed on 2026-05-27:

- `./mvnw -B -ntp clean install` passed.
- The sample-app Playwright suite passed all 43 tests.
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

## 5. Alpha release scope

The codebase contains more than the original v0.1 MVP. The first alpha used this release strategy:

| Strategy | Scope | Trade-off |
|---|---|---|
| Harden all visible panels | Ship every current route as supported alpha functionality. | Delivered for `0.1.0-alpha.1`; keep focused backend tests, docs, and release validation current. |
| Mark newer panels experimental | Keep all routes visible but clearly label Data, Startup, Memory, Scheduled, HTTP Probe, Log Tail, Profile Diff, and Security as experimental. | Faster release, but docs must set expectations. |
| Hide unfinished panels | Only expose the original MVP routes plus any fully hardened additions. | Safest alpha surface, but requires UI gating work. |

Delivered alpha stance: **harden all visible panels**.

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

Newly in scope (added 2026-06):

- **In-app OTLP/HTTP receiver**. BootUI exposes `POST /bootui/api/otlp/v1/traces` and accepts protobuf-encoded `ExportTraceServiceRequest` payloads from the host JVM (and from any cooperating local service that points its OTLP exporter there). Traces are buffered in memory only; nothing is forwarded.
- **Traces panel**. Lists recent traces with a service swim-lane chip strip and per-trace waterfall, including span attributes and events.
- **AI Usage panel**. Aggregates Spring AI's `gen_ai.client.operation`, `spring.ai.tool`, `db.vector.client.operation`, and embedding spans. Shows recent completions, token usage, tool calls, and (when content capture is enabled) a conversation drawer with messages.
- **Multi-service dev orchestration (sink-only)**. Multiple cooperating local processes can each export OTLP to BootUI and their spans show up in the same waterfall. This is the dev-time equivalent of Aspire's distributed-tracing view; BootUI does not run, schedule, or restart the other services.

Still excluded:

- Live Docker Compose lifecycle control beyond the existing snapshot view.
- Production-grade tracing or APM replacement. The OTLP receiver is dev-only and bounded in memory.
- Request history.
- Extension SPI.
- CLI.
- Gradle plugin.
- Hosted features.
- Spring Boot 3.x compatibility.

## 6. v0.2 candidates

Potential features:

- Dev Services hardening for Docker Compose/Testcontainers edge cases.
- Better WebFlux support.
- Link from UI to source files when possible.
- Large-app edge-case hardening for current panels as real-world usage reveals gaps.
- Optional UI gating based on classpath/endpoint availability so irrelevant panels can be hidden or clearly disabled.
- Frontend test setup if the project decides to add Vitest or another UI test runner.

## 7. v1.0 candidates

Potential features:

- Extension SPI.
- Spring Batch panel.
- Spring Cloud panel.
- CLI attach mode.
- Gradle-first documentation.
- Spring Boot 3.5 compatibility if adoption requires it.

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
- Users of `bootui-spring-boot-starter` must not need Node.js, npm, or a separate frontend build in their own applications.

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
- Lets panels such as Spring Data, Scheduled Tasks, and Security remain read-only metadata views instead of invoking application behavior, while keeping mutating controls such as Spring Cache clear actions explicit and confirmation-gated.

## 9. Suggested next steps

Maven Central publishing prerequisites (`central` server credentials, GPG signing key, and release-profile deploy configuration) are in place, and the first alpha has been released to Maven Central via the `Prepare Release` workflow.

Backend test coverage for the harden-all-visible-panels scope has been completed: `BootUiPropertiesTests`, `BootUiActivationConditionAdditionalTests`, controller mapping and DTO serialization tests for every `/bootui/api/**` endpoint, `AdditionalMissingActuatorEndpointsTests`, `ConfigControllerHttpCrudTests`, `ConfigControllerMaskingTests`, `LoggersControllerMutationTests`, `CacheControllerTests`, `SecretMaskerBrowserVisibleSurfaceTests`, and edge-case tests for Scheduled, HTTP Probe, Log Tail, Profile Diff masking, Security, Memory, and OTLP trace ingestion.

User-facing documentation is reconciled with current behavior: `README.md`, `docs/FEATURES.md`, and `docs/SPECIFICATION.md` use the `AUTO|ON|OFF` activation model, the persisted-overrides behavior, the plain-JavaScript Vue 3 frontend, and the full visible panel set. The repository now ships a `CHANGELOG.md` (release notes back to `0.1.0-alpha.1`) and a sample-app walkthrough at `bootui-sample-app/README.md`.

On 2026-05-27, `./mvnw -B -ntp clean install` and the Playwright suite under `bootui-sample-app/e2e` both passed on the current branch. The Playwright run covered all 43 sample-app browser tests.

The next workstream is v0.2 scoping and implementation. Start with the candidates in §6, with Dev Services edge-case hardening as the likely first focus because it is already visible, safety-sensitive, and explicitly called out for v0.2 follow-up. Keep release validation and docs in sync as changes land.

## 10. Validation checklist

Last completed on 2026-05-27:

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
- [x] Newer diagnostic panels have release-grade coverage/docs for the alpha.
- [x] BootUI is disabled with `prod` and `production` profiles unless `bootui.enabled=ON`.
- [x] Non-local requests are rejected by default.
- [x] Documentation matches actual behavior.

Before future alphas, rerun this checklist after any release-facing code or documentation change.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Accidentally exposing sensitive data | High | Localhost-only, dev-only activation, secret masking, value exposure controls, production fail-closed defaults, and focused tests for every panel surfacing values. |
| Regressing visible panel hardening | High | Keep the current route set as the supported alpha surface, and require focused tests, docs, and release validation for release-facing changes. |
| Actuator endpoints unavailable | Medium | Internal bridge, stable empty DTOs, graceful UI states, setup guidance. |
| Optional Spring modules unavailable | Medium | Classpath gating, empty DTOs, and clear UI empty states for Spring Data, Spring Cache, Security, scheduling, and startup data. |
| Duplicating Spring Boot Admin | Medium | Stay focused on embedded local single-app developer experience. |
| Frontend bundle too large | Medium | Avoid heavy dependencies and lazy-load later panels if needed. |
| Boot 4 adoption slower than expected | Medium | Keep compatibility seams for a later Boot 3.5 branch. |
| Scope creep | High | Treat the current route set as the maximum near-term surface and move new ideas to later releases. |
