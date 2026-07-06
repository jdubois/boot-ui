# Spring Advisor checks

The Spring panel runs a fixed, on-demand ruleset against the host application's **running Spring application context** and `Environment`. It takes a read-only snapshot of selected bean groups (Jackson `ObjectMapper`s, `TaskExecutor`s, `DataSource`s) and feature flags, then evaluates a curated set of configuration and best-practice checks. It never mutates the context, intercepts live traffic, or surfaces secrets.

Because the advisor runs inside the *started* application, it focuses on "started but suboptimal" states: settings that let the app boot yet deviate from production best practices. Conditions that would prevent startup entirely are out of scope. The checks are heuristic review prompts; the right remediation still depends on the application's requirements and deployment topology.

This advisor is complementary to the **Architecture** panel: Architecture statically analyzes compiled bytecode with ArchUnit, whereas the Spring Advisor inspects the live, wired runtime context.

The same ruleset runs on both the servlet (Spring MVC) and reactive (Spring WebFlux) adapters; the advisor detects which one is running (the same reactive-context check the panel-availability code uses) and adjusts a handful of rules accordingly: SPRING-WEB-007 does not apply to WebFlux at all (no Tomcat thread pool exists to cap), SPRING-WEB-005 also treats a `WebClient` bean as an HTTP client needing timeouts, SPRING-PERF-001's virtual-threads guidance is worded for whichever stack is running, and four rules (SPRING-WEB-001, -003, -004, -006) link their "Learn more" reference at the reactive web docs instead of the servlet ones when running on WebFlux. Two additional rules, SPRING-REACTIVE-001 and SPRING-REACTIVE-002, only evaluate (are not `SKIPPED`) on a WebFlux application; see the [Reactive](#reactive-webflux-only) section.

## Availability and bounds

The panel is always available (a Spring application context always exists). Beans or properties that cannot be read are skipped gracefully and reported rather than failing the panel. The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id.

## Severity scale

- **CRITICAL** - an immediately dangerous exposure that should be fixed before production; currently emitted by SPRING-MGMT-004 when a write-accessible shutdown endpoint is exposed on the application port with a production-like profile.
- **HIGH** - a setting that commonly causes problems and usually needs attention before production.
- **MEDIUM** - a hardening or correctness gap that warrants review.
- **LOW** - lower-impact hygiene or optimization findings.
- **INFO** - informational prompts where the right fix depends heavily on project context.

---

## Bean wiring

### SPRING-WIRING-001 - Bean definition overriding should stay disabled

- **Severity**: MEDIUM
- **Detects**: Detects spring.main.allow-bean-definition-overriding=true, which lets a later bean definition silently replace an earlier one of the same name.
- **Recommendation**: Remove spring.main.allow-bean-definition-overriding (it defaults to false) and give conflicting beans distinct names so clashes fail fast at startup.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-WIRING-002 - Circular bean references should stay disabled

- **Severity**: MEDIUM
- **Detects**: Detects spring.main.allow-circular-references=true, which re-enables the legacy behaviour of resolving circular bean dependencies instead of failing.
- **Recommendation**: Remove spring.main.allow-circular-references and break the cycle, for example by introducing an intermediary bean or using setter/@Lazy injection deliberately.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-WIRING-003 - Avoid multiple JSON mapper beans

- **Severity**: LOW
- **Detects**: Detects more than one Jackson JSON mapper bean (Jackson 2 ObjectMapper or the Jackson 3 JsonMapper that Spring Boot 4 auto-configures) with none marked @Primary, which can lead to inconsistent JSON (de)serialization depending on which one is injected.
- **Recommendation**: Keep a single primary JSON mapper. With Jackson 3 (the Spring Boot 4 default) customize the auto-configured mapper via a JsonMapperBuilderCustomizer, or mark one bean @Primary.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/json.html>

### SPRING-WIRING-004 - Multiple TaskExecutor beans need a primary

- **Severity**: MEDIUM
- **Detects**: Detects more than one TaskExecutor bean without a @Primary, so @Async and other consumers may resolve an unexpected executor. A bean conventionally named applicationTaskExecutor/taskExecutor, or an AsyncConfigurer, resolves the ambiguity and suppresses this check.
- **Recommendation**: Mark the intended executor @Primary, name it applicationTaskExecutor, implement AsyncConfigurer, or qualify each injection point with the executor bean name.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/integration/scheduling.html>

### SPRING-WIRING-005 - Multiple DataSource beans need a primary

- **Severity**: MEDIUM
- **Detects**: Detects more than one DataSource bean without a @Primary, which makes auto-configured consumers (JPA, JdbcTemplate) fail or pick an unexpected source.
- **Recommendation**: Mark the main DataSource @Primary and qualify any secondary DataSource explicitly.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html>

### SPRING-WIRING-006 - Multiple transaction managers need a primary

- **Severity**: MEDIUM
- **Detects**: Detects more than one PlatformTransactionManager bean without a @Primary, so @Transactional methods may bind to an unexpected manager. A bean named transactionManager or a TransactionManagementConfigurer resolves the default and suppresses this check.
- **Recommendation**: Mark the main transaction manager @Primary, name it transactionManager, implement TransactionManagementConfigurer, or set `@Transactional("<name>")` on each usage.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/data-access/transaction.html>

### SPRING-WIRING-007 - Prefer RestClient over RestTemplate

- **Severity**: LOW
- **Detects**: A RestTemplate bean is defined. RestTemplate is in maintenance mode; Spring Boot 4 favors the fluent, modern RestClient for synchronous HTTP access.
- **Recommendation**: Migrate RestTemplate usage to RestClient (RestClient.create() or an injected RestClient.Builder). Keep RestTemplate only where a dependency still requires it.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/integration/rest-clients.html>

### SPRING-WIRING-008 - Avoid components in the default package

- **Severity**: MEDIUM
- **Detects**: Detects application beans whose class lives in the default (unnamed) package. A class there forces component scanning to scan the entire classpath, slows startup, and breaks several Spring features. This inspects the live bean registry, so it also catches an unannotated class wired up via an `@Bean` factory method - a case the Architecture panel's ARCH-SPRING-005 (a static ArchUnit scan restricted to classes directly annotated with a Spring stereotype) cannot see, since the plain class carries no annotation for ArchUnit to match. The two checks are kept intentionally separate for this reason; see the Architecture panel for the complementary static-scan finding.
- **Recommendation**: Move these classes into a named package (for example com.example.app) so component scanning is bounded to your application's packages.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html>

### SPRING-WIRING-009 - Avoid mutable public fields on singleton beans

- **Severity**: MEDIUM
- **Detects**: Detects a public, non-final, non-static field on a singleton-scoped application bean that is not an injection point (not annotated @Autowired, @Value, @Qualifier, @Inject, @Resource, @PersistenceContext, or @PersistenceUnit). Because the default bean scope is a shared singleton, such a field is effectively unsynchronized mutable global state: any caller can reassign it, and concurrent requests reading and writing it race without a memory barrier.
- **Recommendation**: Make the field private and expose an accessor if needed, mark it final and set it only from the constructor, or move truly per-request/per-call state out of the singleton (a method-local variable, a request-scoped bean, or immutable value types).
- **Learn more**: <https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html#beans-factory-scopes-singleton>

## Configuration

### SPRING-CONFIG-001 - Consider lazy initialization for large contexts

- **Severity**: INFO
- **Detects**: A large bean context is initialized eagerly. Lazy initialization can shorten startup for development, tests, and short-lived or serverless workloads.
- **Recommendation**: Evaluate spring.main.lazy-initialization=true, weighing the trade-offs: wiring errors surface on first use instead of at startup, the first request to each bean pays an initialization cost, and it interacts with AOT/native processing. Keep beans that must start eagerly (listeners, schedulers) annotated @Lazy(false).
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-CONFIG-002 - Disable global debug or trace logging

- **Severity**: LOW (MEDIUM when a production-like profile is active)
- **Detects**: Detects debug=true or trace=true, or broad root/web/sql/org.springframework/org.hibernate loggers set to DEBUG or TRACE, which enable verbose framework logging that can leak internal details or slow down the application.
- **Recommendation**: Remove the debug/trace flags and configure logging levels only for narrow packages that need them.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/logging.html>

### SPRING-CONFIG-003 - Remove renamed or deleted Spring Boot 4 properties

- **Severity**: MEDIUM
- **Detects**: Detects configuration keys that were renamed or removed in Spring Boot 4 and therefore no longer take effect, which can silently change behaviour after an upgrade. The curated set (individually source-verified against Spring Boot 4.1.0, 41 keys) covers: common server.undertow.* keys (Undertow was removed entirely); server.error.* (renamed to spring.web.error.*); server.servlet.encoding.* except .mapping, which still works (renamed to spring.servlet.encoding.*); spring.http.client.* (renamed to spring.http.clients.*, with .factory moving to .imperative.factory); spring.data.mongodb.* connection keys such as .host/.uri/.username (renamed to spring.mongodb.*; unrelated Spring Data settings like .gridfs.* and .auto-index-creation stay under spring.data.mongodb and are not flagged); management.tracing.enabled (renamed to management.tracing.export.enabled); and spring.session.redis.* (renamed to spring.session.data.redis.*). spring.dao.exceptiontranslation.enabled is deliberately **not** included: despite Spring Boot's own deprecation metadata suggesting a rename to spring.persistence.exceptiontranslation.enabled, DataSourceTransactionManagerAutoConfiguration still reads the old key directly and it remains fully functional - the "replacement" is an unrelated property gating JPA repository exception translation.
- **Recommendation**: Update each key to its Spring Boot 4 equivalent (the spring-boot-properties-migrator module lists the replacements at startup) and remove keys for dropped features.
- **Learn more**: <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide>

### SPRING-CONFIG-004 - Set spring.application.name

- **Severity**: INFO
- **Detects**: spring.application.name is not set. The application name labels logs, metrics, tracing, and service discovery, and several integrations fall back to anonymous defaults without it.
- **Recommendation**: Set spring.application.name to a stable identifier for this service.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-CONFIG-005 - Do not ignore missing config files

- **Severity**: MEDIUM
- **Detects**: spring.config.on-not-found=ignore makes Spring silently skip imported configuration files that are missing, so a typo or a misplaced file can ship without any error.
- **Recommendation**: Remove spring.config.on-not-found=ignore (the default fails fast) and use the optional: prefix only on the specific imports that are genuinely optional.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/external-config.html>

## Profiles and environment

### SPRING-PROFILE-001 - Run with an explicit active profile

- **Severity**: INFO
- **Detects**: No Spring profile is active beyond the default, so any profile-specific configuration (such as application-prod.yml) is never applied.
- **Recommendation**: Set spring.profiles.active (for example via SPRING_PROFILES_ACTIVE) so the intended environment configuration takes effect.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/profiles.html>

### SPRING-PROFILE-002 - Spring Boot DevTools should be scoped to development

- **Severity**: MEDIUM
- **Detects**: Spring Boot DevTools is on the classpath. It enables automatic restart, a live-reload server, and relaxed caching. DevTools disables itself in a fully packaged jar, but it is still active here and must never be bundled into a production artifact.
- **Recommendation**: Scope spring-boot-devtools to development only (Maven `<optional>true</optional>` / Gradle developmentOnly) so it is excluded from production builds.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/using/devtools.html>

### SPRING-PROFILE-003 - Keep profile-name validation enabled

- **Severity**: LOW
- **Detects**: spring.profiles.validate=false disables Spring Boot's check that profile names are sensible, so a malformed or unexpected profile name no longer fails fast.
- **Recommendation**: Remove spring.profiles.validate=false (validation is on by default) and fix any profile names that do not satisfy the naming rules.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/profiles.html>

## Performance and concurrency

### SPRING-PERF-001 - Consider enabling virtual threads

- **Severity**: INFO
- **Detects**: The JVM supports virtual threads (Java 21+) but spring.threads.virtual.enabled is not set. Blocking workloads - request-per-thread web handlers, and blocking @Async/@Scheduled work on either the servlet or WebFlux stack - can often scale further on virtual threads - an opportunity to evaluate, not a defect. On a WebFlux application, the finding message clarifies that the benefit applies only to blocking work still offloaded to a thread pool (@Async, @Scheduled, or `Schedulers.boundedElastic()`), since the reactive HTTP path itself already runs non-blocking on Reactor Netty's event loop.
- **Recommendation**: Consider spring.threads.virtual.enabled=true after verifying that blocking code paths do not hold synchronized monitors that would pin carrier threads.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-002 - Pooled executor cancels virtual-thread benefits

- **Severity**: MEDIUM
- **Detects**: Virtual threads are enabled, but a platform-thread pool executor (ThreadPoolTaskExecutor) is also defined, so work routed through it still runs on a bounded pool. A bounded pool can be intentional (for example to throttle a downstream system), so confirm whether this executor should keep using platform threads.
- **Recommendation**: If the pooling is not deliberate, remove the custom ThreadPoolTaskExecutor or replace it with a virtual-thread executor so asynchronous work benefits from virtual threads.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-003 - @Async should use a reviewed executor

- **Severity**: MEDIUM
- **Detects**: @EnableAsync is active and virtual threads are not enabled, and either (a) no TaskExecutor bean exists at all - rare, since Spring Boot auto-configures one, but when it happens @Async falls back to the unbounded SimpleAsyncTaskExecutor (a new platform thread per task) - or (b) the only TaskExecutor is Spring Boot's auto-configured `applicationTaskExecutor` left at its default settings (a bounded ThreadPoolTaskExecutor with a core pool size of 8 and an effectively unbounded queue) with neither spring.task.execution.pool.core-size nor .max-size customized, so a generic default - not a size chosen for this application's @Async workload - backs every @Async method unreviewed.
- **Recommendation**: Define a dedicated executor sized for the workload, or explicitly review and set spring.task.execution.pool.core-size / max-size instead of relying on the unreviewed default, or enable spring.threads.virtual.enabled so @Async work is not pooled at all.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-004 - Connection pool may bottleneck virtual threads

- **Severity**: LOW
- **Detects**: Virtual threads are enabled while the HikariCP connection pool keeps a small (default) maximum size, so many virtual threads can contend for few database connections.
- **Recommendation**: Review spring.datasource.hikari.maximum-pool-size against the expected concurrency, and size it for the database rather than the (now cheap) thread count.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html>

### SPRING-PERF-005 - Scheduler runs on a single thread

- **Severity**: INFO
- **Detects**: @EnableScheduling is active, virtual threads are not enabled, and the scheduling pool size is at its default of one thread (spring.task.scheduling.pool.size), so a long-running or overlapping @Scheduled task can delay every other scheduled task.
- **Recommendation**: Increase spring.task.scheduling.pool.size to match the number of concurrent scheduled tasks, or enable virtual threads (spring.threads.virtual.enabled=true).
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-006 - Bound the @Async executor queue

- **Severity**: LOW
- **Detects**: @EnableAsync is active and spring.task.execution.pool.queue-capacity is not set, so the auto-configured task executor uses an effectively unbounded queue that can hide a backlog and grow heap usage under load.
- **Recommendation**: Set spring.task.execution.pool.queue-capacity (and a matching max pool size) to a bounded value, or enable virtual threads so async work is not pooled.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-CACHE-001 - Use a real cache provider in production

- **Severity**: LOW
- **Detects**: Caching is enabled (@EnableCaching) but every CacheManager is an in-memory development default (ConcurrentMapCacheManager or NoOpCacheManager), which never evicts, has no TTL, and is not shared across instances.
- **Recommendation**: Configure a production cache provider (Caffeine, Redis, Hazelcast, ...) with eviction and TTL so cached data is bounded and consistent across instances.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/io/caching.html>

## Web and HTTP

### SPRING-WEB-001 - Enable HTTP response compression

- **Severity**: LOW
- **Detects**: HTTP response compression is not enabled (server.compression.enabled is not true), so text responses are sent uncompressed. This may be intentional when a reverse proxy, load balancer, or CDN already compresses responses at the edge.
- **Recommendation**: If nothing upstream compresses responses, set server.compression.enabled=true (and tune mime-types / min-response-size) to reduce bandwidth for JSON, HTML, and other text.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-002 - Keep graceful shutdown enabled

- **Severity**: MEDIUM
- **Detects**: Detects server.shutdown=immediate, which overrides the Spring Boot 4 default of graceful shutdown, so in-flight requests can be dropped when the application stops. Also detects graceful shutdown left nominally enabled but with spring.lifecycle.timeout-per-shutdown-phase set to a zero duration (0, 0s, 0ms, PT0S, ...), which grants no grace period and so drops in-flight requests just like the immediate mode.
- **Recommendation**: Remove server.shutdown=immediate (Spring Boot 4 defaults to graceful) and set spring.lifecycle.timeout-per-shutdown-phase to a positive duration (the default is 30s) so active requests can complete during rollouts.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html>

### SPRING-WEB-003 - Consider enabling HTTP/2

- **Severity**: INFO
- **Detects**: HTTP/2 is not enabled (server.http2.enabled is not true). HTTP/2 multiplexing can improve latency for browsers and modern clients. A reverse proxy or load balancer often terminates HTTP/2 at the edge, in which case enabling it on the app is unnecessary.
- **Recommendation**: If no edge proxy already serves HTTP/2, enable server.http2.enabled=true (over TLS) once the runtime and clients support it.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-004 - Do not always expose error details

- **Severity**: MEDIUM
- **Detects**: A spring.web.error.* property is set to 'always', or include-exception is true, so stack traces, exception messages, binding errors, or exception types are returned in error responses to every client - a common way to leak internal implementation details. The legacy server.error.* prefix (renamed in Spring Boot 4) is intentionally not checked here to avoid double-reporting the same misconfiguration as SPRING-CONFIG-003, which already flags the stale server.error.* keys themselves.
- **Recommendation**: Use 'never' (or 'on-param') for include-stacktrace / include-message / include-binding-errors, and leave include-exception false, so details are not exposed to arbitrary callers.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-005 - Set HTTP client timeouts

- **Severity**: INFO
- **Detects**: A RestClient, WebClient, or RestTemplate bean is defined but one or both global HTTP client timeouts are unset (spring.http.clients.connect-timeout / read-timeout - the same property namespace configures both imperative clients and the reactive WebClient in Spring Boot 4), so a slow or unresponsive dependency can block indefinitely.
- **Recommendation**: Set spring.http.clients.connect-timeout and spring.http.clients.read-timeout (or configure timeouts per client) so outbound calls fail fast.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/io/rest-client.html>

### SPRING-WEB-006 - Configure forwarded-headers handling behind a proxy

- **Severity**: INFO
- **Detects**: A production-like profile is active but server.forward-headers-strategy is not set. Behind a reverse proxy or load balancer, the app may then build URLs and read client IPs from the proxy hop instead of the original request.
- **Recommendation**: If the application runs behind a proxy, set server.forward-headers-strategy=framework (or native when the container handles it) so X-Forwarded-* headers are honoured.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-007 - Tomcat thread cap is redundant with virtual threads

- **Severity**: LOW
- **Detects**: Virtual threads are enabled but server.tomcat.threads.max is set explicitly. With virtual threads handling requests, a small platform-thread cap can needlessly limit concurrency, while a large one has little effect. On a WebFlux application this rule is always `SKIPPED`: WebFlux runs on Reactor Netty's event-loop group, not a Tomcat servlet thread pool, so server.tomcat.threads.max has no effect there at all.
- **Recommendation**: Remove server.tomcat.threads.max when running on virtual threads, or confirm the cap is a deliberate back-pressure limit.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

## Data and persistence

### SPRING-JPA-001 - Disable Open Session in View

- **Severity**: MEDIUM (HIGH when a production-like profile is active)
- **Detects**: Detects a servlet JPA application where spring.jpa.open-in-view is not set (and therefore defaults to enabled) or is explicitly true. Open Session in View keeps a JPA persistence context (and often its database connection) open for the whole web request, hides lazy-loading boundaries, encourages N+1 queries, and holds connections longer under load. The Hibernate panel's HIB-CONFIG-001 checks the same property and mirrors the same production-profile severity escalation, but cannot reproduce this rule's servlet/EntityManagerFactory skip-guard (a framework-neutral engine has no concept of "is this a servlet web application"); both are kept so each panel reports the finding for its own domain without contradicting the other.
- **Recommendation**: Set spring.jpa.open-in-view=false and load the associations each request needs explicitly (fetch joins, entity graphs, or DTO projections).
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.open-entity-manager-in-view>

### SPRING-DATA-001 - Do not run production on an in-memory database

- **Severity**: MEDIUM
- **Detects**: A production-like profile is active while spring.datasource.url points at an in-process, in-memory database (an H2 jdbc:h2:mem: or HSQLDB jdbc:hsqldb:mem: URL, or a Derby jdbc:derby:memory: URL). These engines keep their data only in the JVM heap of a single instance: a restart, redeploy, or crash silently discards everything, and the data is invisible to any other instance in a multi-instance deployment. A file-backed embedded URL (for example jdbc:h2:file:) is not flagged.
- **Recommendation**: Point spring.datasource.url at a durable, shared database server (PostgreSQL, MySQL, SQL Server, ...) for any production-like profile. Reserve the in-memory engines for tests and local development.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.datasource.embedded>

## Actuator and management

### SPRING-MGMT-001 - Avoid exposing all Actuator endpoints

- **Severity**: MEDIUM (HIGH when a production-like profile is active and management endpoints share the application port)
- **Detects**: management.endpoints.web.exposure.include is set to '*' (unless web exposure is disabled or excluded with '*'), which exposes every non-excluded Actuator endpoint (including sensitive ones such as env, configprops, and loggers) over the web. This is convenient in development but rarely intended in production.
- **Recommendation**: List only the endpoints you need (for example health,info,metrics) instead of '*', and use management.endpoints.web.exposure.exclude to trim further. Endpoint authorization is handled separately by the Security advisor.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

### SPRING-MGMT-002 - Do not web-expose sensitive Actuator endpoints

- **Severity**: MEDIUM (HIGH when a production-like profile is active and management endpoints share the application port)
- **Detects**: Sensitive Actuator endpoints (env, configprops, beans, threaddump, loggers, httpexchanges, startup, or mappings) are explicitly listed in management.endpoints.web.exposure.include and remain readable. Wildcard exposure is handled by SPRING-MGMT-001, and shutdown/heapdump are handled by SPRING-MGMT-004.
- **Recommendation**: Expose only health and info publicly; keep diagnostic endpoints off the web exposure list, move them to a separate, firewalled management port, and require authentication. The Security advisor covers endpoint authorization.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

### SPRING-MGMT-003 - Do not always show Actuator values or health details

- **Severity**: MEDIUM
- **Detects**: management.endpoint.env.show-values=ALWAYS or management.endpoint.configprops.show-values=ALWAYS on readable endpoints, or management.endpoint.health.show-details=always while health is readable. Property values, including credentials, and internal health probe details are then returned to every caller.
- **Recommendation**: Use show-values=WHEN_AUTHORIZED and show-details=when-authorized so sensitive values and health details are only revealed to authenticated, authorized users.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization>

### SPRING-MGMT-004 - Do not web-expose shutdown or heapdump endpoints

- **Severity**: HIGH (CRITICAL when shutdown is write-accessible with a production-like profile and management endpoints share the application port)
- **Detects**: The shutdown endpoint is web-exposed with write access, or heapdump is web-exposed and readable. Shutdown permits a remote caller to stop the application, and heapdump streams a full heap dump that can contain credentials, tokens, and personal data.
- **Recommendation**: Keep shutdown disabled (its default access is 'none') and never web-expose it; exclude heapdump from the web exposure list or restrict it to an authenticated, firewalled management port.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

## Reactive (WebFlux only)

Both rules in this category are `SKIPPED` unconditionally on a servlet (Spring MVC) application; they only evaluate when the advisor detects a WebFlux `ReactiveWebApplicationContext`.

### SPRING-REACTIVE-001 - Reactive endpoints alongside a blocking JDBC datasource

- **Severity**: INFO
- **Detects**: This is a WebFlux application with Mono/Flux-returning handler methods, and a blocking JDBC DataSource is also configured. WebFlux runs on a small, fixed Reactor Netty event-loop group; a blocking JDBC call made directly inside a reactive chain (instead of offloaded to a bounded scheduler) stalls that event loop and can starve every other in-flight request. Modeled after the Quarkus advisor's QA-RX-001, but deliberately coarser: this reflection-only scanner cannot see inside a handler method's body, so it cannot tell whether offloading is already done correctly. It is an app-level prompt to verify, not a per-endpoint finding.
- **Recommendation**: Offload blocking database calls, for example with `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`, or migrate to a reactive driver such as R2DBC; verify this per endpoint.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/web/webflux/reactive-spring.html>

### SPRING-REACTIVE-002 - Set a WebFlux codec in-memory buffer limit

- **Severity**: LOW
- **Detects**: This is a WebFlux application and spring.codec.max-in-memory-size is not set, so request and response body encoding/decoding falls back to the default 256KB in-memory buffer limit. A request or response body larger than that throws DataBufferLimitException.
- **Recommendation**: If the application sends or receives payloads larger than 256KB (large JSON bodies, file uploads, multipart forms), set spring.codec.max-in-memory-size explicitly rather than relying on the low default.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/reactive.html>

---

## Rule summary

The Spring Advisor ships **39 curated rules** across eight categories. By declared default severity: **0 CRITICAL**, **1 HIGH**, **19 MEDIUM**, **10 LOW**, **9 INFO**. Context-aware rules can raise SPRING-JPA-001, SPRING-MGMT-001, and SPRING-MGMT-002 to HIGH, and SPRING-MGMT-004 to CRITICAL; SPRING-CONFIG-002 can raise from LOW to MEDIUM.

| Category | Rules |
| --- | --- |
| Bean wiring | SPRING-WIRING-001 ... SPRING-WIRING-009 |
| Configuration | SPRING-CONFIG-001 ... SPRING-CONFIG-005 |
| Profiles and environment | SPRING-PROFILE-001 ... SPRING-PROFILE-003 |
| Performance and concurrency | SPRING-PERF-001 ... SPRING-PERF-006, SPRING-CACHE-001 |
| Web and HTTP | SPRING-WEB-001 ... SPRING-WEB-007 |
| Data and persistence | SPRING-JPA-001, SPRING-DATA-001 |
| Actuator and management | SPRING-MGMT-001 ... SPRING-MGMT-004 |
| Reactive (WebFlux only) | SPRING-REACTIVE-001, SPRING-REACTIVE-002 |
