# Quarkus checks

The Quarkus application advisor is the Quarkus flavor of the shared **Spring** panel:
the panel ID remains `spring`, the endpoint remains `/bootui/api/spring`, and the shared
`SpringReport` JSON and dismissal contract are unchanged. It is separate from the
[Quarkus Security advisor](QUARKUS-CHECKS.md).

The advisor has **13 active rules**. It inspects application-owned build metadata and
selected configuration only when the user requests a scan. It never invokes application
beans, constructs REST clients, executes scheduled work, reads JDBC data, intercepts
traffic, or changes configuration. Findings are review prompts, not proof of a race,
measured performance failure, or the configuration of an unseen deployment.

## Evidence and availability

CDI evidence comes from Arc's resolved application class beans, including effective scope
and injection points, rather than counts of directly declared scope annotations.
Application metadata is captured in non-production launch modes; the existing `NORMAL`
production exclusion remains unchanged. Missing or unreadable metadata is **unknown**,
not an empty, clean application.

Configuration analysis distinguishes active effective settings, framework defaults, and
visible production declarations. The native configuration machinery resolves active
values and registered REST-client aliases. An inactive production profile is inspected
only through already-loaded production declarations: BootUI does not load another
profile's files, invent external environment values, resolve a production expression
through development values, or switch the application's profiles. Multiple active
profiles are not treated as proof of a future production deployment.

Production declarations can produce useful findings while coverage remains incomplete.
`PARTIAL` reports retain those findings and explain unavailable evidence in
`analysisErrors`; `ERROR` means no applicable evidence could be inspected.
Skipped/unknown checks are not counted as successfully evaluated. Dismissing a finding
does not remove coverage errors or turn an incomplete scan into a complete one.
Retired rule IDs are never reused, and existing dismissals of surviving IDs still apply.

### Bounds

- Configuration name discovery considers at most 4,096 source names, plus an overflow
  sentinel, across at most 128 existing sources. Original profile prefixes are preserved.
- Production declaration projection reuses those sources and retains at
  most 4,096 declarations; it discovers no new sources.
- Build metadata considers at most 10,000 application classes/beans and 100,000 members.
- At most 256 registered REST clients are inspected.
- Each rule shows at most 20 deterministic samples; occurrence totals are independent
  of the display limit.

A reached bound or failed conversion is reported explicitly. Unrelated collected
findings remain available. These are cardinality bounds, not a guarantee that arbitrary
third-party configuration-source code can be interrupted. Raw URLs, credentials,
unrecognized configuration values, and exception messages are not exposed.

## CDI

### QA-CDI-001 - Public mutable state on an application-scoped bean

**LOW.** A resolved `@ApplicationScoped` application class bean exposes a public,
potentially mutable instance field that is not an injection point. Public final immutable
values are excluded. Private fields alone are not evidence of unsynchronized access:
initialization-only writes, volatile flags, atomics, concurrent collections and locking
can all be legitimate.

Review whether the field should expose an immutable value or an encapsulated operation.
`final` does not make an object deeply immutable, and making a field private does not
itself make its accesses thread-safe. Arc `@Lock` protects intercepted method calls,
not arbitrary external reads/writes of public fields; a returned asynchronous operation
is not necessarily protected for its entire lifetime.

### QA-CDI-002 - Public mutable state on a shared REST resource

**MEDIUM.** An actual application REST resource with a resolved shared scope exposes
a public mutable instance field that is not injected. Quarkus REST's default singleton
scope and its automatic request scope for REST parameter-field injection are respected;
outbound REST-client interfaces are not inbound resources. The resource-specific rule
does not also charge the same field under the two general CDI rules.

Review the state lifetime and public access. Move genuinely request-specific state into
a request-scoped object, or expose an appropriate immutable value or operation. This is
not a claim that concurrent mutation was observed.

### QA-CDI-003 - Public mutable state on a singleton bean

**LOW.** The same policy as QA-CDI-001 applies to a resolved `@Singleton` application
class bean, including a scope assigned by the framework. Scope annotations on a producer
class do not establish the scope of the object returned by a producer.

CDI sources: [Jakarta CDI scopes](https://jakarta.ee/specifications/cdi/4.1/jakarta-cdi-spec-4.1.html#scopes),
[Arc effective scope resolution](https://github.com/quarkusio/quarkus/blob/3.33.3.1/independent-projects/arc/processor/src/main/java/io/quarkus/arc/processor/Beans.java#L1382-L1393),
[implicit qualifier-field injection](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/arc/deployment/src/main/java/io/quarkus/arc/deployment/AutoInjectFieldProcessor.java#L70-L82),
and [Quarkus REST scope handling](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/resteasy-reactive/rest/deployment/src/main/java/io/quarkus/resteasy/reactive/server/deployment/ResteasyReactiveCDIProcessor.java#L69-L121).

## Configuration and production declarations

### QA-CFG-002 - Production SQL logging

**MEDIUM.** Observed production configuration enables Hibernate SQL logging on a
default or named persistence unit. Review production log volume and the sensitivity of
SQL text. SQL logging does **not** automatically enable bind-parameter logging, which
has separate settings; SQL literals may nevertheless contain application data.

### QA-CFG-003 - Verbose production root logging

**MEDIUM.** The observed production root log level is `DEBUG`, `TRACE`, or `ALL`.
Review whether that verbosity is intended for the deployment; `INFO` or `WARN` is often
a more appropriate baseline. BootUI does not infer a measured slowdown or an actual
secret disclosure from the level alone.

### QA-CFG-004 - Deprecated Hibernate schema property

**LOW.** A configured default/named/profile variant of
`quarkus.hibernate-orm.database.generation` uses the deprecated namespace.
Migrate to `quarkus.hibernate-orm.schema-management.strategy`, preserving the intended
action. A property name merely supplied by framework defaults or an empty value is not
enough to trigger this rule.

On Quarkus **3.33.3.1**, an explicitly configured legacy property takes precedence over
the new property. Remove or migrate the old declaration rather than assuming a new
`schema-management.strategy=none` has overridden it.

### QA-PROD-002 - Production schema creation, alteration or dropping

**CRITICAL** for `drop` or `drop-and-create`; **HIGH** for `create` or `update`.
The rule distinguishes the actual schema actions and includes named persistence units.

Quarkus sends this value through the **Jakarta** schema-action setting. Its `create`
means **create-only**, not Hibernate's legacy `hibernate.hbm2ddl.auto=create`, which
drops before recreating. `create` and `update` can still perform unreviewed production
DDL, but are not described as guaranteed deletion of existing data. `none` and `validate`
do not trigger this rule. Unknown spellings are not silently normalized into a
destructive action.

Prefer reviewed migrations or the deployment's existing schema-management process.
The advisor does not require that migration tooling run inside the application.

### QA-PROD-003 - Observed in-memory production datasource

**MEDIUM.** A supported JDBC URL explicitly selects in-memory storage. H2, HSQLDB or
Derby **database kind alone is not evidence of volatile storage**: file-backed and
server-backed databases are valid. URL matching uses storage-mode forms, not an
incidental `mem` substring elsewhere in the URL.

Confirm that the data is intentionally transient, or select durable storage.
An in-memory database exposed by a database server can be shared across application
instances; its durability follows that database process, not necessarily the application
process. The raw JDBC URL is never included in the report.

Sources: [profile and source precedence](https://quarkus.io/guides/config-reference#profiles),
[Quarkus schema-action wiring and legacy precedence](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/hibernate-orm/runtime/src/main/java/io/quarkus/hibernate/orm/runtime/FastBootHibernatePersistenceProvider.java#L497-L499),
[Hibernate create-only distinction](https://github.com/hibernate/hibernate-orm/blob/7.2.19/hibernate-core/src/main/java/org/hibernate/tool/schema/Action.java#L22-L25),
[separate bind logging](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/hibernate-orm/deployment/src/main/java/io/quarkus/hibernate/orm/deployment/HibernateOrmConfig.java#L145-L163),
and [H2 storage modes](https://github.com/h2database/h2database/blob/version-2.4.240/h2/src/docsrc/html/features.html#L246-L260).

## HTTP and clients

### QA-WEB-001 - Application HTTP compression disabled

**INFO.** Compression is either explicitly disabled or disabled by the framework
default; the report distinguishes the two. Enabling compression may help suitable
payloads, but is not universally necessary. Upstream compression, client negotiation,
response media types and workload are not inspected.

### QA-WEB-002 - Explicit zero shutdown timeout

**MEDIUM.** `quarkus.shutdown.timeout=0` disables HTTP request-draining grace.
Set a **positive duration**, for example `10s`, if requests should be allowed to finish.
Removing the override is not equivalent: the framework default also leaves draining
disabled. This does not promise completion of every scheduled or messaging operation.

### QA-WEB-003 - Managed REST-client timer disabled

**MEDIUM.** A registered client's effective connect or read timer is zero.
The native Quarkus configuration interceptors resolve quoted FQCN, `configKey`,
MicroProfile `/mp-rest/` aliases, profiles and configuration-source priority.
Client-specific values override the global fallback as the framework specifies;
properties for unrelated/unregistered clients do not trigger findings.

The standard defaults are **15s connect / 30s read**. A positive finite timeout,
including one above five minutes, is not automatically unsafe. Zero disables the
corresponding standard-transport timer, not necessarily every other deadline.
In particular, a read timer is not a universal total-operation deadline.
Review whether that disabled timer is intentional, or configure an appropriate positive
duration in milliseconds. Arbitrary programmatic/custom client settings are not inferred.

### QA-WEB-004 - HTTP request draining not configured

**INFO.** The shutdown timeout is known to be absent. Quarkus request-draining grace is
opt-in, so configure a positive `quarkus.shutdown.timeout` if required.
This rule is mutually exclusive with QA-WEB-002. An unreadable duration is an analysis
error, not an absent value.

Sources: [compression default](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/VertxHttpBuildTimeConfig.java#L72-L80),
[shutdown default](https://github.com/quarkusio/quarkus/blob/3.33.3.1/core/runtime/src/main/java/io/quarkus/runtime/shutdown/ShutdownConfig.java#L18-L23),
[client defaults](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/resteasy-classic/rest-client-config/runtime/src/main/java/io/quarkus/restclient/config/RestClientsConfig.java#L139-L153),
[native aliases and priority handling](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/resteasy-classic/rest-client-config/runtime/src/main/java/io/quarkus/restclient/config/AbstractRestClientConfigBuilder.java#L71-L232),
and [zero connect-timer semantics](https://github.com/netty/netty/blob/netty-4.1.136.Final/transport/src/main/java/io/netty/channel/ChannelConfig.java#L105-L121).

## Virtual threads

### QA-PERF-002 - Potential synchronized virtual-thread pinning

**LOW.** On the **running JDK 21-23**, an identified Quarkus REST virtual-thread entry
method is also declared `synchronized`. Review blocking work performed while holding
the monitor; declaration metadata alone does not establish that blocking occurs.

The rule does not treat every helper in an annotated class as a virtual-thread entry
point and does not double-count a method annotated at both class and method level.
Method-body synchronized blocks and unmodeled scheduler/messaging invocation paths are
outside its evidence. [JEP 491](https://openjdk.org/jeps/491) removes synchronized-related
pinning from JDK 24 onward, so this rule does not fire there. The build JDK is not used
as a substitute for the running JDK.

Sources: [Quarkus dispatch decisions](https://github.com/quarkusio/quarkus/blob/3.33.3.1/independent-projects/resteasy-reactive/common/processor/src/main/java/org/jboss/resteasy/reactive/common/processor/EndpointIndexer.java#L881-L960),
[framework-interpreted annotation contract](https://github.com/smallrye/smallrye-common/blob/2.16.0/annotation/src/main/java/io/smallrye/common/annotation/RunOnVirtualThread.java#L8-L22),
and [Quarkus virtual-thread guide](https://quarkus.io/guides/virtual-threads).

## Complete audit disposition

All 19 original IDs are accounted for. Retired IDs remain reserved so stored dismissals
cannot later target an unrelated rule.

| Rule | Disposition | Reason |
| --- | --- | --- |
| QA-CDI-001 | Updated | Resolved application scope/injection; public-state-only LOW review, no private-field race claim. |
| QA-CDI-002 | Updated | Actual shared REST resources and injection/scope handling; no duplicate general-CDI charge. |
| QA-CDI-003 | Updated | Resolved singleton scope; same narrowed public-state policy. |
| QA-CFG-001 | Retired | ConfigMapping is recommended, not mandatory; no custom configuration and programmatic access are valid. |
| QA-CFG-002 | Updated | Production/named-unit evidence; SQL text and bind logging distinguished. |
| QA-CFG-003 | Updated | Production provenance and unavailable coverage made explicit. |
| QA-CFG-004 | Retained | Valid deprecation advice; actual declarations and legacy precedence handled correctly. |
| QA-RX-001 | Retired | JDBC capability plus reactive signatures does not prove an event-loop call; Mutiny offloading is legitimate. |
| QA-SCH-001 | Retired | Local/per-replica jobs are legitimate; no topology or once-per-cluster intent is observed. |
| QA-PROD-001 | Retired | `RUN` can use profile `prod` with Dev Services; the declaration need not be useless. |
| QA-PROD-002 | Updated | Quarkus create-only semantics, legacy precedence, named units and explicit coverage. |
| QA-PROD-003 | Updated | Require observed in-memory URL mode, not database vendor. |
| QA-PROF-001 | Retired | Missing `%prod` keys do not establish missing production configuration. |
| QA-DB-001 | Retired | The finite default maximum is 50; workload/database sizing cannot be inferred from omission. |
| QA-WEB-001 | Updated | Distinguish disabled/default/unknown; compression remains a conditional optimization. |
| QA-WEB-002 | Updated | Positive-duration remediation; removing zero does not enable draining. |
| QA-WEB-003 | Updated | Effective registered-client timers; zero only, no arbitrary finite ceiling. |
| QA-WEB-004 | Retained | Valid opt-in default information, only when absence is known. |
| QA-PERF-002 | Updated | Actual REST entry points and running JDK; conditional LOW risk, not observed HIGH pinning. |

Retirement sources: [supported configuration APIs](https://github.com/quarkusio/quarkus/blob/3.33.3.1/docs/src/main/asciidoc/config-reference.adoc#L266-L278),
[Mutiny offloading](https://github.com/smallrye/smallrye-mutiny/blob/3.1.1/documentation/docs/guides/imperative-to-reactive.md#L14-L33),
[local versus composite scheduling](https://github.com/quarkusio/quarkus/blob/3.33.3.1/docs/src/main/asciidoc/scheduler-reference.adoc#L282-L403),
[launch modes](https://github.com/quarkusio/quarkus/blob/3.33.3.1/core/runtime/src/main/java/io/quarkus/runtime/LaunchMode.java#L3-L17),
and [Agroal default maximum](https://github.com/quarkusio/quarkus/blob/3.33.3.1/extensions/agroal/runtime/src/main/java/io/quarkus/agroal/runtime/DataSourceJdbcRuntimeConfig.java#L39-L43).

The source audit targets Quarkus **3.33.3.1**. Pinned supporting JDK, Hibernate, SmallRye
and database references explain specific semantics; they do not claim every application
uses the same dependency patch. Floating guides are reading aids, not stronger evidence
than the version-pinned implementation.
