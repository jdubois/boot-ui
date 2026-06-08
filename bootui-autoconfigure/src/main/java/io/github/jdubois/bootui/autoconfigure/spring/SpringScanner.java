package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.CacheManagerRef;
import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.core.dto.SpringScanStatusDto;
import io.github.jdubois.bootui.core.dto.SpringSeverityCountDto;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Bounded, on-demand Spring Advisor.
 *
 * <p>The scanner takes a read-only snapshot of the running application context (selected bean groups
 * and feature flags) plus the {@link Environment}, and evaluates a curated registry of static
 * best-practice checks. It never mutates the context, intercepts requests, or surfaces secrets.</p>
 */
final class SpringScanner {

    private static final String ANALYZER = "BootUI Spring Advisor";
    private static final String DISCLAIMER =
            "Heuristic Spring rules run against the running application context and environment only. "
                    + "These checks are review prompts, not verdicts, and should be validated against the "
                    + "application's own requirements.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    private static final String OBJECT_MAPPER_TYPE = "com.fasterxml.jackson.databind.ObjectMapper";
    private static final String JACKSON3_OBJECT_MAPPER_TYPE = "tools.jackson.databind.ObjectMapper";
    private static final String TASK_EXECUTOR_TYPE = "org.springframework.core.task.TaskExecutor";
    private static final String POOLED_TASK_EXECUTOR_TYPE =
            "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
    private static final String DATA_SOURCE_TYPE = "javax.sql.DataSource";
    private static final String HIKARI_DATA_SOURCE_TYPE = "com.zaxxer.hikari.HikariDataSource";
    private static final String ASYNC_CONFIGURER_TYPE = "org.springframework.scheduling.annotation.AsyncConfigurer";
    private static final String TRANSACTION_MANAGER_TYPE = "org.springframework.transaction.PlatformTransactionManager";
    private static final String TRANSACTION_MANAGEMENT_CONFIGURER_TYPE =
            "org.springframework.transaction.annotation.TransactionManagementConfigurer";
    private static final String REST_TEMPLATE_TYPE = "org.springframework.web.client.RestTemplate";
    private static final String REST_CLIENT_TYPE = "org.springframework.web.client.RestClient";
    private static final String CACHE_MANAGER_TYPE = "org.springframework.cache.CacheManager";
    private static final String ASYNC_PROCESSOR_BEAN =
            "org.springframework.context.annotation.internalAsyncAnnotationProcessor";
    private static final String CACHE_ADVISOR_BEAN = "org.springframework.cache.config.internalCacheAdvisor";
    private static final String SCHEDULED_PROCESSOR_BEAN =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
    private static final String DEVTOOLS_MARKER = "org.springframework.boot.devtools.restart.Restarter";

    /** Hard cap on the number of default-package bean names collected, to keep the scan bounded. */
    private static final int MAX_DEFAULT_PACKAGE_BEANS = 50;

    private static final Comparator<SpringRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (SpringRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(
                    Comparator.comparingInt(SpringRuleResultDto::violationCount).reversed())
            .thenComparing(SpringRuleResultDto::id);

    private final Supplier<SpringContext> contextSupplier;
    private final Clock clock;

    SpringScanner(ConfigurableListableBeanFactory beanFactory, Environment environment, Clock clock) {
        this(() -> discover(beanFactory, environment), clock);
    }

    SpringScanner(SpringContext context, Clock clock) {
        this(() -> context, clock);
    }

    private SpringScanner(Supplier<SpringContext> contextSupplier, Clock clock) {
        this.contextSupplier = contextSupplier;
        this.clock = clock;
    }

    SpringReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Spring Advisor has not run yet. Click Run Spring checks to inspect the application context.",
                null,
                List.of(),
                0,
                0,
                List.of());
    }

    SpringReport scan() {
        SpringContext context;
        try {
            context = contextSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return report(
                    "DISABLED",
                    "The application context could not be inspected: " + safeMessage(ex),
                    clock.millis(),
                    List.of(),
                    0,
                    0,
                    List.of());
        }
        if (context == null) {
            return report(
                    "DISABLED",
                    "No application context was available to inspect.",
                    clock.millis(),
                    List.of(),
                    0,
                    0,
                    List.of());
        }

        List<SpringRuleResultDto> results = SpringRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        List<String> inspected = describe(context);
        String message = "Spring Advisor completed against " + context.beanDefinitionCount() + " bean definition(s).";
        return report(
                "SCANNED", message, clock.millis(), inspected, context.beanDefinitionCount(), results.size(), results);
    }

    private SpringReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> inspected,
            int componentsAnalyzed,
            int rulesEvaluated,
            List<SpringRuleResultDto> results) {
        List<SpringRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        SpringScanStatusDto scan = new SpringScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, componentsAnalyzed, violationsFound);
        return new SpringReport(
                true,
                DISCLAIMER,
                inspected,
                componentsAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations,
                analysisErrors(results));
    }

    SpringReport applyDismissals(SpringReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<SpringRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<SpringRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        SpringScanStatusDto scan = report.scan();
        SpringScanStatusDto updatedScan = new SpringScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.componentsAnalyzed(),
                violationsFound);
        return new SpringReport(
                report.localOnly(),
                report.disclaimer(),
                report.inspected(),
                report.componentsAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked,
                report.analysisErrors());
    }

    static List<SpringRuleResultDto> analysisErrors(List<SpringRuleResultDto> results) {
        return results.stream()
                .filter(result -> SpringRuleSupport.ERROR.equals(result.status()))
                .sorted(Comparator.comparing(SpringRuleResultDto::id))
                .toList();
    }

    private static List<String> describe(SpringContext context) {
        List<String> inspected = new ArrayList<>();
        String[] profiles = context.activeProfiles();
        inspected.add("Active profiles: " + (profiles.length == 0 ? "none" : String.join(", ", profiles)));
        inspected.add("Bean definitions: " + context.beanDefinitionCount());
        inspected.add("Virtual threads: "
                + (!context.virtualThreadsSupported()
                        ? "unsupported"
                        : context.isVirtualThreadsEnabled() ? "enabled" : "available but disabled"));
        return List.copyOf(inspected);
    }

    private List<SpringSeverityCountDto> severityCounts(List<SpringRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (SpringRuleResultDto result : results) {
            counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new SpringSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<SpringRuleResultDto> violationResults(List<SpringRuleResultDto> results) {
        return results.stream()
                .filter(result -> SpringRuleSupport.VIOLATION.equals(result.status()))
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    // ── Discovery ──────────────────────────────────────────────────────────────

    private static SpringContext discover(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        ClassLoader classLoader = SpringScanner.class.getClassLoader();
        List<BeanRef> objectMappers = unionBeans(
                beansOfType(beanFactory, OBJECT_MAPPER_TYPE, classLoader),
                beansOfType(beanFactory, JACKSON3_OBJECT_MAPPER_TYPE, classLoader));
        List<BeanRef> taskExecutors = beansOfType(beanFactory, TASK_EXECUTOR_TYPE, classLoader);
        List<BeanRef> dataSources = beansOfType(beanFactory, DATA_SOURCE_TYPE, classLoader);
        boolean pooledExecutor = !beansOfType(beanFactory, POOLED_TASK_EXECUTOR_TYPE, classLoader)
                .isEmpty();
        boolean hikariPresent = ClassUtils.isPresent(HIKARI_DATA_SOURCE_TYPE, classLoader)
                && !beansOfType(beanFactory, HIKARI_DATA_SOURCE_TYPE, classLoader)
                        .isEmpty();
        boolean asyncEnabled = beanFactory != null && beanFactory.containsBeanDefinition(ASYNC_PROCESSOR_BEAN);
        boolean devToolsPresent = ClassUtils.isPresent(DEVTOOLS_MARKER, classLoader);
        int beanCount = beanFactory != null ? beanFactory.getBeanDefinitionCount() : 0;

        boolean asyncConfigurerPresent =
                !beansOfType(beanFactory, ASYNC_CONFIGURER_TYPE, classLoader).isEmpty();
        List<BeanRef> transactionManagers = beansOfType(beanFactory, TRANSACTION_MANAGER_TYPE, classLoader);
        boolean transactionManagementConfigurerPresent = !beansOfType(
                        beanFactory, TRANSACTION_MANAGEMENT_CONFIGURER_TYPE, classLoader)
                .isEmpty();
        List<BeanRef> restTemplates = beansOfType(beanFactory, REST_TEMPLATE_TYPE, classLoader);
        boolean restClientBeanPresent =
                !beansOfType(beanFactory, REST_CLIENT_TYPE, classLoader).isEmpty();
        boolean cachingEnabled = beanFactory != null && beanFactory.containsBeanDefinition(CACHE_ADVISOR_BEAN);
        List<CacheManagerRef> cacheManagers = cacheManagers(beanFactory, classLoader);
        boolean schedulingEnabled = beanFactory != null && beanFactory.containsBeanDefinition(SCHEDULED_PROCESSOR_BEAN);
        List<String> defaultPackageBeans = defaultPackageBeans(beanFactory);

        return SpringContext.builder(environment)
                .virtualThreadsSupported(virtualThreadsSupported())
                .beanDefinitionCount(beanCount)
                .objectMappers(objectMappers)
                .taskExecutors(taskExecutors)
                .dataSources(dataSources)
                .pooledTaskExecutorPresent(pooledExecutor)
                .asyncEnabled(asyncEnabled)
                .devToolsPresent(devToolsPresent)
                .hikariDataSourcePresent(hikariPresent)
                .asyncConfigurerPresent(asyncConfigurerPresent)
                .transactionManagers(transactionManagers)
                .transactionManagementConfigurerPresent(transactionManagementConfigurerPresent)
                .restTemplates(restTemplates)
                .restClientBeanPresent(restClientBeanPresent)
                .cachingEnabled(cachingEnabled)
                .cacheManagers(cacheManagers)
                .schedulingEnabled(schedulingEnabled)
                .defaultPackageBeans(defaultPackageBeans)
                .build();
    }

    /** Merges two bean lists, de-duplicating by bean name and preserving first-seen order. */
    private static List<BeanRef> unionBeans(List<BeanRef> first, List<BeanRef> second) {
        Map<String, BeanRef> byName = new LinkedHashMap<>();
        for (BeanRef ref : first) {
            byName.putIfAbsent(ref.name(), ref);
        }
        for (BeanRef ref : second) {
            byName.putIfAbsent(ref.name(), ref);
        }
        return List.copyOf(byName.values());
    }

    private static List<CacheManagerRef> cacheManagers(
            ConfigurableListableBeanFactory beanFactory, ClassLoader classLoader) {
        if (beanFactory == null || !ClassUtils.isPresent(CACHE_MANAGER_TYPE, classLoader)) {
            return List.of();
        }
        Class<?> type;
        try {
            type = ClassUtils.forName(CACHE_MANAGER_TYPE, classLoader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return List.of();
        }
        String[] names;
        try {
            names = beanFactory.getBeanNamesForType(type, true, false);
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
        List<CacheManagerRef> refs = new ArrayList<>();
        for (String name : names) {
            refs.add(new CacheManagerRef(name, cacheManagerClassName(beanFactory, name)));
        }
        return refs;
    }

    private static String cacheManagerClassName(ConfigurableListableBeanFactory beanFactory, String name) {
        try {
            Class<?> resolved = beanFactory.getType(name, false);
            return resolved != null ? resolved.getName() : null;
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /** Collects application beans whose resolved class lives in the default (unnamed) package. */
    private static List<String> defaultPackageBeans(ConfigurableListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return List.of();
        }
        String[] names;
        try {
            names = beanFactory.getBeanDefinitionNames();
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
        List<String> defaultPackage = new ArrayList<>();
        for (String name : names) {
            if (defaultPackage.size() >= MAX_DEFAULT_PACKAGE_BEANS) {
                break;
            }
            try {
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                if (definition.getRole() != BeanDefinition.ROLE_APPLICATION) {
                    continue;
                }
                String className = definition.getBeanClassName();
                if (className != null && className.indexOf('.') < 0) {
                    defaultPackage.add(name);
                }
            } catch (RuntimeException ex) {
                // Ignore beans whose definition cannot be inspected.
            }
        }
        return List.copyOf(defaultPackage);
    }

    private static List<BeanRef> beansOfType(
            ConfigurableListableBeanFactory beanFactory, String typeName, ClassLoader classLoader) {
        if (beanFactory == null || !ClassUtils.isPresent(typeName, classLoader)) {
            return List.of();
        }
        Class<?> type;
        try {
            type = ClassUtils.forName(typeName, classLoader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return List.of();
        }
        String[] names;
        try {
            names = beanFactory.getBeanNamesForType(type, true, false);
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
        List<BeanRef> refs = new ArrayList<>();
        for (String name : names) {
            refs.add(new BeanRef(name, isPrimary(beanFactory, name)));
        }
        return refs;
    }

    private static boolean isPrimary(ConfigurableListableBeanFactory beanFactory, String name) {
        try {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            return definition.isPrimary();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean virtualThreadsSupported() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }
}
