# BootUI — Copilot instructions

BootUI is a **Spring Boot 4 starter** that adds a local-only developer console (Vue 3 SPA + REST API) to a host Spring Boot 4 app. The authoritative scope/behavior lives in `docs/SPECIFICATION.md`, `docs/PLAN.md`, and `docs/FEATURES.md` — read those before changing public behavior or visible panel behavior.

## Toolchain

- Java 25, Spring Boot 4.0.x (`spring-boot.version` in root `pom.xml`; currently 4.0.6).
- Maven Wrapper (`./mvnw`) using Maven 3.9.16; do not require a system Maven install.
- Published Maven coordinates use `com.julien-dubois.bootui:*`; Java packages remain `io.github.jdubois.bootui.*`.
- Node.js / npm for the packaged Vue app are downloaded automatically by the `frontend-maven-plugin` (`node.version` / `npm.version` in root `pom.xml`); do not add a manual Node install step for the Maven build.

## Build, run, test

```bash
# CI-equivalent multi-module build (downloads Node, builds Vue UI, packages all JARs).
./mvnw -B -ntp clean install

# Backend-only iteration loop (skips the Vue build).
./mvnw -pl bootui-core,bootui-autoconfigure,bootui-spring-boot-starter,bootui-sample-app -am install

# Run the sample app (smoke-test path: http://localhost:8080/bootui).
./mvnw -pl bootui-sample-app spring-boot:run -Dspring-boot.run.profiles=dev

# Single test class / single test method.
./mvnw -pl bootui-core test -Dtest=SecretMaskerTests
./mvnw -pl bootui-core test -Dtest=SecretMaskerTests#detectsCommonSecretKeys

# Front-end inner loop (Vite dev server with HMR; proxies /bootui/api/* to a running sample app).
(cd bootui-ui/src/main/frontend && npm install && npm run dev)
# After changing UI code that needs to be re-bundled into the JAR:
./mvnw -pl bootui-ui install

# Browser end-to-end suite (required for UI, browser-facing API, or sample-app changes).
(cd bootui-sample-app/e2e && npm ci && npx playwright install --with-deps chromium && npm test)

# Maven Central release path for non-SNAPSHOT versions.
./mvnw -B -ntp -Prelease clean deploy
```

CI (`.github/workflows/build.yml`) runs `./mvnw -B -ntp clean install` on Java 25, installs Playwright Chromium, and runs `bootui-sample-app/e2e` with `npm test`. CodeQL covers Java/Kotlin and JavaScript/TypeScript when code scanning is enabled. The release workflow (`.github/workflows/release.yml`) publishes `v*` tags to Maven Central through the `release` Maven profile and the Sonatype Central Publishing plugin.

## Release plumbing (Maven Central)

A few subtle constraints that have already burned us in past releases — preserve them when touching `pom.xml` files or the release profile:

- **Source-less modules (`bootui-ui`, `bootui-spring-boot-starter`) must attach their empty `javadoc.jar` at phase `package`, not `verify`.** The parent's `release` profile binds `maven-source-plugin`, `maven-javadoc-plugin`, and `maven-gpg-plugin:sign` all to `verify`, and gpg runs before any child-pom executions in the same phase. If the empty javadoc is attached at `verify`, gpg signs everything *except* the javadoc.jar, and Sonatype Central rejects the deployment with `Missing signature for file: ...-javadoc.jar`. If you add another source-less module, copy the existing `attach-empty-javadocs` execution (phase `package`, classifier `javadoc`, `skipIfEmpty=false`, fed from `target/empty-javadocs`).
- **The signing GPG public key must be queryable by fingerprint on `keys.openpgp.org` and/or `keyserver.ubuntu.com`.** Sonatype Central validates signatures against those keyservers; if the public key isn't there, every signature comes back `Invalid signature ... Could not find a public key by the key fingerprint`. After rotating the `GPG_PRIVATE_KEY` secret, re-publish the matching public key. On macOS, `gpg --send-keys` often fails with `Invalid argument` from dirmngr; the reliable fallback is the HTTPS upload APIs (`POST https://keys.openpgp.org/vks/v1/upload` with a JSON `{"keytext": ...}` body, and `POST https://keyserver.ubuntu.com/pks/add` with form field `keytext`).
- **A failed deployment still consumes the version coordinate in Central.** Once the publishing plugin uploads `com.julien-dubois.bootui:<artifact>:<version>` — even if validation rejects it — you cannot re-upload that exact GAV without dropping the failed deployment from the Sonatype Central Portal first. The default path is to bump the version (`./mvnw -B versions:set -DnewVersion=… -DgenerateBackupPoms=false`), commit, tag `v<version>`, and let the tag push trigger `release.yml` again.
- **Use the `Prepare Release` workflow (`.github/workflows/prepare-release.yml`) to bump versions** rather than running `versions:set` by hand. It runs `versions:set` *and* `perl`-substitutes the old version into `README.md` (the install snippet there is the only documentation that references a specific version), then verify-builds, commits, tags, and pushes. Manual `versions:set` skips the README rewrite, leaving the install snippet pointing at a stale (and possibly never-published) version.
- **The sample app is excluded from release deploys** via `<maven.deploy.skip>true</maven.deploy.skip>` in `bootui-sample-app/pom.xml`; keep it that way — it must still be built so the central-publishing plugin sees the full reactor, but it must not be published.

## Module topology

```
bootui-core                  Records (BootUiDtos), SecretMasker, BootUiInfo — no Spring dependency
bootui-autoconfigure         BootUiAutoConfiguration, @RestController endpoints, safety filter, config overrides, OSV scanning
bootui-spring-boot-starter   Drop-in dependency: pulls autoconfigure + ui + spring-boot-starter-web + actuator
bootui-ui                    Vue 3 + Vite SPA; built into META-INF/resources/bootui/ via frontend-maven-plugin
bootui-sample-app            Reference Spring Boot 4 app used for demos and integration testing
```

`bootui-ui` has **no Java sources**. Its Maven build runs `npm install` + `npm run build`, then `maven-resources-plugin` copies `src/main/frontend/dist/` into `target/classes/META-INF/resources/bootui/`. Spring Boot then serves that classpath path automatically — consumers must never need npm.

## Activation & safety model (critical)

- `BootUiAutoConfiguration` is gated by `BootUiActivationCondition`, `@ConditionalOnWebApplication(SERVLET)`, and `@ConditionalOnClass(DispatcherServlet)`. It is registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, **not** component-scanned. New controllers must be added to `@Import(...)` on `BootUiAutoConfiguration`.
- Activation rules (see `BootUiActivationCondition.resolve`): `bootui.enabled=ON|OFF` wins, otherwise an active profile in `bootui.enabled-profiles` (`dev`, `local` by default) or `spring-boot-devtools` on the classpath turns BootUI on. `bootui.disabled-profiles` (`prod`, `production`) force-off unless `bootui.enabled=ON`.
- `LocalhostOnlyFilter` is registered with `Integer.MIN_VALUE` order on `/bootui/*` and `/bootui/api/*` and **fails closed** for non-loopback callers. Opt-out is `bootui.allow-non-localhost=true` only.
- BootUI contributes local Actuator defaults via `BootUiActuatorDefaultsEnvironmentPostProcessor` while active. Host `management.*` settings must still win over BootUI defaults.
- Fail-closed mindset: when ambiguous, BootUI should be **disabled and silent**, not exposed.

## API & DTO conventions

- All endpoints live under `/bootui/api/**`. The browser UI is at `/bootui/` (Vite `base: '/bootui/'`, hash router).
- Never return raw Actuator descriptors. Map them to records in `io.github.jdubois.bootui.core.BootUiDtos` so the UI binds to a stable shape. Add new DTOs there as nested `record`s, not in feature packages.
- Actuator endpoints are consumed in-process via `ObjectProvider<XxxEndpoint>` (see `BeansController`, `LoggersController`). Always handle `getIfAvailable() == null` by returning an empty DTO — Actuator may be partially disabled.
- Any code path that surfaces a property name/value to the browser **must** route it through `SecretMasker` (or honor `BootUiProperties.exposeValues`) before serialization. See `ConfigController.toDto` and `ConfigOverrideService.displayValue` for the pattern.
- Do not perform dependency vulnerability lookups on page load. The Vulnerabilities panel lists local dependency inventory first and calls OSV.dev only after the explicit `/bootui/api/dependencies/scan` action; honor `bootui.dependencies.osv-enabled=false`.
- Dev Services must sanitize service connection details, keep Docker Compose entries as startup snapshots, cap service logs by `bootui.dev-services.log-tail-bytes`, and leave restart controls disabled unless `bootui.dev-services.restart-enabled=true`.

## Configuration overrides flow

Runtime config edits are non-trivial — they use two cooperating components:

1. `BootUiOverridesEnvironmentPostProcessor` (wired via `META-INF/spring.factories`) runs at `HIGHEST_PRECEDENCE + 10` and `addFirst`s a `BootUiOverridesPropertySource` populated from `.bootui/application-bootui.properties` (path configurable via `bootui.overrides-file`).
2. `ConfigOverrideService` mutates that same property source at runtime and persists via `ConfigOverridesFileStore`.

Because already-bound `@ConfigurationProperties` beans won't auto-rebind, every mutation returns a `ConfigOverrideResult` whose `message` warns about restart caveats. Preserve that contract when adding new override paths.

## Current panel surface

The selected `0.1.0-alpha.1` stance is to harden **all visible panels**, not hide newer ones. Keep API, UI, docs, and Playwright coverage aligned for:

- Overview, Startup Timeline, Memory, Health, Metrics, Conditions, Beans, Mappings, Configuration, Profile Diff, Loggers, Log Tail, HTTP Probe, DevTools, Dev Services, Scheduled Tasks, Data, Security, and Vulnerabilities.
- The router order in `bootui-ui/src/main/frontend/src/main.js`, `docs/FEATURES.md`, README feature table, and sample-app Playwright navigation tests should stay consistent when panels are added, renamed, hidden, or reordered.
- New browser-facing behavior usually needs a stable DTO, controller tests where practical, Vue route/view updates, `docs/FEATURES.md` / README updates, and an e2e spec when the UI or sample app behavior changes.

## Java conventions

- Package root: `io.github.jdubois.bootui.<module>`. Controllers live in `...autoconfigure.web`, safety in `...autoconfigure.safety`, override plumbing in `...autoconfigure.config`.
- DTOs are immutable Java `record`s (Jackson-friendly with Spring Boot defaults — no annotations needed).
- Compiler is configured with `-parameters` (root `pom.xml` `<parameters>true</parameters>`); rely on that for `@RequestBody`/`@PathVariable` binding without explicit `value=` attributes.
- 4-space indent for Java/XML, 2-space for JS/Vue/JSON/YAML/MD (`.editorconfig`). UTF-8, LF, trailing newline.
- Keep external/network behavior explicit and bounded: OSV scanning is user-initiated, timeouts and max package/advisory limits come from `BootUiProperties.Dependencies`, and failures should return a clear error status while preserving local data.

## Frontend conventions

- Vue 3 **Composition API with `<script setup>`**, Vue Router 4 with `createWebHashHistory()`, Bootstrap 5.3 + bootstrap-icons. No TypeScript yet (despite the spec; current code is plain `.js` / `.vue`).
- API calls use **relative** paths (`fetch('api/overview')`) so they resolve against the `/bootui/` SPA base — do not hardcode `/bootui/api/...`.
- New panel = add a `views/Xxx.vue`, register it in `src/main/frontend/src/main.js` with an `icon` + `title` in `meta`; the sidebar in `App.vue` renders from `router.options.routes`.
- The Vite dev server proxies `/bootui/api/*` to the running sample app, but packaged assets must work from `/bootui/` without requiring consumers to install Node.

## Contribution conventions (from `CONTRIBUTING.md`)

- Branch names start with the GitHub username, e.g. `jdubois/improve-config-ui`.
- Keep PRs small and update `docs/` whenever public behavior changes. The PR template's checklist (`./mvnw clean install`, sample-app smoke test, no committed secrets) is enforced in review.
- Run the Playwright suite when changing the Vue UI, browser-facing API response shapes, visible routes, or sample-app behavior.
- The sample app is for demos/integration tests and has `<maven.deploy.skip>true</maven.deploy.skip>`; do not publish it as part of Maven Central releases.
- Spring Boot 3.x compatibility is **out of scope** for v0.1 — don't add compatibility shims.
