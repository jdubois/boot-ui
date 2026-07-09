# Quarkus Application Advisor checks

The Spring advisor panel, on Quarkus, runs a fixed, on-demand ruleset against the host application's
**Quarkus idioms** — not the Spring application context. It reads build-time counts of CDI scope
annotations, `@ConfigProperty` injection sites, `@ConfigMapping` interfaces, JAX-RS endpoints, reactive
(`Uni`/`Multi`/`CompletionStage`/`CompletableFuture`/`Publisher`) signatures, `@Blocking`/`@Transactional`
sites, `@Scheduled` methods, `@RegisterRestClient` interfaces, `@RunOnVirtualThread` (method- or class-level)
/`synchronized` combinations, the build JDK version, public mutable fields on JAX-RS resources (excluding
`@RequestScoped` ones), and shared mutable fields on `@ApplicationScoped` and `@Singleton` beans, plus
active/`%prod.` profile keys (Hibernate schema strategy, the legacy `database.generation` property, datasource
kind/URL/pool size, SQL logging, log level, Dev Services, clustered scheduler) and HTTP settings (response
compression, graceful-shutdown timeout presence/value, REST client timeouts) from MicroProfile config. It
never intercepts live traffic, exposes config values, or modifies the application. Findings are heuristic
review prompts; the right remediation depends on the application.

This is the Quarkus replacement for the Spring ruleset in [SPRING-CHECKS.md](SPRING-CHECKS.md): the panel
and DTO are shared, but the rules are framework-specific (CDI/Arc, MicroProfile Config vs Spring beans), so
there is no overlap. The panel keeps the shared id `spring` and endpoint `/bootui/api/spring`; on Quarkus
the UI relabels it "Quarkus".

## Availability and bounds

The advisor is always available on Quarkus (no extension required). Idiom counts are captured at build time
in dev/test only (skipped in `NORMAL`/production); profile keys are read live. Missing values fail safe
(counted as absent), so a sparse app yields fewer findings, not an error.

## Severity scale

- **CRITICAL** - active data-loss or destructive-in-production risk; fix immediately.
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
mutable state accessed concurrently across requests. A resource explicitly annotated `@RequestScoped` gets a
fresh instance per request and carries no shared-state risk, so it is excluded from this scan. Make the field
`private final`, inject it, or move per-request state to a `@RequestScoped` bean. (Private fields set in
`@PostConstruct`, e.g. injected Micrometer meters, are not flagged.)

### QA-CDI-003 - Shared mutable state on a @Singleton bean (MEDIUM)
`@Singleton` beans are a single instance shared across threads, exactly like `@ApplicationScoped` — the same
unsynchronised-shared-state risk applies to public or non-final fields (other than injected dependencies).
Make fields `private final`, or move per-request state to a `@RequestScoped` bean.

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

### QA-CFG-004 - Legacy Hibernate schema-generation property in use (LOW)
`quarkus.hibernate-orm.database.generation` (or a `%profile`/named-persistence-unit variant) is deprecated
for removal in favour of `quarkus.hibernate-orm.schema-management.strategy`; it still works today but may be
removed in a future Quarkus release. Migrate to `quarkus.hibernate-orm.schema-management.strategy` (it
accepts the same values: `none`/`create`/`drop-and-create`/`drop`/`update`/`validate`).

## Reactive

### QA-RX-001 - Reactive endpoints with a blocking JDBC datasource (HIGH)
One or more endpoints return `Uni`/`Multi`/`CompletionStage`/`CompletableFuture`/`Publisher` (run on the I/O
event loop), and neither the method nor its declaring resource class carries a `@Blocking` or `@Transactional`
guard (Quarkus REST dispatches a `@Transactional` method to a worker thread just like `@Blocking` — see the
[REST execution-model docs](https://quarkus.io/guides/rest#execution-model-blocking-non-blocking), latest stable guide),
while a blocking JDBC datasource is configured; a JDBC call on the event loop stalls it and throws
`BlockingOperationNotAllowedException` at runtime. This is one of the most common and severe Quarkus
production footguns, hence HIGH rather than an informational note. Annotate blocking work with `@Blocking`
(or make the method `@Transactional`), or use a reactive datasource client. (Only raised when a JDBC
datasource is present, so fully-reactive apps are not flagged. The check is correlated per endpoint — an
unrelated guard elsewhere in the app does not suppress a finding on a genuinely unguarded reactive endpoint.)

## Scheduling

### QA-SCH-001 - Scheduled tasks without a clustered scheduler (LOW)
`@Scheduled` methods run on every instance; without a clustered scheduler each replica fires the job,
causing duplicate work in a scaled-out deployment. Use the Quartz extension with
`quarkus.quartz.clustered=true`, or confirm single-instance deployment.

## Profiles

### QA-PROD-001 - Dev Services override present in the prod profile (LOW)
A `%prod.*devservices.enabled=true` key is set. Dev Services is a build-time/augmentation-only mechanism —
per the [Dev Services guide](https://quarkus.io/guides/dev-services), it starts containers during
`quarkus:dev`/`quarkus:test`/augmentation only, and never runs in a `LaunchMode.NORMAL` packaged JAR or
native executable regardless of this property's value. So the override has no effect in production; its
presence is a config-hygiene signal (leftover or copy-pasted config) rather than an active production risk,
hence LOW rather than HIGH. Remove the unused `%prod` Dev Services override to avoid confusing readers of the
config.

### QA-PROD-002 - Destructive Hibernate schema strategy in the prod profile (CRITICAL or HIGH)
A `%prod` Hibernate schema strategy of `drop-and-create`/`create`/`drop` rebuilds or drops the production
schema on every boot, destroying data — **CRITICAL**, matching the sibling Hibernate advisor's
[`HIB-CONFIG-002`](HIBERNATE-CHECKS.md). A strategy of `update` is a distinct, slightly lower risk: Hibernate
silently alters the schema in place (adding/changing columns or tables to match the entity model) instead of
destroying data outright, which can still lock tables or apply an unreviewed structural change directly to
production — **HIGH**. Use `none` (or `validate`) in `%prod` and manage the schema with Flyway/Liquibase.

### QA-PROD-003 - In-memory/dev datasource in the prod profile (MEDIUM)
The `%prod` datasource targets an in-memory/embedded database (H2/HSQLDB/Derby) — by `db-kind` or a
`jdbc:*:mem:`/`:memory:` URL — so production data is lost on restart and never shared across instances.
Point `%prod` at a real managed database (PostgreSQL, MySQL, …).

### QA-PROF-001 - No prod-specific configuration overrides (INFO)
No `%prod.` overrides were found anywhere in config. (Rebased from active-profile emptiness: Quarkus's
`SmallRyeConfig.getProfiles()` almost always resolves to a live profile — dev/test/prod — when read from a
running app, so an "active profile is empty" condition rarely fires in practice; absence of any `%prod.`
override key is the signal this rule actually intends to catch.) This is fine when production config is
externalised (env vars, Secrets/ConfigMaps); otherwise prod shares dev defaults for every setting. Add
`%prod.` overrides (or externalise config) so production differs from dev defaults.

## Database

### QA-DB-001 - JDBC datasource without an explicit pool size (LOW)
A JDBC datasource is configured, but `quarkus.datasource.jdbc.max-size` is never set. Agroal (Quarkus's
connection pool) defaults to a max pool size of 50. Under high concurrency — especially with virtual threads
increasing request parallelism — the default pool can become a bottleneck or exhaust the database's own
connection limit. Set `quarkus.datasource.jdbc.max-size` (with a `%prod` override if it should differ from
dev) to a value sized for the target database and expected concurrency.

## Web

### QA-WEB-001 - HTTP response compression disabled (INFO)
`quarkus.http.enable-compression` is not set (Quarkus's own default), so responses are not gzip/deflate
-compressed, increasing bandwidth and latency for text-heavy payloads. Set
`quarkus.http.enable-compression=true` (tune `quarkus.http.compress-media-types` if needed).

### QA-WEB-002 - Graceful shutdown grace period zeroed (MEDIUM)
`quarkus.shutdown.timeout` or `quarkus.http.shutdown.timeout` is explicitly set to `0`, disabling the
graceful-shutdown grace period; in-flight requests are dropped instead of being allowed to complete on
`SIGTERM`. Remove the override (or set a positive duration) so in-flight requests can drain before shutdown.

### QA-WEB-003 - REST client connect/read timeout disabled or excessive (MEDIUM)
A connect-timeout or read-timeout for a `@RegisterRestClient` interface is explicitly set to `0` (no timeout)
or to an excessively high value (over 5 minutes). Quarkus REST clients already default to a 15s
connect-timeout and 30s read-timeout (`quarkus.rest-client.connect-timeout` / `read-timeout` — see
[`RestClientsConfig`](https://github.com/quarkusio/quarkus/blob/main/extensions/resteasy-classic/rest-client-config/runtime/src/main/java/io/quarkus/restclient/config/RestClientsConfig.java)),
so a slow/hanging remote service is normally bounded; this override removes that safety net. Remove the
override to keep Quarkus's 15s/30s defaults, or set a specific, bounded timeout appropriate for the remote
service.

### QA-WEB-004 - Graceful shutdown timeout never configured (LOW)
Neither `quarkus.shutdown.timeout` nor `quarkus.http.shutdown.timeout` is set anywhere. Quarkus's graceful
shutdown is opt-in — with no timeout configured at all, the application exits immediately on `SIGTERM`
instead of draining in-flight requests. This is a distinct, lower-severity case from QA-WEB-002 (which fires
only when the timeout is explicitly disabled via `=0`); the two are mutually exclusive; see the
[lifecycle guide](https://quarkus.io/guides/lifecycle#graceful-shutdown). Set `quarkus.shutdown.timeout` to a
positive duration (e.g. `10s`) so in-flight requests can drain before shutdown.

## Performance

### QA-PERF-001 - No virtual-thread adoption (INFO)
The app declares JAX-RS endpoint(s) but none use `@RunOnVirtualThread` at the method or class level (checked
only from JDK 21, when virtual threads became stable/mainstream). If any endpoint performs blocking I/O
(JDBC, file access, blocking REST calls), running it on a virtual thread can improve throughput without
sizing a worker thread pool. Annotate blocking I/O-bound endpoint methods (or the resource class) with
`@RunOnVirtualThread`.

### QA-PERF-002 - Virtual-thread pinning via synchronized (HIGH)
**Quarkus/JDK-specific — no Spring equivalent.** One or more methods that run on a virtual thread — via a
method-level `@RunOnVirtualThread`, or because their declaring class carries a class-level
`@RunOnVirtualThread` (which makes every method in that class run on a virtual thread) — are also declared
`synchronized`. On JDK 21-23, entering a `synchronized` method or block pins the carrier thread instead of
yielding it, defeating the scalability benefit of virtual threads under load; [JEP
491](https://openjdk.org/jeps/491) removes this pinning starting in JDK 24. Replace `synchronized` with a
`java.util.concurrent.locks.ReentrantLock`, or upgrade to JDK 24+. (Detected via the method's `synchronized`
modifier; a `synchronized(lock) { }` block inside an otherwise-unsynchronized method is not visible to this
build-time check.)
