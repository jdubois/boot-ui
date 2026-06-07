package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import java.util.List;

abstract class AbstractSpringRule implements SpringRule {

    private final SpringRuleDefinition definition;

    AbstractSpringRule(SpringRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final SpringRuleDefinition definition() {
        return definition;
    }

    abstract SpringRuleResultDto evaluateRule(SpringContext context);

    @Override
    public final SpringRuleResultDto evaluate(SpringContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return SpringRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    SpringRuleResultDto pass() {
        return SpringRuleSupport.pass(definition);
    }

    SpringRuleResultDto skipped(String reason) {
        return SpringRuleSupport.skipped(definition, reason);
    }

    SpringRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : SpringRuleSupport.violation(definition, details);
    }

    SpringRuleResultDto violation(String detail) {
        return SpringRuleSupport.violation(definition, List.of(detail));
    }

    static String names(List<BeanRef> refs) {
        return refs.stream().map(BeanRef::name).reduce((a, b) -> a + ", " + b).orElse("");
    }
}

// ---------------------------------------------------------------------------
// Bean wiring
// ---------------------------------------------------------------------------

final class BeanDefinitionOverridingRule extends AbstractSpringRule {

    BeanDefinitionOverridingRule() {
        super(new SpringRuleDefinition(
                "SPRING-WIRING-001",
                "Bean definition overriding should stay disabled",
                SpringCategory.BEAN_WIRING,
                "MEDIUM",
                "Detects spring.main.allow-bean-definition-overriding=true, which lets a later bean"
                        + " definition silently replace an earlier one of the same name.",
                "Remove spring.main.allow-bean-definition-overriding (it defaults to false) and give"
                        + " conflicting beans distinct names so clashes fail fast at startup.",
                "https://docs.spring.io/spring-boot/reference/features/spring-application.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.isPropertyTrue("spring.main.allow-bean-definition-overriding")) {
            return violation("spring.main.allow-bean-definition-overriding=true allows duplicate bean"
                    + " definitions to override each other silently.");
        }
        return pass();
    }
}

final class CircularReferencesAllowedRule extends AbstractSpringRule {

    CircularReferencesAllowedRule() {
        super(new SpringRuleDefinition(
                "SPRING-WIRING-002",
                "Circular bean references should stay disabled",
                SpringCategory.BEAN_WIRING,
                "MEDIUM",
                "Detects spring.main.allow-circular-references=true, which re-enables the legacy"
                        + " behaviour of resolving circular bean dependencies instead of failing.",
                "Remove spring.main.allow-circular-references and break the cycle, for example by"
                        + " introducing an intermediary bean or using setter/@Lazy injection deliberately.",
                "https://docs.spring.io/spring-boot/reference/features/spring-application.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.isPropertyTrue("spring.main.allow-circular-references")) {
            return violation("spring.main.allow-circular-references=true masks circular dependencies"
                    + " that Spring would otherwise reject at startup.");
        }
        return pass();
    }
}

final class DuplicateObjectMapperRule extends AbstractSpringRule {

    DuplicateObjectMapperRule() {
        super(new SpringRuleDefinition(
                "SPRING-WIRING-003",
                "Avoid multiple ObjectMapper beans",
                SpringCategory.BEAN_WIRING,
                "LOW",
                "Detects more than one Jackson ObjectMapper bean, which can lead to inconsistent JSON"
                        + " (de)serialization depending on which one is injected.",
                "Keep a single primary ObjectMapper (customise the auto-configured one via a"
                        + " Jackson2ObjectMapperBuilderCustomizer) or mark one bean @Primary.",
                "https://docs.spring.io/spring-boot/reference/features/json.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<BeanRef> mappers = context.objectMappers();
        if (mappers.size() > 1 && SpringModel.primaryCount(mappers) == 0) {
            return violation(
                    "Found " + mappers.size() + " ObjectMapper beans and none is @Primary: " + names(mappers) + ".");
        }
        return pass();
    }
}

final class AmbiguousTaskExecutorRule extends AbstractSpringRule {

    AmbiguousTaskExecutorRule() {
        super(new SpringRuleDefinition(
                "SPRING-WIRING-004",
                "Multiple TaskExecutor beans need a primary",
                SpringCategory.BEAN_WIRING,
                "MEDIUM",
                "Detects more than one TaskExecutor bean without a @Primary, so @Async and other"
                        + " consumers may resolve an unexpected executor.",
                "Mark the intended executor @Primary, or qualify each injection point with the"
                        + " executor bean name.",
                "https://docs.spring.io/spring-framework/reference/integration/scheduling.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<BeanRef> executors = context.taskExecutors();
        if (executors.size() > 1 && SpringModel.primaryCount(executors) == 0) {
            return violation("Found " + executors.size() + " TaskExecutor beans and none is @Primary: "
                    + names(executors) + ".");
        }
        return pass();
    }
}

final class AmbiguousDataSourceRule extends AbstractSpringRule {

    AmbiguousDataSourceRule() {
        super(new SpringRuleDefinition(
                "SPRING-WIRING-005",
                "Multiple DataSource beans need a primary",
                SpringCategory.BEAN_WIRING,
                "MEDIUM",
                "Detects more than one DataSource bean without a @Primary, which makes auto-configured"
                        + " consumers (JPA, JdbcTemplate) fail or pick an unexpected source.",
                "Mark the main DataSource @Primary and qualify any secondary DataSource explicitly.",
                "https://docs.spring.io/spring-boot/reference/data/sql.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<BeanRef> dataSources = context.dataSources();
        if (dataSources.size() > 1 && SpringModel.primaryCount(dataSources) == 0) {
            return violation("Found " + dataSources.size() + " DataSource beans and none is @Primary: "
                    + names(dataSources) + ".");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

final class LazyInitializationDisabledRule extends AbstractSpringRule {

    private static final int LARGE_CONTEXT_THRESHOLD = 250;

    LazyInitializationDisabledRule() {
        super(new SpringRuleDefinition(
                "SPRING-CONFIG-001",
                "Consider lazy initialization for large contexts",
                SpringCategory.CONFIGURATION,
                "LOW",
                "A large bean context is initialised eagerly. Lazy initialization can shorten startup"
                        + " for development, tests, and short-lived or serverless workloads.",
                "Evaluate spring.main.lazy-initialization=true, ideally combined with @Lazy(false) on"
                        + " beans that must still initialise eagerly (such as listeners and schedulers).",
                "https://docs.spring.io/spring-boot/reference/features/spring-application.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.isPropertyTrue("spring.main.lazy-initialization")) {
            return pass();
        }
        if (context.beanDefinitionCount() > LARGE_CONTEXT_THRESHOLD) {
            return violation("The context defines " + context.beanDefinitionCount()
                    + " beans and is initialised eagerly; lazy initialization may cut startup time.");
        }
        return pass();
    }
}

final class DebugOrTraceLoggingRule extends AbstractSpringRule {

    DebugOrTraceLoggingRule() {
        super(new SpringRuleDefinition(
                "SPRING-CONFIG-002",
                "Disable global debug or trace logging",
                SpringCategory.CONFIGURATION,
                "LOW",
                "Detects debug=true or trace=true, which switch on verbose auto-configuration logging"
                        + " and can leak internal details or slow down the application.",
                "Remove the debug/trace flags and configure logging levels per package instead.",
                "https://docs.spring.io/spring-boot/reference/features/logging.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.isPropertyTrue("debug")) {
            return violation("debug=true enables verbose auto-configuration debug logging.");
        }
        if (context.isPropertyTrue("trace")) {
            return violation("trace=true enables verbose auto-configuration trace logging.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Profiles and environment
// ---------------------------------------------------------------------------

final class NoActiveProfileRule extends AbstractSpringRule {

    NoActiveProfileRule() {
        super(new SpringRuleDefinition(
                "SPRING-PROFILE-001",
                "Run with an explicit active profile",
                SpringCategory.PROFILES,
                "LOW",
                "No Spring profile is active, so any profile-specific configuration (such as"
                        + " application-prod.yml) is never applied.",
                "Set spring.profiles.active (for example via SPRING_PROFILES_ACTIVE) so the intended"
                        + " environment configuration takes effect.",
                "https://docs.spring.io/spring-boot/reference/features/profiles.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.activeProfiles().length == 0) {
            return violation("No active Spring profile is set; only the default profile is in effect.");
        }
        return pass();
    }
}

final class DevToolsOnClasspathRule extends AbstractSpringRule {

    DevToolsOnClasspathRule() {
        super(new SpringRuleDefinition(
                "SPRING-PROFILE-002",
                "Spring Boot DevTools should not ship to production",
                SpringCategory.PROFILES,
                "MEDIUM",
                "Spring Boot DevTools is on the classpath. It enables automatic restart, a remote"
                        + " debug tunnel, and relaxed caching that are unsafe in production.",
                "Scope spring-boot-devtools to development only (Maven <optional>true</optional> /"
                        + " Gradle developmentOnly) so it is excluded from production artifacts.",
                "https://docs.spring.io/spring-boot/reference/using/devtools.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.devToolsPresent()) {
            String severityNote = context.isProductionProfileActive()
                    ? " A production-like profile is active, which makes this especially risky."
                    : "";
            return violation("Spring Boot DevTools is on the classpath." + severityNote);
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Performance and concurrency
// ---------------------------------------------------------------------------

final class VirtualThreadsAvailableRule extends AbstractSpringRule {

    VirtualThreadsAvailableRule() {
        super(new SpringRuleDefinition(
                "SPRING-PERF-001",
                "Consider enabling virtual threads",
                SpringCategory.PERFORMANCE,
                "MEDIUM",
                "The JVM supports virtual threads (Java 21+) but spring.threads.virtual.enabled is not"
                        + " set. Blocking, request-per-thread workloads usually scale better with them.",
                "Set spring.threads.virtual.enabled=true and verify that blocking code paths do not"
                        + " hold synchronized monitors that would pin carrier threads.",
                "https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!context.virtualThreadsSupported()) {
            return skipped("This JVM does not support virtual threads (requires Java 21+).");
        }
        if (!context.isVirtualThreadsEnabled()) {
            return violation("Virtual threads are supported but spring.threads.virtual.enabled is not true.");
        }
        return pass();
    }
}

final class VirtualThreadsOverriddenByPoolRule extends AbstractSpringRule {

    VirtualThreadsOverriddenByPoolRule() {
        super(new SpringRuleDefinition(
                "SPRING-PERF-002",
                "Pooled executor cancels virtual-thread benefits",
                SpringCategory.PERFORMANCE,
                "MEDIUM",
                "Virtual threads are enabled, but a platform-thread pool executor (ThreadPoolTaskExecutor)"
                        + " is also defined, so work routed through it still runs on a bounded pool.",
                "Remove the custom ThreadPoolTaskExecutor or replace it with a virtual-thread executor"
                        + " so asynchronous work actually benefits from virtual threads.",
                "https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.isVirtualThreadsEnabled() && context.pooledTaskExecutorPresent()) {
            return violation("Virtual threads are enabled but a ThreadPoolTaskExecutor bean re-pools work"
                    + " onto bounded platform threads.");
        }
        return pass();
    }
}

final class AsyncWithoutCustomExecutorRule extends AbstractSpringRule {

    AsyncWithoutCustomExecutorRule() {
        super(new SpringRuleDefinition(
                "SPRING-PERF-003",
                "@Async should use an explicit executor",
                SpringCategory.PERFORMANCE,
                "MEDIUM",
                "@EnableAsync is active but no TaskExecutor bean is defined. Without virtual threads,"
                        + " @Async falls back to an unbounded SimpleAsyncTaskExecutor that creates a new"
                        + " thread per task.",
                "Define a dedicated executor (or enable spring.threads.virtual.enabled) so asynchronous"
                        + " work runs on a bounded, monitored thread source.",
                "https://docs.spring.io/spring-framework/reference/integration/scheduling.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.asyncEnabled() && context.taskExecutors().isEmpty() && !context.isVirtualThreadsEnabled()) {
            return violation("@EnableAsync is active with no TaskExecutor bean, so @Async uses the"
                    + " unbounded SimpleAsyncTaskExecutor.");
        }
        return pass();
    }
}

final class ConnectionPoolSmallForVirtualThreadsRule extends AbstractSpringRule {

    private static final int DEFAULT_HIKARI_POOL_SIZE = 10;

    ConnectionPoolSmallForVirtualThreadsRule() {
        super(new SpringRuleDefinition(
                "SPRING-PERF-004",
                "Connection pool may bottleneck virtual threads",
                SpringCategory.PERFORMANCE,
                "LOW",
                "Virtual threads are enabled while the HikariCP connection pool keeps a small (default)"
                        + " maximum size, so many virtual threads can contend for few database connections.",
                "Review spring.datasource.hikari.maximum-pool-size against the expected concurrency, and"
                        + " size it for the database rather than the (now cheap) thread count.",
                "https://docs.spring.io/spring-boot/reference/data/sql.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!context.isVirtualThreadsEnabled() || !context.hikariDataSourcePresent()) {
            return pass();
        }
        Integer maxPoolSize = context.firstIntegerProperty("spring.datasource.hikari.maximum-pool-size");
        int effective = maxPoolSize != null ? maxPoolSize : DEFAULT_HIKARI_POOL_SIZE;
        if (effective <= DEFAULT_HIKARI_POOL_SIZE) {
            String configured =
                    maxPoolSize != null ? String.valueOf(maxPoolSize) : "default " + DEFAULT_HIKARI_POOL_SIZE;
            return violation("Virtual threads are enabled but the HikariCP maximum pool size is " + configured
                    + ", which can become the concurrency bottleneck.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Web and HTTP
// ---------------------------------------------------------------------------

final class ResponseCompressionDisabledRule extends AbstractSpringRule {

    ResponseCompressionDisabledRule() {
        super(new SpringRuleDefinition(
                "SPRING-WEB-001",
                "Enable HTTP response compression",
                SpringCategory.WEB,
                "LOW",
                "HTTP response compression is not enabled (server.compression.enabled is not true), so"
                        + " text responses are sent uncompressed.",
                "Set server.compression.enabled=true (and tune mime-types / min-response-size) to reduce"
                        + " bandwidth for JSON, HTML, and other text payloads.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!context.isPropertyTrue("server.compression.enabled")) {
            return violation("server.compression.enabled is not true, so responses are sent uncompressed.");
        }
        return pass();
    }
}

final class GracefulShutdownDisabledRule extends AbstractSpringRule {

    GracefulShutdownDisabledRule() {
        super(new SpringRuleDefinition(
                "SPRING-WEB-002",
                "Enable graceful shutdown",
                SpringCategory.WEB,
                "MEDIUM",
                "Graceful shutdown is not enabled (server.shutdown is not 'graceful'), so in-flight"
                        + " requests can be dropped when the application stops.",
                "Set server.shutdown=graceful and tune spring.lifecycle.timeout-per-shutdown-phase so"
                        + " active requests can complete during rollouts.",
                "https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        String shutdown = context.firstProperty("server.shutdown");
        if (shutdown == null || !"graceful".equalsIgnoreCase(shutdown)) {
            return violation("server.shutdown is not 'graceful', so in-flight requests may be dropped on stop.");
        }
        return pass();
    }
}

final class Http2DisabledRule extends AbstractSpringRule {

    Http2DisabledRule() {
        super(new SpringRuleDefinition(
                "SPRING-WEB-003",
                "Consider enabling HTTP/2",
                SpringCategory.WEB,
                "INFO",
                "HTTP/2 is not enabled (server.http2.enabled is not true). HTTP/2 multiplexing can"
                        + " improve latency for browsers and modern clients.",
                "Enable server.http2.enabled=true (over TLS) once the runtime and clients support it.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!context.isPropertyTrue("server.http2.enabled")) {
            return violation("server.http2.enabled is not true; HTTP/2 multiplexing is unavailable.");
        }
        return pass();
    }
}
