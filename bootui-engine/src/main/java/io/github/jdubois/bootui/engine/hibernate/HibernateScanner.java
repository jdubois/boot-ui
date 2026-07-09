package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.core.dto.HibernateScanStatusDto;
import io.github.jdubois.bootui.core.dto.HibernateSeverityCountDto;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bounded, on-demand Hibernate/JPA mapping advisor.
 *
 * <p>The scanner reads mapped entities from the JPA metamodel and runs a curated registry of static
 * Hibernate best-practice checks. It never intercepts runtime queries or invokes repositories.</p>
 */
public final class HibernateScanner {

    private static final String ANALYZER = "BootUI Hibernate Advisor";
    private static final String DISCLAIMER =
            "Heuristic Hibernate/JPA mapping rules run against the host application's mapped entities only. "
                    + "These checks are review prompts, not verdicts, and should be validated against the "
                    + "application's data access patterns.";
    private static final Comparator<HibernateRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (HibernateRuleResultDto result) -> SeverityOrder.rank(result.severity()))
            .thenComparing(Comparator.comparingInt(HibernateRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(HibernateRuleResultDto::id);

    private final Supplier<EntityDiscovery> entityDiscoverySupplier;
    private final Supplier<HibernateRuntimeVersion> hibernateVersionSupplier;
    private final Function<String, String> propertyLookup;
    private final Supplier<List<String>> activeProfiles;
    private final Clock clock;

    /**
     * Builds the scanner an adapter wires: entity discovery (typically the engine
     * {@link JpaMetamodelReader} plus any framework-specific repositories) is read <em>live</em> on
     * every scan, and configuration is read through a neutral property-lookup + active-profiles seam.
     */
    public static HibernateScanner using(
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Function<String, String> propertyLookup,
            Supplier<List<String>> activeProfiles,
            Clock clock) {
        return new HibernateScanner(
                entityDiscoverySupplier, propertyLookup, activeProfiles, clock, HibernateRuntimeVersion::detect);
    }

    HibernateScanner(
            List<HibernateEntityModel> entities,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            Clock clock) {
        this(
                () -> new EntityDiscovery(entities, List.of(), List.of()),
                propertyLookup,
                () -> activeProfiles,
                clock,
                HibernateRuntimeVersion::detect);
    }

    HibernateScanner(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            Clock clock) {
        this(
                () -> new EntityDiscovery(entities, repositories, List.of()),
                propertyLookup,
                () -> activeProfiles,
                clock,
                HibernateRuntimeVersion::detect);
    }

    HibernateScanner(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            Clock clock,
            String hibernateVersion) {
        this(
                () -> new EntityDiscovery(entities, repositories, List.of()),
                propertyLookup,
                () -> activeProfiles,
                clock,
                () -> HibernateRuntimeVersion.parse(hibernateVersion));
    }

    private HibernateScanner(
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Function<String, String> propertyLookup,
            Supplier<List<String>> activeProfiles,
            Clock clock,
            Supplier<HibernateRuntimeVersion> hibernateVersionSupplier) {
        this.entityDiscoverySupplier = entityDiscoverySupplier;
        this.hibernateVersionSupplier = hibernateVersionSupplier;
        this.propertyLookup = propertyLookup;
        this.activeProfiles = activeProfiles;
        this.clock = clock;
    }

    public HibernateReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Hibernate Advisor has not run yet. Click Run Hibernate checks to inspect mapped entities.",
                null,
                List.of(),
                0,
                0,
                List.of());
    }

    public HibernateReport scan() {
        EntityDiscovery discovery = safeEntityDiscovery();
        if (discovery.entities().isEmpty()) {
            String message = discovery.errors().isEmpty()
                    ? "No EntityManagerFactory beans or mapped entities were found to inspect."
                    : "Hibernate metamodel could not be read: " + String.join("; ", discovery.errors());
            return report("DISABLED", message, clock.millis(), List.of(), 0, 0, List.of());
        }

        HibernateContext context = new HibernateContext(
                discovery.entities(),
                discovery.repositories(),
                propertyLookup,
                activeProfiles.get(),
                hibernateVersionSupplier.get());
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

    public HibernateReport applyDismissals(HibernateReport report, Set<String> dismissedIds) {
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

    private List<HibernateSeverityCountDto> severityCounts(List<HibernateRuleResultDto> results) {
        Map<String, Integer> counts =
                SeverityOrder.counts(results, HibernateScanner::isViolation, HibernateRuleResultDto::severity);
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

    private static boolean isViolation(HibernateRuleResultDto result) {
        return HibernateRuleSupport.VIOLATION.equals(result.status());
    }
}
