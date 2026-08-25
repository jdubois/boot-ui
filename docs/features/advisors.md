# Advisors


BootUI's advisors run explicit, on-demand rule-based scans and surface severity-ranked findings, feeding the weighted
score on the Overview dashboard. Each advisor inspects a different facet of the application — compiled architecture, the
REST layer, the live Spring context, persistence, JVM memory, and security posture — and is read-only. Once an advisor
has run, its panel shows the same 0–100 score the Overview computes for it (100 minus the weighted finding penalty), so
each panel and the dashboard always agree.

Expensive advisor actions are single-flight per scanner: a second tab, Overview card, REST caller, or MCP tool cannot
start the same scanner while it is active. Duplicate REST requests fail immediately with the shared `409` busy response
instead of waiting or repeating work; MCP reports the same message as an in-band tool error. The panel keeps the last
completed report and Overview score visible and shows the conflict as a warning. Different scanners remain independent.

Every advisor finding can be **dismissed** when it does not apply to your project. Each rule result carries a _Dismiss_
button; dismissing a rule moves it into a collapsed "Dismissed rules" list at the bottom of the panel and excludes it
from the panel's finding count, severity bars, the panel's own advisor score, and the weighted Overview score. The
panel's score recomputes immediately, and the Overview dashboard — which stays mounted in the background — re-reads the
advisor's score when you return to it, so dismissing or restoring a finding is reflected in both places. Dismissed rules
can be restored at any time from that list. Dismissals are applied server-side and persisted under the `dismissedRules`
node of a local `.bootui/boot-ui.yml` configuration file (next to the runtime overrides file), so they survive restarts
and stay consistent between each panel and the Overview dashboard. The file is developer-local and intended to be
git-ignored; rule identifiers are globally unique across advisors, so a dismissal always targets exactly one rule.

## Architecture

The Architecture panel runs a curated, zero-config [ArchUnit](https://www.archunit.org/) ruleset against the host
application's own classes at runtime. It detects the application's base package from the `@SpringBootApplication`
configuration, imports the compiled classes from that package, and evaluates a fixed set of universally-sensible
architecture hygiene rules: package cycles between slices, general coding practices (no standard streams, generic
exceptions, `java.util.logging`, JodaTime, `printStackTrace`, `System.exit`, JDK-internal APIs, legacy date/time, or
deprecated APIs, poorly named exceptions/interfaces, mutable/visible loggers, production dependencies on test
frameworks, public mutable static fields, non-final utility classes, standard-annotation (`jakarta.inject.Inject` /
`@Resource`) field injection, direct `Thread` instantiation, or message-less assertions), and Spring stereotype/proxy
heuristics (no `@Autowired`/`@Value` field injection, controllers should not depend on repositories, repositories
should not depend on controllers or services, services should not depend on controllers, services and repositories
should stay servlet-agnostic, no self-invocation or unproxyable proxy annotations, async/scheduled method signatures
should be supported, async should stay out of configuration classes, stereotypes should stay outside the default
package, `@ConfigurationProperties` classes should be immutable, and code should avoid `AopContext.currentProxy()`).
When BootUI is installed through
`bootui-spring-boot-starter`, ArchUnit is included transitively; the panel is available when a base package is resolvable
and the scan runs on demand, caching the last report.

Generic rules are necessarily less powerful than project-authored ArchUnit tests, so the panel is positioned as a
starting-point and review aid that complements — rather than replaces — a project-specific ArchUnit test suite. Each
rule is registered with a stable identifier, category, severity, and recommendation; the rule results list shows only
violating rules, sorted by severity and violation count. See
[ARCHITECTURE-CHECKS.md](../ARCHITECTURE-CHECKS.md) for the full catalogue of rules and what each one inspects.

> **Not available in GraalVM native images.** The advisor scans compiled `.class` files via ArchUnit's
> `ClassFileImporter`, which is incompatible with a native executable; the panel is automatically hidden when the
> application is detected to be running as a native image.

On Quarkus the panel runs the same shared ArchUnit ruleset and on-demand scan over the same report contract. Generic
hygiene rules apply unchanged; Spring-only annotation rules find no matching classes, while Jakarta-based rules and
framework-sensitive proxy rules evaluate with Quarkus-specific semantics. Proxy rules receive the active platform explicitly:
the Spring self-invocation rule is skipped because Arc supports intercepted self-invocation, while proxy visibility
follows Arc's support for static interception and final-method transformation instead of applying Spring's proxy
restrictions. On Spring, protected and package-private methods are accepted for Spring Boot's default class-based
proxies, matching Spring Framework 6+ behavior — see
[ARCHITECTURE-CHECKS.md](../ARCHITECTURE-CHECKS.md) for the per-rule detail. The one platform
difference is base-package discovery: Quarkus has no
`@SpringBootApplication` to read and no reliable runtime package scan under its classloader, so the application's base
packages are discovered at **build time** from the Jandex application index and supplied to the scanner. Discovery is
single-module today (sibling modules in a multi-module build are not auto-discovered; the `bootui.internal.base-packages`
config key — a comma-separated package list — overrides it when needed). The scan still runs on demand and caches the
last report, and dismissing a rule persists to `.bootui/boot-ui.yml` exactly as on Spring Boot.

![BootUI Architecture panel](../images/bootui-architecture.webp)

## REST API

The REST API panel runs a curated, zero-config ruleset against the host application's own web layer
(`@RestController` / `@Controller` handler methods on Spring or JAX-RS resources on Quarkus) at runtime. Like the
Architecture panel, it imports the compiled handlers from bounded application base packages and derives a read-only
model — HTTP method(s), path(s), parameters and their annotations, return type, `produces`/`consumes`, validation flags,
and declared throws. It then evaluates 56 REST best-practice rules across eight categories: routing
and HTTP-method mapping, resource naming, status codes and responses, input validation and binding, DTO and payload
contracts, pagination, versioning and content negotiation, and error handling and documentation. The `RAPI-DOC-*`
documentation rules only run when Swagger or MicroProfile OpenAPI annotations are on the host classpath.

The advisor deliberately avoids security concerns (CORS, authentication, authorization), which remain owned by the
Security panel. The scan runs on demand and caches the last report; each rule is registered with a stable
identifier, category, severity, recommendation, and a learn-more link, and the rule results list shows only flagged
rules, sorted by severity and finding count. The heuristics complement — rather than replace — an API design review or
contract testing. See [REST-API-CHECKS.md](../REST-API-CHECKS.md) for the full catalogue of rules and what
each one inspects.

The panel also shows the application's **declared error contract**: every `@ControllerAdvice` /
`@RestControllerAdvice` / `@ExceptionHandler` method on Spring MVC and WebFlux, and every Jakarta REST
`@Provider` `ExceptionMapper` and Quarkus REST `@ServerExceptionMapper` on Quarkus. Each row names the handled
exception type, the declaring component and method, the scope (application-wide, narrowed, or
controller-local), the resolved precedence, the declared HTTP status, the response-body category (RFC 9457
`ProblemDetail`, a custom object, a string, empty, or explicitly unresolved), and the declared media types.
This is a pure declaration read: no handler is instantiated or invoked, no request is synthesized, and no
exception is thrown to observe a response — so anything the declarations cannot prove is reported as
unresolved rather than guessed. Only the application's own declarations are listed: the handlers the
framework itself contributes (Spring Boot's `BasicErrorController`, Quarkus's built-in RESTEasy Reactive and
Jackson mappers) are identical in every application, so an application that declares nothing shows an empty
catalogue on all three stacks. When a retained failure in the Exceptions panel can be attributed to exactly
one declared handler, that panel links straight to that declaration in this catalogue; ambiguous and unmatched
failures stay unlinked.

Three cases are deliberately reported as unresolved rather than guessed. An advice that implements `Ordered`
chooses its position at runtime, so its whole precedence group is reported as ambiguous. A Spring handler
without `@ResponseBody` (directly or through `@RestControllerAdvice`) renders a view rather than a body, so its
body category is unresolved instead of being read from the return type. On Quarkus only `@Provider`-annotated
`ExceptionMapper`s are listed, because an unregistered implementation never participates in exception
resolution.

> **Not available in GraalVM native images.** The advisor scans compiled `.class` files via ArchUnit's
> `ClassFileImporter`, which is incompatible with a native executable; the panel is automatically hidden when the
> application is detected to be running as a native image.

![BootUI REST API panel](../images/bootui-rest-api.webp)

## Spring

The Spring panel runs an explicit, read-only scan of the host application's running Spring application context and
`Environment`. It takes a bounded snapshot of selected bean groups (Jackson `ObjectMapper`s, `TaskExecutor`s,
`DataSource`s) and feature flags, then evaluates a curated ruleset across bean wiring, configuration hygiene, profiles and
environment, performance and concurrency (including virtual threads), web/HTTP settings, data and persistence,
Actuator/management exposure, and reactive (WebFlux-only) checks.
Because it runs inside the
already-started application, it focuses on "started but suboptimal" states rather than fatal startup conditions. It
complements the Architecture panel, which statically analyzes compiled bytecode with ArchUnit, by inspecting the live,
wired runtime context instead. The report is a heuristic review prompt, not a verdict: it never mutates the context,
intercepts live traffic, or surfaces secrets. The ruleset detects whether the host is running the servlet (Spring MVC) or
reactive (Spring WebFlux) stack and adjusts a handful of rules accordingly — see [SPRING-CHECKS.md](../SPRING-CHECKS.md) for
the full rule catalogue and remediation links.

This is a single framework-application advisor that is **relabelled per framework**: it appears as **Spring** on the
Spring Boot adapter and as **Quarkus** on the Quarkus adapter — the same menu slot, the same `/bootui/api/spring`
contract, and the same report shape. The [Quarkus](#quarkus) section below covers the Quarkus flavour.

![BootUI Spring panel](../images/bootui-spring.webp)

## Quarkus

On the Quarkus adapter the framework-application advisor above is relabelled **Quarkus** and runs a Quarkus-native idiom
ruleset in place of the Spring rules. It takes the same explicit, read-only approach — evaluating a curated set of checks
against the running application and its MicroProfile `Config` — but the rules target Quarkus idioms: CDI/Arc scopes and
shared mutable state on `@ApplicationScoped`/`@Singleton` beans, build-time type-safe configuration (`@ConfigProperty` vs
`@ConfigMapping`), reactive-versus-blocking endpoints, `@Scheduled` clustering, and production-profile hygiene
(destructive Hibernate schema strategies, SQL logging). It is the **same panel and menu slot** as the Spring advisor —
the same `/spring` route, `/bootui/api/spring` endpoint, and report contract — so the shared UI simply renders the
"Quarkus" label and Quarkus-flavoured copy. The report is a heuristic review prompt, not a verdict. See
[QUARKUS-ADVISOR-CHECKS.md](../QUARKUS-ADVISOR-CHECKS.md) for the full rule catalogue and remediation links.

![BootUI Quarkus panel](../images/bootui-quarkus.webp)

## Database

The Database panel introspects the physical schema of every discovered application `DataSource` bean through
plain JDBC `DatabaseMetaData` — tables, columns, primary keys, foreign keys, and indexes — and evaluates a fixed,
on-demand ruleset of deterministic, low-false-positive structural checks (a missing primary key, a foreign key whose
complete ordered column list has no usable supporting index, duplicate/overlapping indexes, a foreign-key column whose
type disagrees with the column it actually references, a redundant unique index duplicating the primary key,
duplicate foreign key constraints, a narrow auto-generated primary key, and a composite foreign key or unique index
with partially nullable columns). It reuses the same proxy-aware datasource discovery as Database Connection Pools and
SQL Trace, skipping Spring's delegating/routing `DataSource` wrappers and de-duplicating by the physical pool behind
BootUI's own SQL Trace proxy, so a wrapped datasource is never introspected twice. It never executes DDL and never
queries application data.

Every scan is bounded and reports exactly what it could not do. It runs under fixed bounds (at most 300 tables, 300
columns and 100 indexes per table, 500 rows per catalog query, an overall 20-second budget, and a 5-second timeout on
every catalog statement), detects a reached bound deterministically by reading one row past it, and restores the
connection's original read-only state before returning it to the pool. A datasource that could not be read, a table
whose metadata was refused, a catalog view a database role cannot see, a truncated scan, and every rule that was
skipped or errored are reported as per-datasource statuses and diagnostics — never as passing checks, and never counted
as findings or against the advisor score. The scan status is `SCANNED` only when everything was read completely,
`PARTIAL` when something was not, `ERROR` when every datasource failed, and `DISABLED` only when there is no
`DataSource` bean to inspect. Credentials in a JDBC URL or a driver error message are always redacted.

For **PostgreSQL**, **MySQL**, **MariaDB** and **Oracle** (19c+), dialect-specific catalog augmentation runs in
addition to the generic checks: PostgreSQL invalid/broken indexes (`pg_index`, excluding partitioned index parents and
one currently being built `CONCURRENTLY`), a PostgreSQL sequence nearing exhaustion measured against the smaller of
its own maximum and its **owning column's** capacity (the classic `bigint` sequence feeding an `integer` column),
PostgreSQL constraints added `NOT VALID` and never validated, a PostgreSQL table published for logical replication
with no usable replica identity, tables on a non-transactional MySQL/MariaDB storage engine, the legacy three-byte
`utf8mb3` character set (with dialect-appropriate collation guidance — MySQL 8.0's `utf8mb4_0900_ai_ci` does not exist
on MariaDB), an `AUTO_INCREMENT` counter nearing its column type's signed/unsigned capacity, unusable Oracle indexes
(including per-partition status for a partitioned index), disabled or unvalidated Oracle constraints, and a
non-cycling Oracle sequence or `GENERATED ... AS IDENTITY` generator nearing exhaustion. PostgreSQL declarative
partitioning is modelled explicitly, so a finding on a partitioned table is reported once on its parent instead of
once per child partition. The dialect is detected from `DatabaseMetaData.getDatabaseProductName()`, the product
version string and the JDBC URL — MariaDB is detected as its own dialect even through the MySQL driver, which reports
the product name as "MySQL" — and catalog SQL is selected from the reported server version (MySQL 8.0's `IS_VISIBLE`
versus MariaDB 10.6's `IGNORED`, PostgreSQL 10's `pg_sequences`, PostgreSQL 11's `INCLUDE` columns, PostgreSQL 15's
`NULLS NOT DISTINCT`). A driver-reported "Oracle" product name is confirmed against `v$version`/
`product_component_version` before any Oracle-specific augmentation runs, since some Oracle-compatible databases
report the same product name; Oracle's own catalog reads are scoped to the connected session's `CURRENT_SCHEMA`
through only `ALL_*` views and `SYS_CONTEXT`, with no production `ojdbc` dependency anywhere in BootUI. Every other
database (H2, SQL Server, Tibero, EDB Postgres Advanced Server, etc.) still runs the full generic ruleset through the
standard JDBC metadata fallback — it is never treated as unsupported. A catalog query blocked by restricted privileges
makes its rule report `SKIPPED` with that reason instead of silently reporting no findings.

Where the vendor catalog can answer, index semantics JDBC cannot express are folded into the shared model — validity,
partial predicates, expression and prefix key parts, access method, visibility, and (Oracle) whether an index is
automatically created to back a constraint or is a specialized type — so every index rule asks "does this index
actually support that lookup, or enforce that uniqueness?" instead of comparing bare column-name lists.

When a Hibernate `EntityManagerFactory`/metamodel is also available for the same application, the panel additionally
cross-references the physical schema against the mapped JPA entities the shared Hibernate metamodel reader already
reads (the same reader `Hibernate` uses): mapped `@JoinColumn`/`@JoinColumns` foreign keys with no supporting physical
index or no physical foreign key constraint at all (skipping an association declaring
`@ForeignKey(ConstraintMode.NO_CONSTRAINT)`, and never double-counting a physical foreign key `DB-SCHEMA-002` already
evaluates), an explicitly-`@Table`-named entity or `@SecondaryTable` with no matching physical table, a mapped column
that does not exist physically, type-family and *explicitly declared* nullability mismatches, an *explicitly
declared* `@Column(length=...)` longer than the physical column can hold, a mapped unique constraint with no physical
index that genuinely enforces it, and a `@SequenceGenerator(allocationSize=...)` that disagrees with the physical
sequence's `INCREMENT BY`. Only entities with an *explicit* `@Table(name = ...)` are cross-referenced — entities
relying on the default naming strategy are skipped rather than guessed — matching honors a declared `catalog`/`schema`,
and a mapped name that matches tables in several readable datasources is treated as ambiguous rather than attributed
to an arbitrary one. An entity split across secondary tables (`@SecondaryTable`) has each column/join-column/unique
constraint checked against the table it is actually pinned to. Composite foreign key matching tolerates the physical
constraint's own column order but not a different child-to-parent pairing, and verifies the referenced table when it
is resolvable. Attributes whose persisted shape is decided by a converter, `@Enumerated` or `@Lob` are skipped by the
type and length rules. This half of the panel is skipped (with a clear reason, not silently dropped) when either a
`DataSource` or a Hibernate metamodel is unavailable. See
[DATABASE-ADVISOR-CHECKS.md](../DATABASE-ADVISOR-CHECKS.md) for the full rule catalogue and remediation links.

Out of scope by design: this panel proposes no query/workload-based optimizations, runs no execution-plan analysis,
performs no partition discovery/management, and never suggests new indexes based on observed usage — it is a
structural, deterministic advisor in the same spirit as the Hibernate Advisor, not a tuning engine. Resolving a mapped
entity's Hibernate-computed physical name (after the configured naming strategy runs), rather than an explicit
`@Table`/`@Column` name, is also out of scope: the engine deliberately reads only the standard JPA metamodel API, not
Hibernate-internal naming-strategy SPI, to stay provider-version-agnostic.

On Quarkus the panel is identical, running the same shared rule engine over the same report contract: `DataSource`
beans are discovered through `@Any Instance<DataSource>` (unconditionally — `javax.sql.DataSource` is core JDK, so no
capability gating is needed), and the Hibernate cross-reference half reuses the same `EntityDiscoverySource` the
Hibernate panel produces when `quarkus-hibernate-orm` is present. Arc reports BootUI's own `@Alternative` SQL Trace
wrapper alongside the real Agroal pool it wraps, so the adapter de-duplicates by the physical pool behind the proxy —
otherwise the same database would be introspected twice, under two names, doubling every finding. Datasource names come
from the Agroal `@DataSource("...")` qualifier, read reflectively by annotation type name so the panel still links no
`io.quarkus.agroal`/`io.agroal` type and stays safe in an application with no JDBC datasource extension; a bean with no
such qualifier falls back to positional naming (`default`, `datasource-2`, ...).

![BootUI Database panel](../images/bootui-database-advisor.webp)

## Hibernate

The Hibernate panel runs an explicit, read-only scan against the JPA `EntityManagerFactory` metamodel when
Hibernate ORM is present. It reviews mapped entities, selected persistence configuration, and Spring Data repository
metadata for common Hibernate/JPA performance and mapping risks such as eager fetching, problematic identifier
generators, collection fetch pagination, unsafe cascades, cache misconfiguration, and risky `ddl-auto` values. The report
is framed as a review prompt, not a verdict: it never intercepts queries, invokes repositories, executes SQL, or modifies
mappings. See [HIBERNATE-CHECKS.md](../HIBERNATE-CHECKS.md) for the full rule catalogue and remediation links.

On Quarkus the panel runs the same 72-rule registry and report contract when `quarkus-hibernate-orm` is present:
entities are discovered from the live JPA `EntityManagerFactory` metamodel (across all persistence units, de-duplicated
by identity), and most mapping/identifier/fetch rules apply unchanged. Spring Data query rules skip when repository
metadata is unavailable instead of reporting a clean result. Other platform differences are worth noting. First,
persistence configuration is read through a key-mapping layer
(`QuarkusHibernatePropertyLookup`) that translates the Spring/native-Hibernate property names the rules expect onto
their Quarkus equivalents — `ddl-auto`/`hbm2ddl.auto` → `quarkus.hibernate-orm.schema-management.strategy` (or the
deprecated `quarkus.hibernate-orm.database.generation`, including the `drop-and-create` ↔ `create-drop` value alias),
`show-sql` → `quarkus.hibernate-orm.log.sql`, `format_sql` → `quarkus.hibernate-orm.log.format-sql`, `batch_size` →
`quarkus.hibernate-orm.jdbc.statement-batch-size`, `default_batch_fetch_size` → `quarkus.hibernate-orm.fetch.batch-size`,
`jdbc.time_zone` → `quarkus.hibernate-orm.jdbc.timezone`, `generate_statistics` → `quarkus.hibernate-orm.statistics`,
`query.in_clause_parameter_padding` → `quarkus.hibernate-orm.query.in-clause-parameter-padding`,
`query.fail_on_pagination_over_collection_fetch` →
`quarkus.hibernate-orm.query.fail-on-pagination-over-collection-fetch`, and both `cache.use_query_cache` and
`cache.use_second_level_cache` → Quarkus' single unified `quarkus.hibernate-orm.second-level-caching-enabled` toggle.
A native `quarkus.hibernate-orm.log.bind-parameters` flag is also read as the neutral bind-parameter-logging signal. For
any other `hibernate.*` key with no first-class Quarkus config option (for example `hibernate.order_inserts` /
`hibernate.order_updates`), the lookup falls back to Quarkus' generic
`quarkus.hibernate-orm.unsupported-properties."..."` escape hatch, which a live-boot test confirmed reaches Hibernate's
own bootstrapped settings. Only a handful of genuinely Hikari/Spring-specific signals stay unmapped (Hikari's
auto-commit setting, which Agroal has no equivalent for) and their INFO advisories may still cite the Spring-flavored
property name. Second, the Open-Session-in-View check is correctly **inert** on Quarkus: Quarkus has no OSIV concept,
so the effective state is always disabled and the rule never fires (on Spring a missing `spring.jpa.open-in-view`
defaults to the web-on behaviour). Third, bytecode enhancement is always considered enabled on Quarkus — it enhances
every entity unconditionally at build time with no config-based opt-out — so the two lazy-`@OneToOne` findings that
depend on enhancement being disabled never fire there. Fourth, and specific to **Panache** active-record entities:
once a Panache extension (`quarkus-hibernate-orm-panache` or `quarkus-hibernate-reactive-panache`) is on the
classpath, its build-time bytecode rewrite makes public-field access on any Hibernate-managed class behave like a
getter/setter call app-wide, so the public-persistent-field finding does not fire; and the
`@GeneratedValue`-without-strategy finding ignores the `id` field Panache's own base entity declares (an
application-declared identifier is still checked normally). Spring Data repository hints (missing-strategy-aware
`isNew()` detection for assigned identifiers) are specific to Spring Data JPA's `save()` semantics: without Spring Data
Commons on the classpath — the normal case for a Panache app, whose `persist()` has no such ambiguity — that whole
check is skipped rather than reported.

![BootUI Hibernate panel](../images/bootui-hibernate.webp)

## Memory

The Memory panel runs an explicit, read-only scan over the live JVM management beans (heap and memory pools,
garbage collection, threads, loaded classes, and an optional class histogram) and turns them into severity-ranked
findings such as heap pressure, metaspace saturation, native-footprint risk inside a container, lifetime GC overhead,
thread deadlocks, and collection bloat. It complements the raw Live Memory and Threads panels by diagnosing the data they
expose. The scan is on demand and caches the last report; new rules are added as small, focused classes in the `memory`
package. See [MEMORY-CHECKS.md](../MEMORY-CHECKS.md) for the full rule catalogue and remediation links.

![BootUI Memory panel](../images/bootui-memory.webp)

## Security

The Security panel runs an explicit, read-only scan of the host application's registered Spring Security
`SecurityFilterChain` beans and related security beans when Spring Security is on the classpath. It introspects the filter
lists, simulates an anonymous authorization decision, and inspects security-relevant beans (`PasswordEncoder`,
`CorsConfigurationSource`, `JwtDecoder`) and `Environment` properties to flag common hardening gaps across authentication,
authorization, CSRF, session management, transport/security headers, CORS, method security, actuator exposure, OAuth2
resource-server validation, and configuration hygiene. The report is framed as a review prompt, not a verdict: it never
intercepts live traffic, exposes credentials, keys, or session identifiers, or modifies the security configuration. See
[SECURITY-CHECKS.md](../SECURITY-CHECKS.md) for the full rule catalogue and remediation links.

The Security advisor supports **all three** runtime security stacks from the same panel, menu slot, and
`/bootui/api/security` report contract. On **Spring Boot** it analyses Spring Security — the `SecurityFilterChain` beans
and security beans described above.

![BootUI Security panel — Spring Security](../images/bootui-security.webp)

On **Quarkus** it runs a Quarkus-native ruleset instead, reading the application's HTTP permission policies, MicroProfile
`Config`, and authorization-annotated endpoints: Elytron/OIDC authentication,
`quarkus.http.auth.permission.*` authorization, TLS and transport policy, CORS (including the
wildcard-origin-with-credentials trap), security response headers, and Jakarta/Quarkus annotations including
`@RolesAllowed`, `@PermissionsAllowed`, and `@AuthorizationPolicy`. It surfaces the same severity-ranked review prompts,
so the shared UI only
relabels the metrics ("Permission policies" in place of "Filter chains") — the panel is otherwise identical. See
[QUARKUS-CHECKS.md](../QUARKUS-CHECKS.md) for the full Quarkus rule catalogue and remediation links.

![BootUI Security panel — Quarkus Security](../images/bootui-quarkus-security.webp)

On **Spring Boot WebFlux** it evaluates a dedicated 26-rule `SEC-RXF-*` catalogue over a framework-neutral observation
of the application's `SecurityWebFilterChain` beans, reactive CORS/OAuth2 beans, and security-relevant configuration.
The Spring adapter owns collection and excludes BootUI's own permit-all chain; the shared engine owns deterministic
rule evaluation and never receives Spring types or secret values. See [WEBFLUX-SUPPORT.md](../WEBFLUX-SUPPORT.md).

## Pentesting

The Pentesting panel runs explicit, local-only OWASP Top 10 2025 hygiene checks against the host application, not
BootUI's `/bootui` routes. On an explicit scan it combines bounded framework metadata with at most one `GET` and one
`OPTIONS` request to literal `127.0.0.1` under the validated application context path. The two-second client never
follows redirects or uses configured proxies. Checks cover missing or unsafe browser-document headers, CORS behavior,
cookie flags, verbose error exposure, Spring Security wiring, actuator exposure, Quarkus CORS/OIDC/TLS configuration,
and common Spring Boot hardening gaps. It intentionally does not crawl discovered endpoints, send SQL/XSS/destructive
payloads, contact external hosts, or include raw response bodies, cookie values, credentials, or full issuer URLs in the
report. Findings are heuristic review prompts, not proof of exploitability or a replacement for a full security
assessment.

The 80 active checks each have a stable identifier, OWASP 2025 category, evidence source, and recommendation. No-finding
category coverage is informational rather than a pass. Failed or bounded-away request/metadata evidence produces a
`PARTIAL` scan, hides the advisor score, and marks affected coverage `INDETERMINATE`. See
[PENTEST-CHECKS.md](../PENTEST-CHECKS.md) for the full catalogue, limits, mappings, and retired IDs.

Spring MVC is the complete reference collector. WebFlux still contributes Spring configuration and OAuth metadata, but
explicitly reports MVC mapping and servlet-filter evidence unavailable; its reactive Security advisor owns
`SecurityWebFilterChain` route policy. Quarkus runs the same shared scanner/report contract and supplies its live port,
root path, CORS, OIDC, and direct-listener TLS configuration while explicitly marking Spring endpoint/security metadata
unavailable. The coverage matrix uses platform-specific wording, so neither adapter turns unsupported checks into a
false clean result.

![BootUI Pentesting panel](../images/bootui-pentesting.webp)

## Vulnerabilities

The Vulnerabilities panel shows dependency inventory and local OSV vulnerability scan results. It helps identify known
vulnerable dependencies from the running project's dependency set during the local development loop. Scan findings are
ordered by severity first (dismissed findings sink to the bottom regardless of severity), with dependencies and
advisories alphabetized within the same severity.

Severity is derived from [OSV.dev](https://osv.dev/)'s `severity[]` entries, whose `type` identifies how its `score`
must be interpreted. BootUI computes only `CVSS_V3` entries carrying a CVSS v3.0/v3.1 vector (for example
`CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H`); it never treats a bare number or another provider's scale as CVSS. Per the
[OSV schema](https://ossf.github.io/osv-schema/#severity), a package-level `affected[].severity` entry — when present for
the specific dependency being scored — takes priority over the advisory's top-level `severity[]` (the schema states the
two are mutually exclusive, and some advisories only carry severity at the package level), so the scanner looks there
first before falling back to the top-level array. When an array contains multiple valid CVSS v3 entries, the highest
Base Score is used conservatively. A CVSS v3.0/v3.1 vector is parsed using the formula from the
[FIRST.org CVSS v3.1 specification](https://www.first.org/cvss/v3-1/specification-document); CVSS v4.0 has no
closed-form Base Score equation (its MacroVector lookup table is a much larger undertaking), and BootUI's calculator is
intentionally v3-specific rather than implementing the separate CVSS v2 formula, so both fall back to the advisory's
`database_specific.severity` label (`CRITICAL`/`HIGH`/`MODERATE`/`LOW`, normalized to BootUI's `MEDIUM` label) when no
v3 score is present at either level. CVSS `0.0` is reported as `NONE`, matching FIRST's qualitative scale. An advisory
with neither a parseable CVSS v3 score nor a `database_specific` label renders as `UNKNOWN` rather than being silently
dropped. Advisories carrying a `withdrawn` timestamp are excluded from results. OSV omits withdrawn records from POST
query responses but returns them from `GET /v1/vulns/{id}`, so the scanner keeps a defensive detail-stage check. A single advisory detail
fetch that fails (network hiccup, rate limiting) no longer aborts the whole scan: it is counted and the scan degrades to
`PARTIAL`, keeping every advisory that *did* fetch successfully instead of discarding the whole result. Advisory detail
fetches (`GET /v1/vulns/{id}`) run with a small bounded concurrency (up to 10 at a time) rather than one at a time, so a
dependency tree with many distinct advisories no longer risks a scan taking up to `maxAdvisories` times the request
timeout in a bad-network scenario; OSV.dev documents no rate limit for this endpoint.

OSV's `/v1/querybatch` endpoint paginates when an individual query matches more than 1,000 vulnerabilities or the whole
batch exceeds 3,000 total, returning a `next_page_token` per affected query. The scanner follows that token with
follow-up `/v1/querybatch` calls, merging every page back into one result set, bounded by a fixed page-count safety
limit so a pathological advisory can't loop the scan forever (degrading to `PARTIAL` if the bound is hit before
pagination is exhausted, rather than silently truncating). Independently, OSV also enforces a hard limit of 1,000
queries per `/v1/querybatch` request; the scanner partitions the (already `max-packages`-bounded) package list into
batches of at most 1,000 before querying, so configuring `max-packages` above 1,000 no longer causes OSV to reject the
whole batch with an HTTP 400. Every successful response must contain exactly one structurally valid result per query,
and every reported vulnerability reference must carry a non-blank id. Missing, short, or malformed result arrays fail
visibly instead of being interpreted as a clean scan. Repeated advisory
ids are fetched/reported once per dependency, and a detail response whose id does not match the requested advisory is
counted as a failed fetch. If a later query chunk fails after an earlier chunk completed, the completed results are
preserved as `PARTIAL` and `packagesScanned` reports only the completed package queries.

Each advisory whose own id or `aliases` includes a canonical `CVE-*` id is additionally enriched with
[EPSS](https://www.first.org/epss/) (Exploit Prediction Scoring System) data from FIRST.org's free, unauthenticated API
— one or more batched `GET /data/v1/epss?cve=...` requests per scan, each respecting FIRST's documented 2,000-character
maximum for the comma-separated `cve` parameter, alongside the OSV calls and following the same "network call only on
the user-initiated scan action" pattern. Returned ids must belong to the request and probability/percentile values must
be finite numbers from 0 to 1. EPSS reports the modeled probability that a CVE will be
exploited in the wild in the next 30 days, plus the percentile that probability ranks against every other scored CVE —
a likelihood-of-exploitation signal that deliberately complements (rather than replaces) CVSS's severity-if-exploited
score, and is rendered as a secondary badge next to the severity/CVSS badge (for example "2.3% EPSS", with a tooltip
spelling out the percentile). EPSS lookups can be disabled independently of OSV scanning via
`bootui.vulnerabilities.epss-enabled=false`, and a failed or unreachable EPSS request never fails the scan or discards
the OSV results — it simply omits the badge for that scan.

Each advisory also carries a derived `fixAvailable` boolean, computed by comparing the dependency's currently-resolved
version against the advisory's Maven fixed-version candidates with `ComparableVersion` qualifier ordering, including
alpha/beta/milestone/RC/SNAPSHOT/release/service-pack semantics. Git commit hashes from `GIT` ranges are not presented as
Maven upgrades, and candidates are de-duplicated and sorted using Maven semantics. The UI renders a newer candidate as
"fixed in `x.y.z`"; if every reported candidate is at or below the installed version, it says only that OSV reported no
newer fixed version, never that the installed dependency is fixed when OSV just matched it as affected. When OSV reports no
`fixed` event, the UI says only "No fixed version reported by OSV": under the OSV schema a range may instead close
with a mutually exclusive `last_affected` event, which identifies the final vulnerable version without naming the first
non-vulnerable version, so absence of `fixedVersions` is not proof that no fix exists.

Like every other advisor, a vulnerability can be **dismissed** when it does not apply to your project (already
patched downstream, accepted risk, or a fix not yet available upstream) — see the shared dismiss/restore explanation
at the top of this section. The one difference from a flat rule-based advisor is the dismissal key's shape: because a
vulnerability is scoped to one dependency, it is keyed by `<vulnerability id>::<package name>` (for example
`GHSA-xxxx-xxxx-xxxx::org.example:sample`) rather than a bare rule id, so dismissing a finding for one dependency never
accidentally hides the same advisory id reported against a different dependency, and a dismissal survives a
patch-version bump of the still-vulnerable dependency. Dismissed vulnerabilities stay visible (dimmed, with a
_Restore_ button) rather than disappearing, are excluded from the per-dependency and panel-level vulnerable counts, and
trigger a fresh deterministic dependency ordering from the recomputed active severity. Dismiss/restore controls are
disabled when the Vulnerabilities panel is read-only.

On Quarkus the panel is identical, listing the local inventory first and contacting OSV.dev only on the user-initiated
scan, over the same report contract, the same CVSS/withdrawn/partial-failure handling, the same pagination/batch-
chunking, the same EPSS enrichment, and the same dismiss/restore workflow. The one platform difference is dependency
discovery: the Spring adapter scans the classpath for `META-INF/maven/*/pom.properties`, which is unreliable under the
Quarkus runtime classloader. For JARs without embedded metadata, Spring also reads an adjacent Maven POM (including in
nonstandard local-repository paths), and only falls back to path-derived coordinates when a literal `repository`
directory makes the group path unambiguous; it never guesses a group id from an arbitrary cache path. Unreadable
individual `pom.properties` resources are logged and skipped instead of failing the complete inventory, and classpath
JAR filenames must match the resolved artifact/version exactly (with an optional classifier) rather than merely sharing
a version prefix. The Quarkus
inventory is captured at build time from the application's resolved runtime
dependency model and read back at runtime (mirroring the Architecture panel's build-time base-package discovery). The
OSV and EPSS lookups are identical, and `bootui.vulnerabilities.osv-enabled=false` /
`bootui.vulnerabilities.epss-enabled=false` disable on-demand scanning / EPSS enrichment on both adapters.

Two known limitations, documented honestly rather than hidden: the dependency inventory on both adapters is
coordinate-based (one resolved JAR = one Maven `groupId:artifactId:version`), so a vulnerable library that has been
relocated or repackaged inside a shaded/uber JAR carries no `pom.properties`/build-time coordinate of its own and is
invisible to the inventory — the same reduced-fidelity honesty precedent already applied to other panels (for example
Cache, Beans). And direct-vs-transitive dependency provenance ("introduced through") is not yet tracked on either
adapter: Quarkus could source it from its existing build-time application dependency graph, but Spring's classpath-based
inventory has no equivalent dependency graph today (adding one would need POM/Maven-plugin integration, a much larger
change), so this is deferred rather than shipped as a Quarkus-only asymmetry for now.

![BootUI Vulnerabilities panel](../images/bootui-vulnerabilities.webp)
