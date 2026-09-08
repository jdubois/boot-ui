package io.github.jdubois.bootui.autoconfigure.spring;

import static io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact.*;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.AsyncSelection;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleAssessment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.Bindable;

abstract class AbstractSpringRule implements SpringRule {
    static final String BOOT = "https://docs.spring.io/spring-boot/reference/";
    private final SpringRuleDefinition definition;

    AbstractSpringRule(
            String id,
            String name,
            SpringCategory category,
            String severity,
            String description,
            String recommendation,
            String reference) {
        this.definition = new SpringRuleDefinition(
                "SPRING-" + id,
                name,
                category,
                severity,
                description,
                recommendation,
                reference.startsWith("https:") ? reference : BOOT + reference);
    }

    @Override
    public final SpringRuleDefinition definition() {
        return definition;
    }

    abstract SpringRuleResultDto evaluateRule(SpringContext context);

    @Override
    public final SpringRuleResultDto evaluate(SpringContext context) {
        return evaluateAssessment(context).result();
    }

    @Override
    public final AdvisorRuleAssessment<SpringRuleResultDto> evaluateAssessment(SpringContext context) {
        try {
            return SpringRuleSupport.assessment(evaluateRule(context));
        } catch (RuntimeException | LinkageError ex) {
            // Exception messages, causes and property values can contain credentials.
            return SpringRuleSupport.assessment(
                    SpringRuleSupport.error(
                            definition,
                            "Required configuration or metadata could not be inspected safely; no runtime conclusion was made."));
        }
    }

    SpringRuleResultDto pass() {
        return SpringRuleSupport.pass(definition);
    }

    SpringRuleResultDto skipped(String reason) {
        return SpringRuleSupport.skipped(definition, reason);
    }

    SpringRuleResultDto unknown() {
        return SpringRuleSupport.unknown(definition);
    }

    SpringRuleResultDto violation(String detail) {
        return violation(List.of(detail));
    }

    SpringRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : SpringRuleSupport.violation(definition, details);
    }

    static String names(List<BeanRef> refs) {
        return refs.stream()
                .limit(10)
                .map(ref -> SpringRuleSupport.detail(ref.name()))
                .collect(Collectors.joining(", "));
    }

    SpringRuleResultDto candidates(List<BeanRef> refs, String subject) {
        if (refs.stream().anyMatch(ref -> !ref.metadataKnown())) return unknown();
        if (refs.size() > 1 && !SpringModel.hasResolvedCandidateMetadata(refs))
            return violation(subject + " default candidate metadata is unresolved: " + names(refs)
                    + ". Qualified/name-matched injection points may be intentional; execution is not established.");
        return pass();
    }

    SpringRuleResultDto webLink(SpringContext context, SpringRuleResultDto result) {
        return context.reactive() ? result.withLearnMoreUrl(BOOT + "web/reactive.html") : result;
    }
}

final class BeanDefinitionOverridingRule extends AbstractSpringRule {
    BeanDefinitionOverridingRule() {
        super(
                "WIRING-001",
                "Review bean definition overriding permission",
                SpringCategory.BEAN_WIRING,
                "MEDIUM",
                "The observed bean factory permits replacement of same-name definitions; this does not prove an override occurred.",
                "Keep overriding disabled unless replacement is deliberate. Review effective factory configuration, not only spring.main properties.",
                "features/spring-application.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        Boolean value = c.observations().get(OVERRIDING, Boolean.class);
        return value == null
                ? unknown()
                : value
                        ? violation(
                                "The current bean factory permits bean definition overriding; no actual replacement was inferred.")
                        : pass();
    }
}

final class CircularReferencesAllowedRule extends AbstractSpringRule {
    CircularReferencesAllowedRule() {
        super(
                "WIRING-002",
                "Review circular-reference permission",
                SpringCategory.BEAN_WIRING,
                "MEDIUM",
                "The current bean factory permits circular-reference resolution. This is not evidence of a dependency cycle.",
                "Prefer explicit acyclic dependencies and disable circular-reference permission unless deliberately required.",
                "features/spring-application.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        Boolean value = c.observations().get(CIRCULAR, Boolean.class);
        return value == null
                ? unknown()
                : value
                        ? violation(
                                "The current bean factory permits circular-reference resolution; no cycle was inferred.")
                        : pass();
    }
}

final class DuplicateObjectMapperRule extends AbstractSpringRule {
    DuplicateObjectMapperRule() {
        super(
                "WIRING-003",
                "Review default JSON mapper selection",
                SpringCategory.BEAN_WIRING,
                "INFO",
                "Checks Jackson 2 ObjectMapper and Jackson 3 JsonMapper groups independently for unresolved default candidate metadata.",
                "Review qualifiers, aliases, primary/default/fallback flags and intended mapper use. Multiple mappers can be legitimate.",
                "features/json.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.observations().incomplete().isEmpty()) return unknown();
        List<String> details = new ArrayList<>();
        for (List<BeanRef> group : c.objectMappers().stream()
                .collect(Collectors.groupingBy(BeanRef::group))
                .values()) {
            SpringRuleResultDto result = candidates(group, "Same-type JSON mapper");
            if ("SKIPPED".equals(result.status())) return unknown();
            details.addAll(result.sampleViolations());
        }
        return violation(details);
    }
}

final class AmbiguousTaskExecutorRule extends AbstractSpringRule {
    AmbiguousTaskExecutorRule() {
        super(
                "WIRING-004",
                "Review default async executor selection",
                SpringCategory.BEAN_WIRING,
                "INFO",
                "With async enabled, unresolved default TaskExecutor metadata is a review opportunity, not proof of failed injection.",
                "Review @Async qualifiers and AsyncConfigurer. Framework taskExecutor fallback and Boot's applicationTaskExecutor wrapper are distinct; naming alone does not resolve all consumers.",
                "features/task-execution-and-scheduling.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.asyncEnabled()) return skipped("Async annotation processing is not present.");
        AsyncSelection selection = c.observations().get(ASYNC_SELECTION, AsyncSelection.class);
        return selection == null
                ? unknown()
                : selection == AsyncSelection.AMBIGUOUS
                        ? violation(
                                "Default async candidate metadata is unresolved; explicit qualifiers/configurers and other consumers require separate review.")
                        : pass();
    }
}

final class AmbiguousDataSourceRule extends AbstractSpringRule {
    AmbiguousDataSourceRule() {
        super(
                "WIRING-005",
                "Review default DataSource selection",
                SpringCategory.BEAN_WIRING,
                "INFO",
                "Multiple DataSources are legal. This checks only default candidate metadata, not individual consumers.",
                "Review primary/default/fallback flags, qualifiers and name/alias matching at injection points.",
                "data/sql.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        return c.observations().incomplete().isEmpty() ? candidates(c.dataSources(), "DataSource") : unknown();
    }
}

final class AmbiguousTransactionManagerRule extends AbstractSpringRule {
    AmbiguousTransactionManagerRule() {
        super(
                "WIRING-006",
                "Review default imperative transaction manager",
                SpringCategory.BEAN_WIRING,
                "INFO",
                "Checks PlatformTransactionManager metadata only; reactive transaction execution and qualified consumers are outside this observation.",
                "Review @Transactional qualifiers and TransactionManagementConfigurer. The transactionManager name is not a universal Java-configuration override.",
                "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (c.transactionManagementConfigurerPresent()
                || !c.observations().incomplete().isEmpty()) return unknown();
        return candidates(c.transactionManagers(), "Imperative transaction manager");
    }
}

final class RestTemplateInUseRule extends AbstractSpringRule {
    RestTemplateInUseRule() {
        super(
                "WIRING-007",
                "Review RestTemplate migration",
                SpringCategory.BEAN_WIRING,
                "LOW",
                "A RestTemplate bean is declared, not necessarily used. Framework 7 deprecates RestTemplate in favor of RestClient.",
                "When migrating active call sites, inject Boot's RestClient.Builder to retain shared customizations. Keep dependency-required RestTemplate usage deliberate.",
                "io/rest-client.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        return c.restTemplates().isEmpty()
                ? c.observations().incomplete().isEmpty() ? pass() : unknown()
                : violation("Declared RestTemplate bean(s): " + names(c.restTemplates())
                        + "; review actual call sites before migration.");
    }
}

final class DefaultPackageComponentsRule extends AbstractSpringRule {
    DefaultPackageComponentsRule() {
        super(
                "WIRING-008",
                "Review default-package application beans",
                SpringCategory.BEAN_WIRING,
                "LOW",
                "Resolved application product types, including @Bean return types, are in the unnamed package. A POJO there does not itself trigger classpath-wide scanning.",
                "Prefer named application packages. Only a component-scan root in the default package implies classpath-wide scanning.",
                "using/structuring-your-code.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (c.defaultPackageBeans().isEmpty() && !c.observations().incomplete().isEmpty()) return unknown();
        return violation(c.defaultPackageBeans().stream()
                .limit(50)
                .map(name -> "Default-package application product: " + SpringRuleSupport.detail(name))
                .toList());
    }
}

final class MutableSingletonFieldRule extends AbstractSpringRule {
    MutableSingletonFieldRule() {
        super(
                "WIRING-009",
                "Review public mutable singleton fields",
                SpringCategory.BEAN_WIRING,
                "LOW",
                "Public mutable application fields are a shared-state review prompt, not proof of a data race. Injection, configuration binding, synthetic and Kotlin accessor-backed fields are excluded.",
                "Review ownership and synchronization of exposed state; encapsulate mutations where appropriate.",
                "https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        return c.mutableSingletonFields().isEmpty()
                        && !c.observations().incomplete().isEmpty()
                ? unknown()
                : violation(c.mutableSingletonFields());
    }
}

final class LazyInitializationDisabledRule extends AbstractSpringRule {
    LazyInitializationDisabledRule() {
        super(
                "CONFIG-001",
                "Consider lazy initialization for large contexts",
                SpringCategory.CONFIGURATION,
                "INFO",
                "More than 300 definitions is a noise-filtering heuristic, not a measured startup threshold. Definition metadata does not prove every bean was eagerly instantiated.",
                "If startup time matters, measure before evaluating lazy initialization. Preserve fail-fast behavior where needed; consider first-use latency and heap sizing.",
                "features/spring-application.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (c.bind("spring.main.lazy-initialization", Boolean.class) != null || c.beanDefinitionCount() <= 300)
            return pass();
        Integer lazy = c.observations().get(LAZY_DEFINITIONS, Integer.class);
        return lazy == null
                ? unknown()
                : c.beanDefinitionCount() - lazy <= 300
                        ? pass()
                        : violation(
                                "More than 300 bean definitions are not marked lazy; this does not prove eager instantiation. If startup time matters, measure before evaluating lazy initialization.");
    }
}

final class DebugOrTraceLoggingRule extends AbstractSpringRule {
    DebugOrTraceLoggingRule() {
        super(
                "CONFIG-002",
                "Review broad verbose logging configuration",
                SpringCategory.CONFIGURATION,
                "LOW",
                "Configured debug/trace intent may increase output and expose internals. It does not establish current runtime logger levels; debug=true affects selected loggers.",
                "Review the diagnostic need and configure targeted logger levels. Runtime logging changes require separate observation.",
                "features/logging.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        List<String> details = new ArrayList<>();
        for (String key : List.of("debug", "trace"))
            if (c.isPropertyTrue(key))
                details.add(
                        key + " is configured for selected diagnostic loggers, not every logger's effective level.");
        for (String logger : List.of("root", "web", "sql", "org.springframework", "org.hibernate")) {
            String value = c.firstProperty("logging.level." + logger);
            if (value == null) continue;
            String normalized = value.toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OFF", "ALL")
                    .contains(normalized)) throw new IllegalArgumentException();
            if (Set.of("TRACE", "DEBUG", "ALL").contains(normalized))
                details.add("Broad verbose logging is configured for " + logger
                        + "; current effective levels are not established.");
        }
        return violation(details);
    }
}

final class RemovedOrRenamedPropertyRule extends AbstractSpringRule {
    RemovedOrRenamedPropertyRule() {
        super(
                "CONFIG-003",
                "Review renamed or removed Boot 4 properties",
                SpringCategory.CONFIGURATION,
                "MEDIUM",
                "Source-verified legacy keys remain configured. The properties migrator can translate supported renames; presence does not prove the key has no effect.",
                "Migrate to current keys and test binding, including codec settings under spring.http.codecs. Remove settings for removed features.",
                "https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        List<String> details = new ArrayList<>();
        for (var entry : SpringMigrationProperties.ENTRIES.entrySet())
            if (c.hasProperty(entry.getKey())) details.add(entry.getKey() + " — " + entry.getValue() + ".");
        return violation(details);
    }
}

final class MissingApplicationNameRule extends AbstractSpringRule {
    MissingApplicationNameRule() {
        super(
                "CONFIG-004",
                "Consider a stable application name",
                SpringCategory.CONFIGURATION,
                "INFO",
                "Boot's application-name defaults are absent. Other logging, metrics or tracing identifiers may be configured independently.",
                "Set spring.application.name when Boot's shared identifier is useful for this application.",
                "features/spring-application.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        return c.firstProperty("spring.application.name") == null
                ? violation("spring.application.name is absent; Boot's application-name defaults are unavailable.")
                : pass();
    }
}

final class ConfigOnNotFoundIgnoreRule extends AbstractSpringRule {
    ConfigOnNotFoundIgnoreRule() {
        super(
                "CONFIG-005",
                "Review globally ignored missing configuration",
                SpringCategory.CONFIGURATION,
                "MEDIUM",
                "Explicit spring.config.on-not-found=ignore suppresses missing-config failures globally; no missing file is inferred.",
                "Prefer optional: on individual optional locations rather than globally suppressing missing configuration.",
                "features/external-config.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        return "ignore".equalsIgnoreCase(c.firstProperty("spring.config.on-not-found"))
                ? violation("Global on-not-found=ignore is configured; no actual missing location was observed.")
                : pass();
    }
}

final class Jackson2DefaultsCompatibilityRule extends AbstractSpringRule {
    Jackson2DefaultsCompatibilityRule() {
        super(
                "CONFIG-006",
                "Review Jackson 2-compatible defaults",
                SpringCategory.CONFIGURATION,
                "INFO",
                "Applicable Jackson 3 configuration requests Jackson 2-compatible defaults. This is not the Jackson 2 implementation or a deprecated compatibility setting.",
                "Document intent and add payload compatibility tests before changing defaults; no mandatory removal deadline is implied.",
                "features/json.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.isPropertyTrue("spring.jackson.use-jackson2-defaults")) return pass();
        return c.observations().yes(JACKSON3_CONFIGURATION)
                ? violation(
                        "Jackson 3 Boot configuration requests Jackson 2-compatible defaults; document intent and test payload compatibility before any change.")
                : unknown();
    }
}

final class DevToolsOnClasspathRule extends AbstractSpringRule {
    DevToolsOnClasspathRule() {
        super(
                "PROFILE-002",
                "Review DevTools packaging for production-like profiles",
                SpringCategory.PROFILES,
                "INFO",
                "DevTools classpath presence alongside production-like effective profile names is a packaging review heuristic, not evidence of active restart or LiveReload.",
                "Verify development-only packaging. Ordinary dev/local use is expected; profile names do not prove deployment.",
                "using/devtools.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        return c.devToolsPresent() && c.isProductionProfileActive()
                ? violation(
                        "DevTools is present with a production-like effective profile name (naming heuristic only); restart and LiveReload activity are not established.")
                : pass();
    }
}

final class ProfileValidationDisabledRule extends AbstractSpringRule {
    ProfileValidationDisabledRule() {
        super(
                "PROFILE-003",
                "Review disabled profile-name validation",
                SpringCategory.PROFILES,
                "INFO",
                "Disabling profile-name validation is a supported flexibility choice, not proof of invalid profiles.",
                "Document why unrestricted profile names are needed; retain validation when its naming constraints fit.",
                "features/profiles.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        return c.isPropertyFalse("spring.profiles.validate")
                ? violation("Profile-name validation is explicitly disabled; no invalid name was inferred.")
                : pass();
    }
}

final class VirtualThreadsAvailableRule extends AbstractSpringRule {
    VirtualThreadsAvailableRule() {
        super(
                "PERF-001",
                "Consider virtual threads for applicable blocking work",
                SpringCategory.PERFORMANCE,
                "INFO",
                "Java 21+ MVC or observed Boot task execution can offer a virtual-thread opportunity, not a guaranteed speedup. Pure reactive HTTP is not the target.",
                "Measure blocking workload suitability and downstream concurrency limits. CPU work does not become faster. Reactor boundedElastic virtual-thread mode is configured separately; JDK 24 removes synchronized-monitor pinning.",
                "features/task-execution-and-scheduling.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.virtualThreadsSupported()) return skipped("Virtual threads require Java 21+.");
        if (c.bind("spring.threads.virtual.enabled", Boolean.class) != null) return pass();
        if (!c.dispatcherServletPresent() && !c.bootApplicationTaskExecutorPresent())
            return skipped("No applicable MVC or Boot task-execution evidence; reactive HTTP alone is inapplicable.");
        return violation(
                "Virtual threads are available but not configured for applicable MVC/Boot task execution. Measure blocking workloads; this does not convert reactive event loops or boundedElastic.");
    }
}

final class VirtualThreadsOverriddenByPoolRule extends AbstractSpringRule {
    VirtualThreadsOverriddenByPoolRule() {
        super(
                "PERF-002",
                "Review pooled executor routing",
                SpringCategory.PERFORMANCE,
                "INFO",
                "A pooled executor coexists with enabled virtual-thread configuration. Actual routing and its thread factory may be unknown; a pool can itself use virtual threads.",
                "Review intended executor usage, CPU isolation and bounded concurrency before any change. Co-presence does not cancel virtual-thread benefits.",
                "features/task-execution-and-scheduling.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        return c.virtualThreadsSupported() && c.isVirtualThreadsEnabled() && c.pooledTaskExecutorPresent()
                ? violation(
                        "A ThreadPoolTaskExecutor coexists with virtual-thread configuration. Its routing and thread factory are not inferred; review intentional pooling.")
                : pass();
    }
}

final class AsyncWithoutCustomExecutorRule extends AbstractSpringRule {
    AsyncWithoutCustomExecutorRule() {
        super(
                "PERF-003",
                "Review Framework default async fallback",
                SpringCategory.PERFORMANCE,
                "LOW",
                "Positive default-selection metadata identifies Framework's SimpleAsyncTaskExecutor fallback, not an unreviewed Boot pool size.",
                "Choose deliberate concurrency and admission control for default @Async work. A virtual-thread property alone does not change Framework fallback.",
                "features/task-execution-and-scheduling.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.asyncEnabled()) return skipped("Async annotation processing is not present.");
        AsyncSelection selection = c.observations().get(ASYNC_SELECTION, AsyncSelection.class);
        return selection == null
                ? unknown()
                : selection == AsyncSelection.FRAMEWORK_FALLBACK
                        ? violation(
                                "Default @Async selection falls back to Framework's SimpleAsyncTaskExecutor, creating a new platform thread per task without a configured concurrency limit.")
                        : pass();
    }
}

final class SchedulerPoolTooSmallRule extends AbstractSpringRule {
    SchedulerPoolTooSmallRule() {
        super(
                "PERF-005",
                "Review single-thread scheduler overlap",
                SpringCategory.PERFORMANCE,
                "INFO",
                "Multiple registered application tasks share a positively identified one-thread scheduler. Overlap requirements, not task count alone, determine suitability.",
                "Review task duration and overlap requirements before changing concurrency. Preserve fixed-delay semantics; do not automatically increase the pool or enable virtual threads.",
                "features/task-execution-and-scheduling.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.schedulingEnabled()) return skipped("Scheduling is not enabled.");
        Integer tasks = c.observations().get(SCHEDULED_TASK_COUNT, Integer.class);
        if (tasks != null && tasks < 2) return pass();
        Integer size = c.observations().get(SCHEDULER_POOL_SIZE, Integer.class);
        return tasks == null || size == null
                ? unknown()
                : size == 1
                        ? violation(
                                "Multiple registered application tasks share an observed one-thread scheduler; review overlap requirements and fixed-delay semantics.")
                        : pass();
    }
}

final class UnboundedAsyncQueueRule extends AbstractSpringRule {
    UnboundedAsyncQueueRule() {
        super(
                "PERF-006",
                "Review an unbounded default async queue",
                SpringCategory.PERFORMANCE,
                "LOW",
                "The positively selected default @Async executor has an observed effectively unbounded queue, including explicit Integer.MAX_VALUE capacity.",
                "Review bounded queueing, rejection/backpressure and downstream admission control. Virtual threads are not admission control.",
                "features/task-execution-and-scheduling.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.asyncEnabled()) return skipped("Async annotation processing is not present.");
        Integer capacity = c.observations().get(ASYNC_QUEUE_CAPACITY, Integer.class);
        return capacity == null
                ? unknown()
                : capacity == Integer.MAX_VALUE
                        ? violation(
                                "The observed default @Async executor queue is effectively unbounded; backlog can increase heap use under sustained load.")
                        : pass();
    }
}

final class InMemoryCacheManagerRule extends AbstractSpringRule {
    InMemoryCacheManagerRule() {
        super(
                "CACHE-001",
                "Review concurrent-map cache capacity",
                SpringCategory.PERFORMANCE,
                "INFO",
                "An exact ConcurrentMapCacheManager has no built-in capacity or expiry policy. NoOp stores nothing; bounded in-process providers such as Caffeine are valid.",
                "Review cache growth and expiry requirements. A distributed provider is not universally required.",
                "io/caching.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.cachingEnabled()) return skipped("Caching annotation infrastructure is absent.");
        var details = c.cacheManagers().stream()
                .filter(m -> "org.springframework.cache.concurrent.ConcurrentMapCacheManager".equals(m.className()))
                .limit(50)
                .map(m -> "ConcurrentMapCacheManager has no built-in capacity/expiry policy: "
                        + SpringRuleSupport.detail(m.name()))
                .toList();
        if (!details.isEmpty()) return violation(details);
        if (c.cacheManagers().isEmpty()
                || c.cacheManagers().stream()
                        .anyMatch(m -> m.className() == null
                                || !Set.of(
                                                "org.springframework.cache.support.NoOpCacheManager",
                                                "org.springframework.cache.caffeine.CaffeineCacheManager")
                                        .contains(m.className()))) return unknown();
        return pass();
    }
}

final class ResponseCompressionDisabledRule extends AbstractSpringRule {
    ResponseCompressionDisabledRule() {
        super(
                "WEB-001",
                "Consider origin response compression",
                SpringCategory.WEB,
                "INFO",
                "Compression is not configured for an attributable Boot origin server; this does not establish whether client responses are compressed at the edge.",
                "If no proxy/CDN already compresses, measure compressible response sizes and CPU cost before evaluating origin compression.",
                "web/servlet.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        SpringRuleResultDto result = c.bind("server.compression.enabled", Boolean.class) != null
                ? pass()
                : c.observations().yes(BOOT_WEB_SERVER)
                        ? violation(
                                "Boot origin compression is not configured. If the edge does not compress, evaluate response sizes, bandwidth and CPU cost.")
                        : unknown();
        return webLink(c, result);
    }
}

final class GracefulShutdownDisabledRule extends AbstractSpringRule {
    GracefulShutdownDisabledRule() {
        super(
                "WEB-002",
                "Review immediate or zero-grace shutdown",
                SpringCategory.WEB,
                "MEDIUM",
                "Explicit immediate shutdown or exactly zero phase timeout can prevent graceful request completion on an applicable embedded web runtime. Boot 4 defaults to graceful.",
                "Review lifecycle requirements and allow positive shutdown time where graceful completion is required; no dropped request was observed.",
                "web/graceful-shutdown.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.observations().yes(BOOT_WEB_SERVER)) return unknown();
        String shutdown = c.firstProperty("server.shutdown");
        if (shutdown != null && !Set.of("immediate", "graceful").contains(shutdown.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException();
        Duration timeout = c.firstDurationProperty("spring.lifecycle.timeout-per-shutdown-phase");
        if (timeout != null && timeout.isNegative()) throw new IllegalArgumentException();
        return "immediate".equalsIgnoreCase(shutdown) || timeout != null && timeout.isZero()
                ? violation(
                        "Immediate shutdown or exactly zero phase grace is configured; in-flight work may not have time to finish.")
                : pass();
    }
}

final class Http2DisabledRule extends AbstractSpringRule {
    Http2DisabledRule() {
        super(
                "WEB-003",
                "Consider origin HTTP/2",
                SpringCategory.WEB,
                "INFO",
                "HTTP/2 is not configured on an attributable Boot origin server; client-facing protocol negotiation at a proxy is not observed.",
                "Review edge termination, clients, h2/h2c support and measurements before enabling origin HTTP/2. It is optional, not a universal TLS requirement.",
                "web/servlet.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        SpringRuleResultDto result = c.bind("server.http2.enabled", Boolean.class) != null
                ? pass()
                : c.observations().yes(BOOT_WEB_SERVER)
                        ? violation(
                                "Boot origin HTTP/2 is not configured; edge HTTP/2 may already satisfy client requirements.")
                        : unknown();
        return webLink(c, result);
    }
}

final class ErrorDetailsExposedRule extends AbstractSpringRule {
    ErrorDetailsExposedRule() {
        super(
                "WEB-004",
                "Review configured fallback error details",
                SpringCategory.WEB,
                "MEDIUM",
                "Boot fallback error configuration permits exception details. Custom handling can differ; DevTools defaults may be deliberate. on-param is caller-controlled, not confidentiality protection.",
                "Use never/false where details must remain private. Review custom handling and development defaults separately; do not use on-param as access control.",
                "web/servlet.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.observations().yes(BOOT_ERROR_HANDLING)) return webLink(c, unknown());
        List<String> details = new ArrayList<>();
        if (c.isPropertyTrue("spring.web.error.include-exception"))
            details.add("Boot fallback error configuration includes exception types; custom responses may differ.");
        for (String key : List.of("include-stacktrace", "include-message", "include-binding-errors")) {
            String value = c.firstProperty("spring.web.error." + key);
            if (value == null) continue;
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("never", "always", "on-param").contains(normalized)) throw new IllegalArgumentException();
            if (!normalized.equals("never"))
                details.add(
                        "spring.web.error." + key
                                + " allows fallback error details; on-param is not an access-control boundary. DevTools defaults may be deliberate.");
        }
        return webLink(c, violation(details));
    }
}

final class HttpClientTimeoutsUnsetRule extends AbstractSpringRule {
    HttpClientTimeoutsUnsetRule() {
        super(
                "WEB-005",
                "Review Boot HTTP client timeout policy",
                SpringCategory.WEB,
                "INFO",
                "Attributable Boot builder defaults or named service-group configuration lacks a complete timeout policy. Programmatic per-client values and transport defaults are not established.",
                "Review effective per-client deadlines and overrides. One complete group does not establish settings for other groups or arbitrary client beans.",
                "io/rest-client.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.observations().yes(HTTP_CLIENT_DEFAULTS) && !c.observations().yes(HTTP_SERVICE_GROUPS)) return unknown();
        Duration connect = c.bind("spring.http.clients.connect-timeout", Duration.class);
        Duration read = c.bind("spring.http.clients.read-timeout", Duration.class);
        Map<String, Map<String, Object>> groups = SpringProperties.bind(
                c.environment(),
                false,
                "spring.http.serviceclient",
                Bindable.of(org.springframework.core.ResolvableType.forType(
                        new org.springframework.core.ParameterizedTypeReference<
                                Map<String, Map<String, Object>>>() {})));
        List<String> details = new ArrayList<>();
        if (c.observations().yes(HTTP_CLIENT_DEFAULTS) && (connect == null || read == null))
            details.add(
                    "Boot global builder defaults lack a complete connect/read timeout policy; effective per-client deadlines are not established.");
        if (c.observations().yes(HTTP_SERVICE_GROUPS) && groups != null) {
            if (groups.size() > 100) throw new IllegalArgumentException();
            for (var group : groups.entrySet()) {
                if (group.getKey().length() > 256 || group.getValue().size() > 100)
                    throw new IllegalArgumentException();
                String prefix = "spring.http.serviceclient.[" + group.getKey() + "].";
                Duration groupConnect = c.bind(prefix + "connect-timeout", Duration.class);
                Duration groupRead = c.bind(prefix + "read-timeout", Duration.class);
                if ((groupConnect == null && connect == null) || (groupRead == null && read == null))
                    details.add(
                            "A named Boot service group lacks a complete connect/read timeout policy; review its effective per-client deadlines.");
            }
        }
        return violation(details);
    }
}

final class RedundantTomcatThreadsRule extends AbstractSpringRule {
    RedundantTomcatThreadsRule() {
        super(
                "WEB-007",
                "Review a Tomcat cap with a virtual executor",
                SpringCategory.WEB,
                "LOW",
                "Requires observed Boot-managed Tomcat virtual executor customization, Java virtual-thread support and an explicit thread cap. Factory type plus a property is insufficient.",
                "When the observed virtual executor handles requests, review the redundant thread cap and enforce admission control independently.",
                "web/servlet.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.virtualThreadsSupported() || !c.tomcatWebServerPresent())
            return skipped("Applicable Java/Tomcat support is absent.");
        Integer cap = c.firstIntegerProperty("server.tomcat.threads.max");
        if (cap == null) return pass();
        return c.observations().yes(TOMCAT_VIRTUAL_EXECUTOR)
                ? violation(
                        "A Boot-managed Tomcat virtual executor is observed alongside an explicit thread cap; review the cap's applicability.")
                : unknown();
    }
}

final class OpenSessionInViewEnabledRule extends AbstractSpringRule {
    OpenSessionInViewEnabledRule() {
        super(
                "JPA-001",
                "Review servlet Open Session in View",
                SpringCategory.PERSISTENCE,
                "MEDIUM",
                "Observed servlet OSIV registration extends a persistence context across request handling. This does not prove a held JDBC connection or N+1 queries.",
                "Review explicit fetching through joins, entity graphs or DTOs. For Boot registration consider spring.jpa.open-in-view=false; custom registrations need separate changes.",
                "data/sql.html#data.sql.jpa-and-spring-data.open-entity-manager-in-view");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (c.reactive()) return skipped("Servlet Open Session in View does not apply to WebFlux.");
        String evidence = c.observations().get(OSIV, String.class);
        return evidence == null
                ? unknown()
                : violation(
                        "Observed " + evidence
                                + "; review persistence-context boundaries and explicit fetching, not inferred connection lifetime.");
    }
}

final class InMemoryDatasourceInProductionRule extends AbstractSpringRule {
    InMemoryDatasourceInProductionRule() {
        super(
                "DATA-001",
                "Review in-memory JDBC with production-like profiles",
                SpringCategory.PERSISTENCE,
                "MEDIUM",
                "An observed supported DataSource URL identifies memory storage with a production-like effective profile name. Profile naming is a heuristic, not proof of deployment.",
                "Review durability requirements and effective connection configuration. URLs and credentials are never displayed.",
                "data/sql.html#data.sql.datasource.embedded");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.isProductionProfileActive())
            return skipped("No production-like effective profile name; deployment purpose is not inferred.");
        String kind = c.observations().get(JDBC_KIND, String.class);
        return kind == null
                ? unknown()
                : kind.endsWith(" memory")
                        ? violation(
                                "Observed supported JDBC memory storage with a production-like effective profile name (naming heuristic); review durability. URL omitted.")
                        : pass();
    }
}

final class InMemoryR2dbcInProductionRule extends AbstractSpringRule {
    InMemoryR2dbcInProductionRule() {
        super(
                "DATA-002",
                "Review in-memory R2DBC with production-like profiles",
                SpringCategory.PERSISTENCE,
                "MEDIUM",
                "Supported, attributable Boot R2DBC memory configuration accompanies production-like effective profile names. Custom connection details and inactive properties are not runtime evidence.",
                "Review durability and effective provider configuration; URLs and credentials are omitted.",
                "data/sql.html#data.sql.r2dbc.embedded");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.isProductionProfileActive())
            return skipped("No production-like effective profile name; deployment purpose is not inferred.");
        String kind = c.observations().get(R2DBC_KIND, String.class);
        if (kind == null) return unknown();
        return kind.equals("H2 memory")
                ? violation(
                        "Supported Boot R2DBC configuration requests H2 memory storage with a production-like effective profile name (naming heuristic). URL omitted.")
                : pass();
    }
}

final class ActuatorExposeAllRule extends AbstractSpringRule {
    ActuatorExposeAllRule() {
        super(
                "MGMT-001",
                "Review host wildcard Actuator exposure",
                SpringCategory.MANAGEMENT,
                "MEDIUM",
                "Host wildcard exposure includes applicable endpoints allowed by access policy. Exclusions and access suppression apply; BootUI contributions are ignored.",
                "Prefer an intentional endpoint allowlist. Port equality and profile names do not establish public reachability or authorization.",
                "actuator/endpoints.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!ActuatorExposure.applicable(c)) return unknown();
        return ActuatorExposure.exposesAll(c) && ActuatorExposure.anyAccessible(c)
                ? violation(
                        "Host wildcard web exposure permits applicable Actuator endpoints after exclusions/access policy; network reachability and authorization are not established.")
                : pass();
    }
}

final class SensitiveActuatorEndpointsExposedRule extends AbstractSpringRule {
    SensitiveActuatorEndpointsExposedRule() {
        super(
                "MGMT-002",
                "Review explicitly exposed sensitive Actuator endpoints",
                SpringCategory.MANAGEMENT,
                "MEDIUM",
                "Explicit host exposure and access settings allow known sensitive endpoint reads. Optional absent endpoints are not inferred.",
                "Review exposure and authorization separately. Diagnostic details are available only to callers allowed to access the endpoint.",
                "actuator/endpoints.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!ActuatorExposure.applicable(c)) return unknown();
        if (ActuatorExposure.exposesAll(c)) return pass();
        List<String> details = new ArrayList<>();
        for (String id : ActuatorExposure.SENSITIVE_READ_ENDPOINTS)
            if (ActuatorExposure.includeTokens(c).contains(id) && ActuatorExposure.isReadable(c, id))
                details.add("Known endpoint '" + id
                        + "' is explicitly web-exposed with read access; caller authorization is not assessed.");
        details.sort(String::compareTo);
        return violation(details);
    }
}

final class ActuatorShowValuesAlwaysRule extends AbstractSpringRule {
    ActuatorShowValuesAlwaysRule() {
        super(
                "MGMT-003",
                "Review Actuator values and health details",
                SpringCategory.MANAGEMENT,
                "MEDIUM",
                "Host show-values=always may disclose raw configuration values to callers allowed to access the endpoint. Health details describe probes, not raw config values.",
                "Use when-authorized where appropriate and separately secure endpoint access. BootUI-contributed defaults are ignored.",
                "actuator/endpoints.html#actuator.endpoints.sanitization");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!ActuatorExposure.applicable(c)) return unknown();
        List<String> details = new ArrayList<>();
        for (String id : List.of("env", "configprops", "health")) {
            String key = "management.endpoint." + id + (id.equals("health") ? ".show-details" : ".show-values");
            String value = c.firstHostProperty(key);
            if (value == null) continue;
            String normalized = value.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            if (!Set.of("never", "always", "when-authorized").contains(normalized))
                throw new IllegalArgumentException();
            if (normalized.equals("always") && ActuatorExposure.isReadable(c, id))
                details.add(key + "=always permits " + (id.equals("health") ? "probe details" : "configuration values")
                        + " for callers allowed to access this endpoint.");
        }
        return violation(details);
    }
}

final class DangerousActuatorEndpointsAccessibleRule extends AbstractSpringRule {
    DangerousActuatorEndpointsAccessibleRule() {
        super(
                "MGMT-004",
                "Review granted heapdump or shutdown web access",
                SpringCategory.MANAGEMENT,
                "HIGH",
                "Boot 4.1.1 heapdump and shutdown both default to access=none. A finding requires known endpoint evidence, exposure and effective read/write permission.",
                "Keep dangerous endpoints inaccessible unless explicitly needed and protect authorized access. No network reachability is inferred.",
                "actuator/endpoints.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!ActuatorExposure.applicable(c)) return unknown();
        List<String> details = new ArrayList<>();
        if (ActuatorExposure.shutdownAccessible(c))
            details.add(
                    "Known shutdown endpoint has web exposure and effective write permission; authorized operation can stop the application.");
        if (ActuatorExposure.heapdumpAccessible(c))
            details.add(
                    "Known heapdump endpoint has web exposure and effective read permission; heap dumps can contain secrets.");
        return violation(details);
    }
}

final class ReactiveHandlerWithBlockingDatasourceRule extends AbstractSpringRule {
    ReactiveHandlerWithBlockingDatasourceRule() {
        super(
                "REACTIVE-001",
                "Review reactive handlers alongside JDBC",
                SpringCategory.REACTIVE,
                "INFO",
                "Application Mono/Flux handlers coexist with a blocking DataSource. This cannot establish blocking inside handlers; offloaded or migration-only JDBC can be intentional.",
                "Review actual JDBC call sites and offloading where used. No per-handler defect or mandatory driver migration is inferred.",
                "https://docs.spring.io/spring-framework/reference/web/webflux/reactive-spring.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.reactive()) return skipped("This is not a WebFlux application.");
        if (!c.observations().incomplete().isEmpty()) return unknown();
        return c.reactiveHandlerMethodCount() > 0 && !c.dataSources().isEmpty()
                ? violation(
                        "Application reactive handlers coexist with JDBC DataSource metadata; review actual call sites. Correct offloading and migration-only usage are not defects.")
                : pass();
    }
}

final class UnlimitedCodecAggregationRule extends AbstractSpringRule {
    UnlimitedCodecAggregationRule() {
        super(
                "REACTIVE-003",
                "Review explicitly unlimited codec aggregation",
                SpringCategory.REACTIVE,
                "LOW",
                "Observed Boot codec configuration explicitly requests unlimited (-1) aggregation on WebFlux. Unset and positive limits are not violations.",
                "Review bounded aggregation for expected payloads. No universal byte threshold is prescribed; codec aggregation is not a universal body/upload size limit.",
                "web/reactive.html");
    }

    @Override
    SpringRuleResultDto evaluateRule(SpringContext c) {
        if (!c.reactive()) return skipped("This is not a WebFlux application.");
        // Validate the current namespace without confusing it with the startup-bound observation.
        c.bind("spring.http.codecs.max-in-memory-size", org.springframework.util.unit.DataSize.class);
        if (!c.observations().yes(BOOT_CODEC_CONFIGURATION)) return unknown();
        Long limit = c.observations().get(CODEC_LIMIT, Long.class);
        return limit != null && limit == -1
                ? violation(
                        "Observed Boot codec metadata requests explicitly unlimited aggregation (-1); review bounded aggregation, not a universal HTTP body/upload limit.")
                : pass();
    }
}
