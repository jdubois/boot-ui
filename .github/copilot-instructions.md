# BootUI repository instructions

BootUI is a local-only developer console delivered from one codebase through three request stacks: Spring Boot 4 MVC,
Spring Boot 4 WebFlux, and Quarkus. All three serve the same Vue UI and stable JSON contract through a
framework-neutral engine.

## Authoritative context

- Read `docs/SPECIFICATION.md`, `docs/PLAN.md`, `docs/features/`, `docs/WEBFLUX-SUPPORT.md`, and
  `docs/QUARKUS-SUPPORT.md` before changing public behavior, panel availability, or visible UI.
- Read `PRODUCT.md` and `DESIGN.md` before changing user-facing design or interaction.
- Spring MVC is the complete reference stack. WebFlux and Quarkus support are capability-specific; verify current
  availability rather than assuming parity or absence.

## Architecture invariants

- Preserve `bootui-core <- bootui-engine <- adapters`. Shared modules never depend on Spring, Quarkus, or a JSON library.
- Put reusable behavior and policy in the engine. Keep Spring and Quarkus adapters thin and native to their frameworks.
- Keep core DTO records immutable, annotation-free, and byte-compatible across Jackson 3 and Jackson 2 serialization.
- Treat Spring MVC, Spring WebFlux, and Quarkus as the default scope for shared behavior. When a capability is
  stack-specific, expose that honestly through availability rather than forking the shared UI contract.
- Keep optional integrations classloading-safe when their dependency is absent.

## Safety and behavior

- BootUI remains local-only and fail-closed. Preserve shared localhost, Host/DNS-rebinding, cross-site-write, masking,
  and per-panel enable/read-only policy.
- Never expose secrets or raw property values without `SecretMasker` and the exposure policy.
- Never perform network calls, scans, or mutations on page load. User-triggered external work must be bounded by
  configuration and return clear failure state while preserving local data.
- Do not hide or swallow invalid input and failures. Use existing canonical errors and adapter mappings.

## Delivery workflow

- Make focused changes and update directly coupled tests and documentation.
- Use the Maven Wrapper and existing npm scripts. Run the smallest targeted validation that proves the change, then the
  required conformance or browser suite for public cross-adapter or UI behavior.
- Before committing or publishing a PR, format touched areas and pass the corresponding checks:

  ```bash
  ./mvnw -B -ntp spotless:apply
  ./mvnw -B -ntp spotless:check
  (cd bootui-ui/src/main/frontend && npm run format && npm run format:check)
  (cd bootui-spring-sample-app/e2e && npm run format && npm run format:check)
  (cd bootui-quarkus-sample-app/e2e && npm run format && npm run format:check)
  ```

- `spotless:check` runs over the whole reactor. On Java 17 that includes the JDK-gated Quarkus sample app, so format
  Quarkus files even when a newer local JDK skips augmentation.
- Keep pull requests small. Do not publish sample or test modules. Do not add Spring Boot 3 compatibility shims.

## Parallel worktrees

Use an isolated Maven repository such as `-Dmaven.repo.local=.m2` to avoid overwriting another worktree's artifacts.
Use the same isolated repository for both installation and subsequent app runs.
For Copilot app sessions, prefer the `MAVEN_OPTS` pattern in `.github/github-app.yml`, which applies to every Maven
invocation in that script. Do not put `${maven.multiModuleProjectDirectory}/.m2` in global `~/.m2/settings.xml`; IntelliJ
may pass it through literally as a non-absolute path. Do not commit a project-wide repository override solely for
worktree isolation.

Detailed rules are path-scoped under `.github/instructions/` and apply automatically by file path. The
`bootui-vertical-pr` custom agent under `.github/agents/` is available for end-to-end feature delivery; it supplements
rather than replaces repository safety rules.
