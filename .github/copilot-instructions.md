# BootUI — Copilot instructions

BootUI is a **Spring Boot 4 starter** that adds a local-only developer console (Vue 3 SPA + REST API) to a host Spring Boot 4 app. The authoritative scope/behavior lives in `docs/SPECIFICATION.md` and `docs/PLAN.md` — read those before changing public behavior.

## Toolchain

- Java 25, Spring Boot 4.0.x (`spring-boot.version` in root `pom.xml`).
- Maven Wrapper (`./mvnw`) using Maven 3.9.16; do not require a system Maven install.
- Node.js / npm are downloaded automatically by the `frontend-maven-plugin`; do not add a manual Node install step.

## Build, run, test

```bash
# Full multi-module build (downloads Node, builds Vue UI, packages all JARs).
./mvnw clean install

# Backend-only iteration loop (skips the Vue build).
./mvnw -pl bootui-core,bootui-autoconfigure,bootui-spring-boot-starter,bootui-sample-app -am install

# Run the sample app (smoke-test path: http://localhost:8080/bootui).
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev

# Single test class / single test method.
./mvnw -pl bootui-core test -Dtest=SecretMaskerTests
./mvnw -pl bootui-core test -Dtest=SecretMaskerTests#detectsCommonSecretKeys

# Front-end inner loop (Vite dev server with HMR; proxies /bootui/api/* to a running sample app).
cd bootui-ui/src/main/frontend
npm install
npm run dev
# After changing UI code that needs to be re-bundled into the JAR:
./mvnw -pl bootui-ui install
```

CI (`.github/workflows/build.yml`) runs `./mvnw -B -ntp clean install` on Java 25 — keep that command green.

## Module topology

```
bootui-core                  Records (BootUiDtos), SecretMasker, version constants — no Spring dependency
bootui-autoconfigure         BootUiAutoConfiguration, @RestController endpoints, safety filter, config overrides
bootui-spring-boot-starter   Drop-in dependency: pulls autoconfigure + ui + spring-boot-starter-web + actuator
bootui-ui                    Vue 3 + Vite SPA; built into META-INF/resources/bootui/ via frontend-maven-plugin
bootui-sample-app            Reference Spring Boot 4 app used for demos and integration testing
```

`bootui-ui` has **no Java sources**. Its Maven build runs `npm install` + `npm run build`, then `maven-resources-plugin` copies `src/main/frontend/dist/` into `target/classes/META-INF/resources/bootui/`. Spring Boot then serves that classpath path automatically — consumers must never need npm.

## Activation & safety model (critical)

- `BootUiAutoConfiguration` is gated by `BootUiActivationCondition`, `@ConditionalOnWebApplication(SERVLET)`, and `@ConditionalOnClass(DispatcherServlet)`. It is registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, **not** component-scanned. New controllers must be added to `@Import(...)` on `BootUiAutoConfiguration`.
- Activation rules (see `BootUiActivationCondition.resolve`): `bootui.enabled=ON|OFF` wins, otherwise an active profile in `bootui.enabled-profiles` (`dev`, `local` by default) or `spring-boot-devtools` on the classpath turns BootUI on. `bootui.disabled-profiles` (`prod`, `production`) force-off unless `bootui.enabled=ON`.
- `LocalhostOnlyFilter` is registered with `Integer.MIN_VALUE` order on `/bootui/*` and `/bootui/api/*` and **fails closed** for non-loopback callers. Opt-out is `bootui.allow-non-localhost=true` only.
- Fail-closed mindset: when ambiguous, BootUI should be **disabled and silent**, not exposed.

## API & DTO conventions

- All endpoints live under `/bootui/api/**`. The browser UI is at `/bootui/` (Vite `base: '/bootui/'`, hash router).
- Never return raw Actuator descriptors. Map them to records in `io.github.jdubois.bootui.core.BootUiDtos` so the UI binds to a stable shape. Add new DTOs there as nested `record`s, not in feature packages.
- Actuator endpoints are consumed in-process via `ObjectProvider<XxxEndpoint>` (see `BeansController`, `LoggersController`). Always handle `getIfAvailable() == null` by returning an empty DTO — Actuator may be partially disabled.
- Any code path that surfaces a property name/value to the browser **must** route it through `SecretMasker` (or honor `BootUiProperties.exposeValues`) before serialization. See `ConfigController.toDto` and `ConfigOverrideService.displayValue` for the pattern.

## Configuration overrides flow

Runtime config edits are non-trivial — they use two cooperating components:

1. `BootUiOverridesEnvironmentPostProcessor` (wired via `META-INF/spring.factories`) runs at `HIGHEST_PRECEDENCE + 10` and `addFirst`s a `BootUiOverridesPropertySource` populated from `.bootui/application-bootui.properties` (path configurable via `bootui.overrides-file`).
2. `ConfigOverrideService` mutates that same property source at runtime and persists via `ConfigOverridesFileStore`.

Because already-bound `@ConfigurationProperties` beans won't auto-rebind, every mutation returns a `ConfigOverrideResult` whose `message` warns about restart caveats. Preserve that contract when adding new override paths.

## Java conventions

- Package root: `io.github.jdubois.bootui.<module>`. Controllers live in `...autoconfigure.web`, safety in `...autoconfigure.safety`, override plumbing in `...autoconfigure.config`.
- DTOs are immutable Java `record`s (Jackson-friendly with Spring Boot defaults — no annotations needed).
- Compiler is configured with `-parameters` (root `pom.xml` `<parameters>true</parameters>`); rely on that for `@RequestBody`/`@PathVariable` binding without explicit `value=` attributes.
- 4-space indent for Java/XML, 2-space for JS/Vue/JSON/YAML/MD (`.editorconfig`). UTF-8, LF, trailing newline.

## Frontend conventions

- Vue 3 **Composition API with `<script setup>`**, Vue Router 4 with `createWebHashHistory()`, Bootstrap 5.3 + bootstrap-icons. No TypeScript yet (despite the spec; current code is plain `.js` / `.vue`).
- API calls use **relative** paths (`fetch('api/overview')`) so they resolve against the `/bootui/` SPA base — do not hardcode `/bootui/api/...`.
- New panel = add a `views/Xxx.vue`, register it in `src/main/frontend/src/main.js` with an `icon` + `title` in `meta`; the sidebar in `App.vue` renders from `router.options.routes`.

## Contribution conventions (from `CONTRIBUTING.md`)

- Branch names start with the GitHub username, e.g. `jdubois/improve-config-ui`.
- Keep PRs small and update `docs/` whenever public behavior changes. The PR template's checklist (`./mvnw clean install`, sample-app smoke test, no committed secrets) is enforced in review.
- Spring Boot 3.x compatibility is **out of scope** for v0.1 — don't add compatibility shims.
