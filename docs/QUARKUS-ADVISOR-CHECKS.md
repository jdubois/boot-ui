# Quarkus Application Advisor checks

The Spring advisor panel, on Quarkus, runs a fixed, on-demand ruleset against the host application's
**Quarkus idioms** — not the Spring application context. It reads build-time counts of CDI scope
annotations, `@ConfigProperty` injection sites, JAX-RS endpoints, reactive (`Uni`/`Multi`) signatures,
`@Blocking` sites, and shared mutable fields on `@ApplicationScoped` beans, plus active/`%prod.` profile
keys from MicroProfile config. It never intercepts live traffic, exposes config values, or modifies the
application. Findings are heuristic review prompts; the right remediation depends on the application.

This is the Quarkus replacement for the Spring ruleset in [SPRING-CHECKS.md](SPRING-CHECKS.md): the panel
and DTO are shared, but the rules are framework-specific (CDI/Arc, MicroProfile Config vs Spring beans), so
there is no overlap. The panel keeps the shared id `spring` and endpoint `/bootui/api/spring`; on Quarkus
the UI relabels it "Quarkus".

## Availability and bounds

The advisor is always available on Quarkus (no extension required). Idiom counts are captured at build time
in dev/test only (skipped in `NORMAL`/production); profile keys are read live. Missing values fail safe
(counted as absent), so a sparse app yields fewer findings, not an error.

## Severity scale

- **HIGH** - likely to cause production trouble; fix before shipping.
- **MEDIUM** - common concurrency or hygiene risk.
- **LOW** - hygiene/maintainability prompt.
- **INFO** - informational; confirm intent.

## CDI

### QA-CDI-001 - Shared mutable state on @ApplicationScoped bean (MEDIUM)
Public or non-final fields on a single-instance `@ApplicationScoped` bean hold unsynchronised shared state.
Make fields `private final`, or move per-request state to a `@RequestScoped` bean.

### QA-CDI-002 - JAX-RS resource without an explicit scope (LOW)
A JAX-RS resource with no explicit CDI scope defaults to `@Singleton`. Annotate it `@ApplicationScoped`
(stateless) or `@RequestScoped` (per-request) to make intent clear.

### QA-EP-001 - Endpoints without managed beans (LOW)
JAX-RS endpoints exist but no CDI beans were discovered; move business logic into `@ApplicationScoped`
beans injected into resources.

## Config

### QA-CFG-001 - No @ConfigProperty usage (LOW)
No `@ConfigProperty` injection sites — configuration is likely read ad hoc. Use `@ConfigProperty` or a
`@ConfigMapping` interface for type-safe MicroProfile Config.

## Reactive

### QA-RX-001 - Reactive endpoints without @Blocking guards (INFO)
Endpoints return `Uni`/`Multi` but no `@Blocking` is declared; confirm no blocking call runs on the I/O
thread inside those handlers.

## Profiles

### QA-PROD-001 - Dev Services enabled in the prod profile (HIGH)
A `%prod.*devservices.enabled=true` key would start throwaway containers in production. Remove it and
configure a real datasource/broker.

### QA-PROF-001 - No profile configuration (LOW)
No active profile and no `%prod.` overrides — production likely shares dev defaults. Add `%prod.` overrides.
