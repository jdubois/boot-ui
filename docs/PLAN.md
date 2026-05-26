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
  - Spring Data repositories
  - scheduled tasks
  - HTTP probe
  - log tail
  - profile diff
  - Spring Security
  - Micrometer metrics
- Vue 3 UI shell with routes for:
  - Overview
  - Beans
  - Conditions
  - Configuration
  - Mappings
  - Health
  - Loggers
  - Data
  - Startup Timeline
  - Scheduled Tasks
  - HTTP Probe
  - Log Tail
  - Profile Diff
  - Security
  - Metrics
- Maven-integrated frontend build that downloads Node/npm, builds the Vite app, and packages assets into `META-INF/resources/bootui`.
- Backend tests covering activation, auto-configuration activation cases, localhost filtering, config override persistence, override property sources, override file storage, environment post-processing, and config metadata loading.

## 3. Milestone status

| Milestone | Status | Notes |
|---|---|---|
| 0. Project foundation | Done | Multi-module build, Java/Spring baseline, sample app, docs, and CI exist. |
| 1. Auto-configuration and safety | Implemented, partially tested | Activation, auto-configuration, localhost filter, banner, and overview API exist. Activation and localhost behavior have focused tests. |
| 2. Static UI shell | Implemented, needs smoke validation | Vue shell, Maven frontend build, classpath packaging, and routes exist. |
| 3. Actuator bridge | Implemented, needs controller coverage | Stable BootUI DTO endpoints exist for the original MVP panels; missing-Actuator behavior needs broader explicit tests. |
| 4. Beans and Conditions panels | Implemented, needs product validation | API and UI panels exist; sample-app usefulness and large-app edge cases should be checked. |
| 5. Config, Mappings, Health, and Loggers | Implemented, partially tested | Runtime config overrides, secret masking, mappings, health, and logger controls exist. Config override plumbing has tests; web endpoint coverage still needs expansion. |
| 6. Post-MVP diagnostic panels | Implemented, needs hardening | Startup, Spring Data, Scheduled Tasks, HTTP Probe, Log Tail, Profile Diff, Security, and Metrics panels have API/UI slices. They need focused tests, safety review, and documentation. |
| 7. Documentation and release hardening | Not complete | Installation, activation, safety, troubleshooting, release notes, and sample walkthrough need to be finished and reconciled with current behavior. |

## 4. What still needs to be done

### 4.1 Expand backend tests

Existing focused tests cover several core safety and config paths. Remaining test work should prioritize browser-facing behavior and newer panels:

1. `BootUiProperties` binding defaults for all public properties, including `expose-values`, `overrides-file`, and endpoint timeout.
2. Activation rules not yet covered explicitly:
   - devtools-based activation.
   - custom disabled profiles.
   - invalid `bootui.enabled` values failing closed.
3. Controller mappings and DTO serialization for every `/bootui/api/**` endpoint.
4. Missing or unavailable Actuator endpoints returning stable empty DTOs.
5. Config controller behavior:
   - create, update, delete through HTTP.
   - persisted display state.
   - masking and `METADATA_ONLY` / `FULL` value exposure.
   - restart-warning messages.
6. Logger level mutation and clearing through HTTP.
7. Secret masking for all browser-visible property names and values.
8. Newer panel controllers:
   - Spring Data repository list/detail, including no Spring Data and no repositories states.
   - Scheduled task reporting when scheduling infrastructure is present or absent.
   - HTTP probe method/path normalization, loopback-only target construction, header filtering, timeout/error responses, and unsafe-body handling.
   - Log tail buffer behavior and message shaping.
   - Profile diff source attribution and masking.
   - Spring Security chain listing, best-effort explain behavior, classpath gating, and credential non-disclosure.
   - Micrometer metrics browsing, tag filtering, and live value shaping.

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
9. Startup Timeline.
10. Scheduled Tasks.
11. HTTP Probe.
12. Log Tail.
13. Profile Diff.
14. Security.
15. Metrics.

The plan no longer treats Startup, Spring Data, HTTP Probe, Profile Diff, Log Tail, Scheduled Tasks, Security, or Metrics as future-only ideas: they are implemented surfaces and should either be hardened for the next alpha or explicitly hidden/marked experimental before release.

### 4.3 Refresh user-facing documentation

Before the first alpha, document:

1. Installation.
2. Activation rules, including `bootui.enabled=AUTO|ON|OFF`, enabled profiles, disabled profiles, and devtools activation.
3. Configuration properties.
4. Safety model and localhost-only behavior, including explicit opt-outs.
5. Secret masking and value exposure behavior.
6. Runtime configuration override behavior, persistence to `.bootui/application-bootui.properties`, and restart/rebind caveats.
7. Actuator requirements and degraded behavior when endpoints are unavailable.
8. Panel-by-panel feature guide for every visible route.
9. Troubleshooting.
10. Sample app walkthrough.
11. Release notes.

Reconcile the specification with the implementation where behavior has become more precise or has diverged:

- `bootui.enabled` currently uses `AUTO|ON|OFF`, while older specification text mentions boolean `true|false`.
- Runtime config overrides are persisted to a BootUI overrides file by default, while older specification text says overrides disappear on restart.
- The frontend is currently plain JavaScript Vue 3, not TypeScript/Vitest.
- Startup Timeline, Spring Data, HTTP Probe, Profile Diff, Log Tail, Scheduled Tasks, Security, and Metrics are implemented and should be moved out of future-only language.
- Local Services / Docker Compose / Testcontainers remains unimplemented.
- Maven Central publishing scope for `0.1.0-alpha.1` still needs a release decision.

### 4.4 Validate release readiness

Run the CI-equivalent build:

```bash
mvn -B -ntp clean install
```

Run the sample app:

```bash
cd bootui-sample-app
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Manual smoke checks:

1. Open `http://localhost:8080/bootui`.
2. Confirm all visible panels load.
3. Confirm `/bootui/api/overview` and the other visible API endpoints return stable DTOs.
4. Confirm secret-like values remain masked by default.
5. Confirm `prod` and `production` profiles disable BootUI unless explicitly forced on.
6. Confirm non-local requests are rejected by default.
7. Confirm generated frontend assets are packaged in the UI artifact.
8. Confirm no generated build output or secrets are accidentally committed.

## 5. Next release scope

The codebase currently contains more than the original v0.1 MVP. The next release should use one of these release strategies:

| Strategy | Scope | Trade-off |
|---|---|---|
| Harden all visible panels | Ship every current route as supported alpha functionality. | More documentation and test work before release. |
| Mark newer panels experimental | Keep all routes visible but clearly label Data, Startup, Scheduled, HTTP Probe, Log Tail, Profile Diff, and Security as experimental. | Faster release, but docs must set expectations. |
| Hide unfinished panels | Only expose the original MVP routes plus any fully hardened additions. | Safest alpha surface, but requires UI gating work. |

Recommended alpha stance: **mark newer panels experimental unless they receive focused tests and documentation before release**.

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
- Spring Data repository explorer.
- Scheduled task inspector.
- HTTP probe panel.
- Live log tail.
- Profile diff.
- Spring Security panel.
- Micrometer metrics browser with live values.

Still excluded:

- Multi-service orchestration.
- Local Services panel for Docker Compose and Testcontainers.
- Request history.
- Distributed tracing.
- Extension SPI.
- CLI.
- Gradle plugin.
- Hosted features.
- Spring Boot 3.x compatibility.

## 6. v0.2 candidates

Potential features:

- Local Services panel for Docker Compose and Testcontainers.
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
- Lets panels such as Spring Data, Scheduled Tasks, and Security remain read-only metadata views instead of invoking application behavior.

## 9. Suggested next steps

1. Decide the next release strategy for the already-visible post-MVP panels: harden, mark experimental, or hide.
2. Add controller and DTO serialization tests for all BootUI API endpoints.
3. Add missing-Actuator and missing-classpath tests for optional panels.
4. Add HTTP-level tests for config override and logger mutations.
5. Run full build and sample app smoke checks.
6. Update `docs/SPECIFICATION.md`, `README.md`, and user-facing docs to match current behavior.
7. Decide whether publishing to Maven Central is required for `0.1.0-alpha.1`.
8. Prepare release notes and tag the first alpha only after CI and manual smoke checks pass.

## 10. Validation checklist

Before considering the next alpha complete:

- `mvn -B -ntp clean install` passes.
- The UI build is executed automatically by Maven.
- Sample app starts with the `dev` profile.
- `/bootui` loads.
- `/bootui/api/overview` returns data.
- Every visible panel loads and handles empty/unavailable data.
- Configuration panel works and masks secrets.
- Runtime config overrides persist through the BootUI overrides file and warn about restart/rebind caveats.
- Logger changes work.
- Newer diagnostic panels either have release-grade coverage/docs or are clearly marked experimental/hidden.
- BootUI is disabled with `prod` and `production` profiles unless `bootui.enabled=ON`.
- Non-local requests are rejected by default.
- Documentation matches actual behavior.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Accidentally exposing sensitive data | High | Localhost-only, dev-only activation, secret masking, value exposure controls, production fail-closed defaults, and focused tests for every panel surfacing values. |
| Shipping too many partially hardened panels | High | Choose an alpha strategy: harden all visible panels, mark newer panels experimental, or hide unfinished panels. |
| Actuator endpoints unavailable | Medium | Internal bridge, stable empty DTOs, graceful UI states, setup guidance. |
| Optional Spring modules unavailable | Medium | Classpath gating, empty DTOs, and clear UI empty states for Spring Data, Security, scheduling, and startup data. |
| Duplicating Spring Boot Admin | Medium | Stay focused on embedded local single-app developer experience. |
| Frontend bundle too large | Medium | Avoid heavy dependencies and lazy-load later panels if needed. |
| Boot 4 adoption slower than expected | Medium | Keep compatibility seams for a later Boot 3.5 branch. |
| Scope creep | High | Treat the current route set as the maximum near-term surface and move new ideas to later releases. |
