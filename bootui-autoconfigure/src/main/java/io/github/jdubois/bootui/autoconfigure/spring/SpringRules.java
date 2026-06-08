package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import java.util.ArrayList;
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

    /** Violation whose severity is raised/lowered from the declared default based on context. */
    SpringRuleResultDto violation(String severityOverride, List<String> details) {
        return details.isEmpty() ? pass() : SpringRuleSupport.violation(definition, severityOverride, details);
    }

    SpringRuleResultDto violation(String severityOverride, String detail) {
        return SpringRuleSupport.violation(definition, severityOverride, List.of(detail));
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
                "Avoid multiple JSON mapper beans",
                SpringCategory.BEAN_WIRING,
                "LOW",
                "Detects more than one Jackson JSON mapper bean (Jackson 2 ObjectMapper or the Jackson 3"
                        + " JsonMapper that Spring Boot 4 auto-configures) with none marked @Primary, which can"
                        + " lead to inconsistent JSON (de)serialization depending on which one is injected.",
                "Keep a single primary JSON mapper. With Jackson 3 (the Spring Boot 4 default) customise the"
                        + " auto-configured mapper via a JsonMapperBuilderCustomizer, or mark one bean @Primary.",
                "https://docs.spring.io/spring-boot/reference/features/json.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<BeanRef> mappers = context.objectMappers();
        if (mappers.size() > 1 && SpringModel.primaryCount(mappers) == 0) {
            return violation(
                    "Found " + mappers.size() + " JSON mapper beans and none is @Primary: " + names(mappers) + ".");
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
                        + " consumers may resolve an unexpected executor. A bean conventionally named"
                        + " applicationTaskExecutor/taskExecutor, or an AsyncConfigurer, resolves the"
                        + " ambiguity and suppresses this check.",
                "Mark the intended executor @Primary, name it applicationTaskExecutor, implement"
                        + " AsyncConfigurer, or qualify each injection point with the executor bean name.",
                "https://docs.spring.io/spring-framework/reference/integration/scheduling.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<BeanRef> executors = context.taskExecutors();
        if (executors.size() > 1
                && SpringModel.primaryCount(executors) == 0
                && !context.asyncConfigurerPresent()
                && !SpringModel.hasName(executors, "applicationTaskExecutor", "taskExecutor")) {
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

final class AmbiguousTransactionManagerRule extends AbstractSpringRule {

    AmbiguousTransactionManagerRule() {
        super(new SpringRuleDefinition(
                "SPRING-WIRING-006",
                "Multiple transaction managers need a primary",
                SpringCategory.BEAN_WIRING,
                "MEDIUM",
                "Detects more than one PlatformTransactionManager bean without a @Primary, so @Transactional"
                        + " methods may bind to an unexpected manager. A bean named transactionManager or a"
                        + " TransactionManagementConfigurer resolves the default and suppresses this check.",
                "Mark the main transaction manager @Primary, name it transactionManager, implement"
                        + " TransactionManagementConfigurer, or set @Transactional(\"<name>\") on each usage.",
                "https://docs.spring.io/spring-framework/reference/data-access/transaction.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<BeanRef> managers = context.transactionManagers();
        if (managers.size() > 1
                && SpringModel.primaryCount(managers) == 0
                && !context.transactionManagementConfigurerPresent()
                && !SpringModel.hasName(managers, "transactionManager")) {
            return violation("Found " + managers.size() + " PlatformTransactionManager beans and none is @Primary: "
                    + names(managers) + ".");
        }
        return pass();
    }
}

final class RestTemplateInUseRule extends AbstractSpringRule {

    RestTemplateInUseRule() {
        super(new SpringRuleDefinition(
                "SPRING-WIRING-007",
                "Prefer RestClient over RestTemplate",
                SpringCategory.BEAN_WIRING,
                "LOW",
                "A RestTemplate bean is defined. RestTemplate is in maintenance mode; Spring Boot 4 favours"
                        + " the fluent, modern RestClient for synchronous HTTP access.",
                "Migrate RestTemplate usage to RestClient (RestClient.create() or an injected"
                        + " RestClient.Builder). Keep RestTemplate only where a dependency still requires it.",
                "https://docs.spring.io/spring-framework/reference/integration/rest-clients.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<BeanRef> restTemplates = context.restTemplates();
        if (!restTemplates.isEmpty()) {
            return violation("Found " + restTemplates.size() + " RestTemplate bean(s): " + names(restTemplates)
                    + "; consider migrating to RestClient.");
        }
        return pass();
    }
}

final class DefaultPackageComponentsRule extends AbstractSpringRule {

    DefaultPackageComponentsRule() {
        super(new SpringRuleDefinition(
                "SPRING-WIRING-008",
                "Avoid components in the default package",
                SpringCategory.BEAN_WIRING,
                "MEDIUM",
                "Detects application beans whose class lives in the default (unnamed) package. A class there"
                        + " forces component scanning to scan the entire classpath, slows startup, and breaks"
                        + " several Spring features.",
                "Move these classes into a named package (for example com.example.app) so component scanning"
                        + " is bounded to your application's packages.",
                "https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<String> beans = context.defaultPackageBeans();
        if (!beans.isEmpty()) {
            return violation("Found " + beans.size() + " application bean(s) whose class is in the default package: "
                    + String.join(", ", beans) + ".");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

final class LazyInitializationDisabledRule extends AbstractSpringRule {

    private static final int LARGE_CONTEXT_THRESHOLD = 300;

    LazyInitializationDisabledRule() {
        super(new SpringRuleDefinition(
                "SPRING-CONFIG-001",
                "Consider lazy initialization for large contexts",
                SpringCategory.CONFIGURATION,
                "INFO",
                "A large bean context is initialised eagerly. Lazy initialization can shorten startup"
                        + " for development, tests, and short-lived or serverless workloads.",
                "Evaluate spring.main.lazy-initialization=true, weighing the trade-offs: wiring errors"
                        + " surface on first use instead of at startup, the first request to each bean pays an"
                        + " initialization cost, and it interacts with AOT/native processing. Keep beans that"
                        + " must start eagerly (listeners, schedulers) annotated @Lazy(false).",
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

    /** Root and broad framework loggers whose DEBUG/TRACE output is verbose and detail-leaking. */
    private static final List<String> VERBOSE_LOGGERS =
            List.of("root", "web", "sql", "org.springframework", "org.hibernate");

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
        List<String> findings = new ArrayList<>();
        if (context.isPropertyTrue("debug")) {
            findings.add("debug=true enables verbose auto-configuration debug logging.");
        }
        if (context.isPropertyTrue("trace")) {
            findings.add("trace=true enables verbose auto-configuration trace logging.");
        }
        for (String logger : VERBOSE_LOGGERS) {
            String level = context.firstProperty("logging.level." + logger);
            if (level != null && ("debug".equalsIgnoreCase(level) || "trace".equalsIgnoreCase(level))) {
                findings.add("logging.level." + logger + "=" + level
                        + " emits verbose framework logging that can leak internals and slow the application.");
            }
        }
        return violation(findings);
    }
}

final class RemovedOrRenamedPropertyRule extends AbstractSpringRule {

    /** Curated, high-confidence keys that were renamed or removed in Spring Boot 4. */
    private static final List<String[]> LEGACY_PROPERTIES = List.of(
            new String[] {
                "spring.dao.exceptiontranslation.enabled", "renamed to spring.persistence.exceptiontranslation.enabled"
            },
            new String[] {
                "server.undertow.threads.io", "Undertow was removed in Spring Boot 4; this property is ignored"
            },
            new String[] {
                "server.undertow.threads.worker", "Undertow was removed in Spring Boot 4; this property is ignored"
            },
            new String[] {
                "server.undertow.accesslog.enabled", "Undertow was removed in Spring Boot 4; this property is ignored"
            },
            new String[] {
                "server.undertow.buffer-size", "Undertow was removed in Spring Boot 4; this property is ignored"
            });

    RemovedOrRenamedPropertyRule() {
        super(new SpringRuleDefinition(
                "SPRING-CONFIG-003",
                "Remove renamed or deleted Spring Boot 4 properties",
                SpringCategory.CONFIGURATION,
                "MEDIUM",
                "Detects configuration keys that were renamed or removed in Spring Boot 4 and therefore no"
                        + " longer take effect, which can silently change behaviour after an upgrade.",
                "Update each key to its Spring Boot 4 equivalent (the spring-boot-properties-migrator"
                        + " module lists the replacements at startup) and remove keys for dropped features.",
                "https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<String> findings = new ArrayList<>();
        for (String[] entry : LEGACY_PROPERTIES) {
            if (context.hasProperty(entry[0])) {
                findings.add(entry[0] + " — " + entry[1] + ".");
            }
        }
        return violation(findings);
    }
}

final class MissingApplicationNameRule extends AbstractSpringRule {

    MissingApplicationNameRule() {
        super(new SpringRuleDefinition(
                "SPRING-CONFIG-004",
                "Set spring.application.name",
                SpringCategory.CONFIGURATION,
                "INFO",
                "spring.application.name is not set. The application name labels logs, metrics, tracing,"
                        + " and service discovery, and several integrations fall back to anonymous defaults"
                        + " without it.",
                "Set spring.application.name to a stable identifier for this service.",
                "https://docs.spring.io/spring-boot/reference/features/spring-application.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.firstProperty("spring.application.name") == null) {
            return violation("spring.application.name is not set, so logs, metrics, and tracing lack a"
                    + " stable application identifier.");
        }
        return pass();
    }
}

final class ConfigOnNotFoundIgnoreRule extends AbstractSpringRule {

    ConfigOnNotFoundIgnoreRule() {
        super(new SpringRuleDefinition(
                "SPRING-CONFIG-005",
                "Do not ignore missing config files",
                SpringCategory.CONFIGURATION,
                "MEDIUM",
                "spring.config.on-not-found=ignore makes Spring silently skip imported configuration files"
                        + " that are missing, so a typo or a misplaced file can ship without any error.",
                "Remove spring.config.on-not-found=ignore (the default fails fast) and use the optional:"
                        + " prefix only on the specific imports that are genuinely optional.",
                "https://docs.spring.io/spring-boot/reference/features/external-config.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        String value = context.firstProperty("spring.config.on-not-found");
        if (value != null && "ignore".equalsIgnoreCase(value)) {
            return violation("spring.config.on-not-found=ignore silently skips missing config imports instead of"
                    + " failing fast.");
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
                "INFO",
                "No Spring profile is active beyond the default, so any profile-specific configuration"
                        + " (such as application-prod.yml) is never applied.",
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
                "Spring Boot DevTools should be scoped to development",
                SpringCategory.PROFILES,
                "MEDIUM",
                "Spring Boot DevTools is on the classpath. It enables automatic restart, a live-reload"
                        + " server, and relaxed caching. DevTools disables itself in a fully packaged jar, but"
                        + " it is still active here and must never be bundled into a production artifact.",
                "Scope spring-boot-devtools to development only (Maven <optional>true</optional> /"
                        + " Gradle developmentOnly) so it is excluded from production builds.",
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

final class ProfileValidationDisabledRule extends AbstractSpringRule {

    ProfileValidationDisabledRule() {
        super(new SpringRuleDefinition(
                "SPRING-PROFILE-003",
                "Keep profile-name validation enabled",
                SpringCategory.PROFILES,
                "LOW",
                "spring.profiles.validate=false disables Spring Boot's check that profile names are sensible,"
                        + " so a malformed or unexpected profile name no longer fails fast.",
                "Remove spring.profiles.validate=false (validation is on by default) and fix any profile"
                        + " names that do not satisfy the naming rules.",
                "https://docs.spring.io/spring-boot/reference/features/profiles.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        String value = context.firstProperty("spring.profiles.validate");
        if (value != null && "false".equalsIgnoreCase(value)) {
            return violation("spring.profiles.validate=false disables profile-name validation.");
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
                "INFO",
                "The JVM supports virtual threads (Java 21+) but spring.threads.virtual.enabled is not"
                        + " set. Blocking, request-per-thread workloads can often scale further on virtual"
                        + " threads — an opportunity to evaluate, not a defect.",
                "Consider spring.threads.virtual.enabled=true after verifying that blocking code paths do"
                        + " not hold synchronized monitors that would pin carrier threads.",
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
                        + " is also defined, so work routed through it still runs on a bounded pool. A bounded"
                        + " pool can be intentional (for example to throttle a downstream system), so confirm"
                        + " whether this executor should keep using platform threads.",
                "If the pooling is not deliberate, remove the custom ThreadPoolTaskExecutor or replace it"
                        + " with a virtual-thread executor so asynchronous work benefits from virtual threads.",
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
                    + "; review whether it matches the database's capacity rather than the now-cheap thread count.");
        }
        return pass();
    }
}

final class SchedulerPoolTooSmallRule extends AbstractSpringRule {

    SchedulerPoolTooSmallRule() {
        super(new SpringRuleDefinition(
                "SPRING-PERF-005",
                "Scheduler runs on a single thread",
                SpringCategory.PERFORMANCE,
                "INFO",
                "@EnableScheduling is active but the scheduling pool size is at its default of one thread"
                        + " (spring.task.scheduling.pool.size), so a long-running or overlapping @Scheduled task"
                        + " can delay every other scheduled task.",
                "Increase spring.task.scheduling.pool.size to match the number of concurrent scheduled"
                        + " tasks, or enable virtual threads (spring.threads.virtual.enabled=true).",
                "https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!context.schedulingEnabled() || context.isVirtualThreadsEnabled()) {
            return pass();
        }
        Integer poolSize = context.firstIntegerProperty("spring.task.scheduling.pool.size");
        int effective = poolSize != null ? poolSize : 1;
        if (effective <= 1) {
            return violation("Scheduling is enabled but the scheduler pool size is " + effective
                    + ", so scheduled tasks run one at a time.");
        }
        return pass();
    }
}

final class UnboundedAsyncQueueRule extends AbstractSpringRule {

    UnboundedAsyncQueueRule() {
        super(new SpringRuleDefinition(
                "SPRING-PERF-006",
                "Bound the @Async executor queue",
                SpringCategory.PERFORMANCE,
                "LOW",
                "@EnableAsync is active and spring.task.execution.pool.queue-capacity is not set, so the"
                        + " auto-configured task executor uses an effectively unbounded queue that can hide a"
                        + " backlog and grow heap usage under load.",
                "Set spring.task.execution.pool.queue-capacity (and a matching max pool size) to a bounded"
                        + " value, or enable virtual threads so async work is not pooled.",
                "https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!context.asyncEnabled() || context.isVirtualThreadsEnabled()) {
            return pass();
        }
        if (!context.hasProperty("spring.task.execution.pool.queue-capacity")) {
            return violation("@Async is enabled but spring.task.execution.pool.queue-capacity is unset, leaving the"
                    + " auto-configured executor with an unbounded queue.");
        }
        return pass();
    }
}

final class InMemoryCacheManagerRule extends AbstractSpringRule {

    private static final String CONCURRENT_MAP_CACHE_MANAGER =
            "org.springframework.cache.concurrent.ConcurrentMapCacheManager";
    private static final String NOOP_CACHE_MANAGER = "org.springframework.cache.support.NoOpCacheManager";

    InMemoryCacheManagerRule() {
        super(new SpringRuleDefinition(
                "SPRING-CACHE-001",
                "Use a real cache provider in production",
                SpringCategory.PERFORMANCE,
                "LOW",
                "Caching is enabled (@EnableCaching) but every CacheManager is an in-memory development"
                        + " default (ConcurrentMapCacheManager or NoOpCacheManager), which never evicts, has no"
                        + " TTL, and is not shared across instances.",
                "Configure a production cache provider (Caffeine, Redis, Hazelcast, …) with eviction and"
                        + " TTL so cached data is bounded and consistent across instances.",
                "https://docs.spring.io/spring-boot/reference/io/caching.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!context.cachingEnabled()) {
            return pass();
        }
        List<SpringModel.CacheManagerRef> managers = context.cacheManagers();
        if (managers.isEmpty()) {
            return pass();
        }
        boolean allInMemory = managers.stream().allMatch(manager -> isInMemory(manager.className()));
        if (allInMemory) {
            return violation("Caching is enabled but the only cache manager(s) are in-memory defaults: "
                    + cacheManagerSummary(managers) + ".");
        }
        return pass();
    }

    private static boolean isInMemory(String className) {
        return CONCURRENT_MAP_CACHE_MANAGER.equals(className) || NOOP_CACHE_MANAGER.equals(className);
    }

    private static String cacheManagerSummary(List<SpringModel.CacheManagerRef> managers) {
        return managers.stream()
                .map(manager -> manager.name() + " (" + simpleName(manager.className()) + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "unknown";
        }
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
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
                        + " text responses are sent uncompressed. This may be intentional when a reverse proxy,"
                        + " load balancer, or CDN already compresses responses at the edge.",
                "If nothing upstream compresses responses, set server.compression.enabled=true (and tune"
                        + " mime-types / min-response-size) to reduce bandwidth for JSON, HTML, and other text.",
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
                "Keep graceful shutdown enabled",
                SpringCategory.WEB,
                "MEDIUM",
                "Detects server.shutdown=immediate, which overrides the Spring Boot 4 default of graceful"
                        + " shutdown, so in-flight requests can be dropped when the application stops.",
                "Remove server.shutdown=immediate (Spring Boot 4 defaults to graceful) and tune"
                        + " spring.lifecycle.timeout-per-shutdown-phase so active requests can complete during"
                        + " rollouts.",
                "https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        String shutdown = context.firstProperty("server.shutdown");
        if (shutdown != null && "immediate".equalsIgnoreCase(shutdown)) {
            return violation("server.shutdown=immediate overrides Spring Boot 4 graceful shutdown, so in-flight"
                    + " requests may be dropped on stop.");
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
                        + " improve latency for browsers and modern clients. A reverse proxy or load balancer"
                        + " often terminates HTTP/2 at the edge, in which case enabling it on the app is"
                        + " unnecessary.",
                "If no edge proxy already serves HTTP/2, enable server.http2.enabled=true (over TLS) once"
                        + " the runtime and clients support it.",
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

final class ErrorDetailsExposedRule extends AbstractSpringRule {

    private static final List<String> ERROR_DETAIL_KEYS =
            List.of("include-stacktrace", "include-message", "include-binding-errors", "include-exception");

    ErrorDetailsExposedRule() {
        super(new SpringRuleDefinition(
                "SPRING-WEB-004",
                "Do not always expose error details",
                SpringCategory.WEB,
                "MEDIUM",
                "An error-detail property is set to 'always', so stack traces, exception messages, or"
                        + " binding errors are returned in error responses to every client — a common way to"
                        + " leak internal implementation details.",
                "Use 'never' (or 'on-param') for include-stacktrace / include-message / include-binding-errors"
                        + " under spring.web.error.* so details are not exposed to arbitrary callers.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<String> findings = new ArrayList<>();
        for (String key : ERROR_DETAIL_KEYS) {
            String value = context.firstProperty("spring.web.error." + key, "server.error." + key);
            if (value == null) {
                continue;
            }
            if ("include-exception".equals(key)) {
                if ("true".equalsIgnoreCase(value)) {
                    findings.add("error include-exception is set to 'true', exposing the exception type to every"
                            + " client.");
                }
            } else if ("always".equalsIgnoreCase(value)) {
                findings.add("error " + key + " is set to 'always', exposing details to every client.");
            }
        }
        return violation(findings);
    }
}

final class HttpClientTimeoutsUnsetRule extends AbstractSpringRule {

    HttpClientTimeoutsUnsetRule() {
        super(new SpringRuleDefinition(
                "SPRING-WEB-005",
                "Set HTTP client timeouts",
                SpringCategory.WEB,
                "INFO",
                "A RestClient or RestTemplate bean is defined but no global HTTP client timeouts are set"
                        + " (spring.http.clients.connect-timeout / read-timeout), so a slow or unresponsive"
                        + " dependency can block threads indefinitely.",
                "Set spring.http.clients.connect-timeout and spring.http.clients.read-timeout (or configure"
                        + " timeouts per client) so outbound calls fail fast.",
                "https://docs.spring.io/spring-boot/reference/io/rest-client.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        boolean clientPresent =
                context.restClientBeanPresent() || !context.restTemplates().isEmpty();
        if (!clientPresent) {
            return pass();
        }
        boolean connectSet = context.hasProperty("spring.http.clients.connect-timeout");
        boolean readSet = context.hasProperty("spring.http.clients.read-timeout");
        if (connectSet && readSet) {
            return pass();
        }
        if (!connectSet && !readSet) {
            return violation("An HTTP client bean is defined but neither spring.http.clients.connect-timeout nor"
                    + " spring.http.clients.read-timeout is set.");
        }
        String missing = connectSet ? "spring.http.clients.read-timeout" : "spring.http.clients.connect-timeout";
        return violation("An HTTP client bean is defined but " + missing
                + " is not set, so outbound calls can still block indefinitely.");
    }
}

final class ForwardHeadersStrategyUnsetRule extends AbstractSpringRule {

    ForwardHeadersStrategyUnsetRule() {
        super(new SpringRuleDefinition(
                "SPRING-WEB-006",
                "Configure forwarded-headers handling behind a proxy",
                SpringCategory.WEB,
                "INFO",
                "A production-like profile is active but server.forward-headers-strategy is not set. Behind a"
                        + " reverse proxy or load balancer, the app may then build URLs and read client IPs from"
                        + " the proxy hop instead of the original request.",
                "If the application runs behind a proxy, set server.forward-headers-strategy=framework (or"
                        + " native when the container handles it) so X-Forwarded-* headers are honoured.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.isProductionProfileActive() && !context.hasProperty("server.forward-headers-strategy")) {
            return violation("A production-like profile is active but server.forward-headers-strategy is not set.");
        }
        return pass();
    }
}

final class RedundantTomcatThreadsRule extends AbstractSpringRule {

    RedundantTomcatThreadsRule() {
        super(new SpringRuleDefinition(
                "SPRING-WEB-007",
                "Tomcat thread cap is redundant with virtual threads",
                SpringCategory.WEB,
                "LOW",
                "Virtual threads are enabled but server.tomcat.threads.max is set explicitly. With virtual"
                        + " threads handling requests, a small platform-thread cap can needlessly limit"
                        + " concurrency, while a large one has little effect.",
                "Remove server.tomcat.threads.max when running on virtual threads, or confirm the cap is a"
                        + " deliberate back-pressure limit.",
                "https://docs.spring.io/spring-boot/reference/web/servlet.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (context.isVirtualThreadsEnabled() && context.hasProperty("server.tomcat.threads.max")) {
            return violation("Virtual threads are enabled but server.tomcat.threads.max is set, which can cap"
                    + " request concurrency unnecessarily.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Actuator and management
// ---------------------------------------------------------------------------

final class ActuatorExposeAllRule extends AbstractSpringRule {

    ActuatorExposeAllRule() {
        super(new SpringRuleDefinition(
                "SPRING-MGMT-001",
                "Avoid exposing all Actuator endpoints",
                SpringCategory.MANAGEMENT,
                "MEDIUM",
                "management.endpoints.web.exposure.include is set to '*', which exposes every Actuator"
                        + " endpoint (including sensitive ones such as env, configprops, and loggers) over the"
                        + " web. This is convenient in development but rarely intended in production.",
                "List only the endpoints you need (for example health,info,metrics) instead of '*', and use"
                        + " management.endpoints.web.exposure.exclude to trim further. Endpoint authorization is"
                        + " handled separately by the Security advisor.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!ActuatorExposure.exposesAll(context) || context.managementWebDisabled()) {
            return pass();
        }
        StringBuilder detail = new StringBuilder(
                "management.endpoints.web.exposure.include=* exposes all Actuator endpoints over the web");
        java.util.Set<String> exclude = ActuatorExposure.excludeTokens(context);
        if (!exclude.isEmpty()) {
            detail.append(" (except excluded: ")
                    .append(String.join(", ", exclude))
                    .append(')');
        }
        detail.append('.');
        boolean prodSamePort = context.isProductionProfileActive() && context.managementOnApplicationPort();
        String severity = prodSamePort ? SpringRuleSupport.HIGH : null;
        return violation(severity, detail.toString());
    }
}

final class SensitiveActuatorEndpointsExposedRule extends AbstractSpringRule {

    SensitiveActuatorEndpointsExposedRule() {
        super(new SpringRuleDefinition(
                "SPRING-MGMT-002",
                "Do not web-expose sensitive Actuator endpoints",
                SpringCategory.MANAGEMENT,
                "MEDIUM",
                "Sensitive Actuator endpoints (such as env, configprops, beans, threaddump, or loggers) are"
                        + " explicitly listed in management.endpoints.web.exposure.include and remain readable."
                        + " They reveal configuration, environment values, and the bean graph to anyone who can"
                        + " reach the management port.",
                "Expose only health and info publicly; keep diagnostic endpoints off the web exposure list, move"
                        + " them to a separate, firewalled management port, and require authentication. The"
                        + " Security advisor covers endpoint authorization.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        // The wildcard case is owned by SPRING-MGMT-001; this rule targets explicitly-named endpoints.
        if (ActuatorExposure.exposesAll(context) || context.managementWebDisabled()) {
            return pass();
        }
        List<String> findings = new ArrayList<>();
        for (String id : ActuatorExposure.SENSITIVE_READ_ENDPOINTS) {
            if (ActuatorExposure.isReadable(context, id)) {
                findings.add("Actuator endpoint '" + id + "' is web-exposed and readable, revealing internal"
                        + " details to callers that reach the management port.");
            }
        }
        if (findings.isEmpty()) {
            return pass();
        }
        findings.sort(String::compareTo);
        boolean prodSamePort = context.isProductionProfileActive() && context.managementOnApplicationPort();
        String severity = prodSamePort ? SpringRuleSupport.HIGH : null;
        return violation(severity, findings);
    }
}

final class ActuatorShowValuesAlwaysRule extends AbstractSpringRule {

    /** Endpoints whose show-values/show-details=ALWAYS only matters when they are readable. */
    private static final List<String> VALUE_ENDPOINTS = List.of("env", "configprops");

    ActuatorShowValuesAlwaysRule() {
        super(
                new SpringRuleDefinition(
                        "SPRING-MGMT-003",
                        "Do not always show Actuator values or health details",
                        SpringCategory.MANAGEMENT,
                        "MEDIUM",
                        "An Actuator endpoint is configured to reveal full values unconditionally"
                                + " (management.endpoint.env|configprops.show-values=ALWAYS or"
                                + " management.endpoint.health.show-details=always). Property values, including"
                                + " credentials, and internal health probe details are then returned to every caller.",
                        "Use show-values=WHEN_AUTHORIZED and show-details=when-authorized so sensitive values and"
                                + " health details are only revealed to authenticated, authorized users.",
                        "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.sanitization"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<String> findings = new ArrayList<>();
        for (String id : VALUE_ENDPOINTS) {
            String showValues = context.firstProperty("management.endpoint." + id + ".show-values");
            if ("always".equalsIgnoreCase(showValues) && ActuatorExposure.isReadable(context, id)) {
                findings.add("management.endpoint." + id + ".show-values=ALWAYS reveals full '" + id
                        + "' values to every caller; use WHEN_AUTHORIZED.");
            }
        }
        String showDetails = context.firstProperty("management.endpoint.health.show-details");
        if ("always".equalsIgnoreCase(showDetails) && ActuatorExposure.isReadable(context, "health")) {
            findings.add("management.endpoint.health.show-details=always exposes internal health probe details to"
                    + " every caller; use when-authorized.");
        }
        findings.sort(String::compareTo);
        return violation(findings);
    }
}

final class DangerousActuatorEndpointsAccessibleRule extends AbstractSpringRule {

    DangerousActuatorEndpointsAccessibleRule() {
        super(new SpringRuleDefinition(
                "SPRING-MGMT-004",
                "Do not web-expose shutdown or heapdump endpoints",
                SpringCategory.MANAGEMENT,
                "HIGH",
                "A high-impact Actuator endpoint is reachable over the web: the shutdown endpoint permits a"
                        + " remote caller to stop the application, and the heapdump endpoint streams a full heap"
                        + " dump that can contain credentials, tokens, and personal data.",
                "Keep shutdown disabled (its default access is 'none') and never web-expose it; exclude heapdump"
                        + " from the web exposure list or restrict it to an authenticated, firewalled management"
                        + " port.",
                "https://docs.spring.io/spring-boot/reference/actuator/endpoints.html"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        List<String> findings = new ArrayList<>();
        boolean shutdown = ActuatorExposure.shutdownAccessible(context);
        if (shutdown) {
            findings.add("The shutdown endpoint is web-exposed with write access, letting a remote caller stop"
                    + " the application.");
        }
        if (ActuatorExposure.heapdumpAccessible(context)) {
            findings.add("The heapdump endpoint is web-exposed and readable, streaming a full heap dump that can"
                    + " contain secrets and personal data.");
        }
        if (findings.isEmpty()) {
            return pass();
        }
        findings.sort(String::compareTo);
        // A remotely-triggerable shutdown on the public app port in production is critical.
        boolean criticalShutdown =
                shutdown && context.isProductionProfileActive() && context.managementOnApplicationPort();
        String severity = criticalShutdown ? SpringRuleSupport.CRITICAL : null;
        return violation(severity, findings);
    }
}

final class OpenSessionInViewEnabledRule extends AbstractSpringRule {

    OpenSessionInViewEnabledRule() {
        super(
                new SpringRuleDefinition(
                        "SPRING-JPA-001",
                        "Disable Open Session in View",
                        SpringCategory.PERSISTENCE,
                        "MEDIUM",
                        "Open Session in View keeps a JPA persistence context (and often its database connection) open"
                                + " for the whole web request, including view rendering. It hides lazy-loading boundaries,"
                                + " encourages N+1 queries, and holds connections longer under load. Spring Boot leaves it"
                                + " enabled by default and only logs a warning.",
                        "Set spring.jpa.open-in-view=false and load the associations each request needs explicitly (fetch"
                                + " joins, entity graphs, or DTO projections).",
                        "https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.open-entity-manager-in-view"));
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext context) {
        if (!context.entityManagerFactoryPresent() || !context.dispatcherServletPresent()) {
            return skipped("No JPA EntityManagerFactory and servlet web context, so Open Session in View does"
                    + " not apply.");
        }
        String value = context.firstProperty("spring.jpa.open-in-view");
        if (value == null) {
            String severity = context.isProductionProfileActive() ? SpringRuleSupport.HIGH : null;
            return violation(
                    severity,
                    "spring.jpa.open-in-view is not set and defaults to enabled, keeping a persistence context"
                            + " open for the entire web request.");
        }
        if ("true".equalsIgnoreCase(value)) {
            String severity = context.isProductionProfileActive() ? SpringRuleSupport.HIGH : null;
            return violation(
                    severity,
                    "spring.jpa.open-in-view=true keeps a persistence context open for the entire web request.");
        }
        return pass();
    }
}
