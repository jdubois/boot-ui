# BootUI Implementation Plan

## 1. Strategy

Build BootUI as a **Spring Boot 4 starter** first, not as a standalone application or platform. The first release should prove the core value: adding one starter dependency to a Spring Boot 4 application gives a safe local UI that explains the running app.

The MVP prioritizes:

1. Safety.
2. Easy installation.
3. Useful runtime explanations.
4. A polished but simple UI.
5. Testable architecture.

## 2. Current implementation state

The project is past the original skeleton phase. The repository currently includes:

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
  - `/bootui/api/**` controllers for overview, beans, conditions, config, mappings, health, loggers, and startup
  - config override property source, environment post-processor, file store, and runtime service
  - secret masking helper
- Vue 3 UI shell:
  - Overview
  - Beans
  - Conditions
  - Configuration
  - Mappings
  - Health
  - Loggers
- Maven-integrated frontend build that downloads Node/npm, builds the Vite app, and packages assets into `META-INF/resources/bootui`.

## 3. Milestone status

| Milestone | Status | Notes |
|---|---|---|
| 0. Project foundation | Done | Multi-module build, Java/Spring baseline, sample app, docs, and CI exist. |
| 1. Auto-configuration and safety | Implemented, needs broader tests | Activation, auto-configuration, localhost filter, banner, and overview API exist. |
| 2. Static UI shell | Implemented, needs smoke validation | Vue shell, Maven frontend build, classpath packaging, and core routes exist. |
| 3. Actuator bridge | Implemented, needs coverage hardening | Stable BootUI DTO endpoints exist for the MVP panels; missing-Actuator behavior needs explicit tests. |
| 4. Beans and Conditions panels | Implemented, needs product validation | API and UI panels exist; sample-app usefulness and edge cases should be checked. |
| 5. Config, Mappings, Health, and Loggers | Implemented, needs coverage hardening | Runtime config overrides, secret masking, mappings, health, and logger controls exist; mutation and masking tests need expansion. |
| 6. Documentation and release hardening | Not complete | Installation, activation, safety, troubleshooting, release notes, and sample walkthrough need to be finished. |

## 4. What still needs to be done

### 4.1 Expand backend tests

Add or broaden tests for:

1. `BootUiProperties` binding and defaults.
2. Activation rules:
   - `dev` and `local` profiles.
   - devtools-based activation.
   - explicit enable/disable values.
   - disabled production profiles.
   - fail-closed behavior when activation is ambiguous.
3. Localhost-only protection:
   - loopback access.
   - non-local rejection.
   - opt-out behavior through `bootui.allow-non-localhost=true`.
   - forwarded-header behavior if BootUI supports it.
4. Controller mappings and DTO serialization for every `/bootui/api/**` endpoint.
5. Missing or unavailable Actuator endpoints returning stable empty DTOs.
6. Config override create, update, delete, persistence, display masking, and restart-warning messages.
7. Logger level mutation and clearing.
8. Secret masking for all browser-visible property names and values.

### 4.2 Finish UI and product parity checks

Validate every v0.1 panel against the sample app:

1. Overview.
2. Beans.
3. Conditions.
4. Configuration.
5. Mappings.
6. Health.
7. Loggers.

Also decide whether the existing startup API should get a visible Startup route in v0.1 or remain a later enhancement. The original plan mentioned Startup and Services in the navigation shell, but the current v0.1 acceptance criteria only require Overview, Beans, Conditions, Config, Mappings, Health, and Loggers.

### 4.3 Refresh user-facing documentation

Before the first alpha, document:

1. Installation.
2. Activation rules.
3. Configuration properties.
4. Safety model and localhost-only behavior.
5. Secret masking behavior.
6. Runtime configuration override behavior and restart caveats.
7. Actuator requirements and degraded behavior when endpoints are unavailable.
8. Troubleshooting.
9. Sample app walkthrough.
10. Release notes.

Reconcile the specification with the implementation where behavior has become more precise, especially:

- `bootui.enabled` value semantics.
- Startup panel scope.
- Services panel scope.
- Maven Central publishing scope for `0.1.0-alpha.1`.

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
2. Confirm all v0.1 panels load.
3. Confirm `/bootui/api/overview` and the other v0.1 API endpoints return stable DTOs.
4. Confirm secret-like values remain masked.
5. Confirm `prod` and `production` profiles disable BootUI unless explicitly forced on.
6. Confirm non-local requests are rejected by default.
7. Confirm generated frontend assets are packaged in the UI artifact.
8. Confirm no generated build output or secrets are accidentally committed.

## 5. v0.1 scope

Included:

- Development-only activation.
- Localhost-only protection.
- Overview panel.
- Beans Explorer.
- Conditions Explorer.
- Configuration Explorer.
- Mappings Browser.
- Health Dashboard.
- Logger Controls.
- Static UI shell packaged into a Java artifact.
- Sample app.
- Documentation.
- Spring Boot 4 target only.

Excluded:

- Multi-service orchestration.
- Request history.
- Distributed tracing.
- Startup timeline, unless explicitly promoted into v0.1.
- Docker Compose/Testcontainers Services panel, unless explicitly promoted into v0.1.
- Extension SPI.
- CLI.
- Gradle plugin.
- Hosted features.
- Spring Boot 3.x compatibility.

## 6. v0.2 candidates

Potential features:

- Startup Timeline.
- Live log tail.
- HTTP probe panel.
- Profile diff.
- Better WebFlux support.
- Services panel for Docker Compose and Testcontainers.
- Link from UI to source files when possible.

## 7. v1.0 candidates

Potential features:

- Extension SPI.
- Spring Security panel.
- Spring Data panel.
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

### 8.5 Actuator integration

Prefer internal endpoint invokers where practical.

Reason:

- Avoids requiring users to expose all Actuator endpoints over HTTP.
- Reduces security friction.
- Keeps BootUI local and internal.

## 9. Suggested next steps

1. Add backend tests for activation and localhost-only safety.
2. Add missing-Actuator and stable DTO serialization tests for all BootUI API endpoints.
3. Add config override and logger mutation tests.
4. Run full build and sample app smoke checks.
5. Complete installation, activation, safety, troubleshooting, and sample walkthrough documentation.
6. Decide whether publishing to Maven Central is required for `0.1.0-alpha.1`.
7. Prepare release notes and tag the first alpha only after CI and manual smoke checks pass.

## 10. Validation checklist

Before considering v0.1 complete:

- `mvn -B -ntp clean install` passes.
- The UI build is executed automatically by Maven.
- Sample app starts with the `dev` profile.
- `/bootui` loads.
- `/bootui/api/overview` returns data.
- Beans panel works.
- Conditions panel works.
- Configuration panel works and masks secrets.
- Mappings panel works.
- Health panel works.
- Logger changes work.
- BootUI is disabled with `prod` and `production` profiles.
- Non-local requests are rejected by default.
- Documentation matches actual behavior.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Accidentally exposing sensitive data | High | Localhost-only, dev-only activation, secret masking, production fail-closed defaults. |
| Actuator endpoints unavailable | Medium | Internal bridge, stable empty DTOs, graceful UI states, setup guidance. |
| Duplicating Spring Boot Admin | Medium | Stay focused on embedded local single-app developer experience. |
| Frontend bundle too large | Medium | Avoid heavy dependencies and lazy-load later panels if needed. |
| Boot 4 adoption slower than expected | Medium | Keep compatibility seams for a later Boot 3.5 branch. |
| Scope creep | High | Keep v0.1 to the explainability panels and local runtime controls. |
