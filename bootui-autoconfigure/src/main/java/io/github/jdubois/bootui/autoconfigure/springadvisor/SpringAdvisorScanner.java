package io.github.jdubois.bootui.autoconfigure.springadvisor;

import io.github.jdubois.bootui.autoconfigure.springadvisor.SpringAdvisorModel.BeanRef;
import io.github.jdubois.bootui.core.dto.SpringAdvisorReport;
import io.github.jdubois.bootui.core.dto.SpringAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.SpringAdvisorScanStatusDto;
import io.github.jdubois.bootui.core.dto.SpringAdvisorSeverityCountDto;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
final class SpringAdvisorScanner {

    private static final String ANALYZER = "BootUI Spring Advisor";
    private static final String DISCLAIMER =
            "Heuristic Spring rules run against the running application context and environment only. "
                    + "These checks are review prompts, not verdicts, and should be validated against the "
                    + "application's own requirements.";
    private static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW", "INFO");

    private static final String OBJECT_MAPPER_TYPE = "com.fasterxml.jackson.databind.ObjectMapper";
    private static final String TASK_EXECUTOR_TYPE = "org.springframework.core.task.TaskExecutor";
    private static final String POOLED_TASK_EXECUTOR_TYPE =
            "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
    private static final String DATA_SOURCE_TYPE = "javax.sql.DataSource";
    private static final String HIKARI_DATA_SOURCE_TYPE = "com.zaxxer.hikari.HikariDataSource";
    private static final String ASYNC_PROCESSOR_BEAN =
            "org.springframework.context.annotation.internalAsyncAnnotationProcessor";
    private static final String DEVTOOLS_MARKER = "org.springframework.boot.devtools.restart.Restarter";

    private static final Comparator<SpringAdvisorRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (SpringAdvisorRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(Comparator.comparingInt(SpringAdvisorRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(SpringAdvisorRuleResultDto::id);

    private final Supplier<SpringAdvisorContext> contextSupplier;
    private final Clock clock;

    SpringAdvisorScanner(ConfigurableListableBeanFactory beanFactory, Environment environment, Clock clock) {
        this(() -> discover(beanFactory, environment), clock);
    }

    SpringAdvisorScanner(SpringAdvisorContext context, Clock clock) {
        this(() -> context, clock);
    }

    private SpringAdvisorScanner(Supplier<SpringAdvisorContext> contextSupplier, Clock clock) {
        this.contextSupplier = contextSupplier;
        this.clock = clock;
    }

    SpringAdvisorReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Spring Advisor has not run yet. Click Run Spring checks to inspect the application context.",
                null,
                List.of(),
                0,
                0,
                List.of());
    }

    SpringAdvisorReport scan() {
        SpringAdvisorContext context;
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

        List<SpringAdvisorRuleResultDto> results = SpringAdvisorRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        List<String> inspected = describe(context);
        String message = "Spring Advisor completed against " + context.beanDefinitionCount() + " bean definition(s).";
        return report(
                "SCANNED",
                message,
                clock.millis(),
                inspected,
                context.beanDefinitionCount(),
                results.size(),
                results);
    }

    private SpringAdvisorReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> inspected,
            int componentsAnalyzed,
            int rulesEvaluated,
            List<SpringAdvisorRuleResultDto> results) {
        List<SpringAdvisorRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        SpringAdvisorScanStatusDto scan = new SpringAdvisorScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, componentsAnalyzed, violationsFound);
        return new SpringAdvisorReport(
                true,
                DISCLAIMER,
                inspected,
                componentsAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations);
    }

    private static List<String> describe(SpringAdvisorContext context) {
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

    private List<SpringAdvisorSeverityCountDto> severityCounts(List<SpringAdvisorRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (SpringAdvisorRuleResultDto result : results) {
            counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new SpringAdvisorSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<SpringAdvisorRuleResultDto> violationResults(List<SpringAdvisorRuleResultDto> results) {
        return results.stream()
                .filter(result -> SpringAdvisorRuleSupport.VIOLATION.equals(result.status()))
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

    private static SpringAdvisorContext discover(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        ClassLoader classLoader = SpringAdvisorScanner.class.getClassLoader();
        List<BeanRef> objectMappers = beansOfType(beanFactory, OBJECT_MAPPER_TYPE, classLoader);
        List<BeanRef> taskExecutors = beansOfType(beanFactory, TASK_EXECUTOR_TYPE, classLoader);
        List<BeanRef> dataSources = beansOfType(beanFactory, DATA_SOURCE_TYPE, classLoader);
        boolean pooledExecutor = !beansOfType(beanFactory, POOLED_TASK_EXECUTOR_TYPE, classLoader).isEmpty();
        boolean hikariPresent = ClassUtils.isPresent(HIKARI_DATA_SOURCE_TYPE, classLoader)
                && !beansOfType(beanFactory, HIKARI_DATA_SOURCE_TYPE, classLoader).isEmpty();
        boolean asyncEnabled = beanFactory != null && beanFactory.containsBeanDefinition(ASYNC_PROCESSOR_BEAN);
        boolean devToolsPresent = ClassUtils.isPresent(DEVTOOLS_MARKER, classLoader);
        int beanCount = beanFactory != null ? beanFactory.getBeanDefinitionCount() : 0;

        return new SpringAdvisorContext(
                environment,
                virtualThreadsSupported(),
                beanCount,
                objectMappers,
                taskExecutors,
                dataSources,
                pooledExecutor,
                asyncEnabled,
                devToolsPresent,
                hikariPresent);
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
