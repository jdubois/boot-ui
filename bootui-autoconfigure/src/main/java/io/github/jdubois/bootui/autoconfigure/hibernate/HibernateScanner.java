package io.github.jdubois.bootui.autoconfigure.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.core.dto.HibernateScanStatusDto;
import io.github.jdubois.bootui.core.dto.HibernateSeverityCountDto;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/**
 * Bounded, on-demand Hibernate/JPA mapping advisor.
 *
 * <p>The scanner reads mapped entities from the JPA metamodel and runs a curated registry of static
 * Hibernate best-practice checks. It never intercepts runtime queries or invokes repositories.</p>
 */
final class HibernateScanner {

    private static final String ANALYZER = "BootUI Hibernate Advisor";
    private static final String DISCLAIMER =
            "Heuristic Hibernate/JPA mapping rules run against the host application's mapped entities only. "
                    + "These checks are review prompts, not verdicts, and should be validated against the "
                    + "application's data access patterns.";
    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    private static final Comparator<HibernateRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (HibernateRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(Comparator.comparingInt(HibernateRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(HibernateRuleResultDto::id);

    private final Supplier<EntityDiscovery> entityDiscoverySupplier;
    private final Supplier<HibernateRuntimeVersion> hibernateVersionSupplier;
    private final Environment environment;
    private final Clock clock;

    HibernateScanner(
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            ObjectProvider<ListableBeanFactory> beanFactories,
            Environment environment,
            Clock clock) {
        this(() -> discover(entityManagerFactories, beanFactories), environment, clock);
    }

    HibernateScanner(List<HibernateEntityModel> entities, Environment environment, Clock clock) {
        this(() -> new EntityDiscovery(entities, List.of(), List.of()), environment, clock);
    }

    HibernateScanner(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Environment environment,
            Clock clock) {
        this(() -> new EntityDiscovery(entities, repositories, List.of()), environment, clock);
    }

    HibernateScanner(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Environment environment,
            Clock clock,
            String hibernateVersion) {
        this(
                () -> new EntityDiscovery(entities, repositories, List.of()),
                environment,
                clock,
                () -> HibernateRuntimeVersion.parse(hibernateVersion));
    }

    private HibernateScanner(Supplier<EntityDiscovery> entityDiscoverySupplier, Environment environment, Clock clock) {
        this(entityDiscoverySupplier, environment, clock, HibernateRuntimeVersion::detect);
    }

    private HibernateScanner(
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Environment environment,
            Clock clock,
            Supplier<HibernateRuntimeVersion> hibernateVersionSupplier) {
        this.entityDiscoverySupplier = entityDiscoverySupplier;
        this.hibernateVersionSupplier = hibernateVersionSupplier;
        this.environment = environment;
        this.clock = clock;
    }

    HibernateReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Hibernate Advisor has not run yet. Click Run Hibernate checks to inspect mapped entities.",
                null,
                List.of(),
                0,
                0,
                List.of());
    }

    HibernateReport scan() {
        EntityDiscovery discovery = safeEntityDiscovery();
        if (discovery.entities().isEmpty()) {
            String message = discovery.errors().isEmpty()
                    ? "No EntityManagerFactory beans or mapped entities were found to inspect."
                    : "Hibernate metamodel could not be read: " + String.join("; ", discovery.errors());
            return report("DISABLED", message, clock.millis(), List.of(), 0, 0, List.of());
        }

        HibernateContext context = new HibernateContext(
                discovery.entities(), discovery.repositories(), environment, hibernateVersionSupplier.get());
        List<HibernateRuleResultDto> results = HibernateRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        String status = discovery.errors().isEmpty() ? "SCANNED" : "PARTIAL";
        String message =
                "Hibernate Advisor completed against " + discovery.entities().size() + " mapped entit"
                        + (discovery.entities().size() == 1 ? "y." : "ies.");
        if (!discovery.errors().isEmpty()) {
            message += " Some persistence units could not be read: " + String.join("; ", discovery.errors());
        }
        return report(
                status,
                message,
                clock.millis(),
                entityPackages(discovery.entities()),
                discovery.entities().size(),
                results.size(),
                results);
    }

    private HibernateReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> entityPackages,
            int entitiesAnalyzed,
            int rulesEvaluated,
            List<HibernateRuleResultDto> results) {
        List<HibernateRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        HibernateScanStatusDto scan = new HibernateScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, entitiesAnalyzed, violationsFound);
        return new HibernateReport(
                true,
                DISCLAIMER,
                entityPackages,
                entitiesAnalyzed,
                rulesEvaluated,
                violationsFound,
                severityCounts(violations),
                scan,
                violations);
    }

    HibernateReport applyDismissals(HibernateReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<HibernateRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<HibernateRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        HibernateScanStatusDto scan = report.scan();
        HibernateScanStatusDto updatedScan = new HibernateScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.entitiesAnalyzed(),
                violationsFound);
        return new HibernateReport(
                report.localOnly(),
                report.disclaimer(),
                report.entityPackages(),
                report.entitiesAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked);
    }

    private EntityDiscovery safeEntityDiscovery() {
        try {
            EntityDiscovery discovery = entityDiscoverySupplier.get();
            return discovery == null
                    ? EntityDiscovery.empty("No EntityManagerFactory beans are available.")
                    : discovery;
        } catch (RuntimeException | LinkageError ex) {
            return EntityDiscovery.empty(ex.getMessage());
        }
    }

    private static EntityDiscovery discover(
            ObjectProvider<EntityManagerFactory> entityManagerFactories,
            ObjectProvider<ListableBeanFactory> beanFactories) {
        EntityDiscovery entityDiscovery = discoverEntities(entityManagerFactories);
        List<String> errors = new ArrayList<>(entityDiscovery.errors());
        List<HibernateRepositoryModel> repositories = discoverRepositories(beanFactories, errors);
        return new EntityDiscovery(entityDiscovery.entities(), repositories, errors);
    }

    private static EntityDiscovery discoverEntities(ObjectProvider<EntityManagerFactory> entityManagerFactories) {
        List<EntityManagerFactory> factories;
        try {
            factories = entityManagerFactories.stream().toList();
        } catch (RuntimeException | LinkageError ex) {
            return EntityDiscovery.empty(ex.getMessage());
        }
        if (factories.isEmpty()) {
            return EntityDiscovery.empty("No EntityManagerFactory beans are available.");
        }

        List<HibernateEntityModel> entities = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (EntityManagerFactory factory : factories) {
            try {
                Metamodel metamodel = factory.getMetamodel();
                for (EntityType<?> entityType : metamodel.getEntities()) {
                    try {
                        entities.add(HibernateEntityModel.from(entityType));
                    } catch (RuntimeException | LinkageError ex) {
                        errors.add("Entity " + entityType.getName() + ": "
                                + (ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage()));
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                errors.add(ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage());
            }
        }
        return new EntityDiscovery(entities, List.of(), errors);
    }

    private static List<HibernateRepositoryModel> discoverRepositories(
            ObjectProvider<ListableBeanFactory> beanFactories, List<String> errors) {
        ListableBeanFactory beanFactory = beanFactories.getIfAvailable();
        if (beanFactory == null) {
            return List.of();
        }
        Class<?> factoryInformationType =
                classForName("org.springframework.data.repository.core.support.RepositoryFactoryInformation");
        Class<?> repositoryInformationType =
                classForName("org.springframework.data.repository.core.RepositoryInformation");
        if (factoryInformationType == null || repositoryInformationType == null) {
            return List.of();
        }
        List<HibernateRepositoryModel> repositories = new ArrayList<>();
        try {
            String[] beanNames = beanFactory.getBeanNamesForType(factoryInformationType);
            Method getRepositoryInformation = factoryInformationType.getMethod("getRepositoryInformation");
            Method getRepositoryInterface = repositoryInformationType.getMethod("getRepositoryInterface");
            Method getDomainType = repositoryInformationType.getMethod("getDomainType");
            Method isQueryMethod = repositoryInformationType.getMethod("isQueryMethod", Method.class);
            for (String beanName : beanNames) {
                try {
                    Object factoryInformation = beanFactory.getBean(beanName, factoryInformationType);
                    Object repositoryInformation = getRepositoryInformation.invoke(factoryInformation);
                    Class<?> repositoryInterface = (Class<?>) getRepositoryInterface.invoke(repositoryInformation);
                    Class<?> domainType = (Class<?>) getDomainType.invoke(repositoryInformation);
                    List<HibernateRepositoryMethodModel> methods =
                            queryMethods(repositoryInterface, domainType, repositoryInformation, isQueryMethod);
                    repositories.add(new HibernateRepositoryModel(
                            repositoryInterface == null ? strip(beanName) : repositoryInterface.getName(),
                            domainType,
                            methods));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    errors.add("Repository " + strip(beanName) + ": " + safeMessage(ex));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            errors.add("Spring Data repositories: " + safeMessage(ex));
        }
        return repositories;
    }

    private static List<HibernateRepositoryMethodModel> queryMethods(
            Class<?> repositoryInterface, Class<?> domainType, Object repositoryInformation, Method isQueryMethod)
            throws ReflectiveOperationException {
        if (repositoryInterface == null) {
            return List.of();
        }
        List<HibernateRepositoryMethodModel> methods = new ArrayList<>();
        for (Method method : repositoryInterface.getMethods()) {
            if (method.getDeclaringClass() == Object.class
                    || !Boolean.TRUE.equals(isQueryMethod.invoke(repositoryInformation, method))) {
                continue;
            }
            QueryAnnotation query = readQueryAnnotation(method);
            ModifyingAnnotation modifying = readModifyingAnnotation(method);
            String queryValue = query == null ? null : query.value();
            String countQueryValue = query == null ? null : query.countQuery();
            boolean nativeQuery = query != null && query.nativeQuery();
            methods.add(new HibernateRepositoryMethodModel(
                    repositoryInterface.getName(),
                    method.getName(),
                    domainType,
                    method.getReturnType(),
                    queryValue,
                    nativeQuery,
                    countQueryValue,
                    hasPageableParameter(method),
                    modifying != null,
                    modifying != null && modifying.clearAutomatically(),
                    modifying != null && modifying.flushAutomatically(),
                    Arrays.asList(method.getParameterTypes())));
        }
        return methods;
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    private static QueryAnnotation readQueryAnnotation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (!"org.springframework.data.jpa.repository.Query"
                    .equals(annotation.annotationType().getName())) {
                continue;
            }
            String value = stringAttribute(annotation, "value");
            String nativeQuery = stringAttribute(annotation, "nativeQuery");
            String countQuery = stringAttribute(annotation, "countQuery");
            boolean hasValue = value != null && !value.isBlank();
            return new QueryAnnotation(value, Boolean.parseBoolean(nativeQuery), countQuery, hasValue);
        }
        return null;
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    private static ModifyingAnnotation readModifyingAnnotation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (!"org.springframework.data.jpa.repository.Modifying"
                    .equals(annotation.annotationType().getName())) {
                continue;
            }
            String clear = stringAttribute(annotation, "clearAutomatically");
            String flush = stringAttribute(annotation, "flushAutomatically");
            return new ModifyingAnnotation(Boolean.parseBoolean(clear), Boolean.parseBoolean(flush));
        }
        return null;
    }

    private static String stringAttribute(Annotation annotation, String attribute) {
        try {
            Object value = annotation.annotationType().getMethod(attribute).invoke(annotation);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    // Optional Spring Data type: compare by class name instead of hard-referencing a class that may be absent at
    // runtime.
    @SuppressWarnings("java:S1872")
    private static boolean hasPageableParameter(Method method) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if ("org.springframework.data.domain.Pageable".equals(parameterType.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }

    private static String safeMessage(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }

    private List<HibernateSeverityCountDto> severityCounts(List<HibernateRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (HibernateRuleResultDto result : results) {
            if (isViolation(result)) {
                counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new HibernateSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<HibernateRuleResultDto> violationResults(List<HibernateRuleResultDto> results) {
        return results.stream()
                .filter(HibernateScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static List<String> entityPackages(List<HibernateEntityModel> entities) {
        return entities.stream()
                .map(HibernateEntityModel::packageName)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static int severityRank(String severity) {
        int index = SEVERITIES.indexOf(severity);
        return index >= 0 ? index : SEVERITIES.size();
    }

    private static boolean isViolation(HibernateRuleResultDto result) {
        return HibernateRuleSupport.VIOLATION.equals(result.status());
    }

    private record EntityDiscovery(
            List<HibernateEntityModel> entities, List<HibernateRepositoryModel> repositories, List<String> errors) {

        EntityDiscovery {
            entities = List.copyOf(entities);
            repositories = List.copyOf(repositories);
            errors = List.copyOf(errors);
        }

        static EntityDiscovery empty(String reason) {
            return new EntityDiscovery(List.of(), List.of(), List.of(reason == null ? "Unavailable." : reason));
        }
    }

    private record QueryAnnotation(String value, boolean nativeQuery, String countQuery, boolean hasValue) {}

    private record ModifyingAnnotation(boolean clearAutomatically, boolean flushAutomatically) {}
}
