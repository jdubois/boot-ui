# Spring Advisor checks

The Spring panel runs a fixed, on-demand ruleset against the host application's **running Spring application context** and `Environment`. It takes a read-only snapshot of selected bean groups (Jackson `ObjectMapper`s, `TaskExecutor`s, `DataSource`s) and feature flags, then evaluates a curated set of configuration and best-practice checks. It never mutates the context, intercepts live traffic, or surfaces secrets.

Because the advisor runs inside the *started* application, it focuses on "started but suboptimal" states: settings that let the app boot yet deviate from production best practices. Conditions that would prevent startup entirely are out of scope. The checks are heuristic review prompts; the right remediation still depends on the application's requirements and deployment topology.

This advisor is complementary to the **Architecture** panel: Architecture statically analyses compiled bytecode with ArchUnit, whereas the Spring Advisor inspects the live, wired runtime context.

## Availability and bounds

The panel is always available (a Spring application context always exists). Beans or properties that cannot be read are skipped gracefully and reported rather than failing the panel. The Rule results panel lists only checks that found findings, ordered by severity, finding count, and rule id.

## Severity scale

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
- **Recommendation**: Keep a single primary JSON mapper. With Jackson 3 (the Spring Boot 4 default) customise the auto-configured mapper via a JsonMapperBuilderCustomizer, or mark one bean @Primary.
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
- **Detects**: A RestTemplate bean is defined. RestTemplate is in maintenance mode; Spring Boot 4 favours the fluent, modern RestClient for synchronous HTTP access.
- **Recommendation**: Migrate RestTemplate usage to RestClient (RestClient.create() or an injected RestClient.Builder). Keep RestTemplate only where a dependency still requires it.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/integration/rest-clients.html>

### SPRING-WIRING-008 - Avoid components in the default package

- **Severity**: MEDIUM
- **Detects**: Detects application beans whose class lives in the default (unnamed) package. A class there forces component scanning to scan the entire classpath, slows startup, and breaks several Spring features.
- **Recommendation**: Move these classes into a named package (for example com.example.app) so component scanning is bounded to your application's packages.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html>

## Configuration

### SPRING-CONFIG-001 - Consider lazy initialization for large contexts

- **Severity**: INFO
- **Detects**: A large bean context is initialised eagerly. Lazy initialization can shorten startup for development, tests, and short-lived or serverless workloads.
- **Recommendation**: Evaluate spring.main.lazy-initialization=true, weighing the trade-offs: wiring errors surface on first use instead of at startup, the first request to each bean pays an initialization cost, and it interacts with AOT/native processing. Keep beans that must start eagerly (listeners, schedulers) annotated @Lazy(false).
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-CONFIG-002 - Disable global debug or trace logging

- **Severity**: LOW
- **Detects**: Detects debug=true or trace=true, which switch on verbose auto-configuration logging and can leak internal details or slow down the application.
- **Recommendation**: Remove the debug/trace flags and configure logging levels per package instead.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/logging.html>

### SPRING-CONFIG-003 - Remove renamed or deleted Spring Boot 4 properties

- **Severity**: MEDIUM
- **Detects**: Detects configuration keys that were renamed or removed in Spring Boot 4 and therefore no longer take effect, which can silently change behaviour after an upgrade. The current curated set covers management.tracing.enabled (renamed to management.tracing.export.enabled), spring.dao.exceptiontranslation.enabled (renamed to spring.persistence.exceptiontranslation.enabled), and common server.undertow.* keys (Undertow was removed in Spring Boot 4).
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
- **Detects**: The JVM supports virtual threads (Java 21+) but spring.threads.virtual.enabled is not set. Blocking, request-per-thread workloads can often scale further on virtual threads - an opportunity to evaluate, not a defect.
- **Recommendation**: Consider spring.threads.virtual.enabled=true after verifying that blocking code paths do not hold synchronized monitors that would pin carrier threads.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-002 - Pooled executor cancels virtual-thread benefits

- **Severity**: MEDIUM
- **Detects**: Virtual threads are enabled, but a platform-thread pool executor (ThreadPoolTaskExecutor) is also defined, so work routed through it still runs on a bounded pool. A bounded pool can be intentional (for example to throttle a downstream system), so confirm whether this executor should keep using platform threads.
- **Recommendation**: If the pooling is not deliberate, remove the custom ThreadPoolTaskExecutor or replace it with a virtual-thread executor so asynchronous work benefits from virtual threads.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-003 - @Async should use an explicit executor

- **Severity**: MEDIUM
- **Detects**: @EnableAsync is active but no TaskExecutor bean is defined. Without virtual threads, @Async falls back to an unbounded SimpleAsyncTaskExecutor that creates a new thread per task.
- **Recommendation**: Define a dedicated executor (or enable spring.threads.virtual.enabled) so asynchronous work runs on a bounded, monitored thread source.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/integration/scheduling.html>

### SPRING-PERF-004 - Connection pool may bottleneck virtual threads

- **Severity**: LOW
- **Detects**: Virtual threads are enabled while the HikariCP connection pool keeps a small (default) maximum size, so many virtual threads can contend for few database connections.
- **Recommendation**: Review spring.datasource.hikari.maximum-pool-size against the expected concurrency, and size it for the database rather than the (now cheap) thread count.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html>

### SPRING-PERF-005 - Scheduler runs on a single thread

- **Severity**: INFO
- **Detects**: @EnableScheduling is active but the scheduling pool size is at its default of one thread (spring.task.scheduling.pool.size), so a long-running or overlapping @Scheduled task can delay every other scheduled task.
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
- **Detects**: Detects server.shutdown=immediate, which overrides the Spring Boot 4 default of graceful shutdown, so in-flight requests can be dropped when the application stops.
- **Recommendation**: Remove server.shutdown=immediate (Spring Boot 4 defaults to graceful) and tune spring.lifecycle.timeout-per-shutdown-phase so active requests can complete during rollouts.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html>

### SPRING-WEB-003 - Consider enabling HTTP/2

- **Severity**: INFO
- **Detects**: HTTP/2 is not enabled (server.http2.enabled is not true). HTTP/2 multiplexing can improve latency for browsers and modern clients. A reverse proxy or load balancer often terminates HTTP/2 at the edge, in which case enabling it on the app is unnecessary.
- **Recommendation**: If no edge proxy already serves HTTP/2, enable server.http2.enabled=true (over TLS) once the runtime and clients support it.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-004 - Do not always expose error details

- **Severity**: MEDIUM
- **Detects**: An error-detail property is set to 'always', so stack traces, exception messages, or binding errors are returned in error responses to every client - a common way to leak internal implementation details. Both spring.web.error.* (Spring Boot 4) and the legacy server.error.* prefixes are checked.
- **Recommendation**: Use 'never' (or 'on-param') for include-stacktrace / include-message / include-binding-errors under spring.web.error.* so details are not exposed to arbitrary callers.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-005 - Set HTTP client timeouts

- **Severity**: INFO
- **Detects**: A RestClient or RestTemplate bean is defined but no global HTTP client timeouts are set (spring.http.clients.connect-timeout / read-timeout), so a slow or unresponsive dependency can block threads indefinitely.
- **Recommendation**: Set spring.http.clients.connect-timeout and spring.http.clients.read-timeout (or configure timeouts per client) so outbound calls fail fast.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/io/rest-client.html>

### SPRING-WEB-006 - Configure forwarded-headers handling behind a proxy

- **Severity**: INFO
- **Detects**: A production-like profile is active but server.forward-headers-strategy is not set. Behind a reverse proxy or load balancer, the app may then build URLs and read client IPs from the proxy hop instead of the original request.
- **Recommendation**: If the application runs behind a proxy, set server.forward-headers-strategy=framework (or native when the container handles it) so X-Forwarded-* headers are honoured.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-007 - Tomcat thread cap is redundant with virtual threads

- **Severity**: LOW
- **Detects**: Virtual threads are enabled but server.tomcat.threads.max is set explicitly. With virtual threads handling requests, a small platform-thread cap can needlessly limit concurrency, while a large one has little effect.
- **Recommendation**: Remove server.tomcat.threads.max when running on virtual threads, or confirm the cap is a deliberate back-pressure limit.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

## Actuator and management

### SPRING-MGMT-001 - Avoid exposing all Actuator endpoints

- **Severity**: LOW
- **Detects**: management.endpoints.web.exposure.include is set to '*', which exposes every Actuator endpoint (including sensitive ones such as env, configprops, and loggers) over the web. This is convenient in development but rarely intended in production.
- **Recommendation**: List only the endpoints you need (for example health,info,metrics) instead of '*', and use management.endpoints.web.exposure.exclude to trim further. Endpoint authorization is handled separately by the Security advisor.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>

---

## Rule summary

The Spring Advisor ships **31 curated rules** across six categories. By severity: **0 HIGH**, **13 MEDIUM**, **10 LOW**, **8 INFO**.

| Category | Rules |
| --- | --- |
| Bean wiring | SPRING-WIRING-001 ... SPRING-WIRING-008 |
| Configuration | SPRING-CONFIG-001 ... SPRING-CONFIG-005 |
| Profiles and environment | SPRING-PROFILE-001 ... SPRING-PROFILE-003 |
| Performance and concurrency | SPRING-PERF-001 ... SPRING-PERF-006, SPRING-CACHE-001 |
| Web and HTTP | SPRING-WEB-001 ... SPRING-WEB-007 |
| Actuator and management | SPRING-MGMT-001 |
