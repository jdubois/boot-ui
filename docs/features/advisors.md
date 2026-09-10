# Advisors

BootUI's advisors run explicit, on-demand, rule-based scans and surface severity-ranked findings and coverage limits.
Each advisor is read-only and inspects a different facet of the application — compiled
architecture, the REST layer, the live Spring context, persistence, JVM memory, and security. A usable advisor
assessment shows the same 0–100 **Known-findings score** in its panel and Overview (100 minus the weighted finding
penalty). This summarizes retained penalties, not application health or safety.

### Score eligibility

A diagnostic report is not necessarily eligible for a score. `SCANNED` and `PARTIAL` reports can score when a valid
severity summary accompanies known-severity observed findings or proven completed applicable checks. An empty
summary, attempted count, rule registry size, or all-skipped/all-failed report does not establish a clean assessment.
`ERROR`, `DISABLED`, and `NOT_SCANNED` never score. Malformed evidence or severity data remains unscored with an
explanation. Findings, severity counts, statuses, and diagnostics are retained independently of eligibility.

The report `evidence` object has three fields:

- `usable`: whether at least one applicable check completed or a genuine known-severity finding was observed,
  before filtering or dismissal. Genuine INFO/NONE findings can establish usability; informational missing-evidence
  notices and UNKNOWN-only vulnerability data cannot.
- `coverageComplete`: whether applicable evidence is complete. Intentionally inapplicable checks are neutral;
  missing required observations and failures leave coverage incomplete.
- `limitations`: an immutable, bounded, sanitized list of explanations for incomplete coverage.

Backend evidence is the sole eligibility authority; it exposes no completion or findings counters. Legacy reports
without valid explicit evidence remain unscored, with their findings visible. The browser does not reconstruct
applicability from rule IDs, finding lists, or dependency details. For example, MySQL/Oracle checks on a PostgreSQL-only
application remain neutral skipped diagnostics, not incomplete assessments or completed passes.

Score eligibility is separate from coverage completeness. A `SCANNED` report with `usable: false`,
`coverageComplete: true`, and no limitations has no score and reads **Not applicable**.
For example, a successful Architecture import with no classes or complete REST API discovery with no supported
controllers establishes an empty assessed scope, not a passing check. Overview counts it as assessed, not incomplete,
without inventing a score. Missing discovery or legacy evidence
remains unknown; a generic lack of score never establishes that nothing applies.

Inside advisor panels, usable scored results show **Results available**, including when coverage is limited.
Overview uses **Scan complete** for these results: the scan has finished, but coverage may still be limited.
Detailed reasons are in a
collapsed **Scan notes** disclosure in each advisor panel, operable by keyboard and screen reader. Scores with notes
include **Scan notes available** in their accessible names. Overview summarizes how many advisors have scan notes,
not how many checks could not run: limitations can be aggregated or capped. Unscored reasons and whole-scan failures
remain visible, not collapsed, inside advisor panels. Overview keeps unscored cards compact with **Not scored**
(or **Not applicable** for confirmed empty scope) and **Open panel** for the full explanation; request failures stay
visible on the card. Backend scan statuses and evidence are unchanged.
Penalties are unchanged: CRITICAL 25, HIGH 10, MEDIUM 3, LOW 1,
and INFO/NONE 0, clamped and rounded to 0–100. A partial 100 means no active penalties in the assessed evidence,
not that unchecked work passed. Advisor panels use neutral numbers; Overview restores green/amber/red score colors
at the historical 80/50 thresholds to prioritize review, without changing eligibility or implying application safety.
Complete assessments need no additional coverage paragraph. Overview shows an **Overall score**, the rounded
arithmetic mean of eligible visible advisor scores and GitHub's eligible security-alert score, with the contributing
score count alongside retained severities and advisor assessment counts. Missing or unscored reports do not
contribute fake zeros or hundreds. GitHub uses 10 points per alert only when authenticated, connected, and all three
required security signals have available valid counts; it never contributes fabricated advisor severities.
See [Overview](overview.md) for its eligibility policy. Three MEDIUM findings keep that advisor at 91 whether
another advisor runs or not; usable partial evidence is included without a missing-check penalty. No coverage
percentage, confidence weight, or combined per-rule coverage total is inferred.

Known-severity dependency findings (including NONE) establish usability before dismissal. A fully assessed package
also establishes usability when every retained advisory has known severity, or the result is genuinely empty, and
both `assessment.queryComplete` and `assessment.detailAssessmentComplete` are true. Query completion requires
exhausting that dependency's pages; detail completion requires interpreting every returned advisory or confirming
withdrawal. Missing, failed, capped, mismatched, or unresolved details are not a no-match. UNKNOWN findings remain
visible and incur no penalty, but UNKNOWN-only evidence stays unscored even after dismissal unless independent
usable evidence exists. Dismissed known findings remain evidence while their penalties are removed. Neither an empty
active count nor dismissing every advisory makes a dependency genuinely empty. Inventory/query/detail gaps and UNKNOWN
findings qualify otherwise usable scores rather than suppressing them. Optional EPSS enrichment does not decide
eligibility. Inventory coverage is only as reliable as the provider's report, not independent runtime verification.

During a new request, or if transport fails, the last accepted report remains visible. A newly received report
replaces that assessment: usable partial evidence contributes a qualified score, while failed, disabled, and unusable
reports remove the previous score.

### Single-flight scans

Expensive advisor actions are single-flight per scanner: a second tab, Overview card, REST caller, or MCP tool cannot
start a scanner while it is active. Duplicate REST requests fail immediately with the shared `409` busy response, and
MCP reports the same message as an in-band tool error. The panel keeps the last completed report and Overview score
visible and shows the conflict as a warning. Different scanners remain independent.

### Dismissing findings

Every advisor finding can be **dismissed** when it does not apply to your project. Each rule result carries a _Dismiss_
button; dismissing moves the rule into a collapsed "Dismissed rules" list and excludes it from the panel's finding
count, severity bars, and that advisor's known-findings score in both its panel and Overview. Dismissal changes penalties, not application
safety, observed evidence, or missing coverage. The panel's score recomputes immediately, and the
Overview dashboard reads cached reports on initial navigation and when you return to it, without rescanning, so a
panel-originated scan, dismissal, or restore updates both the score and eligibility in both places.
Rules can be restored at any time from that list.

::: details Where dismissals are stored
Dismissals are applied server-side and persisted under the `dismissedRules` node of a local `.bootui/boot-ui.yml` file
(next to the runtime overrides file), so they survive restarts and stay consistent between each panel and the Overview
dashboard. The file is developer-local and intended to be git-ignored. Rule identifiers are globally unique across
advisors, so a dismissal always targets exactly one rule.

`bootui.overrides-file` moves both files together: BootUI resolves `boot-ui.yml` in the same directory as the configured
overrides file. That is what lets dismissals survive a container image rebuild — point the key at a mounted volume, or
commit a baseline of accepted findings and copy it into the image. See
[Persisting console state across image rebuilds](../setup/environments.md#persisting-console-state-across-image-rebuilds).
:::

### Kotlin applications

The bytecode-driven advisors — Architecture, REST API, and Hibernate — are Kotlin-aware. They read compiled classes, so
they work on Kotlin without configuration, and BootUI recognizes Kotlin constructs by bytecode name only: no
`kotlin-stdlib` dependency is added to your application, and the behaviour is identical on Spring MVC, Spring WebFlux,
and Quarkus because it lives in the shared engine.

- Compiler-generated members and classes — `$suspendImpl` / `$default` bridges, `componentN` and `copy` accessors on
  data classes, `Companion` and `DefaultImpls` holders, `WhenMappings` tables, and top-level `FooKt` file facades — are
  filtered out, so they never appear as findings.
- Suspending functions are judged on their declared signature: the implicit `kotlin.coroutines.Continuation` parameter
  is hidden, the real result type is read from it, and `kotlin.Unit` counts as `void`.
- Properties are judged as properties. A `lateinit var` on an entity is not a public field finding: the compiler must
  leave its backing field public, but every access goes through the generated accessor pair. A `@JvmField var`, which
  has no accessors, is still reported.
- Nested types are read with their enclosing name. The variants of a `sealed class` exception hierarchy are not
  renaming findings, because `ClaimException.AlreadyAssigned` already says what it is.
- Suppressing the compiler's noise never suppresses your code. A call routed through a `$default` bridge is followed
  through to the function it dispatches to, and a self-invocation written inside a lambda stays reported even though
  the compiler hides the body in a synthetic method.
- Where an idiomatic Java fix does not translate, the recommendation offers the Kotlin equivalent — marking a class
  `open` or applying the `kotlin-spring` plugin instead of "remove `final`", and constructor `val` injection instead of
  an `@Autowired lateinit var`.

See [Architecture checks](../ARCHITECTURE-CHECKS.md), [REST API checks](../REST-API-CHECKS.md), and
[Hibernate checks](../HIBERNATE-CHECKS.md) for the per-rule notes.

## Architecture

![BootUI Architecture panel](../images/bootui-architecture.webp)

The Architecture panel runs a curated, zero-config [ArchUnit](https://www.archunit.org/) ruleset against the host
application's own compiled classes at runtime. It detects the base package from the `@SpringBootApplication`
configuration, imports the classes from that package, and evaluates a fixed set of universally-sensible hygiene rules:
package cycles between slices, general coding practices (banned APIs, unsafe patterns, naming and immutability
conventions), and Spring stereotype/proxy heuristics (no field injection, correct layering, no self-invocation,
proxyable annotations). See [ARCHITECTURE-CHECKS.md](../ARCHITECTURE-CHECKS.md) for the full catalogue and what each rule
inspects.

When BootUI is installed through `bootui-spring-boot-starter`, ArchUnit is included transitively; the panel is available
when a base package is resolvable, and the scan runs on demand and caches the last report. Generic rules are less
powerful than project-authored ArchUnit tests, so the panel is a starting-point and review aid that complements — not
replaces — a project-specific ArchUnit test suite. Each rule carries a stable identifier, category, severity, and
recommendation; the results list shows only violating rules, sorted by severity and violation count.

> **Not available in GraalVM native images.** The advisor scans compiled `.class` files via ArchUnit's
> `ClassFileImporter`, which is incompatible with a native executable; the panel is automatically hidden when the
> application is detected to be running as a native image.

::: details On Quarkus

The panel runs the same shared ArchUnit ruleset and on-demand scan over the same report contract. Generic hygiene rules
apply unchanged; Spring-only annotation rules find no matching classes, while Jakarta-based and proxy rules evaluate with
Quarkus-specific semantics.

**Quarkus proxy semantics and base-package discovery**

Proxy rules receive the active platform explicitly. The Spring self-invocation rule is skipped because Arc supports
intercepted self-invocation, and proxy visibility follows Arc's support for static interception and final-method
transformation instead of Spring's proxy restrictions. On Spring, protected and package-private methods are accepted for
Spring Boot's default class-based proxies, matching Spring Framework 6+ behavior — see
[ARCHITECTURE-CHECKS.md](../ARCHITECTURE-CHECKS.md) for the per-rule detail.

The one platform difference is base-package discovery. Quarkus has no `@SpringBootApplication` to read and no reliable
runtime package scan under its classloader, so base packages are discovered at **build time** from the Jandex
application index and supplied to the scanner. Discovery is single-module today: sibling modules in a multi-module build
are not auto-discovered, and the `bootui.internal.base-packages` config key (a comma-separated package list) overrides it
when needed. The scan still runs on demand and caches the last report, and dismissing a rule persists to
`.bootui/boot-ui.yml` exactly as on Spring Boot.

:::

## REST API

![BootUI REST API panel](../images/bootui-rest-api.webp)

The REST API panel runs a curated, zero-config ruleset against the host application's own web layer — `@RestController`
/ `@Controller` handler methods on Spring, or JAX-RS resources on Quarkus. Like the Architecture panel, it imports the
compiled handlers from bounded base packages and derives a read-only model: HTTP method(s), path(s), parameters and
annotations, return type, `produces`/`consumes`, validation flags, and declared throws. It then evaluates 56 REST
best-practice rules across eight categories: routing and HTTP-method mapping, resource naming, status codes and
responses, input validation and binding, DTO and payload contracts, pagination, versioning and content negotiation, and
error handling and documentation. The `RAPI-DOC-*` rules only run when Swagger or MicroProfile OpenAPI annotations are on
the host classpath.

The advisor deliberately avoids security concerns (CORS, authentication, authorization), which the Security panel owns.
The scan runs on demand and caches the last report; each rule carries a stable identifier, category, severity,
recommendation, and a learn-more link, and the results list shows only flagged rules, sorted by severity and finding
count. The heuristics complement — not replace — an API design review or contract testing. See
[REST-API-CHECKS.md](../REST-API-CHECKS.md) for the full catalogue and what each rule inspects.

### Declared error contract

The panel also shows the application's **declared error contract**: every `@ControllerAdvice` / `@RestControllerAdvice`
/ `@ExceptionHandler` method on Spring MVC and WebFlux, and every Jakarta REST `@Provider` `ExceptionMapper` and Quarkus
REST `@ServerExceptionMapper` on Quarkus. Each row names:

- the handled exception type;
- the declaring component and method;
- the scope (application-wide, narrowed, or controller-local);
- the resolved precedence;
- the declared HTTP status;
- the response-body category (RFC 9457 `ProblemDetail`, custom object, string, empty, or explicitly unresolved);
- the declared media types.

This is a pure declaration read: no handler is instantiated or invoked, no request is synthesized, and no exception is
thrown to observe a response — so anything the declarations cannot prove is reported as unresolved rather than guessed.
Only the application's own declarations are listed. The handlers the framework contributes (Spring Boot's
`BasicErrorController`, Quarkus's built-in RESTEasy Reactive and Jackson mappers) are identical everywhere, so an
application that declares nothing shows an empty catalogue on all three stacks. When a retained Exceptions-panel failure
can be attributed to exactly one declared handler, that panel links straight to the declaration here; ambiguous and
unmatched failures stay unlinked.

::: details Three cases reported as unresolved rather than guessed
- An advice that implements `Ordered` chooses its position at runtime, so its whole precedence group is ambiguous.
- A Spring handler without `@ResponseBody` (directly or via `@RestControllerAdvice`) renders a view rather than a body,
  so its body category is unresolved instead of read from the return type.
- On Quarkus only `@Provider`-annotated `ExceptionMapper`s are listed, because an unregistered implementation never
  participates in exception resolution.
:::

> **Not available in GraalVM native images.** The advisor scans compiled `.class` files via ArchUnit's
> `ClassFileImporter`, which is incompatible with a native executable; the panel is automatically hidden when the
> application is detected to be running as a native image.

## Spring

![BootUI Spring panel](../images/bootui-spring.webp)

The Spring panel runs an explicit, read-only scan of the host application's running Spring application context and
`Environment`. It takes a bounded snapshot of selected bean groups (Jackson `ObjectMapper`s, `TaskExecutor`s,
`DataSource`s) and feature flags, then evaluates a curated ruleset across bean wiring, configuration hygiene, profiles
and environment, performance and concurrency (including virtual threads), web/HTTP settings, data and persistence,
Actuator/management exposure, and reactive (WebFlux-only) checks. See [SPRING-CHECKS.md](../SPRING-CHECKS.md) for the
full catalogue and remediation links.

Because it runs inside the already-started application, it focuses on "started but suboptimal" states rather than fatal
startup conditions. It complements the Architecture panel — which statically analyzes compiled bytecode — by inspecting
the live, wired runtime context instead. The report is a heuristic review prompt, not a verdict: it never mutates the
context, intercepts live traffic, or surfaces secrets. The ruleset detects whether the host runs the servlet (Spring
MVC) or reactive (Spring WebFlux) stack and adjusts a handful of rules accordingly.

Standard Boot scheduling observability and BootUI's cache-activity decoration do not by themselves make the assessment
partial. The scheduler check observes registered tasks and the selected native scheduler; the cache check preserves the
underlying native provider's identity without calling custom delegates. On MVC, OSIV is reported as present, confirmed
absent, or unknown from actual registration metadata—not inferred absent from `spring.jpa.open-in-view=false`.
Custom or unavailable registrations retain explicit coverage limitations, and evaluation failures have separate safe
explanations. These evidence corrections do not change finding severities, score penalties or the report's JSON shape.

This single framework-application advisor is **relabelled per framework**: **Spring** on the Spring Boot adapter,
**Quarkus** on the Quarkus adapter — the same menu slot, `/bootui/api/spring` contract, and report shape. The
[Quarkus](#quarkus) section below covers the Quarkus flavour.

## Quarkus

![BootUI Quarkus panel](../images/bootui-quarkus.webp)

On the Quarkus adapter the framework-application advisor above is relabelled **Quarkus** and runs a Quarkus-native idiom
ruleset in place of the Spring rules. It takes the same explicit, read-only approach against the running application and
its MicroProfile `Config`, but the rules target Quarkus idioms:

- Resolved CDI/Arc scopes and publicly exposed state on shared beans and REST resources.
- Production configuration evidence, including schema actions, SQL logging and explicit in-memory storage.
- Effective managed REST-client timers, HTTP compression and request-draining configuration.
- Conditional synchronized virtual-thread pinning on the running JDK 21-23.

Missing configuration annotations, production overrides, pool-size overrides, or clustered scheduling are not
defects by themselves. Neither does a JDBC dependency alongside reactive endpoints prove event-loop blocking.
The advisor keeps useful declarations as inspection information, and reports incomplete evidence explicitly
instead of treating unreadable metadata or unseen production configuration as clean.

It is the **same panel and menu slot** as the Spring advisor — the same `/spring` route, `/bootui/api/spring` endpoint,
and report contract — so the shared UI simply renders the "Quarkus" label and copy. The report is a heuristic review
prompt, not a verdict. See [QUARKUS-ADVISOR-CHECKS.md](../QUARKUS-ADVISOR-CHECKS.md) for the full catalogue and
remediation links.

## Database

![BootUI Database panel](../images/bootui-database-advisor.webp)

The Database panel introspects the physical schema of every discovered application `DataSource` bean through plain JDBC
`DatabaseMetaData` — tables, columns, primary keys, foreign keys, and indexes — and evaluates a fixed, on-demand ruleset
of deterministic, low-false-positive structural checks. It never executes DDL and never queries application data. See
[DATABASE-ADVISOR-CHECKS.md](../DATABASE-ADVISOR-CHECKS.md) for the full catalogue and remediation links.

It reuses the same proxy-aware datasource discovery as Database Connection Pools and SQL Trace, de-duplicating by the
physical pool behind BootUI's own SQL Trace proxy so a wrapped datasource is never introspected twice. Spring's
delegating and routing `DataSource` wrappers are resolved rather than ignored: one is skipped only when the pool it
forwards to is a bean of its own, so the pool inside a `LazyConnectionDataSourceProxy` (what
`spring.datasource.connection-fetch=lazy` installs) is still inspected, and a routing datasource is expanded into its
resolved targets, named `beanName[lookupKey]`.

::: details The generic structural checks
- A missing primary key.
- A physical foreign key without a known complete leading-column access path, as a contextual review.
- Exact ordinary-index definition overlap, not merely a shorter leading prefix.
- A foreign-key column whose type disagrees with the column it references.
- A redundant unique index duplicating the primary key.
- Duplicate foreign-key constraints.
- A narrow auto-generated primary key.
:::

### Bounded, honest scans

Every scan runs under fixed bounds and reports exactly what it could not do:

- at most 300 tables, 300 columns and 100 indexes per table, 500 rows per catalog query;
- a cooperative 20-second budget and a 5-second timeout on every catalog statement.

The budget cannot guarantee interruption of a blocking driver metadata call or connection acquisition.

A reached bound is detected deterministically by reading one row past it, and the connection's original read-only state
is restored before it returns to the pool. A datasource that could not be read, a refused table, a catalog view a role
cannot see, a truncated scan, and every skipped or errored rule are reported as per-datasource statuses and
diagnostics — never as passing checks, and never counted as findings. Credentials in a JDBC URL or a
driver error message are always redacted.

| Status     | Meaning                          |
| ---------- | -------------------------------- |
| `SCANNED`  | Everything was read completely   |
| `PARTIAL`  | Something was not read           |
| `ERROR`    | Discovery failed or no schema could be read |
| `DISABLED` | Successful discovery found no datasource |

A catalog query blocked by restricted privileges makes its rule report `SKIPPED` with that reason instead of silently
reporting no findings.

### Dialect-specific augmentation

For **PostgreSQL**, **MySQL**, **MariaDB** and **Oracle** (19c+), dialect-specific catalog augmentation runs in addition
to the generic checks.

::: details What each dialect adds
**PostgreSQL:**

- Invalid or broken indexes (`pg_index`, excluding partitioned index parents and one being built `CONCURRENTLY`).
- A sequence nearing exhaustion, measured against the smaller of its own maximum and its **owning column's** capacity
  (the classic `bigint` sequence feeding an `integer` column).
- Constraints currently not validated, without inferring migration history.
- A table publishing updates/deletes with no usable replica identity; INSERT-only publications are excluded.

**MySQL/MariaDB:**

- Tables on a non-transactional storage engine.
- The legacy three-byte `utf8mb3` character set, with version-aware comparison-semantics guidance.
  MariaDB 11.4.5+ supports `0900` collation names as aliases; older versions must not be assumed to support them.
- An `AUTO_INCREMENT` counter nearing its column type's signed/unsigned capacity.

**Oracle:**

- Unusable indexes, including per-partition status for a partitioned index.
- Disabled or unvalidated constraints.
- A non-cycling sequence or `GENERATED ... AS IDENTITY` generator nearing exhaustion.

PostgreSQL declarative partitioning is modelled explicitly, so a finding on a partitioned table is reported once on its
parent instead of once per child partition.
:::

::: details How the dialect is detected
The dialect is detected from `DatabaseMetaData.getDatabaseProductName()`, the product version string, and the JDBC URL.
MariaDB is detected as its own dialect even through the MySQL driver, which reports the product name as "MySQL". Catalog
SQL is selected from the reported server version (MySQL 8.0's `IS_VISIBLE` versus MariaDB 10.6's `IGNORED`, PostgreSQL
10's `pg_sequences`, PostgreSQL 11's `INCLUDE` columns, PostgreSQL 15's `NULLS NOT DISTINCT`).

A driver-reported "Oracle" product name is confirmed against `v$version`/`product_component_version` before any
Oracle-specific augmentation runs, since some Oracle-compatible databases report the same name. Oracle's own catalog
reads are scoped to the session's `CURRENT_SCHEMA` through only `ALL_*` views and `SYS_CONTEXT`, with no production
`ojdbc` dependency anywhere in BootUI.

Every other database (H2, SQL Server, Tibero, EDB Postgres Advanced Server, etc.) still runs the full generic ruleset
through the standard JDBC metadata fallback — it is never treated as unsupported.
:::

Where the vendor catalog can answer, index semantics JDBC cannot express are folded into the shared model — validity,
partial predicates, expression and prefix key parts, access method, visibility, and (Oracle) whether an index backs a
constraint or is a specialized type. Every index rule can then ask "does this index actually support that lookup, or
enforce that uniqueness?" instead of comparing bare column-name lists.

### Hibernate cross-reference

When a Hibernate `EntityManagerFactory`/metamodel is also available for the same application, the panel additionally
cross-references the physical schema against the mapped JPA entities, using the same shared metamodel reader the
Hibernate panel uses. This half is skipped (with a clear reason, not silently dropped) when either a `DataSource` or a
Hibernate metamodel is unavailable.

::: details What the cross-reference checks
- An explicit association without a matching physical foreign-key constraint, excluding `NO_CONSTRAINT`.
- An explicit declared table or column name not observed in complete scoped metadata.
- Supported nondefault nullability declaration mismatches, not guessed Java-to-JDBC type mappings.
- A nondefault declared `@Column(length=...)` longer than a positively bounded physical string column.
- A mapped unique constraint with no physical index that genuinely enforces it.

Only entities with an *explicit* `@Table(name = ...)` are cross-referenced — entities relying on the default naming
strategy are skipped rather than guessed. Even explicit names remain logical names subject to a physical naming
strategy: these are declaration-versus-observation reviews, not effective runtime-mapping validation.
Matching honors a declared `catalog`/`schema`, and a mapped name that matches
tables in several readable datasources is treated as ambiguous rather than attributed to an arbitrary one. An entity
split across secondary tables has each column, join column, and unique constraint checked against the table it is
actually pinned to. Composite foreign-key matching tolerates the physical constraint's own column order but not a
different child-to-parent pairing, and verifies the referenced table when resolvable. Attributes whose persisted shape is
decided by a converter, `@Enumerated`, or `@Lob` are not treated as known JDBC representations.
The catalog documents four retired IDs, including annotation-only sequence-allocation inference.
:::

::: details Out of scope by design
This panel proposes no query/workload-based optimizations, runs no execution-plan analysis, performs no partition
discovery or management, and never suggests new indexes from observed usage — it is a structural, deterministic advisor
in the same spirit as the Hibernate Advisor, not a tuning engine. Resolving a mapped entity's Hibernate-computed physical
name (after the naming strategy runs), rather than an explicit `@Table`/`@Column` name, is also out of scope: the engine
reads only the standard JPA metamodel API, not Hibernate-internal naming-strategy SPI, to stay
provider-version-agnostic.
:::

::: details On Quarkus

The panel is identical, running the same shared rule engine over the same report contract. `DataSource` beans are
discovered through `@Any Instance<DataSource>` unconditionally (`javax.sql.DataSource` is core JDK, so no capability
gating is needed), and the Hibernate cross-reference reuses the same `EntityDiscoverySource` the Hibernate panel produces
when `quarkus-hibernate-orm` is present. Arc reports BootUI's own `@Alternative` SQL Trace wrapper alongside the real
Agroal pool it wraps, so the adapter de-duplicates by the physical pool behind the proxy — otherwise the same database
would be introspected twice, doubling every finding. Datasource names come from the Agroal `@DataSource("...")`
qualifier, read reflectively by annotation type name so the panel links no `io.quarkus.agroal`/`io.agroal` type and stays
safe in an application with no JDBC datasource extension; a bean with no such qualifier falls back to positional naming
(`default`, `datasource-2`, ...).

:::

## Hibernate

![BootUI Hibernate panel](../images/bootui-hibernate.webp)

The Hibernate panel runs an explicit, read-only scan against the JPA `EntityManagerFactory` metamodel when Hibernate ORM
is present. It reviews mapped entities, attributed persistence-unit observations, and verified Spring Data JPA repository metadata for
common Hibernate/JPA performance and mapping risks such as eager fetching, problematic identifier generators, collection
fetch pagination, unsafe cascades, cache misconfiguration, and risky `ddl-auto` values. The report is a review prompt,
not a verdict: it never intercepts queries, invokes repositories, executes SQL, or modifies mappings. See
[HIBERNATE-CHECKS.md](../HIBERNATE-CHECKS.md) for the full catalogue and remediation links.

The catalog has 71 active rules; five declaration-only or structurally duplicated checks are retired without reusing
their identifiers. Unavailable required observations and rule failures yield `PARTIAL` while retaining valid findings.
The scan message distinguishes attempted-rule coverage from successful evaluation; an empty findings list is not proof
that every mapping or query was verified. XML overrides, auto-apply converters, custom generators and runtime query
plans are not fully reconstructed.

::: details On Quarkus

The panel runs the same 71-rule registry and report contract when `quarkus-hibernate-orm` is present. Entities are
discovered from the live JPA `EntityManagerFactory` metamodel (across all persistence units, de-duplicated by identity),
and most mapping/identifier/fetch rules apply unchanged. Spring Data query rules skip when repository metadata is
unavailable instead of reporting a clean result. Four platform differences are worth noting:

- **Effective factory settings are unit-scoped.** Live native options include integration defaults and programmatic
  settings; named units do not inherit the first factory's values. Native property translation supplements appropriate
  declaration/application facts but is not proof of effective factory state.
- **Spring Open-Session-in-View is inapplicable.** The Spring-specific rule does not fire on Quarkus. Spring requires
  actual activation evidence rather than inferring activation solely from a missing property.
- **Bytecode enhancement is always enabled.** Quarkus enhances every entity unconditionally at build time with no
  opt-out, so known-absent-enhancement findings do not fire with the verified adapter capability.
- **Panache active-record entities are handled specially** (see below).

**The Quarkus property-key mapping**

| Spring / native Hibernate key                     | Quarkus equivalent                                                  |
| ------------------------------------------------- | ------------------------------------------------------------------- |
| `ddl-auto` / `hbm2ddl.auto`                       | `quarkus.hibernate-orm.schema-management.strategy` (*)              |
| `show-sql`                                        | `quarkus.hibernate-orm.log.sql`                                     |
| `format_sql`                                      | `quarkus.hibernate-orm.log.format-sql`                             |
| `batch_size`                                      | `quarkus.hibernate-orm.jdbc.statement-batch-size`                   |
| `default_batch_fetch_size`                        | `quarkus.hibernate-orm.fetch.batch-size`                            |
| `jdbc.time_zone`                                  | `quarkus.hibernate-orm.jdbc.timezone`                               |
| `generate_statistics`                             | `quarkus.hibernate-orm.statistics`                                  |
| `query.in_clause_parameter_padding`               | `quarkus.hibernate-orm.query.in-clause-parameter-padding`           |
| `query.fail_on_pagination_over_collection_fetch`  | `quarkus.hibernate-orm.query.fail-on-pagination-over-collection-fetch` |
| `cache.use_query_cache` / `cache.use_second_level_cache` | `quarkus.hibernate-orm.second-level-caching-enabled` (single unified toggle) |

(*) In Quarkus 3.33.3.1, explicitly configured deprecated `quarkus.hibernate-orm.database.generation` takes precedence.
Quarkus/Jakarta `create` is create-only; Hibernate `hbm2ddl.auto=create` is destructive. The advisor preserves that
difference rather than treating the keys as interchangeable raw strings.

A native `quarkus.hibernate-orm.log.bind-parameters` flag is also read as the neutral bind-parameter-logging signal. For
any other `hibernate.*` key with no first-class Quarkus option (for example `hibernate.order_inserts` /
`hibernate.order_updates`), the lookup falls back to Quarkus' generic `quarkus.hibernate-orm.unsupported-properties."..."`
escape hatch. The advisor uses the bootstrapped factory options for supported facts rather than assuming that the
presence or absence of a property proves a value. Unsupported Spring/Hikari-specific evidence is not guessed from an
unrelated property, and no connection is acquired to inspect it.

**Panache active-record entities**

With verified Panache transformation capability, build-time bytecode rewriting makes public-field access on
Hibernate-managed classes behave like getter/setter calls, so the public-persistent-field finding does not fire.
Mere classpath presence is not proof that transformation ran. The `@GeneratedValue`-without-strategy finding
ignores the `id` field Panache's own base entity declares (an application-declared identifier is still checked normally).
Spring Data repository hints (missing-strategy-aware `isNew()` detection for assigned identifiers) are specific to Spring
Data JPA's `save()` semantics: without verified, attributable JPA repository metadata, that check is inapplicable.
Panache query methods are not inferred from names or generated bytecode.

:::

## Memory

![BootUI Memory panel](../images/bootui-memory.webp)

The Memory panel runs an explicit, read-only scan over the live JVM management beans — heap and memory pools, garbage
collection, threads, loaded classes, and an optional class histogram. It turns them into severity-ranked findings such as
heap pressure, metaspace saturation, native-footprint risk inside a container, lifetime GC overhead, thread deadlocks,
and collection bloat. It complements the raw Live Memory and Threads panels by diagnosing the data they expose. The scan
is on demand and caches the last report; new rules are added as small, focused classes in the `memory` package. See
[MEMORY-CHECKS.md](../MEMORY-CHECKS.md) for the full catalogue and remediation links.

## Security

The Security panel runs an explicit, read-only scan of the host application's security configuration to flag common
hardening gaps across authentication, authorization, CSRF, session management, transport/security headers, CORS, method
security, actuator exposure, OAuth2 resource-server validation, and configuration hygiene. The report is a review prompt,
not a verdict: it never intercepts live traffic, exposes credentials, keys, or session identifiers, or modifies the
security configuration.

The advisor supports **all three** runtime security stacks from the same panel, menu slot, and `/bootui/api/security`
report contract.

### Spring Boot (Spring Security)

![BootUI Security panel — Spring Security](../images/bootui-security.webp)

On Spring Boot it analyses Spring Security when it is on the classpath: it inspects already-created
`SecurityFilterChain` configuration and supported security metadata without executing application authorization
managers, custom matchers or credential providers. Unsupported evidence remains incomplete rather than becoming a
security verdict. See
[SECURITY-CHECKS.md](../SECURITY-CHECKS.md) for the full catalogue and remediation links.

### Spring WebFlux

On Spring Boot WebFlux it evaluates a dedicated 25-rule `SEC-RXF-*` catalogue over a framework-neutral observation of the
application's `SecurityWebFilterChain` beans, reactive CORS/OAuth2 beans, and security-relevant configuration. The Spring
adapter owns collection and excludes BootUI's own permit-all chain; the shared engine owns deterministic rule evaluation
and never receives Spring types or secret values.

::: details On Quarkus

![BootUI Security panel — Quarkus Security](../images/bootui-quarkus-security.webp)

On Quarkus it runs a Quarkus-native ruleset instead, reading the application's HTTP permission policies, MicroProfile
`Config`, and authorization-annotated endpoints: Elytron/OIDC authentication, `quarkus.http.auth.permission.*`
authorization, TLS and transport policy, CORS (including the wildcard-origin-with-credentials trap), security response
headers, and Jakarta/Quarkus annotations including `@RolesAllowed`, `@PermissionsAllowed`, and `@AuthorizationPolicy`. It
surfaces the same severity-ranked prompts, so the shared UI only relabels the metrics ("Permission policies" in place of
"Filter chains"). See [QUARKUS-CHECKS.md](../QUARKUS-CHECKS.md) for the full Quarkus catalogue and remediation links.

:::

## Pentesting

![BootUI Pentesting panel](../images/bootui-pentesting.webp)

The Pentesting panel runs explicit, local-only OWASP Top 10 2025 hygiene checks against the host application, not
BootUI's `/bootui` routes. On an explicit scan it combines bounded framework metadata with at most one `GET` and one
`OPTIONS` request to literal `127.0.0.1` under the validated application context path; the two-second client never
follows redirects or uses configured proxies. Checks cover missing or unsafe browser-document headers, CORS behavior,
cookie flags, verbose error exposure, Spring Security wiring, actuator exposure, Quarkus CORS/OIDC/TLS configuration, and
common Spring Boot hardening gaps.

It intentionally does not crawl discovered endpoints, send SQL/XSS/destructive payloads, contact external hosts, or
include raw response bodies, cookie values, credentials, or full issuer URLs. Findings are heuristic review prompts, not
proof of exploitability or a replacement for a full security assessment.

The 79 active checks each carry a stable identifier, OWASP 2025 category, evidence source, and recommendation.
The panel shows **Findings by severity**, matching the other advisors, rather than a separate OWASP Top 10 coverage
matrix. Severity bars summarize retained findings; category metadata is not a passing-check count.
Failed or bounded-away evidence produces a `PARTIAL` scan. Usable known findings still score under the shared
eligibility policy, with limits in **Scan notes**; skipped, failed, or unknown-only evidence cannot establish a score.
See [PENTEST-CHECKS.md](../PENTEST-CHECKS.md) for the
full catalogue, limits, mappings, and retired IDs.

### Per-stack coverage

- **Spring MVC** is the complete reference collector.
- **Spring WebFlux** still contributes Spring configuration and OAuth metadata, but explicitly reports MVC mapping and
  servlet-filter evidence unavailable, even on a mixed MVC/WebFlux classpath; runtime classification follows the active
  application context. Its reactive Security advisor owns `SecurityWebFilterChain` route policy.
- **Quarkus** runs the same shared scanner/report contract and supplies its live port, root path, CORS, OIDC, and
  selected/default/direct HTTP-listener TLS configuration, not unrelated client TLS keys, while explicitly marking
  Spring endpoint/security metadata unavailable. Local HTTP does not assess proxy-edge HTTPS.

Platform-specific limitations remain explicit, so unsupported checks do not become a false clean result.

## Vulnerabilities

![BootUI Vulnerabilities panel](../images/bootui-vulnerabilities.webp)

The Vulnerabilities panel shows locally discovered dependency inventory and explicit OSV vulnerability lookup results,
helping identify known-vulnerable dependencies from the running project's dependency set during the local development loop. Findings are
ordered by severity first (dismissed findings sink to the bottom regardless of severity), with dependencies and
advisories alphabetized within the same severity.

The [Vulnerabilities checks catalogue](../VULNERABILITIES-CHECKS.md) documents the interpretation rules, official
sources/version caveats, full audit disposition, and deferred inventory limitations. A completed lookup is not proof
of application safety or complete runtime discovery. Panel and Overview use the same
[evidence-based eligibility](#score-eligibility), including qualification after dismissal and GET-only cached refresh.

### Severity scoring

Severity is derived from [OSV.dev](https://osv.dev/)'s `severity[]` entries, whose `type` says how the `score` must be
interpreted. BootUI computes only `CVSS_V3` entries carrying a CVSS v3.0/v3.1 vector; it never treats a bare number or
another provider's scale as CVSS. CVSS `0.0` is reported as `NONE`, matching FIRST's qualitative scale. An advisory with
neither a parseable CVSS v3 score nor a `database_specific` label renders as `UNKNOWN` rather than being silently
dropped.

::: details How a CVSS score is selected and parsed
BootUI matches exact `Maven` ecosystem and `groupId:artifactId`, allowing only OSV's literal `*` package wildcard,
not arbitrary globs or other Maven repository ecosystems. It evaluates the installed version against the union of
explicit versions and supported Maven `ECOSYSTEM` ranges. Applicability is matched, not matched, or unresolved;
unsupported `SEMVER`/`GIT` or malformed evidence never becomes an unaffected verdict. A positive OSV query retains
the finding even when detail association is unsupported or contradictory, with `PARTIAL` and an explanation.

Choose the highest valid v3 Base score across applicable matching package assessments, excluding unrelated branches.
Only when no applicable package severity is supplied does top-level severity supply the fallback. OSV makes package
and top-level severity mutually exclusive: invalid/unsupported applicable package severity must not borrow a
conflicting top-level score. A recognized `database_specific.severity` label remains a compatibility fallback,
otherwise the finding is `UNKNOWN`. `MODERATE` maps to `MEDIUM`; a valid zero remains `NONE`.

A CVSS v3.0/v3.1 vector (for example `CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H`) uses the
[FIRST CVSS v3.1 specification](https://www.first.org/cvss/v3.1/specification-document). All eight Base metrics are
required. Valid metric orders and optional Temporal/Environmental metrics are accepted, but optional values are also
validated; empty/trailing segments, unknown metrics, duplicates, and invalid values are rejected. Calculation remains
**Base only**, with the specified scope and Roundup rules. CVSS v2/v4 calculation is deferred; v4 has a
[public reference calculator](https://github.com/FIRSTdotorg/cvss-v4-calculator) but requires a separate implementation.
Unsupported vectors do not remove findings.
:::

### OSV querying and robustness

Advisories carrying a `withdrawn` timestamp are excluded. OSV omits withdrawn records from POST query responses but
returns them from `GET /v1/vulns/{id}`, so the scanner keeps a defensive detail-stage check. Detail fetches run with a
small bounded concurrency (up to 10 at a time), with a configured advisory-detail cap. Request timeouts are not a
whole-scan deadline; a total elapsed-time budget and retry policy remain deferred. A single detail fetch that fails
(network hiccup, rate limiting) does not abort the whole scan: it is counted, the scan
degrades to `PARTIAL`, and every advisory that *did* fetch is kept.

::: details Pagination, batching, and result validation
OSV's `/v1/querybatch` endpoint returns a `next_page_token` per query when pagination is needed; upstream pagination
thresholds can change. The scanner follows that token with follow-up calls, retaining validated pages incrementally,
bounded by 20 page rounds per chunk so a pathological advisory can't loop the scan forever (degrading to `PARTIAL` if
the bound is hit rather than silently truncating).

OSV also enforces a hard limit of 1,000 queries per `/v1/querybatch` request; the scanner partitions the (already
`max-packages`-bounded) package list into batches of at most 1,000 before querying, so configuring `max-packages` above
1,000 no longer causes OSV to reject the whole batch with an HTTP 400. Every successful response must contain exactly one
structurally valid result per query, and every reported vulnerability reference must carry a non-blank id; missing,
short, or malformed result arrays fail visibly instead of reading as a clean scan. Repeated advisory ids are
fetched/reported once per dependency, a detail response whose id does not match the request is counted as a failed fetch,
and a later page or chunk failure preserves previous pages and completed queries as `PARTIAL`. This includes queries
already completed in the same chunk as a failed page. `packagesScanned` counts only queries exhausted without a token,
including at the page cap; a token-only empty page is not complete. Failure before any valid page is `ERROR` with local
inventory. `packagesSkipped` counts only the configured package-cap omission; unfinished/failed work is explained in
the message. Per-request streaming limits remain 5 MiB for queries and 1 MiB for details/EPSS.
:::

### EPSS enrichment

Each advisory whose own id or `aliases` includes a canonical `CVE-*` id is additionally enriched with
[EPSS](https://www.first.org/epss/) (Exploit Prediction Scoring System) data from FIRST.org's free, unauthenticated API.
EPSS reports the modeled probability that a CVE will be exploited in the wild in the next 30 days, plus the percentile
that probability ranks against every other scored CVE — a likelihood-of-exploitation signal that complements, rather than
replaces, CVSS's severity-if-exploited score. It renders as a secondary badge next to the severity/CVSS badge (for
example "2.3% EPSS", with a tooltip spelling out the percentile).

When several CVEs are available, the badge uses the **highest available per-CVE probability**, with the percentile from
that same record and a stable CVE tie-break. This is a prioritization heuristic, not combined advisory/application
exploit likelihood. A missing alias does not hide an available score; a valid zero is available, not unknown.

::: details EPSS request and validation details
Enrichment runs as one or more batched `GET /data/v1/epss?cve=...` requests per scan, each respecting FIRST's documented
2,000-character maximum for the comma-separated `cve` parameter, alongside the OSV calls and following the same "network
call only on the user-initiated scan action" pattern. Validate an object root, array `data`, and supplied numeric
`total`/`offset`/`limit` metadata. Follow smaller service pages with progress validation, bounded to
`min(20, requested CVE count)` pages per chunk. Returned ids must belong to the request, and probability/percentile
values must be finite numbers from 0 to 1. EPSS lookups can be disabled independently of OSV scanning via
`bootui.vulnerabilities.epss-enabled=false`, and a failed or unreachable EPSS request never fails the scan or discards
the OSV results. Successful earlier pages/chunks and scores survive later failure. `scan.message` appends
requested/available/no-data counts or an incomplete/failure explanation without changing OSV status. A successfully
exhausted lookup with no row is no data, not zero; an empty body or malformed envelope is not a successful no-data
response. No canonical CVE or disabled EPSS makes no FIRST call. Enrichment covers the own ID and at most 20 retained
aliases; selected CVE/date/model provenance and date pinning across daily updates are not exposed by the unchanged DTO.
:::

### Fix availability

Each advisory carries unchanged `fixedVersions` and `fixAvailable` fields with evidence-backed candidate semantics.
A candidate must be a `fixed` event closing a supported Maven `ECOSYSTEM` interval containing the installed version,
be positively newer under Maven `ComparableVersion` qualifier ordering, and be proven unaffected across **all matching
entries**, including explicit versions and overlapping/reintroduced intervals. Unknown comparison or unresolved target
evidence does not establish a fix. Neither Git/SEMVER ranges nor `last_affected`/`limit` boundaries supply Maven upgrades.

Applicable newer candidates are filtered before de-duplication, Maven ordering, and the ten-candidate limit. A false
signal or empty list means no target was established under these rules, not that the dependency is safe or no upstream
fix exists. Candidates are reported upgrade evidence, not guarantees of artifact publication, compatibility, or
reachability remediation; BootUI does not install them.

### Dismissing a vulnerability

Like every other advisor, a vulnerability can be **dismissed** when it does not apply (already patched downstream,
accepted risk, or a fix not yet available upstream) — see the shared dismiss/restore explanation at the top of this page.
The one difference is the dismissal key's shape. Because a vulnerability is scoped to one dependency, it is keyed by
`<vulnerability id>::<package name>` (for example `GHSA-xxxx-xxxx-xxxx::org.example:sample`) rather than a bare rule id.
So dismissing a finding for one dependency never hides the same advisory id reported against a different dependency, and
a dismissal survives a patch-version bump of the still-vulnerable dependency. Dismissed vulnerabilities stay visible
(dimmed, with a _Restore_ button), are excluded from the per-dependency and panel-level vulnerable counts, and trigger a
fresh deterministic ordering from the recomputed active severity. Dismiss/restore controls are disabled when the panel is
read-only.

::: details How Spring discovers dependencies
The Spring adapter resolves coordinates from three sources, in decreasing order of authority:

1. **The application's embedded CycloneDX SBOM** (`META-INF/sbom/application.cdx.json` or `META-INF/sbom/bom.json`, the
   files Spring Boot's `/actuator/sbom` serves). Maven PURLs carry `groupId`, artifact, and version, for example
   `pkg:maven/org.example/sample@1.0` — including the `groupId` no manifest header carries. This can identify artifacts
   published without a Maven descriptor; PURL decoding limitations remain deferred below.
2. **`META-INF/maven/*/*/pom.properties`** descriptors on the classpath, which Spring's resolver also sees inside a
   repackaged archive's nested JARs.
3. **`java.class.path` entries**, read through the Maven repository directory layout or an adjacent Maven POM (including
   in nonstandard local-repository paths). A group id is only derived from a path when a literal `repository` directory
   makes it unambiguous; it is never guessed from an arbitrary cache path. Classpath JAR filenames must match the
   resolved artifact/version exactly (with an optional classifier) rather than merely sharing a version prefix. Inside a
   repackaged fat JAR this source is dead, because `java.class.path` is then just the application archive.

Unreadable `pom.properties` resources, a malformed or unreadable SBOM, and unreadable classpath archives are each logged
and skipped instead of failing the whole inventory.
:::

### Coverage

A coordinate-based inventory can only scan what it can name, so the panel also reports what it *couldn't*. Alongside the
inventory, BootUI takes a census of the application's real archives — the `BOOT-INF/lib/`/`WEB-INF/lib/` entries of a
repackaged JAR or WAR, or the classpath JARs when running exploded — and attributes each to a resolved coordinate. The
provider reports one of three states, subject to the discovery limitations below:

| `coverage.status` | Meaning |
| --- | --- |
| `COMPLETE` | The provider reports all enumerated archives identified; this is not independent verification of the runtime inventory. |
| `INCOMPLETE` | Some archives did not; they are counted and named, and the panel warns that they were not scanned. |
| `UNAVAILABLE` | The census itself could not run (a blank or synthetic classpath, for example under a native image), so coverage is unknown rather than claimed. |

When coverage is incomplete the panel shows an "Unidentified JARs" metric and a warning naming the gap
("139 of 325 JARs could not be identified and were not scanned"), with a collapsible list of the archive names and a
pointer to adding the CycloneDX plugin to the build. Unidentified archives deliberately stay out of the scannable
dependency table — they have no coordinates to show. The census does not extract nested JAR contents.

The scan status reports the same kind of gap for the `bootui.vulnerabilities.max-packages` bound: packages beyond it are
counted in `scan.packagesSkipped` and surfaced as a warning, instead of letting `packagesScanned` present a truncated
scan as a complete one. The default bound is `500`, sized to cover a typical Spring Boot application's full JAR set.

::: details On Quarkus

The panel is identical: it lists the local inventory first and contacts OSV.dev only on the user-initiated scan, over the
same report contract, CVSS/withdrawn/partial-failure handling, pagination/batch-chunking, EPSS enrichment, and
dismiss/restore workflow. `bootui.vulnerabilities.osv-enabled=false` / `bootui.vulnerabilities.epss-enabled=false`
disable on-demand scanning / EPSS enrichment on both adapters. The one platform difference is dependency discovery: the
Quarkus inventory is captured at **build time** from the application's resolved runtime dependency model and read back at
runtime (mirroring the Architecture panel's build-time base-package discovery). It does not require an SBOM to obtain
those coordinates. However, the current provider can report `coverage.status=COMPLETE` even for a missing/blank model or
after malformed entries were skipped. Distinguishing missing, truly empty, and partially decoded models is deferred;
this change must not be read as repairing or independently verifying Quarkus inventory coverage.

:::

### Known limitations

Inventory repairs are explicitly deferred. Reported coverage surfaces some gaps, but does not reliably diagnose all
of them:

- **Discovery can overclaim coverage.** Spring de-duplicates archive basenames and matches filenames without group
  identity; case/classifier ambiguity can overstate identification. PURL literal-plus decoding and namespace rewriting,
  SBOM runtime-scope attribution, and a traversal cap on resolved coordinates rather than inspected nodes need separate
  fixes. Quarkus missing/invalid model coverage can also overclaim completeness, as described above.
- **Without an SBOM, some JARs cannot be identified.** No JAR manifest header carries a `groupId`
  (`Implementation-Title` is a display name as often as an artifact id, and `Implementation-Vendor-Id` is not a group
  id), so an application built without a CycloneDX SBOM cannot resolve coordinates for artifacts published with no Maven
  descriptor — Spring Framework, Spring Boot, Spring Security, `tomcat-embed-*`, `hibernate-core`, `kotlin-stdlib`, the
  PostgreSQL driver, and the `opentelemetry-*`/`micrometer-*` families among them. Archives detected as unidentified by
  the census are reported through coverage, subject to the attribution limitations above. External hash-based
  coordinate lookup would add another service and configuration surface, so it is deliberately not done.
- **Shaded/uber JARs are invisible.** The inventory on both adapters is coordinate-based (one resolved JAR = one Maven
  `groupId:artifactId:version`), so a vulnerable library relocated or repackaged inside a shaded/uber JAR carries no
  `pom.properties`/build-time coordinate of its own and is invisible — the same reduced-fidelity honesty precedent
  applied to other panels (for example Cache, Beans).
- **No direct-vs-transitive provenance yet.** "Introduced through" is not tracked on either adapter. Quarkus could source
  it from its build-time application dependency graph, but Spring's classpath-based inventory has no equivalent graph
  today (adding one would need POM/Maven-plugin integration, a much larger change), so this is deferred rather than
  shipped as a Quarkus-only asymmetry.
- **Evidence is bounded, not exhaustive.** Distinct advisory IDs can count the same underlying CVE more than once;
  alias-cluster merging would change dismissal semantics and is deferred. Full CVSS provenance, CVSS v4 calculation,
  selected EPSS CVE/date/model fields, cross-request date pinning, and a whole-scan deadline are also deferred.
  Browser version sorting remains lexical for same-package rows rather than sharing the server's Maven ordering.
