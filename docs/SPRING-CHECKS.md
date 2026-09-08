# Spring checks

The Spring panel runs a fixed, on-demand ruleset against the host application's **running Spring application context** and `Environment`. It takes a read-only snapshot of selected bean groups (Jackson `ObjectMapper`s, `Executor`s/`TaskExecutor`s, `DataSource`s) and feature flags, then evaluates a curated set of configuration and best-practice checks. It never mutates the context, intercepts live traffic, or surfaces secrets.

Because the advisor runs inside the *started* application, its evidence is deliberately limited. A configured default is not proof of effective execution, a bean definition is not proof that the bean is used, and a production-like profile name does not establish deployment topology. Concrete configuration concerns and optional INFO opportunities are both useful; an optimization suggestion does not promise a measured benefit or require a change.

This advisor is complementary to the **Architecture** panel: Architecture statically analyzes compiled bytecode with ArchUnit, whereas the Spring Advisor inspects the live, wired runtime context.

The same ruleset runs on Spring MVC and WebFlux. Servlet OSIV guidance is inapplicable on WebFlux, while the two [reactive rules](#reactive-webflux-only) are inapplicable on MVC. Virtual-thread advice distinguishes Boot task execution from reactive event loops; Boot's virtual-thread switch does not switch Reactor's shared `boundedElastic` scheduler. Client advice distinguishes observable Boot settings from unknown per-client customization.

## Availability and bounds

The panel is always available when the Spring advisor is enabled. Scanning is explicit and on demand; GET returns the cached report. Collection uses bounded, non-eager bean metadata and does not create lazy beans, invoke application customizers, open database connections, or probe remote services. Missing required evidence is unevaluated, not a successful check; inspection failures and invalid bindings are reported without raw exception messages or property values. The Rule results panel lists findings, ordered by severity, finding count, and rule ID.

Scheduler, cache-provider and servlet OSIV gaps retain specific, bounded explanations in both inspected observations
and coverage limitations. A rule evaluation failure is identified separately from unavailable evidence; neither exposes
exception messages or raw settings. Independently observed findings survive incomplete registration/provider coverage.

## Severity scale

A `SCANNED` report can still contain unknown observations or analysis errors. Usable known-findings scores retain
those limitations under the shared [score eligibility policy](features/advisors.md#score-eligibility).

- **CRITICAL** - reserved by the shared report contract; no Spring rule infers this severity from profile names or management-port equality.
- **HIGH** - a setting that commonly causes problems and usually needs attention before production.
- **MEDIUM** - a hardening or correctness gap that warrants review.
- **LOW** - lower-impact hygiene or optimization findings.
- **INFO** - informational prompts where the right fix depends heavily on project context.

The advisor score applies the shared severity penalty to every concrete finding, not just once per violated rule.
Dismissed rules remove all of their findings from the score.

---

## Bean wiring

### SPRING-WIRING-001 - Review bean definition overriding

- **Severity**: MEDIUM
- **Detects**: The running bean factory permits bean-definition overriding. Programmatic factory configuration can differ from `spring.main.allow-bean-definition-overriding`; permission is not evidence that an override actually occurred.
- **Recommendation**: Keep overriding disabled unless it is intentional, and use distinct names where accidental replacement should fail fast.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-WIRING-002 - Review circular-reference permission

- **Severity**: MEDIUM
- **Detects**: The running bean factory permits circular references. This is not proof of a dependency cycle, and the current Environment alone does not establish the factory's startup configuration.
- **Recommendation**: Prefer explicit dependency boundaries and keep circular-reference resolution disabled unless it is deliberately required.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-WIRING-003 - Review JSON mapper candidate selection

- **Severity**: INFO
- **Detects**: Multiple candidates within the same relevant mapper type have unresolved default candidate metadata. Jackson 2 `ObjectMapper` and Jackson 3 `JsonMapper` are separate groups: one of each is not an ambiguity. Candidate flags and aliases are considered; qualifiers, priorities and injection-point names prevent a blanket conclusion about injection.
- **Recommendation**: Review the intended default and qualified consumers. For Boot's Jackson 3 mapper, prefer `JsonMapperBuilderCustomizer` where a separate mapper is unnecessary.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/json.html>

### SPRING-WIRING-004 - Review default async executor selection

- **Severity**: INFO
- **Detects**: Async infrastructure is present and default executor candidate metadata is unresolved. Framework's `taskExecutor` fallback differs from Boot's `applicationTaskExecutor` integration and force mode. A custom `AsyncConfigurer` or qualified method can choose differently; the scan does not invoke it.
- **Recommendation**: Review the default executor and explicit `@Async` qualifiers. Do not assume naming any executor `applicationTaskExecutor` resolves all consumers.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/integration/scheduling.html>

### SPRING-WIRING-005 - Review DataSource candidate selection

- **Severity**: INFO
- **Detects**: Multiple `DataSource` candidates have unresolved default candidate metadata. Multiple databases and qualified consumers are valid; bean counts do not prove failed or incorrect injection.
- **Recommendation**: Review the intended default, candidate flags and qualified consumers. Add a primary only when a default is actually intended.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html>

### SPRING-WIRING-006 - Review transaction-manager selection

- **Severity**: INFO
- **Detects**: Multiple imperative `PlatformTransactionManager` candidates have unresolved default metadata. The XML convention `transactionManager` is not a universal override for Java `@EnableTransactionManagement`; custom configurers and qualifiers can determine selection. This inventory does not prove reactive transaction behavior.
- **Recommendation**: Review the default/configurer or use explicit transaction-manager qualifiers where multiple managers are intentional.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/data-access/transaction.html>

### SPRING-WIRING-007 - Prefer RestClient over RestTemplate

- **Severity**: LOW
- **Detects**: A `RestTemplate` bean is defined; this is not proof it is used. Framework 7 documentation directs new synchronous client work toward `RestClient`, without establishing a removal date or a compiler deprecation warning for every supported version.
- **Recommendation**: Prefer an injected Boot `RestClient.Builder` when migrating so common settings and customizations are preserved. Retain `RestTemplate` where a dependency still requires it.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/integration/rest-clients.html>

### SPRING-WIRING-008 - Avoid components in the default package

- **Severity**: LOW
- **Detects**: Non-eagerly resolvable application bean types in the unnamed package, including `@Bean` product types. A plain object in that package does not itself cause a classpath-wide component scan; that concern applies to a scan root there.
- **Recommendation**: Prefer named packages and keep component-scan roots bounded. The Architecture advisor independently covers static application structure.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html>

### SPRING-WIRING-009 - Avoid public mutable fields on singleton beans

- **Severity**: LOW
- **Detects**: Public mutable fields on singleton application beans, excluding injection points, configuration-bound fields, synthetic fields and accessor-backed Kotlin properties. Exposed mutable state warrants review but does not prove concurrent access or a data race.
- **Recommendation**: Make the field private and expose an accessor if needed, mark it final and set it only from the constructor, or move truly per-request/per-call state out of the singleton (a method-local variable, a request-scoped bean, or immutable value types).
- **Learn more**: <https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html#beans-factory-scopes-singleton>

## Configuration

### SPRING-CONFIG-001 - Consider lazy initialization for large contexts

- **Severity**: INFO
- **Detects**: A large context has non-lazy definitions and no explicit lazy-initialization opt-out. The more-than-300-definition threshold filters noise; it is not a measured startup-cost threshold, and the global property does not prove every bean's initialization state.
- **Recommendation**: If startup matters, measure before evaluating lazy initialization. Consider delayed wiring failures, first-use latency and heap sizing; preserve deliberately eager infrastructure. An explicit `spring.main.lazy-initialization=false` suppresses this optional prompt.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-CONFIG-002 - Disable global debug or trace logging

- **Severity**: LOW
- **Detects**: Configured debug/trace flags or broad verbose logger levels. These are configuration observations, not a measurement of current logger state or sensitive log contents. Boot's debug flag does not set every logger to DEBUG; profile names do not increase severity.
- **Recommendation**: Remove the debug/trace flags and configure logging levels only for narrow packages that need them.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/logging.html>

### SPRING-CONFIG-003 - Remove renamed or deleted Spring Boot 4 properties

- **Severity**: MEDIUM
- **Detects**: Source-verified renamed/removed properties for Boot 4.1.1, including old server error and encoding keys, Undertow, HTTP clients, Mongo connection keys, tracing export, Redis sessions, Mongo session auto-configuration, and `spring.codec.max-in-memory-size` / `spring.codec.log-request-details` (now `spring.http.codecs.*`). A properties migrator or host integration can still translate a legacy key; the finding does not assert it has no effect everywhere.
- **Live-property exceptions**: `spring.dao.exceptiontranslation.enabled` is still read by JDBC transaction-manager auto-configuration. `server.servlet.encoding.mapping`, Jackson stream versus JSON feature groups, and unrelated `spring.data.mongodb` GridFS/index settings remain distinct live configuration and are not indiscriminately renamed.
- **Recommendation**: Update each key to its Spring Boot 4 equivalent (the spring-boot-properties-migrator module lists the replacements at startup) and remove keys for dropped features.
- **Learn more**: <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide>

### SPRING-CONFIG-004 - Set spring.application.name

- **Severity**: INFO
- **Detects**: `spring.application.name` is not set. This omits Boot's common application-name default; individual logging, metrics, tracing and discovery integrations can still have separately configured identities.
- **Recommendation**: Set spring.application.name to a stable identifier for this service.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-CONFIG-005 - Do not ignore missing config files

- **Severity**: MEDIUM
- **Detects**: `spring.config.on-not-found=ignore` configures global tolerance of missing config data. The scan does not prove a file was missing.
- **Recommendation**: Remove spring.config.on-not-found=ignore (the default fails fast) and use the optional: prefix only on the specific imports that are genuinely optional.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/external-config.html>

### SPRING-CONFIG-006 - Review Jackson 2-compatible defaults

- **Severity**: INFO
- **Detects**: Applicable Jackson 3 configuration requests Jackson 2-compatible defaults with `spring.jackson.use-jackson2-defaults=true`. This is not a switch to the Jackson 2 implementation, and the compatibility setting has no established removal deadline.
- **Recommendation**: Document the intended payload contract and add compatibility tests before changing defaults. Keeping compatible serialization can be intentional; removing this setting is not mandatory.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/json.html>

## Profiles and environment

### SPRING-PROFILE-002 - Spring Boot DevTools should be scoped to development

- **Severity**: INFO
- **Detects**: DevTools is on the classpath alongside production-like effective profile names. The naming heuristic is not proof of a production deployment, and classpath presence does not prove restart or live reload is enabled. Ordinary development use is expected.
- **Recommendation**: Review packaging and actual activation if those profiles represent production. Maven `optional` controls transitive use, not by itself every packaging path; check the deployed artifact.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/using/devtools.html>

### SPRING-PROFILE-003 - Keep profile-name validation enabled

- **Severity**: INFO
- **Detects**: `spring.profiles.validate=false` explicitly disables Boot's profile-name validation. This is a supported flexibility choice, not evidence that a profile name is invalid.
- **Recommendation**: Keep the default validation when its naming constraints suit the application; document any deliberate exception.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/profiles.html>

## Performance and concurrency

### SPRING-PERF-001 - Consider enabling virtual threads

- **Severity**: INFO
- **Detects**: Java 21+ and applicable MVC or Boot task-execution evidence, without an explicit virtual-thread opt-out. A pure reactive HTTP path is not an opportunity to replace event loops.
- **Recommendation**: Consider `spring.threads.virtual.enabled=true` for suitable blocking workloads, measuring the effect and preserving downstream concurrency limits. It does not accelerate CPU-bound work or switch Reactor `boundedElastic`; Reactor has a separate `reactor.schedulers.defaultBoundedElasticOnVirtualThreads` system property. Java 24's JEP 491 removes synchronized-monitor pinning; assess limitations against the actual JDK.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-002 - Review pooled executor routing

- **Severity**: INFO
- **Detects**: Virtual threads are configured and a `ThreadPoolTaskExecutor` is defined. Co-presence does not cancel virtual-thread execution elsewhere, establish routing, or prove the pool's custom thread factory uses platform threads.
- **Recommendation**: Review which tasks use the pool and why. CPU isolation and bounded concurrency can be intentional; do not remove a useful constraint merely to eliminate this prompt.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-003 - Review the default async fallback

- **Severity**: LOW
- **Detects**: The default async path has no resolvable executor and would use Framework's `SimpleAsyncTaskExecutor` fallback. Custom configurers, qualifiers and unresolved metadata are not guessed. Boot's ordinary core-size default of eight is not labeled unreviewed, and setting the virtual-thread property alone does not replace a Framework fallback.
- **Recommendation**: Supply a deliberate executor for unqualified async work and review concurrency/admission policy. This does not prove every `@Async` method uses the default.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-005 - Scheduler runs on a single thread

- **Severity**: INFO
- **Detects**: Known single-thread scheduler selection with multiple relevant registered application tasks. Native task outcome wrappers, Boot's scheduling-observation configurer and BootUI's observation-only configurer do not obscure that evidence. Application tasks are not excluded by a BootUI-like package name. For multiple tasks, the registrar's already-selected scheduler instance must agree with observable candidate selection before its native pool is read; no lazy scheduler supplier is invoked. Fewer than two registered tasks need no pool-size observation. Exact `SimpleAsyncTaskScheduler` selection is inapplicable to this pool check, not a missing thread-pool size. `@EnableScheduling` or an absent pool-size property alone does not establish selection; ambiguous/unobservable schedulers, qualifiers and custom configurers remain unknown.
- **Recommendation**: Review whether tasks need to overlap and how delays affect other tasks. Deliberate serialization can be correct. Virtual threads do not universally solve fixed-delay scheduling, and changing fixed-delay to fixed-rate changes semantics.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-006 - Bound the @Async executor queue

- **Severity**: LOW
- **Detects**: A positively identified default async executor has an observed effectively unbounded queue. By-type executor lookup considers primary metadata before the default-candidate fallback; an executor excluded from unqualified injection can still be selected by that lookup. An explicit `Integer.MAX_VALUE` is still unbounded; a bounded custom executor is not flagged merely because Boot properties are absent. Lazy/unobservable executor or custom selection remains unknown.
- **Recommendation**: Review queue capacity, rejection/backpressure and downstream limits together. An unbounded queue prevents maximum pool size from serving as a normal expansion control; virtual threads are not admission control.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-CACHE-001 - Review concurrent-map cache bounds

- **Severity**: INFO
- **Detects**: An exact known `ConcurrentMapCacheManager` lacks built-in capacity/expiry policy, including when wrapped by BootUI's own final cache-activity decorator. The same safe unwrapping retains `NoOpCacheManager` and `CaffeineCacheManager` provider identity and completion credit. NoOp stores nothing; a Caffeine pass means this concurrent-map-specific concern does not apply, **not** that every Caffeine configuration is bounded. No application delegate callback is invoked, and custom wrappers/implementations/subclasses remain unknown. A known concurrent-map finding is retained even when another provider remains unclassified.
- **Recommendation**: If key growth or staleness matters, review bounds/expiry or a suitable provider. Local Caffeine is a valid production choice; shared storage is not universally required.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/io/caching.html>

## Web and HTTP

### SPRING-WEB-001 - Consider origin response compression

- **Severity**: INFO
- **Detects**: An applicable Boot-managed origin is not configured for compression, without an explicit opt-out. This does not establish whether responses reaching the client are compressed; custom server behavior and proxy/CDN compression differ.
- **Recommendation**: If the edge does not already compress, evaluate `server.compression.enabled=true` against response sizes, content types and CPU cost. Explicit false suppresses this optional prompt.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-002 - Keep graceful shutdown enabled

- **Severity**: MEDIUM
- **Detects**: Applicable embedded-server configuration requests immediate shutdown or no positive lifecycle grace period. Boot 4's graceful default passes. Duration comparison preserves positive sub-millisecond values; malformed configuration is an error rather than a default.
- **Recommendation**: Prefer graceful shutdown with an appropriate positive grace period where in-flight work should finish. The default 30 seconds applies per lifecycle phase, not necessarily to the entire shutdown.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html>

### SPRING-WEB-003 - Consider enabling HTTP/2

- **Severity**: INFO
- **Detects**: An applicable Boot-managed origin is not configured for HTTP/2, without an explicit opt-out. This is not proof HTTP/2 is absent at the edge or that latency is suboptimal.
- **Recommendation**: Evaluate origin HTTP/2 only where clients/topology benefit. Account for TLS `h2`, cleartext `h2c` behind suitable infrastructure, and edge termination. Explicit `server.http2.enabled=false` suppresses this optional prompt.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-004 - Review configured error-detail disclosure

- **Severity**: MEDIUM
- **Detects**: Current `spring.web.error.*` settings request detail disclosure (`always` or caller-controlled `on-param`, or `include-exception=true`) in Boot's fallback error handling. This does not characterize every custom error response. Legacy `server.error.*` keys belong to SPRING-CONFIG-003; deliberate development defaults and custom handling need separate context.
- **Recommendation**: Use `never` for stacktrace/message/binding-error details and false for exception inclusion where details must stay private. `on-param` is not an authorization boundary.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-005 - Review Boot HTTP client timeout policy

- **Severity**: INFO
- **Detects**: Identifiable Boot-managed client configuration has an incomplete timeout policy at the global/default or relevant named-service group level. A configured group does not cover unrelated clients. Programmatic client settings and provider defaults can still supply timeouts; custom-only or unattributable clients are not labeled unconfigured.
- **Recommendation**: Check effective per-client connect/read/deadline behavior against dependency budgets, using `spring.http.clients.*`, relevant `spring.http.serviceclient.<name>.*` settings or deliberate code customization. No universal timeout value or infinite-wait claim is inferred.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/io/rest-client.html>

### SPRING-WEB-007 - Tomcat thread cap is redundant with virtual threads

- **Severity**: LOW
- **Detects**: An explicit Tomcat thread cap alongside positively observed applicable virtual-thread executor configuration on a supporting JDK. A Tomcat factory type and a property alone do not prove the selected executor; custom/unknown routing and non-Tomcat servers are unevaluated.
- **Recommendation**: Review/remove the cap when the observed executor no longer uses it. Preserve deliberate admission limits elsewhere; virtual threads do not bound request concurrency.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

## Data and persistence

### SPRING-JPA-001 - Review Open Session in View

- **Severity**: MEDIUM
- **Detects**: Observed servlet OSIV registration, including the interceptor actually adapted into an MVC handler mapping. Boot interceptor/configurer definitions alone are insufficient: custom MVC configuration can omit applying those configurers. Collection distinguishes observed presence, confirmed absence and unknown registration coverage. Absence requires inspection of native applied MVC/resource/WebSocket/Actuator handler mappings and servlet filter registration metadata, including native Spring Security filter-chain proxies. Native empty interceptor lists and completed null-returning mapping factories are known empty; lazy mappings, custom handler mappings, custom registration subclasses/initializers and unresolved filter targets suppress absence claims. `spring.jpa.open-in-view=false` alone is never proof of absence. Independently observed registrations still produce the existing MEDIUM finding when other coverage is incomplete. Custom mapping limits are stated, WebFlux is inapplicable, and an open persistence context does not itself prove a held JDBC connection or N+1 queries.
- **Recommendation**: Review whether request-wide persistence access is intentional. Prefer explicit fetch boundaries (fetch joins, entity graphs, DTO projections) where appropriate; disable Boot OSIV with `spring.jpa.open-in-view=false` or adjust custom registrations separately.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.open-entity-manager-in-view>

### SPRING-DATA-001 - Avoid an in-memory database in production

- **Severity**: MEDIUM
- **Detects**: Supported datasource configuration provenance indicates an H2/HSQLDB/Derby memory subprotocol alongside production-like effective profile names. Configured default profiles apply when active profiles are absent. Naming is only a heuristic, and memory-looking query values in a durable URL are not memory databases. Unknown custom connection details are unevaluated; raw URLs are never shown.
- **Recommendation**: Verify durability requirements and the actual datasource. Memory databases can be intentional; use durable storage where data must survive process termination.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.datasource.embedded>

### SPRING-DATA-002 - Do not run production R2DBC on an in-memory database

- **Severity**: MEDIUM
- **Detects**: Supported R2DBC memory-driver configuration with attributable factory evidence and production-like effective profile names. A configured URL with no relevant runtime provenance is insufficient; custom connection details remain unknown and URL values are not exposed.
- **Recommendation**: Verify the application's durability requirements and actual connection factory rather than inferring them from profile names.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.r2dbc.embedded>

## Actuator and management

The access-aware rules follow Boot 4.1.1: endpoint settings override global defaults, then `max-permitted` caps access. Typed conversion accepts Boot-supported spellings such as `readonly` and legacy `enabled=on`. Empty values that bind to null inherit the next policy level; two non-null typed `access` and legacy `enabled` values at the same scope conflict rather than silently taking precedence. Both heapdump and shutdown default to access `none`. A valid empty include falls back to `health`; excludes win. Invalid binding is not silently treated as empty.

Host configuration is distinguished from BootUI's low-priority management defaults. Exposure/access observations do not establish authorization or public reachability; a separate management port is not itself an access control, and disabling endpoint discovery does not disable routes.

### SPRING-MGMT-001 - Avoid exposing all Actuator endpoints

- **Severity**: MEDIUM
- **Detects**: Host-configured wildcard web exposure with applicable endpoint evidence, respecting exclusion and access suppression. It is not proof every optional endpoint exists or that endpoints are public.
- **Recommendation**: Prefer an explicit needed endpoint list and review access controls separately. Profile names and port equality do not increase severity.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

### SPRING-MGMT-002 - Do not web-expose sensitive Actuator endpoints

- **Severity**: MEDIUM
- **Detects**: Explicit host inclusion of observed sensitive diagnostic endpoints with readable access. Wildcards are handled by SPRING-MGMT-001; shutdown/heapdump by SPRING-MGMT-004. Missing optional endpoints and BootUI-contributed exposure are not host findings.
- **Recommendation**: Review which diagnostics need web access and how that access is restricted. This rule does not assess endpoint authorization.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

### SPRING-MGMT-003 - Do not always show Actuator values or health details

- **Severity**: MEDIUM
- **Detects**: Host-configured `show-values=always` or health `show-details=always` on applicable readable endpoints. These settings govern disclosure to callers allowed to access the endpoint, not authorization itself; health details and raw configuration values are distinct.
- **Only host configuration**: BootUI contributes `management.endpoint.health.show-details=always` itself, as a lowest-priority default, so its own Health panel works. That contribution is ignored here, so the rule reports only what the application configured.
- **Recommendation**: Prefer `never` when details are unnecessary, or `when-authorized` with deliberate endpoint roles and authorization configuration. Review sanitization separately.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization>

### SPRING-MGMT-004 - Do not web-expose shutdown or heapdump endpoints

- **Severity**: HIGH
- **Detects**: An observed shutdown endpoint has effective write access and web exposure, or an observed heapdump endpoint has read access and web exposure. Both default to `none` in Boot 4.1.1, so include/wildcard exposure alone is not sufficient. A read-only cap allows heapdump reads but not shutdown writes.
- **Recommendation**: Keep unnecessary endpoint access at `none` and remove unnecessary exposure. Where deliberate, review endpoint authorization and network restrictions. No profile/port-based CRITICAL escalation or claim of unauthenticated access is made.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

## Reactive (WebFlux only)

Both rules in this category are `SKIPPED` unconditionally on a servlet (Spring MVC) application; they only evaluate when the advisor detects a WebFlux `ReactiveWebApplicationContext`.

### SPRING-REACTIVE-001 - Reactive endpoints alongside a blocking JDBC datasource

- **Severity**: INFO
- **Detects**: This is a WebFlux application with Mono/Flux-returning handler methods, and a blocking JDBC DataSource is also configured. A blocking JDBC call made directly inside a reactive chain (instead of offloaded to a bounded scheduler) can block request processing and reduce concurrent capacity. Modeled after the Quarkus advisor's QA-RX-001, but deliberately coarser: this reflection-only scanner cannot see inside a handler method's body, so it cannot tell whether offloading is already done correctly. It is an app-level prompt to verify, not a per-endpoint finding.
- **Recommendation**: Offload blocking database calls, for example with `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`, or migrate to a reactive driver such as R2DBC; verify this per endpoint.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webflux/reactive-spring.html>

### SPRING-REACTIVE-003 - Review explicitly unlimited codec aggregation

- **Severity**: LOW
- **Detects**: Positive Boot codec configuration evidence with `spring.http.codecs.max-in-memory-size=-1` requests unlimited aggregation. Defaults and positive limits are not findings; custom codec overrides with unknown effective behavior are unevaluated. The legacy namespace belongs to SPRING-CONFIG-003.
- **Recommendation**: Use a workload-appropriate bounded aggregation limit where possible. This is not a universal request-body, upload or streaming limit; no arbitrary larger buffer is prescribed. The new ID does not inherit dismissal of the retired opposite recommendation.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/reactive.html>

---

## Rule summary

The Spring Advisor ships **38 active rules** across eight categories: 37 retained/corrected rules and one new rule after the Boot 4.1.1 audit. Declared severities are **0 CRITICAL**, **1 HIGH**, **12 MEDIUM**, **8 LOW** and **17 INFO**. Profile names and management-port equality do not escalate severities.

| Category | Rules |
| --- | --- |
| Bean wiring | SPRING-WIRING-001 ... SPRING-WIRING-009 |
| Configuration | SPRING-CONFIG-001 ... SPRING-CONFIG-006 |
| Profiles and environment | SPRING-PROFILE-002, SPRING-PROFILE-003 |
| Performance and concurrency | SPRING-PERF-001 ... SPRING-PERF-003, SPRING-PERF-005, SPRING-PERF-006, SPRING-CACHE-001 |
| Web and HTTP | SPRING-WEB-001 ... SPRING-WEB-005, SPRING-WEB-007 |
| Data and persistence | SPRING-JPA-001, SPRING-DATA-001, SPRING-DATA-002 |
| Actuator and management | SPRING-MGMT-001 ... SPRING-MGMT-004 |
| Reactive (WebFlux only) | SPRING-REACTIVE-001, SPRING-REACTIVE-003 |

## Retired rule IDs

These IDs remain reserved. Existing dismissals are preserved without migration or reuse.

| ID | Previous subject | Reason for retirement |
| --- | --- | --- |
| SPRING-PROFILE-001 | Require an explicit active profile | Profiles are optional; configured default profiles and profile-free external configuration are valid. |
| SPRING-PERF-004 | Hikari default may bottleneck virtual threads | Property absence cannot establish an unreviewed/undersized pool. Explicitly writing the default 10 changes no runtime behavior; size against database capacity and observed load. |
| SPRING-WEB-006 | Forwarded headers from a production profile | A profile name cannot establish a trusted proxy. Changing header trust needs deployment evidence. |
| SPRING-REACTIVE-002 | Increase the default codec buffer | A safe bounded default is not a defect, and `spring.codec.*` is stale in Boot 4. The new unlimited-aggregation check addresses a different condition. |

## Audit sources and limitations

The audit targets **Spring Boot 4.1.1 / Spring Framework 7.0.9**. Version-pinned implementation is authoritative when current reference pages describe newer releases.

- [Boot's managed Framework version](https://github.com/spring-projects/spring-boot/blob/v4.1.1/gradle.properties), [Framework candidate resolution](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java), and [primary/fallback semantics](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired-primary.html).
- [Boot execution/scheduling guidance](https://github.com/spring-projects/spring-boot/blob/v4.1.1/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/features/task-execution-and-scheduling.adoc), [executor configuration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/task/TaskExecutorConfigurations.java), [Reactor schedulers](https://projectreactor.io/docs/core/release/reference/coreFeatures/schedulers.html), and [JEP 491](https://openjdk.org/jeps/491).
- [Application/lazy-initialization tradeoffs](https://github.com/spring-projects/spring-boot/blob/v4.1.1/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/features/spring-application.adoc), [JSON support](https://github.com/spring-projects/spring-boot/blob/v4.1.1/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/features/json.adoc), [client configuration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/io/rest-client.adoc), and [webserver guidance](https://github.com/spring-projects/spring-boot/blob/v4.1.1/documentation/spring-boot-docs/src/docs/antora/modules/how-to/pages/webserver.adoc).
- [Endpoint access resolver](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/PropertiesEndpointAccessResolver.java), [include/exclude filter](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/expose/IncludeExcludeEndpointFilter.java), [web endpoint defaults](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/web/WebEndpointAutoConfiguration.java), [heapdump](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/management/HeapDumpWebEndpoint.java), and [shutdown](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/context/ShutdownEndpoint.java).
- [Codec properties](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-codec/src/main/java/org/springframework/boot/http/codec/autoconfigure/HttpCodecsProperties.java), [Framework codec limits](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/codec/CodecConfigurer.java), [servlet OSIV conditions](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-jpa/src/main/java/org/springframework/boot/jpa/autoconfigure/JpaBaseConfiguration.java), [Hikari 7.0.2 defaults](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java), and [Hikari pool-sizing guidance](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing).

The scan does not execute arbitrary application code to discover every custom client, scheduler, datasource wrapper, endpoint supplier or security chain. It cannot infer workload demand, authorization or network reachability from property presence. INFO opportunities explain these limits rather than declaring every default suboptimal.
