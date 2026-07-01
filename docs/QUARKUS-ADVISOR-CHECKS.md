# Quarkus Application Advisor checks

The Spring advisor panel, on Quarkus, runs a fixed, on-demand ruleset against the host application's
**Quarkus idioms** — not the Spring application context. It reads build-time counts of CDI scope
annotations, `@ConfigProperty` injection sites, `@ConfigMapping` interfaces, JAX-RS endpoints, reactive
(`Uni`/`Multi`) signatures, `@Blocking` sites, `@Scheduled` methods, `@RegisterRestClient` interfaces,
`@RunOnVirtualThread`/`synchronized` combinations, the build JDK version, public mutable fields on JAX-RS
resources, and shared mutable fields on `@ApplicationScoped` beans, plus active/`%prod.` profile keys
(Hibernate schema strategy, datasource kind/URL, SQL logging, log level, Dev Services, clustered scheduler)
and HTTP settings (response compression, graceful-shutdown timeout, REST client timeouts) from MicroProfile
config. It never intercepts live traffic, exposes config values, or modifies the application. Findings are
heuristic review prompts; the right remediation depends on the application.

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
`@ApplicationScoped` beans are single instances shared across threads; public or non-final fields (other
than injected dependencies — `@Inject`/`@ConfigProperty`/`@RestClient` fields are excluded) hold
unsynchronised shared state. Make fields `private final`, or move per-request state to a `@RequestScoped`
bean.

### QA-CDI-002 - Public mutable field on a JAX-RS resource (MEDIUM)
JAX-RS resources default to `@Singleton`, so a public non-final (non-static) field is process-wide shared
mutable state accessed concurrently across requests. Make the field `private final`, inject it, or move
per-request state to a `@RequestScoped` bean. (Private fields set in `@PostConstruct`, e.g. injected
Micrometer meters, are not flagged.)

## Config

### QA-CFG-001 - No type-safe configuration (LOW)
The app declares no `@ConfigProperty` injection sites and no `@ConfigMapping` interfaces, suggesting
configuration is read ad hoc rather than through type-safe MicroProfile Config. Inject configuration with
`@ConfigProperty` or a `@ConfigMapping` interface.

### QA-CFG-002 - Hibernate SQL logging enabled in the prod profile (MEDIUM)
`%prod.quarkus.hibernate-orm.log.sql=true` logs every statement in production, hurting performance and
risking sensitive data in logs. Disable SQL logging in `%prod`; enable it only in `%dev` when debugging.

### QA-CFG-003 - Verbose log level in the prod profile (MEDIUM)
`%prod.quarkus.log.level` resolves to `DEBUG`/`TRACE`/`ALL`, far more verbose than production needs; it
hurts performance and risks leaking sensitive data into logs. Set `%prod.quarkus.log.level` to `INFO` or
`WARN`; use `DEBUG`/`TRACE` only in `%dev`.

## Reactive

### QA-RX-001 - Reactive endpoints with a blocking JDBC datasource (INFO)
One or more endpoints return `Uni`/`Multi` (run on the I/O event loop), and neither the method nor its
declaring resource class carries a `@Blocking` guard, while a blocking JDBC datasource is configured; a
JDBC call on the event loop stalls it. Annotate blocking work with `@Blocking`, or use a reactive datasource
client. (Only raised when a JDBC datasource is present, so fully-reactive apps are not flagged. The check is
correlated per endpoint — an unrelated `@Blocking` method elsewhere in the app does not suppress a finding on
a genuinely unguarded reactive endpoint.)

## Scheduling

### QA-SCH-001 - Scheduled tasks without a clustered scheduler (LOW)
`@Scheduled` methods run on every instance; without a clustered scheduler each replica fires the job,
causing duplicate work in a scaled-out deployment. Use the Quartz extension with
`quarkus.quartz.clustered=true`, or confirm single-instance deployment.

## Profiles

### QA-PROD-001 - Dev Services enabled in the prod profile (HIGH)
A `%prod.*devservices.enabled=true` key would start throwaway containers in production. Remove the `%prod`
Dev Services override; configure a real datasource/broker for prod.

### QA-PROD-002 - Destructive Hibernate schema strategy in the prod profile (HIGH)
A `%prod` Hibernate schema strategy of `drop-and-create`/`create`/`drop` rebuilds or drops the production
schema on every boot, destroying data. Use `none` (or `validate`) in `%prod` and manage the schema with
Flyway/Liquibase.

### QA-PROD-003 - In-memory/dev datasource in the prod profile (MEDIUM)
The `%prod` datasource targets an in-memory/embedded database (H2/HSQLDB/Derby) — by `db-kind` or a
`jdbc:*:mem:`/`:memory:` URL — so production data is lost on restart and never shared across instances.
Point `%prod` at a real managed database (PostgreSQL, MySQL, …).

### QA-PROF-001 - No profile configuration (INFO)
No active profile and no `%prod.` overrides were found. This is fine when production config is externalised
(env vars, Secrets/ConfigMaps); otherwise prod shares dev defaults. Add `%prod.` overrides (or externalise
config) so production differs from dev defaults.

## Web

### QA-WEB-001 - HTTP response compression disabled (INFO)
`quarkus.http.enable-compression` is not set (Quarkus's own default), so responses are not gzip/brotli
-compressed, increasing bandwidth and latency for text-heavy payloads. Set
`quarkus.http.enable-compression=true` (tune `quarkus.http.compress-media-types` if needed).

### QA-WEB-002 - Graceful shutdown grace period zeroed (MEDIUM)
`quarkus.shutdown.timeout` or `quarkus.http.shutdown.timeout` is explicitly set to `0`, disabling the
graceful-shutdown grace period; in-flight requests are dropped instead of being allowed to complete on
`SIGTERM`. Remove the override (or set a positive duration) so in-flight requests can drain before shutdown.

### QA-WEB-003 - REST client without a connect/read timeout (MEDIUM)
A `@RegisterRestClient` interface is declared, but no connect-timeout/read-timeout is configured anywhere.
Unlike many HTTP client libraries, Quarkus REST clients have no default timeout, so a slow/hanging remote
service can block a caller indefinitely. Set `quarkus.rest-client."<client-key>".connect-timeout` /
`read-timeout`, or the global `quarkus.rest-client.connect-timeout` / `read-timeout`.

## Performance

### QA-PERF-001 - No virtual-thread adoption (INFO)
The app declares JAX-RS endpoint(s) but none use `@RunOnVirtualThread` (checked only from JDK 21, when
virtual threads became stable/mainstream). If any endpoint performs blocking I/O (JDBC, file access,
blocking REST calls), running it on a virtual thread can improve throughput without sizing a worker thread
pool. Annotate blocking I/O-bound endpoint methods (or the resource class) with `@RunOnVirtualThread`.

### QA-PERF-002 - Virtual-thread pinning via synchronized (HIGH)
**Quarkus/JDK-specific — no Spring equivalent.** One or more `@RunOnVirtualThread` methods are also declared
`synchronized`. On JDK 21-23, entering a `synchronized` method or block pins the carrier thread instead of
yielding it, defeating the scalability benefit of virtual threads under load; [JEP
491](https://openjdk.org/jeps/491) removes this pinning starting in JDK 24. Replace `synchronized` with a
`java.util.concurrent.locks.ReentrantLock`, or upgrade to JDK 24+. (Detected via the method's `synchronized`
modifier; a `synchronized(lock) { }` block inside an otherwise-unsynchronized method is not visible to this
build-time check.)
