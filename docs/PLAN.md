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
- Vue 3 UI shell with routes for:
  - Overview
  - Beans
  - Conditions
  - Configuration
  - Mappings
  - Health
  - Loggers
  - Data
  - Cache
  - Startup Timeline
  - Memory
  - Scheduled Tasks
  - HTTP Probe
  - Log Tail
  - Profile Diff
  - Security
  - Metrics
  - Vulnerabilities
  - DevTools
  - Dev Services
- Maven-integrated frontend build that downloads Node/npm, builds the Vite app, and packages assets into `META-INF/resources/bootui`.
- Backend tests covering activation, auto-configuration activation cases, localhost filtering, config override persistence, override property sources, override file storage, environment post-processing, config metadata loading, selected web controllers, missing Actuator behavior, and sample-app integration.
- Playwright end-to-end tests in `bootui-sample-app/e2e` covering the sample API, every visible BootUI route, and focused flows for the current panel set, including Metrics, Redis-backed Cache, Vulnerabilities, DevTools, and Dev Services.

## 3. Milestone status

| Milestone | Status | Notes |
|---|---|---|
| 0. Project foundation | Done | Multi-module build, Java/Spring baseline, sample app, docs, and CI exist. |
| 1. Auto-configuration and safety | Implemented, partially tested | Activation, auto-configuration, localhost filter, banner, and overview API exist. Activation and localhost behavior have focused tests. |
| 2. Static UI shell | Implemented and smoke-tested | Vue shell, Maven frontend build, classpath packaging, and routes exist; Playwright verifies sidebar navigation across every visible section. |
| 3. Actuator bridge | Implemented, needs deeper backend coverage | Stable BootUI DTO endpoints exist for the original MVP panels; missing-Actuator behavior needs broader explicit tests. |
| 4. Beans and Conditions panels | Implemented and covered by sample e2e | API and UI panels exist; large-app edge cases remain release-hardening work. |
| 5. Config, Mappings, Health, and Loggers | Implemented and covered by sample e2e | Runtime config overrides, secret masking, mappings, health, and logger controls exist. Config override plumbing has focused backend tests. |
| 6. Post-MVP diagnostic panels | Implemented and covered by sample e2e | Startup, Memory, Spring Data, Spring Cache, Scheduled Tasks, HTTP Probe, Log Tail, Profile Diff, Security, Metrics, Vulnerabilities, DevTools, and Dev Services panels have API/UI slices plus route-level and focused Playwright coverage. Backend edge-case tests and safety review remain. |
| 7. Documentation and release hardening | In progress | Installation, activation, safety, troubleshooting, release notes, screenshots, and sample walkthrough need to stay reconciled with current behavior. |

## 4. What still needs to be done

### 4.1 Expand backend tests

Existing focused tests cover several core safety and config paths, and the sample-app Playwright suite covers every visible browser route. Remaining backend test work should prioritize edge cases that are hard to exercise reliably from a browser:

1. `BootUiProperties` binding defaults for all public properties, including `expose-values`, `overrides-file`, and endpoint timeout.
2. Activation rules not yet covered explicitly:
   - devtools-based activation.
   - custom disabled profiles.
   - invalid `bootui.enabled` values failing closed.
3. Controller mappings and DTO serialization for every `/bootui/api/**` endpoint, especially newer DevTools and Dev Services endpoints.
4. Missing or unavailable Actuator endpoints returning stable empty DTOs.
5. Config controller behavior:
   - create, update, delete through HTTP.
   - persisted display state.
   - masking and `METADATA_ONLY` / `FULL` value exposure.
   - restart-warning messages.
6. Logger level mutation and clearing through HTTP.
7. Secret masking for all browser-visible property names and values.
8. Newer panel controller edge cases:
   - Spring Data repository list/detail, including no Spring Data and no repositories states.
   - Scheduled task reporting when scheduling infrastructure is present or absent.
   - HTTP probe method/path normalization, loopback-only target construction, header filtering, timeout/error responses, and unsafe-body handling.
   - Log tail buffer behavior and message shaping.
   - Profile diff source attribution and masking.
   - Spring Cache manager discovery, cache metrics, annotation scanning, confirmed clear mutations, and opt-out disabling.
   - Spring Security chain listing, best-effort explain behavior, classpath gating, and credential non-disclosure.
   - Micrometer metrics browsing, tag filtering, and live value shaping.
   - DevTools unavailable states, confirmation-required restart API behavior, and LiveReload action mapping.
   - Dev Services service discovery, secret sanitization, bounded logs, disabled-by-default restart behavior, and restart-enabled opt-in behavior.
   - JVM memory report values, JVM argument disclosure review, and suggested JVM option generation.

### 4.2 Finish UI and product parity checks

Validate every visible route against the sample app:

1. Overview.
2. Beans.
3. Conditions.
4. Configuration.
5. Mappings.
6. Health.
7. Loggers.
8. Data.
9. Cache.
10. Startup Timeline.
11. Memory.
12. Metrics.
13. Vulnerabilities.
14. DevTools.
15. Dev Services.
16. Scheduled Tasks.
17. HTTP Probe.
18. Log Tail.
19. Profile Diff.
20. Security.

The plan no longer treats Startup, Memory, Spring Data, Spring Cache, HTTP Probe, Profile Diff, Log Tail, Scheduled Tasks, Security, Metrics, Vulnerabilities, DevTools, or Dev Services as future-only ideas: they are implemented surfaces with sample-app Playwright coverage and should either be hardened for the next alpha or explicitly marked experimental before release.

### 4.3 Refresh user-facing documentation

Before the first alpha, document:

1. Installation.
2. Activation rules, including `bootui.enabled=AUTO|ON|OFF`, enabled profiles, disabled profiles, and devtools activation.
3. Configuration properties.
4. Safety model and localhost-only behavior, including explicit opt-outs.
5. Secret masking and value exposure behavior.
6. Runtime configuration override behavior, persistence to `.bootui/application-bootui.properties`, and restart/rebind caveats.
7. Actuator requirements and degraded behavior when endpoints are unavailable.
8. Panel-by-panel feature guide for every visible route, including Metrics, DevTools, Dev Services, and the JVM memory panel with its suggested JVM options.
9. Troubleshooting.
10. Sample app walkthrough.
11. Release notes.

Reconcile the specification with the implementation where behavior has become more precise or has diverged:

- `bootui.enabled` currently uses `AUTO|ON|OFF`, while older specification text mentions boolean `true|false`.
- Runtime config overrides are persisted to a BootUI overrides file by default, while older specification text says overrides disappear on restart.
- The frontend is currently plain JavaScript Vue 3, not TypeScript/Vitest.
- Startup Timeline, Memory, Spring Data, Spring Cache, HTTP Probe, Profile Diff, Log Tail, Scheduled Tasks, Security, Metrics, DevTools, and Dev Services are implemented and should be moved out of future-only language.
- Dev Services / Docker Compose / Testcontainers is implemented and included in the harden-all-visible-panels alpha scope.
  Docker Compose entries are startup snapshots; bean-backed Testcontainers services can expose bounded logs,
  and restart is disabled unless `bootui.dev-services.restart-enabled=true`.
- Maven Central publishing is required for `0.1.0-alpha.1`; the release profile signs and stages artifacts through the Sonatype Central Publishing plugin.

### 4.4 Validate release readiness

Run the CI-equivalent build:

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

## 5. Next release scope

The codebase currently contains more than the original v0.1 MVP. The next release uses this release strategy:

| Strategy | Scope | Trade-off |
|---|---|---|
| Harden all visible panels | Ship every current route as supported alpha functionality. | Selected for `0.1.0-alpha.1`; requires focused backend tests, docs, and release validation. |
| Mark newer panels experimental | Keep all routes visible but clearly label Data, Startup, Memory, Scheduled, HTTP Probe, Log Tail, Profile Diff, and Security as experimental. | Faster release, but docs must set expectations. |
| Hide unfinished panels | Only expose the original MVP routes plus any fully hardened additions. | Safest alpha surface, but requires UI gating work. |

Selected alpha stance: **harden all visible panels**.

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

Still excluded:

- Multi-service orchestration.
- Live Docker Compose lifecycle control.
- Request history.
- Distributed tracing.
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
- Experimental-panel hardening if any current panel is not promoted in the alpha.
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

Backend test coverage for the harden-all-visible-panels scope has been completed: `BootUiPropertiesTests`, `BootUiActivationConditionAdditionalTests`, controller mapping and DTO serialization tests for every `/bootui/api/**` endpoint, `AdditionalMissingActuatorEndpointsTests`, `ConfigControllerHttpCrudTests`, `ConfigControllerMaskingTests`, `LoggersControllerMutationTests`, `CacheControllerTests`, `SecretMaskerBrowserVisibleSurfaceTests`, and edge-case tests for Scheduled, HTTP Probe, Log Tail, Profile Diff masking, Security, and Memory.

User-facing documentation is reconciled with current behavior: `README.md`, `docs/FEATURES.md`, and `docs/SPECIFICATION.md` use the `AUTO|ON|OFF` activation model, the persisted-overrides behavior, the plain-JavaScript Vue 3 frontend, and the full visible panel set. The repository now ships a `CHANGELOG.md` (release notes back to `0.1.0-alpha.1`) and a sample-app walkthrough at `bootui-sample-app/README.md`.

`./mvnw -B -ntp clean install` and the Playwright suite under `bootui-sample-app/e2e` both pass on the current `main`.

Remaining release-hardening work tracks under the milestones in §3 and the v0.2 candidates in §6.

## 10. Validation checklist

Before considering the next alpha complete:

- `./mvnw -B -ntp clean install` passes.
- The UI build is executed automatically by Maven.
- Sample app starts with the `dev` profile.
- `/bootui` loads.
- `/bootui/api/overview` returns data.
- Every visible panel loads and handles empty/unavailable data.
- Configuration panel works and masks secrets.
- Runtime config overrides persist through the BootUI overrides file and warn about restart/rebind caveats.
- Logger changes work.
- DevTools controls show unavailable states when Spring Boot DevTools is absent.
- Newer diagnostic panels have release-grade coverage/docs for the alpha.
- BootUI is disabled with `prod` and `production` profiles unless `bootui.enabled=ON`.
- Non-local requests are rejected by default.
- Documentation matches actual behavior.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Accidentally exposing sensitive data | High | Localhost-only, dev-only activation, secret masking, value exposure controls, production fail-closed defaults, and focused tests for every panel surfacing values. |
| Shipping too many partially hardened panels | High | Choose an alpha strategy: harden all visible panels, mark newer panels experimental, or hide unfinished panels. |
| Actuator endpoints unavailable | Medium | Internal bridge, stable empty DTOs, graceful UI states, setup guidance. |
| Optional Spring modules unavailable | Medium | Classpath gating, empty DTOs, and clear UI empty states for Spring Data, Spring Cache, Security, scheduling, and startup data. |
| Duplicating Spring Boot Admin | Medium | Stay focused on embedded local single-app developer experience. |
| Frontend bundle too large | Medium | Avoid heavy dependencies and lazy-load later panels if needed. |
| Boot 4 adoption slower than expected | Medium | Keep compatibility seams for a later Boot 3.5 branch. |
| Scope creep | High | Treat the current route set as the maximum near-term surface and move new ideas to later releases. |
