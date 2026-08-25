# Database

## Database Connection Pools

![BootUI Database Connection Pools panel](../images/bootui-database-connection-pools.webp)

Inspects supported JDBC connection pool beans, read-only. It never executes SQL, borrows connections, or resizes pools,
and fails closed when no supported pool implementation or pool beans are present.

For each pool it shows the pool identity, masked JDBC URL and username, driver, min/max sizing, and timeout/lifetime
settings, and surfaces a clear unavailable reason for closed or uninitialized pools. A local live chart polls bounded
snapshots of active, idle, total, and pending connections every two seconds so you can watch saturation trends without
leaving BootUI.

::: details On Quarkus: served over Agroal

The panel is served over **Agroal** (Quarkus' pool library) instead of HikariCP. A Quarkus provider maps the live Agroal
pool configuration and `AgroalDataSourceMetrics` (active/available/awaiting counts) into the same DTO shape, so the panel
looks and behaves identically. Pool metrics require `quarkus.datasource.jdbc.metrics.enabled=true`; with metrics disabled
the pool configuration still renders but the live snapshot is marked unavailable. A few Hikari-specific fields have no
faithful Agroal equivalent and are reported as neutral defaults (per-call validation timeout, keepalive interval, and
read-only flag).

:::

## SQL Trace

![BootUI SQL Trace panel](../images/bootui-sql-trace.webp)

The SQL Trace panel shows the SQL statements your application recently executed. Capture uses a hand-written JDBC tracing
proxy on the JDK's own dynamic-proxy support — BootUI bundles **no** third-party database-proxy library. Each recorded
execution row expands to reveal the full statement, bound parameters, statement type, connection id, executing thread,
call site, and error.

Executions are retained in a bounded in-memory ring buffer (most recent first) with aggregate stats: total/average/max
time, slow-query and failure counts, per-category counters, and evictions. A configurable slow-query threshold highlights
expensive statements, and local-only **Pause/Resume** and **Clear** actions stop recording or empty the buffer without
unwrapping the data source. Repeated `SELECT`s that look like an **N+1 access pattern** are flagged (repeat count set by
`bootui.sql-trace.n-plus-one-threshold`); a flagged group lists the distinct call site(s) — class, method, line —
most-recently-seen first and bounded to a handful, so you can jump straight to the repository or service method causing
the repetition.

::: details How capture works

BootUI transparently wraps each `DataSource` bean and intercepts statement execution on the resulting
`Connection`/`Statement`/`PreparedStatement`/`CallableStatement` objects, recording the SQL text, statement type, SQL
category (`SELECT`/`INSERT`/`UPDATE`/`DELETE`/`DDL`/`OTHER`), wall-clock duration, affected-row counts, batch size,
originating connection, executing thread, the call site that triggered it (when call-site capture is enabled), and any
failure. Spring's delegating/routing `DataSource` wrappers are skipped so executions are not double-counted, and wrapping
**fails open**: if a `DataSource` cannot be proxied it is left untouched so application database access is never
compromised.

:::

### Rankings

Above the execution list the panel ranks the retained window twice. Both tables deep-link into the filtered execution
list below, so a slow ranking row is one click away from the individual executions behind it.

**Statement rankings** aggregate executions by a normalized statement — literals and existing bind markers are collapsed
to `?` and `IN (…)` lists folded, so equivalent parameterized executions group together without ever exposing a bound
value. They rank by cumulative duration, slowest single execution, execution count, average duration, error count, p95,
or p99, alongside p50/p95/p99 durations and each group's share of retained database time. A statement that scores zero on
the selected criterion is not ranked for it, so "top by errors" never lists statements that never failed. This is a
*different* grouping from the **Most frequent statements** table (a fallback shown when statement rankings are
unavailable), which keeps literal values so you can see the exact statements that repeated.

**Database time by request route** attributes those executions back to the inbound requests that issued them. Each route
row shows its requests, executions, distinct statements, error count, and share of retained database time, and expands to
that route's own top statements.

::: details How route grouping resolves a template

Grouping uses the framework's own route template (`GET /api/sample/orders/{id}`) when the adapter can supply one,
otherwise matches the captured path against the application's own declared route mappings, and falls back to a masked
path — identifier-looking segments replaced with `{value}`, query string discarded — only when neither is available.
Matching a declaration is deliberately strict: it must agree segment for segment and be the single most literal match,
and two equally plausible declarations produce no template at all. That strictness is a privacy property as much as a
grouping one, because masking alone cannot tell a word-shaped path parameter such as `/api/users/alice` from a fixed
route segment.

:::

### Attribution is evidence, not lifetime metrics

These rankings are **diagnostic evidence over the bounded capture window**: they describe only the statements still
retained in the ring buffer, and the panel states that window — retained statements, buffer size, evictions, and the age
of the oldest retained execution — inline. Attribution is deliberately conservative and correlates a statement to a
request in tiers, each requiring a single unambiguous candidate:

| Tier         | When used                                     |
| ------------ | --------------------------------------------- |
| Trace id     | Always tried first                            |
| Serving thread | JVM only, where thread affinity is reliable |
| Time window  | Last resort                                   |

Executions it cannot place, and executions more than one request matched equally well, are kept in explicit
**Unattributed** and **Ambiguous** buckets rather than being dropped or guessed. A statement carrying a trace id no
retained request carries is left unattributed rather than handed to a weaker tier, and a statement already running when a
request began is never absorbed into it.

On Spring WebFlux and Quarkus a request is not pinned to one thread, so only trace-id and time-window correlation are
used and the panel says so. On WebFlux the request evidence arrives with the OpenTelemetry integration; without it the
panel reports route attribution as unavailable and names the requirement, while rankings still work. Quarkus has no
per-request route template, so it resolves declared JAX-RS mappings after the fact and falls back to a masked path.

### Privacy and configuration

The panel is read-mostly and privacy-conscious. Parameter bindings are **not** captured by default; even when capture is
enabled they are suppressed under metadata-only value exposure and routed through BootUI's masking rules, with an inline
warning when captured parameters are shown in clear text. Call-site capture is separate: a call site is metadata about
your own code (class, method, line), never a bound value, so it is **not** privacy-gated. `bootui.sql-trace.capture-call-site`
defaults to `true` and only trades a small, defensively-bounded stack walk per statement for the ability to see where a
query came from; set it to `false` to skip that walk. The panel fails closed when no `DataSource` bean is wrapped. Tracing, the initial recording state, parameter capture, call-site capture, buffer
size, the slow-query and N+1 thresholds, and SQL/parameter truncation limits are all configurable under
`bootui.sql-trace.*`.

The panel refreshes over **Server-Sent Events** instead of fixed-interval polling: the browser subscribes to
`/bootui/api/sql-trace/stream` and the server pushes a small coalesced notification the moment a statement is captured,
the buffer is cleared, or recording is paused/resumed, prompting a re-fetch. The push carries no data — masking,
truncation, and value-exposure rules still apply through the regular endpoint — and bursts of statements fold into a
single refresh. When the auto-refresh toggle is off or the tab is hidden the stream is closed, and the panel falls back
to its initial load when Server-Sent Events are unavailable.

> **GraalVM native images are supported.** The tracing proxies are created over a fixed set of standard JDBC API
> interfaces, and those JDK proxies are registered as native-image proxy metadata by BootUI, so SQL Trace works in a
> native executable. If a proxy ever cannot be created (for example an interface set that was not registered), wrapping
> still fails open and the `DataSource` is left untraced rather than breaking application startup.

::: details Vendor-interface preservation on the JVM

On the JVM (Spring MVC and WebFlux), the traced proxy also advertises every interface the original `DataSource` bean's
concrete class implements, so a vendor-specific contract — such as Oracle UCP's `PoolDataSource` — survives wrapping and
by-type/by-interface injection of that vendor interface keeps resolving to the traced proxy. This extra interface set is
not used in a GraalVM native image, where the interface set must be known and registered at build time; native images
keep using the fixed, pre-registered set above.

:::

::: details On Quarkus: two feeders reach Spring parity

The panel is identical, running over the same engine recorder (buffer, grouping, stats, N+1 detection, and call-site
capture are byte-identical to Spring). Capture comes from two complementary feeders:

- an `@Alternative` Agroal `DataSource` that wraps the default pool with the same JDK-proxy tracer. It handles manual
  JDBC access and is gated on a datasource being present.
- a `@PersistenceUnitExtension` Hibernate `StatementInspector` that records ORM-issued SQL for the default persistence
  unit (gated on `quarkus-hibernate-orm`; SQL from a named persistence unit is not traced). This is needed because
  Hibernate ORM resolves its pool from Agroal's own registry and so bypasses the CDI `DataSource`.

Between them the panel reaches parity with Spring whether SQL originates from raw JDBC or the ORM. Statement text, type,
category, execution count, and N+1 detection are full-fidelity; for ORM SQL the per-statement duration, affected-row
count, and bound parameters are not available (the `StatementInspector` SPI exposes only the SQL text at prepare time,
with no execution-end hook), so those degrade cleanly while never leaking ORM parameter values. Both feeders are wired in
dev/test only and never in production.

:::

## Hibernate Statistics

![BootUI Hibernate Statistics panel](../images/bootui-hibernate-statistics.webp)

The Hibernate Statistics panel exposes a live, read-only snapshot of Hibernate's own `org.hibernate.stat.Statistics` for
the application's `SessionFactory`. It is a continuously-refreshing runtime monitor — closer in spirit to Database
Connection Pools or SQL Trace than to an advisor — and is deliberately separate from the Hibernate Advisor panel, which
runs static on-demand checks and reports findings.

The snapshot covers session/transaction counts (opened/closed sessions, flushes, connections, transactions, successful
transactions), entity and collection load/fetch/insert/update/delete/recreate/remove counts, query execution counts (and
the slowest recorded query), and — when enabled — query-cache and second-level-cache hit/miss/put counters, including
per-region second-level cache breakdowns.

- **Availability gating**: the panel requires a resolvable Hibernate `SessionFactory` (via
  `EntityManagerFactory#unwrap(SessionFactory.class)`). When statistics collection is disabled, the panel offers an
  explicit **Enable for this runtime** action. It calls `Statistics#setStatisticsEnabled(true)`, starts collecting from
  that moment, and does not rewrite application configuration. The persistent startup alternatives remain
  `hibernate.generate_statistics=true` and `quarkus.hibernate-orm.statistics=true`; this is the same HIB-CONFIG-007
  recommendation the static advisor makes (see [HIBERNATE-CHECKS.md](../HIBERNATE-CHECKS.md)).
- **Read-mostly**: the only mutation enables future collection for the current runtime and is covered by BootUI's
  localhost, cross-site-write, and panel read-only policy. There is no reset/clear action, so BootUI never discards
  Hibernate's counters.
- **Out of scope for this iteration**: no per-entity or per-query drill-down beyond what `Statistics` itself
  exposes (e.g. no per-entity-class breakdown, no query-by-query cache stats); only the **first** resolved
  `EntityManagerFactory`/`SessionFactory` is inspected, so multi-persistence-unit applications only see statistics
  for one persistence unit — a known limitation for a future iteration.
- **Not filtered by `bootui.monitoring.exclude-self`**: Hibernate statistics are process-global counters on the
  `SessionFactory`, not per-request/per-caller data, so there is nothing to attribute to "self" the way HTTP
  exchange or SQL-trace filtering does. BootUI's own entity-metamodel introspection for the advisor scan does not
  open sessions or transactions, so it does not inflate these counters in practice, but this is a documented
  limitation rather than an enforced filter.

The panel is identical on Quarkus, gated on the same Hibernate ORM capability as the Hibernate advisor panel.

## Transactions

![BootUI Transactions panel](../images/bootui-transactions.webp)

The Transactions panel shows the `@Transactional` boundaries your application recently ran — begin, commit, and rollback
events — captured by BootUI's own listener wiring, **not** a third-party transaction-observability library. On Spring MVC
and WebFlux, BootUI contributes a `TransactionExecutionListener` (Spring Framework 6.1+) through Spring Boot's standard
transaction-manager customization, completing registration for user-defined `ConfigurableTransactionManager` beans after
singleton initialization. It composes with (never replaces) the application's own transaction management and listeners.
Managers that do not implement the configurable listener SPI remain unobserved.

Transactions are retained in a bounded in-memory ring buffer (most recently completed first) with aggregate stats
(total/average/max duration, slow- and connection-held counts, commit/rollback/unknown outcome counts, and nested-
transaction count). The panel renders a parent/child tree so a root transaction's nested calls are visible directly
underneath it, and each row expands to reveal its thread, trace id, read-only flag, and any error. Configurable
slow-transaction and connection-hold-time thresholds flag transactions worth a closer look, and local-only
**Pause/Resume** and **Clear** actions stop recording or empty the buffer without deregistering the listener.

::: details What each captured transaction records

Each captured transaction records:

- the declared boundary name, typically `ClassName.methodName`.
- a best-effort `propagation` classification. `NEW` means the manager actually started a transaction; `PARTICIPATING`
  means it joined one already active on the same thread. Spring's listener SPI does not expose the declared `Propagation`
  enum value itself.
- the JDBC isolation level active at begin time.
- the outcome — `COMMITTED`, `ROLLED_BACK`, or `UNKNOWN` when a begin/commit/rollback threw.
- start/end timestamps and wall-clock duration.
- the enclosing transaction's id, so nested transactions form a call tree.
- the executing thread and, when present, the active Micrometer/W3C trace id.

Each entry is also correlated, by thread and time window, to the executions already captured by the SQL Trace panel,
surfacing the number of SQL statements and distinct JDBC connections a transaction touched without instrumenting anything
twice.

:::

The panel is read-mostly: transaction metadata (method names, propagation, isolation, thread names, trace ids) is not
sensitive application data the way bound SQL parameters are, so none of it is masked or gated behind value-exposure
settings. It fails closed — reporting unavailable with a clear reason — when no `PlatformTransactionManager` bean exists,
or when a WebFlux application uses only a `ReactiveTransactionManager` (R2DBC), since Spring's transaction-execution
listener hook exists solely on the blocking `PlatformTransactionManager` SPI. Capture, the initial recording state,
buffer size, and the slow-transaction and connection-hold thresholds are all configurable under `bootui.transactions.*`.

The panel refreshes over **Server-Sent Events**: the browser subscribes to `/bootui/api/transactions/stream` and the
server pushes a small coalesced notification whenever a transaction completes, the buffer is cleared, or recording is
paused/resumed. When the auto-refresh toggle is off or the tab is hidden the stream is closed, and the panel falls back
to its initial load when Server-Sent Events are unavailable.

::: details Sample app scenarios and MCP exposure

The Spring sample's product list and uncached product-search operations use explicit read-only service transactions, so
loading or checking products produces representative entries here as well as in SQL Trace. Its sample action lab has a
one-click transaction scenario set that generates committed, slow, rolled-back, and nested boundaries, plus a Hibernate
second-level cache action that loads one entity through two persistence contexts to produce visible miss, put, and hit
counters in Hibernate Statistics. When the opt-in MCP Server is enabled on Spring MVC or WebFlux, `get_transactions`
exposes the same bounded, local-only report to an agent; it is not advertised on Quarkus because transaction capture is
not available there.

:::

> **Quarkus is not applicable.** Quarkus' transaction management goes through Narayana's JTA `TransactionManager`/
> `Synchronization` or the CDI `@Transactional` interceptor, neither of which exposes a comparable per-boundary listener
> hook without much more invasive instrumentation than Spring's opt-in listener registration. Rather than force false
> parity, the Quarkus endpoint always reports unavailable with a clear reason explaining the gap.

## Spring Data

![BootUI Spring Data panel](../images/bootui-data.webp)

The Spring Data panel inspects Spring Data repositories. It shows repository interfaces, domain types, ID types, and query
methods, and degrades to a clear empty state when Spring Data is not present or no repositories are registered.

## Flyway

![BootUI Flyway panel](../images/bootui-flyway.webp)

The Flyway panel shows schema migrations for each `Flyway` bean in the context and lists, per database, the current schema
version together with applied and pending migrations (version, description, type, script, state, installed-by,
installed-on, execution time, and checksum). Multiple or named datasources appear independently. When Spring Modulith
module-aware Flyway migrations are active, the panel shows the root and module-specific history tables separately so
module-local migrations are visible even though Spring Modulith creates those Flyway views only during migration.

The panel also exposes confirmation-gated `migrate` and `clean` actions. They are available by default for trusted local
sessions and are blocked by `bootui.read-only=true` or `bootui.panels.flyway.read-only=true`; `clean` also requires
Flyway's own `clean-disabled=false` setting. Spring Modulith module-aware entries are read-only in BootUI because their
module-specific history tables are managed by Spring Modulith's migration strategy. The panel degrades to a clear empty
state when Flyway is not on the classpath or no `Flyway` beans are present.

On Quarkus the panel is identical, because both frameworks use the same `org.flywaydb.core.Flyway` library. The Quarkus
adapter reads the active `io.quarkus.flyway.runtime.FlywayContainer` beans (one per datasource, default or
`@FlywayDataSource`-named) and exposes the same confirmation-gated `migrate`/`clean` actions, with `clean` likewise
honoring Flyway's disabled-by-default setting (`quarkus.flyway.clean-disabled`). The optional `quarkus-flyway` extension
is capability-gated, so when it is absent the panel reports an honest "add the quarkus-flyway extension" reason rather
than failing. The Spring Modulith module-aware history block is Spring-specific and is not reported on Quarkus.

## Liquibase

![BootUI Liquibase panel](../images/bootui-liquibase.webp)

The Liquibase panel shows change sets for each discovered Liquibase database (on Spring Boot, each `SpringLiquibase`
bean; on Quarkus, each active `LiquibaseFactory` — including `@LiquibaseDataSource`-named datasources). It reads the
change-log history and configured changelog, then lists applied and pending change sets per database (id, author,
change-log, description, comments, execution type, date executed, order executed, checksum, tag, deployment id,
contexts, and labels). Multiple or named datasources appear independently.

The panel also exposes a confirmation-gated `update` action that applies pending change sets. It is available by default
for trusted local sessions and is blocked by `bootui.read-only=true` or `bootui.panels.liquibase.read-only=true`
(enforced identically on Spring and Quarkus). The panel fails closed per database when its history cannot be read
and degrades to a clear empty state when Liquibase is not on the classpath or no Liquibase databases are present.
