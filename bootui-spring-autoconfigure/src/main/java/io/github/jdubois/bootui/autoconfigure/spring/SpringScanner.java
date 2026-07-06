package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.CacheManagerRef;
import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.core.dto.SpringScanStatusDto;
import io.github.jdubois.bootui.core.dto.SpringSeverityCountDto;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;

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
    private static final String ENTITY_MANAGER_FACTORY_TYPE = "jakarta.persistence.EntityManagerFactory";
    private static final String DISPATCHER_SERVLET_TYPE = "org.springframework.web.servlet.DispatcherServlet";
    private static final String WEB_CLIENT_TYPE = "org.springframework.web.reactive.function.client.WebClient";

    // Matched by string type name (via ClassUtils.isPresent/forName), never a direct import: reactor-core
    // is pulled in only by the optional spring-boot-starter-webflux dependency, so a servlet-only consumer
    // never has these classes on its classpath. A direct import of reactor.core.publisher.Mono/Flux
    // anywhere in this always-loaded scanner would risk a NoClassDefFoundError on that consumer the first
    // time the class is verified - the same optional-dependency classloading trap documented for
    // jakarta.persistence/Flyway/Liquibase elsewhere in BootUI.
    private static final String MONO_TYPE = "reactor.core.publisher.Mono";

    private static final String FLUX_TYPE = "reactor.core.publisher.Flux";
    private static final String ASYNC_PROCESSOR_BEAN =
            "org.springframework.context.annotation.internalAsyncAnnotationProcessor";
    private static final String CACHE_ADVISOR_BEAN = "org.springframework.cache.config.internalCacheAdvisor";
    private static final String SCHEDULED_PROCESSOR_BEAN =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
    private static final String DEVTOOLS_MARKER = "org.springframework.boot.devtools.restart.Restarter";

    /** Hard cap on the number of default-package bean names collected, to keep the scan bounded. */
    private static final int MAX_DEFAULT_PACKAGE_BEANS = 50;

    /** Hard cap on the number of mutable-singleton-field findings collected, to keep the scan bounded. */
    private static final int MAX_MUTABLE_SINGLETON_FIELDS = 50;

    /** Hard cap on the number of reactive (Mono/Flux) handler methods counted, to keep the scan bounded. */
    private static final int MAX_REACTIVE_HANDLER_METHODS = 50;

    /**
     * Field-level annotations that mark a legitimate injection point rather than loose shared state.
     * Matched by annotation type name (not the annotation {@code Class} literal) so this works even
     * when {@code jakarta.inject}/{@code jakarta.persistence} are not on the advisor's own classpath;
     * the annotated field's own class can only carry these annotations if its classloader already
     * resolves them.
     */
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Value",
            "org.springframework.beans.factory.annotation.Qualifier",
            "jakarta.inject.Inject",
            "jakarta.annotation.Resource",
            "jakarta.persistence.PersistenceContext",
            "jakarta.persistence.PersistenceUnit");

    private static final Comparator<SpringRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (SpringRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(
                    Comparator.comparingInt(SpringRuleResultDto::violationCount).reversed())
            .thenComparing(SpringRuleResultDto::id);

    private final Supplier<SpringContext> contextSupplier;
    private final Clock clock;

    SpringScanner(ConfigurableListableBeanFactory beanFactory, Environment environment, boolean reactive, Clock clock) {
        this(() -> discover(beanFactory, environment, reactive), clock);
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

    private static SpringContext discover(
            ConfigurableListableBeanFactory beanFactory, Environment environment, boolean reactive) {
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
        boolean entityManagerFactoryPresent = !beansOfType(beanFactory, ENTITY_MANAGER_FACTORY_TYPE, classLoader)
                .isEmpty();
        boolean dispatcherServletPresent =
                !beansOfType(beanFactory, DISPATCHER_SERVLET_TYPE, classLoader).isEmpty();
        boolean webClientBeanPresent =
                !beansOfType(beanFactory, WEB_CLIENT_TYPE, classLoader).isEmpty();
        int reactiveHandlerMethodCount = reactiveHandlerMethodCount(beanFactory, classLoader);
        List<String> defaultPackageBeans = defaultPackageBeans(beanFactory);
        List<String> mutableSingletonFields = mutableSingletonFields(beanFactory);

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
                .entityManagerFactoryPresent(entityManagerFactoryPresent)
                .dispatcherServletPresent(dispatcherServletPresent)
                .reactive(reactive)
                .webClientBeanPresent(webClientBeanPresent)
                .reactiveHandlerMethodCount(reactiveHandlerMethodCount)
                .defaultPackageBeans(defaultPackageBeans)
                .mutableSingletonFields(mutableSingletonFields)
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

    /**
     * Collects public, non-final, non-static fields declared on singleton-scoped application beans.
     * A singleton is a single instance shared across every concurrent request and thread, so a public
     * mutable field is unsynchronised shared state that any caller can read or overwrite outside the
     * bean's own control. Injection-point fields (recognised by annotation) are excluded: intentional
     * field injection is a separate, narrower concern than accidental shared mutable state.
     */
    private static List<String> mutableSingletonFields(ConfigurableListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return List.of();
        }
        String[] names;
        try {
            names = beanFactory.getBeanDefinitionNames();
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
        List<String> findings = new ArrayList<>();
        for (String name : names) {
            if (findings.size() >= MAX_MUTABLE_SINGLETON_FIELDS) {
                break;
            }
            try {
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                if (definition.getRole() != BeanDefinition.ROLE_APPLICATION || !definition.isSingleton()) {
                    continue;
                }
                Class<?> type = beanFactory.getType(name, false);
                if (type == null || type.isInterface()) {
                    continue;
                }
                collectMutableFields(type, findings);
            } catch (RuntimeException | LinkageError ex) {
                // Ignore beans whose type/fields cannot be inspected.
            }
        }
        return List.copyOf(findings);
    }

    private static void collectMutableFields(Class<?> type, List<String> findings) {
        for (Field field : type.getDeclaredFields()) {
            if (findings.size() >= MAX_MUTABLE_SINGLETON_FIELDS) {
                return;
            }
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) {
                continue;
            }
            if (isInjectionPoint(field)) {
                continue;
            }
            findings.add(type.getName() + "#" + field.getName());
        }
    }

    private static boolean isInjectionPoint(Field field) {
        try {
            for (Annotation annotation : field.getAnnotations()) {
                if (INJECTION_ANNOTATIONS.contains(annotation.annotationType().getName())) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            // If the field's annotations cannot be inspected, fail safe and don't flag it.
            return true;
        }
        return false;
    }

    /**
     * Counts public {@code @Controller}/{@code @RestController} handler methods (matched by
     * {@code @RequestMapping} or one of its shortcut meta-annotations) whose return type is
     * {@code Mono} or {@code Flux}. Used to detect WebFlux reactive endpoints without depending on
     * bytecode/AST analysis: a live-bean reflection scan, consistent with the rest of this scanner.
     *
     * <p>Returns {@code 0} immediately when neither Reactor type is on the classpath, since no method
     * could declare that return type in the first place - this also means the (possibly expensive)
     * per-controller reflection below never runs on a plain servlet application.</p>
     */
    private static int reactiveHandlerMethodCount(
            ConfigurableListableBeanFactory beanFactory, ClassLoader classLoader) {
        if (beanFactory == null) {
            return 0;
        }
        Class<?> monoType = presentType(MONO_TYPE, classLoader);
        Class<?> fluxType = presentType(FLUX_TYPE, classLoader);
        if (monoType == null && fluxType == null) {
            return 0;
        }
        String[] names;
        try {
            names = beanFactory.getBeanNamesForAnnotation(Controller.class);
        } catch (RuntimeException | LinkageError ex) {
            return 0;
        }
        int count = 0;
        for (String name : names) {
            if (count >= MAX_REACTIVE_HANDLER_METHODS) {
                break;
            }
            try {
                Class<?> type = beanFactory.getType(name, false);
                if (type == null) {
                    continue;
                }
                for (Method method : type.getMethods()) {
                    if (count >= MAX_REACTIVE_HANDLER_METHODS) {
                        break;
                    }
                    if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                        continue;
                    }
                    Class<?> returnType = method.getReturnType();
                    if ((monoType != null && monoType.isAssignableFrom(returnType))
                            || (fluxType != null && fluxType.isAssignableFrom(returnType))) {
                        count++;
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                // Ignore beans whose type/methods cannot be inspected.
            }
        }
        return count;
    }

    /** Resolves a class by name, returning {@code null} rather than throwing when it isn't present. */
    private static Class<?> presentType(String typeName, ClassLoader classLoader) {
        if (!ClassUtils.isPresent(typeName, classLoader)) {
            return null;
        }
        try {
            return ClassUtils.forName(typeName, classLoader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
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
