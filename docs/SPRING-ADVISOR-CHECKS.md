# Spring Advisor checks

The Spring Advisor panel runs a fixed, on-demand ruleset against the host application's **running Spring application context** and `Environment`. It takes a read-only snapshot of selected bean groups (Jackson `ObjectMapper`s, `TaskExecutor`s, `DataSource`s) and feature flags, then evaluates a curated set of configuration and best-practice checks. It never mutates the context, intercepts live traffic, or surfaces secrets.

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

### SPRING-WIRING-003 - Avoid multiple ObjectMapper beans

- **Severity**: LOW
- **Detects**: Detects more than one Jackson ObjectMapper bean, which can lead to inconsistent JSON (de)serialization depending on which one is injected.
- **Recommendation**: Keep a single primary ObjectMapper (customise the auto-configured one via a Jackson2ObjectMapperBuilderCustomizer) or mark one bean @Primary.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/json.html>

### SPRING-WIRING-004 - Multiple TaskExecutor beans need a primary

- **Severity**: MEDIUM
- **Detects**: Detects more than one TaskExecutor bean without a @Primary, so @Async and other consumers may resolve an unexpected executor.
- **Recommendation**: Mark the intended executor @Primary, or qualify each injection point with the executor bean name.
- **Learn more**: <https://docs.spring.io/spring-framework/reference/integration/scheduling.html>

### SPRING-WIRING-005 - Multiple DataSource beans need a primary

- **Severity**: MEDIUM
- **Detects**: Detects more than one DataSource bean without a @Primary, which makes auto-configured consumers (JPA, JdbcTemplate) fail or pick an unexpected source.
- **Recommendation**: Mark the main DataSource @Primary and qualify any secondary DataSource explicitly.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/data/sql.html>

## Configuration

### SPRING-CONFIG-001 - Consider lazy initialization for large contexts

- **Severity**: LOW
- **Detects**: A large bean context is initialised eagerly. Lazy initialization can shorten startup for development, tests, and short-lived or serverless workloads.
- **Recommendation**: Evaluate spring.main.lazy-initialization=true, ideally combined with @Lazy(false) on beans that must still initialise eagerly (such as listeners and schedulers).
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/spring-application.html>

### SPRING-CONFIG-002 - Disable global debug or trace logging

- **Severity**: LOW
- **Detects**: Detects debug=true or trace=true, which switch on verbose auto-configuration logging and can leak internal details or slow down the application.
- **Recommendation**: Remove the debug/trace flags and configure logging levels per package instead.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/logging.html>

## Profiles and environment

### SPRING-PROFILE-001 - Run with an explicit active profile

- **Severity**: LOW
- **Detects**: No Spring profile is active, so any profile-specific configuration (such as application-prod.yml) is never applied.
- **Recommendation**: Set spring.profiles.active (for example via SPRING_PROFILES_ACTIVE) so the intended environment configuration takes effect.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/profiles.html>

### SPRING-PROFILE-002 - Spring Boot DevTools should not ship to production

- **Severity**: MEDIUM
- **Detects**: Spring Boot DevTools is on the classpath. It enables automatic restart, a remote debug tunnel, and relaxed caching that are unsafe in production.
- **Recommendation**: Scope spring-boot-devtools to development only (Maven <optional>true</optional> / Gradle developmentOnly) so it is excluded from production artifacts.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/using/devtools.html>

## Performance and concurrency

### SPRING-PERF-001 - Consider enabling virtual threads

- **Severity**: MEDIUM
- **Detects**: The JVM supports virtual threads (Java 21+) but spring.threads.virtual.enabled is not set. Blocking, request-per-thread workloads usually scale better with them.
- **Recommendation**: Set spring.threads.virtual.enabled=true and verify that blocking code paths do not hold synchronized monitors that would pin carrier threads.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>

### SPRING-PERF-002 - Pooled executor cancels virtual-thread benefits

- **Severity**: MEDIUM
- **Detects**: Virtual threads are enabled, but a platform-thread pool executor (ThreadPoolTaskExecutor) is also defined, so work routed through it still runs on a bounded pool.
- **Recommendation**: Remove the custom ThreadPoolTaskExecutor or replace it with a virtual-thread executor so asynchronous work actually benefits from virtual threads.
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

## Web and HTTP

### SPRING-WEB-001 - Enable HTTP response compression

- **Severity**: LOW
- **Detects**: HTTP response compression is not enabled (server.compression.enabled is not true), so text responses are sent uncompressed.
- **Recommendation**: Set server.compression.enabled=true (and tune mime-types / min-response-size) to reduce bandwidth for JSON, HTML, and other text payloads.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

### SPRING-WEB-002 - Enable graceful shutdown

- **Severity**: MEDIUM
- **Detects**: Graceful shutdown is not enabled (server.shutdown is not 'graceful'), so in-flight requests can be dropped when the application stops.
- **Recommendation**: Set server.shutdown=graceful and tune spring.lifecycle.timeout-per-shutdown-phase so active requests can complete during rollouts.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html>

### SPRING-WEB-003 - Consider enabling HTTP/2

- **Severity**: INFO
- **Detects**: HTTP/2 is not enabled (server.http2.enabled is not true). HTTP/2 multiplexing can improve latency for browsers and modern clients.
- **Recommendation**: Enable server.http2.enabled=true (over TLS) once the runtime and clients support it.
- **Learn more**: <https://docs.spring.io/spring-boot/reference/web/servlet.html>

