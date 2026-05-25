# BootUI Implementation Plan

## 1. Strategy

Build BootUI as a **Spring Boot 4 Starter** first, not as a standalone application or platform. The first release should prove the core value: adding one starter dependency to a Spring Boot 4 application gives a safe local UI that explains the running app.

The MVP should prioritize:

1. Safety.
2. Easy installation.
3. Useful runtime explanations.
4. A polished but simple UI.
5. Testable architecture.

## 2. Milestones

### Milestone 0: Project foundation

Goal: create the repository structure and build system.

Deliverables:

- Maven multi-module project.
- Java 25 configuration.
- Spring Boot 4.x baseline.
- Root README.
- License.
- `.gitignore`, `.editorconfig`, `.gitattributes`.
- CI workflow.
- Basic module skeleton:
  - `bootui-core`
  - `bootui-autoconfigure`
  - `bootui-spring-boot-starter`
  - `bootui-ui`
  - `bootui-sample-app`

Validation:

```bash
./mvnw clean verify
```

Exit criteria:

- Empty modules compile.
- Sample app starts.
- CI passes.

### Milestone 1: Auto-configuration and safety

Goal: BootUI can enable itself safely in local development and stay disabled elsewhere.

Deliverables:

- `BootUiProperties`.
- `BootUiAutoConfiguration`.
- Activation conditions:
  - devtools present.
  - `dev` or `local` profile.
  - explicit `bootui.enabled=true`.
- Production safety:
  - disabled for `prod` profile by default.
  - localhost-only request filter.
  - clear startup logs.
- Startup banner line with BootUI URL.
- `/bootui/api/overview` endpoint.

Tests:

- Properties binding tests.
- Auto-configuration condition tests.
- Localhost filter tests.
- Production profile disabled test.

Exit criteria:

- Adding the starter to the Spring Boot 4 sample app exposes `/bootui/api/overview` locally.
- Non-local access is rejected by default.
- `prod` profile disables BootUI.

### Milestone 2: Static UI shell

Goal: the browser UI loads and calls the overview API.

Deliverables:

- Vue 3 + TypeScript + Vite frontend.
- Bootstrap 5.3 styling.
- Static Vue assets built automatically by Maven into `bootui-ui`.
- Compiled Vue assets packaged into the BootUI Java artifact.
- Assets served by `bootui-autoconfigure`.
- Navigation shell:
  - Overview.
  - Beans.
  - Conditions.
  - Config.
  - Mappings.
  - Health.
  - Loggers.
  - Startup.
  - Services.
- Empty states and error states.

Tests:

- UI build test.
- Root Maven build packages the Vue UI without manual npm commands.
- Asset serving integration test.
- Browser smoke test if practical.

Exit criteria:

- `/bootui` loads in the sample app.
- Overview panel displays real app/runtime data.
- `./mvnw clean package` produces artifacts that include the compiled Vue UI.

### Milestone 3: Actuator bridge

Goal: BootUI has a stable internal API over Actuator data.

Deliverables:

- Actuator availability detection.
- Internal endpoint access layer.
- Normalized error model.
- Endpoints:
  - `/bootui/api/beans`
  - `/bootui/api/conditions`
  - `/bootui/api/config`
  - `/bootui/api/mappings`
  - `/bootui/api/health`
  - `/bootui/api/loggers`
- Graceful unavailable responses.

Tests:

- Endpoint mapping tests.
- Missing Actuator tests.
- Hidden endpoint tests.
- Serialization tests.

Exit criteria:

- BootUI UI can render real data from all MVP panels.
- Missing Actuator endpoints show actionable messages.

### Milestone 4: Beans and Conditions panels

Goal: deliver the first high-value explainability experience.

Deliverables:

- Beans Explorer:
  - list.
  - search.
  - filters.
  - detail drawer.
  - dependencies.
- Conditions Explorer:
  - positive/negative matches.
  - group by auto-configuration class.
  - search.
  - human-readable summaries for common condition types.
  - raw details disclosure.

Tests:

- DTO normalization tests.
- UI component tests.
- Sample app assertions.

Exit criteria:

- Developer can find a bean and inspect dependencies.
- Developer can determine why a common auto-configuration matched or did not match.

### Milestone 5: Config, Mappings, Health, and Loggers

Goal: complete the v0.1 feature set.

Deliverables:

- Configuration Properties Explorer:
  - list/search Spring Boot configuration properties.
  - effective values.
  - property source.
  - metadata description where available.
  - secret masking.
  - local runtime property overrides.
  - create/update/remove property overrides.
  - clear labels for runtime-only, non-persistent changes.
- Mappings Browser:
  - method/path/handler.
  - search/filter.
- Health Dashboard:
  - tree rendering.
  - status badges.
- Logger Controls:
  - list/search loggers.
  - set runtime log level.
  - clear runtime log level.

Tests:

- Secret masking tests.
- Configuration property override tests.
- Logger mutation tests.
- Health tree tests.
- Mappings normalization tests.

Exit criteria:

- All v0.1 panels are useful in the sample app.
- Secret-like config values are masked everywhere.
- Developers can list and locally modify Spring Boot configuration properties through runtime overrides.

### Milestone 6: Documentation and release hardening

Goal: prepare the first usable alpha release.

Deliverables:

- Installation documentation.
- Configuration reference.
- Security model documentation.
- Troubleshooting guide.
- Screenshots/GIFs.
- Sample app walkthrough.
- Release notes.
- Maven Central publishing setup, if publishing is desired.

Validation:

```bash
./mvnw clean verify
```

Manual validation:

- Start sample app.
- Open BootUI.
- Confirm all panels load.
- Confirm production profile disables BootUI.
- Confirm secret values remain masked.

Exit criteria:

- `0.1.0-alpha.1` can be tagged or published.

## 3. v0.1 scope

Included:

- Development-only activation.
- Localhost-only protection.
- Overview panel.
- Beans Explorer.
- Conditions Explorer.
- Config Explainer.
- Mappings Browser.
- Health Dashboard.
- Logger Controls.
- Static UI shell.
- Sample app.
- Documentation.
- Spring Boot 4 target only.

Excluded:

- Multi-service orchestration.
- Request history.
- Distributed tracing.
- Startup timeline, unless easy after Actuator bridge.
- Docker Compose/Testcontainers Services panel, unless easy after core panels.
- Extension SPI.
- CLI.
- Gradle plugin.
- Hosted features.

## 4. v0.2 scope

Potential features:

- Startup Timeline.
- Live log tail.
- HTTP probe panel.
- Profile diff.
- Better WebFlux support.
- Services panel for Docker Compose and Testcontainers.
- Link from UI to source files when possible.

## 5. v1.0 scope

Potential features:

- Extension SPI.
- Spring Security panel.
- Spring Data panel.
- Spring Batch panel.
- Spring Cloud panel.
- CLI attach mode.
- Gradle-first documentation.
- Spring Boot 3.5 compatibility if needed.

## 6. Technical decisions

### 6.1 Java and Spring Boot

Use Java 25 and Spring Boot 4.x for the initial codebase.

Reason:

- Aligns with the current Spring Boot forward-looking ecosystem.
- Keeps the project modern.
- Avoids spending v0.1 effort on older compatibility.

Risk:

- Spring Boot 3.x users are a large audience, but they are not the target of v0.1.

Mitigation:

- Keep abstractions clean enough to add a Boot 3.5 compatibility branch later.

### 6.2 Build tool

Use Maven.

Reason:

- Spring Boot library publishing and multi-module builds are straightforward.
- The project can add Gradle examples later.

### 6.3 Frontend

Use Vue 3 + TypeScript + Vite.

Reason:

- Fast development.
- Small enough for this UI.
- Good component model for data-heavy panels.
- Matches the local Spring Boot skill defaults.
- Provides a maintainable component model for the BootUI panels.
- Is the committed UI choice for BootUI.

Packaging requirement:

- The Vue build must be wired into Maven.
- A root Maven build must run the frontend build automatically.
- Built assets must be included in the BootUI Java artifact and served from the classpath.
- Users of `bootui-spring-boot-starter` must not need Node.js, npm, or a separate frontend build in their own applications.

### 6.4 Data access

Use BootUI internal DTOs rather than binding the frontend directly to raw Actuator JSON.

Reason:

- Shields UI from Spring Boot response shape changes.
- Allows better explanations and safety masking.
- Allows reduced functionality when some endpoints are unavailable.

### 6.5 Actuator integration

Prefer internal endpoint invokers where practical; fall back to web endpoint behavior only when needed.

Reason:

- Avoids requiring users to expose all Actuator endpoints over HTTP.
- Reduces security friction.
- Keeps BootUI local and internal.

## 7. Work breakdown

### Foundation tasks

1. Create Maven parent project.
2. Add wrapper.
3. Add common dotfiles.
4. Add modules.
5. Add CI.
6. Add sample app.

### Backend tasks

1. Implement `BootUiProperties`.
2. Implement activation conditions.
3. Implement local request filter.
4. Implement overview endpoint.
5. Implement Actuator bridge.
6. Implement DTO mappers.
7. Implement secret masking.
8. Implement logger mutation endpoint.

### Frontend tasks

1. Create Vue app.
2. Configure Vite build into Java resources.
3. Wire the Vue build into Maven so `./mvnw clean package` packages the UI automatically.
4. Build layout and navigation.
5. Implement API client.
6. Implement Overview panel.
7. Implement Beans panel.
8. Implement Conditions panel.
9. Implement Config panel.
10. Implement Mappings panel.
11. Implement Health panel.
12. Implement Loggers panel.

### Documentation tasks

1. Installation guide.
2. Configuration reference.
3. Safety model.
4. Troubleshooting.
5. Sample app walkthrough.
6. Contribution guide.

## 8. Suggested initial issue list

1. Create Maven multi-module skeleton.
2. Add BootUI docs and initial README.
3. Add sample Spring Boot app.
4. Add `bootui-core` module.
5. Add `bootui-autoconfigure` module.
6. Add `bootui-spring-boot-starter` module.
7. Implement `BootUiProperties`.
8. Implement dev-only activation.
9. Implement localhost-only filter.
10. Implement `/bootui/api/overview`.
11. Serve placeholder `/bootui` page.
12. Create Vue UI shell.
13. Wire Vue build into Maven packaging.
14. Bundle compiled Vue assets into starter.
15. Implement Actuator bridge.
16. Implement Beans API.
17. Implement Conditions API.
18. Implement Config API with masking.
19. Implement Mappings API.
20. Implement Health API.
21. Implement Loggers API and update operation.
22. Build Overview UI.
23. Build Beans UI.
24. Build Conditions UI.
25. Build Config UI.
26. Build Mappings UI.
27. Build Health UI.
28. Build Loggers UI.
29. Add integration tests with sample app.
30. Write installation docs.
31. Prepare `0.1.0-alpha.1`.

## 9. Validation checklist

Before considering v0.1 complete:

- `./mvnw clean verify` passes.
- `./mvnw clean package` builds and packages the Vue UI automatically.
- UI build passes.
- Sample app starts.
- `/bootui` loads.
- `/bootui/api/overview` returns data.
- Beans panel works.
- Conditions panel works.
- Config panel works and masks secrets.
- Mappings panel works.
- Health panel works.
- Logger changes work.
- BootUI is disabled with `prod` profile.
- Non-local requests are rejected by default.
- Documentation matches actual behavior.

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Accidentally exposing sensitive data | High | Localhost-only, dev-only, secret masking, production fail-closed |
| Actuator endpoints unavailable | Medium | Internal bridge, graceful empty states, clear setup guidance |
| Duplicating Spring Boot Admin | Medium | Stay focused on embedded local single-app DX |
| Frontend bundle too large | Medium | Lazy load panels, avoid heavy charting early |
| Boot 4 adoption slower than expected | Medium | Keep compatibility seams for Boot 3.5 |
| Scope creep | High | Keep v0.1 to explainability panels only |

## 11. Immediate next step

Create the actual Maven multi-module project skeleton for BootUI, then implement Milestone 1 before investing heavily in the frontend.
