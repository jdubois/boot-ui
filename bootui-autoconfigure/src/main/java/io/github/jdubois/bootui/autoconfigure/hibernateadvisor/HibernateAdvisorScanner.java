package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

import io.github.jdubois.bootui.core.dto.HibernateAdvisorReport;
import io.github.jdubois.bootui.core.dto.HibernateAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.HibernateAdvisorScanStatusDto;
import io.github.jdubois.bootui.core.dto.HibernateAdvisorSeverityCountDto;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/**
 * Bounded, on-demand Hibernate/JPA mapping advisor.
 *
 * <p>The scanner reads mapped entities from the JPA metamodel and runs a curated registry of static
 * Hibernate best-practice checks. It never intercepts runtime queries or invokes repositories.</p>
 */
final class HibernateAdvisorScanner {

    private static final String ANALYZER = "BootUI Hibernate Advisor";
    private static final String DISCLAIMER =
            "Heuristic Hibernate/JPA mapping rules run against the host application's mapped entities only. "
                    + "These checks are review prompts, not verdicts, and should be validated against the "
                    + "application's data access patterns.";
    private static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW", "INFO");
    private static final Comparator<HibernateAdvisorRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (HibernateAdvisorRuleResultDto result) -> severityRank(result.severity()))
            .thenComparing(Comparator.comparingInt(HibernateAdvisorRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(HibernateAdvisorRuleResultDto::id);

    private final Supplier<EntityDiscovery> entityDiscoverySupplier;
    private final Environment environment;
    private final Clock clock;

    HibernateAdvisorScanner(
            ObjectProvider<EntityManagerFactory> entityManagerFactories, Environment environment, Clock clock) {
        this(() -> discoverEntities(entityManagerFactories), environment, clock);
    }

    HibernateAdvisorScanner(List<HibernateEntityModel> entities, Environment environment, Clock clock) {
        this(() -> new EntityDiscovery(entities, List.of()), environment, clock);
    }

    private HibernateAdvisorScanner(
            Supplier<EntityDiscovery> entityDiscoverySupplier, Environment environment, Clock clock) {
        this.entityDiscoverySupplier = entityDiscoverySupplier;
        this.environment = environment;
        this.clock = clock;
    }

    HibernateAdvisorReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Hibernate Advisor has not run yet. Click Run Hibernate checks to inspect mapped entities.",
                null,
                List.of(),
                0,
                0,
                List.of());
    }

    HibernateAdvisorReport scan() {
        EntityDiscovery discovery = safeEntityDiscovery();
        if (discovery.entities().isEmpty()) {
            String message = discovery.errors().isEmpty()
                    ? "No EntityManagerFactory beans or mapped entities were found to inspect."
                    : "Hibernate metamodel could not be read: " + String.join("; ", discovery.errors());
            return report("DISABLED", message, clock.millis(), List.of(), 0, 0, List.of());
        }

        HibernateAdvisorContext context = new HibernateAdvisorContext(discovery.entities(), environment);
        List<HibernateAdvisorRuleResultDto> results = HibernateAdvisorRuleRegistry.activeRules().stream()
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

    private HibernateAdvisorReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> entityPackages,
            int entitiesAnalyzed,
            int rulesEvaluated,
            List<HibernateAdvisorRuleResultDto> results) {
        List<HibernateAdvisorRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        HibernateAdvisorScanStatusDto scan = new HibernateAdvisorScanStatusDto(
                ANALYZER, status, message, scannedAt, rulesEvaluated, entitiesAnalyzed, violationsFound);
        return new HibernateAdvisorReport(
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
        return new EntityDiscovery(entities, errors);
    }

    private List<HibernateAdvisorSeverityCountDto> severityCounts(List<HibernateAdvisorRuleResultDto> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : SEVERITIES) {
            counts.put(severity, 0);
        }
        for (HibernateAdvisorRuleResultDto result : results) {
            if (isViolation(result)) {
                counts.computeIfPresent(result.severity(), (ignored, count) -> count + 1);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new HibernateAdvisorSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<HibernateAdvisorRuleResultDto> violationResults(List<HibernateAdvisorRuleResultDto> results) {
        return results.stream()
                .filter(HibernateAdvisorScanner::isViolation)
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

    private static boolean isViolation(HibernateAdvisorRuleResultDto result) {
        return HibernateAdvisorRuleSupport.VIOLATION.equals(result.status());
    }

    private record EntityDiscovery(List<HibernateEntityModel> entities, List<String> errors) {

        EntityDiscovery {
            entities = List.copyOf(entities);
            errors = List.copyOf(errors);
        }

        static EntityDiscovery empty(String reason) {
            return new EntityDiscovery(List.of(), List.of(reason == null ? "Unavailable." : reason));
        }
    }
}
