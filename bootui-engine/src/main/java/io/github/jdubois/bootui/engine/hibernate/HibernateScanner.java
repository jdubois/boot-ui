package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.AdvisorAssessmentEvidenceDto;
import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateRuleResultDto;
import io.github.jdubois.bootui.core.dto.HibernateScanStatusDto;
import io.github.jdubois.bootui.core.dto.HibernateSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    public static final String OPEN_IN_VIEW_APPLICABLE_PROPERTY = "bootui.internal.hibernate.open-in-view-applicable";
    public static final String BYTECODE_ENHANCEMENT_VERIFIED_PROPERTY =
            "bootui.internal.hibernate.bytecode-enhancement-verified";

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
    private final Supplier<List<String>> activeProfiles;
    private final Clock clock;
    private final HibernateAdvisorObservationSource observationSource;
    private final List<HibernateRule> rules;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    /**
     * Compatibility factory for declaration-only discovery. The property callback is retained for
     * source compatibility, but arbitrary application properties are not evidence of unit-effective
     * settings. Use {@link #observing(HibernateAdvisorObservationSource, Clock)} for native observations.
     */
    public static HibernateScanner using(
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Function<String, String> propertyLookup,
            Supplier<List<String>> activeProfiles,
            Clock clock) {
        return new HibernateScanner(entityDiscoverySupplier, activeProfiles, clock);
    }

    public static HibernateScanner observing(HibernateAdvisorObservationSource source, Clock clock) {
        return new HibernateScanner(source, clock, HibernateRuleRegistry.activeRules());
    }

    HibernateScanner(HibernateAdvisorObservationSource source, Clock clock, List<HibernateRule> rules) {
        this.observationSource = source;
        this.clock = clock;
        this.rules = List.copyOf(rules);
        this.entityDiscoverySupplier = null;
        this.activeProfiles = List::of;
    }

    HibernateScanner(
            List<HibernateEntityModel> entities,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            Clock clock) {
        this(() -> new EntityDiscovery(entities, List.of(), List.of()), () -> activeProfiles, clock);
    }

    HibernateScanner(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            Clock clock) {
        this(() -> new EntityDiscovery(entities, repositories, List.of()), () -> activeProfiles, clock);
    }

    HibernateScanner(
            List<HibernateEntityModel> entities,
            List<HibernateRepositoryModel> repositories,
            Function<String, String> propertyLookup,
            List<String> activeProfiles,
            Clock clock,
            String hibernateVersion) {
        this(() -> new EntityDiscovery(entities, repositories, List.of()), () -> activeProfiles, clock);
    }

    private HibernateScanner(
            Supplier<EntityDiscovery> entityDiscoverySupplier, Supplier<List<String>> activeProfiles, Clock clock) {
        this.entityDiscoverySupplier = entityDiscoverySupplier;
        this.activeProfiles = activeProfiles;
        this.clock = clock;
        this.observationSource = null;
        this.rules = HibernateRuleRegistry.activeRules();
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
        return singleFlight.run(ActionOperations.HIBERNATE_SCAN, this::doScan);
    }

    private HibernateReport doScan() {
        HibernateAdvisorObservation observation = safeObservation();
        List<HibernateEntityModel> entities = observation.units().stream()
                .flatMap(unit -> unit.entities().stream())
                .toList();
        if (entities.isEmpty()) {
            String message = observation.diagnostics().isEmpty()
                    ? "No EntityManagerFactory beans or mapped entities were found to inspect."
                    : "Required Hibernate observations are unavailable.";
            return report(
                    observation.diagnostics().isEmpty() ? "DISABLED" : "PARTIAL",
                    message,
                    clock.millis(),
                    List.of(),
                    0,
                    0,
                    List.of());
        }

        Map<String, HibernateRuleResultDto> violations = new LinkedHashMap<>();
        Set<String> failed = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        int skipped = 0;
        int attempts = 0;
        boolean usable = false;
        HibernateApplicationFacts app = observation.application();
        Boolean logging = app.sqlLoggerEnabled();
        if (observation.units().stream()
                .anyMatch(unit -> Boolean.TRUE.equals(unit.settings().showSql()))) {
            logging = true;
        } else if (!Boolean.TRUE.equals(logging)
                && observation.units().stream().anyMatch(unit -> unit.settings().showSql() == null)) {
            logging = null;
        }
        HibernateApplicationFacts globalApp = new HibernateApplicationFacts(
                app.activeProfiles(),
                app.openInView(),
                app.deferredDatasourceInitialization(),
                logging,
                app.bindLoggerEnabled(),
                app.panacheEnhancementVerified());
        HibernateContext global = HibernateContext.observed(
                new HibernatePersistenceUnitObservation(
                        "application",
                        "application",
                        entities,
                        List.of(),
                        null,
                        HibernateFactorySettings.unknown(),
                        null),
                globalApp);
        for (HibernateRule rule : rules) {
            List<HibernatePersistenceUnitObservation> units =
                    applicationRule(rule.definition().id()) ? List.of() : observation.units();
            List<HibernateContext> contexts = units.isEmpty()
                    ? List.of(global)
                    : units.stream()
                            .map(unit -> HibernateContext.observed(unit, app))
                            .toList();
            for (int i = 0; i < contexts.size(); i++) {
                HibernateContext context = contexts.get(i);
                String label = units.isEmpty() ? "application" : units.get(i).label();
                String identity = rule.definition().id() + " [" + label + "]";
                context.evidence().reset();
                attempts++;
                HibernateRuleResultDto result;
                try {
                    result = rule.evaluate(context);
                } catch (RuntimeException | LinkageError ex) {
                    result = HibernateRuleSupport.error(rule.definition(), "Rule evaluation failed.");
                }
                if (HibernateRuleSupport.ERROR.equals(result.status())) failed.add(identity);
                if (context.evidence().requiredUnknown) unknown.add(identity);
                if (HibernateRuleSupport.SKIPPED.equals(result.status())) skipped++;
                usable |= isViolation(result)
                        || (HibernateRuleSupport.PASS.equals(result.status()) && !context.evidence().requiredUnknown);
                if (isViolation(result)) mergeViolation(violations, result, label);
            }
        }
        boolean incomplete = !observation.diagnostics().isEmpty() || !failed.isEmpty() || !unknown.isEmpty();
        String message = "Hibernate Advisor inspected " + entities.size() + " entity mappings across "
                + observation.units().size() + " persistence units. Attempted " + rules.size()
                + " distinct rules (" + attempts + " unit/application evaluations); failed " + failed.size()
                + ", skipped " + skipped + ", required evidence unavailable " + unknown.size() + ".";
        if (!failed.isEmpty()) message += " Failed: " + bounded(failed) + ".";
        if (!unknown.isEmpty()) message += " Incomplete: " + bounded(unknown) + ".";
        if (!observation.diagnostics().isEmpty())
            message += " Discovery: "
                    + bounded(observation.diagnostics().stream()
                            .map(diagnostic -> "[" + diagnostic.unitLabel() + "] " + diagnostic.reason())
                            .toList())
                    + ".";
        return report(
                incomplete ? "PARTIAL" : "SCANNED",
                message,
                clock.millis(),
                entityPackages(entities),
                entities.size(),
                rules.size(),
                List.copyOf(violations.values()),
                usable);
    }

    private static boolean applicationRule(String id) {
        return Set.of("HIB-CONFIG-001", "HIB-CONFIG-012", "HIB-CONFIG-015", "HIB-CONFIG-018")
                .contains(id);
    }

    private static String bounded(java.util.Collection<String> values) {
        return String.join("; ", values.stream().limit(8).toList())
                + (values.size() > 8 ? "; +" + (values.size() - 8) + " more" : "");
    }

    private static void mergeViolation(
            Map<String, HibernateRuleResultDto> results, HibernateRuleResultDto result, String label) {
        HibernateRuleResultDto previous = results.get(result.id());
        List<String> samples = new ArrayList<>();
        if (previous != null) samples.addAll(previous.sampleViolations());
        for (String sample : result.sampleViolations()) {
            if (samples.size() == 10) break;
            samples.add(HibernateRuleSupport.detail("[" + label + "] " + sample));
        }
        String severity =
                previous != null && SeverityOrder.rank(previous.severity()) < SeverityOrder.rank(result.severity())
                        ? previous.severity()
                        : result.severity();
        results.put(
                result.id(),
                new HibernateRuleResultDto(
                        result.id(),
                        result.name(),
                        result.category(),
                        severity,
                        result.description(),
                        result.status(),
                        result.violationCount() + (previous == null ? 0 : previous.violationCount()),
                        samples,
                        result.recommendation(),
                        result.learnMoreUrl()));
    }

    private HibernateAdvisorObservation safeObservation() {
        try {
            if (observationSource != null) {
                HibernateAdvisorObservation observation = observationSource.observe();
                if (observation != null) return observation;
                throw new IllegalStateException();
            }
            EntityDiscovery legacy = safeEntityDiscovery();
            return new HibernateAdvisorObservation(
                    legacy.entities().isEmpty()
                            ? List.of()
                            : List.of(new HibernatePersistenceUnitObservation(
                                    "legacy",
                                    "legacy",
                                    legacy.entities(),
                                    legacy.repositories(),
                                    null,
                                    HibernateFactorySettings.unknown(),
                                    null)),
                    HibernateApplicationFacts.unknown(activeProfiles.get()),
                    legacy.errors().isEmpty()
                            ? List.of()
                            : List.of(new HibernateObservationDiagnostic(
                                    "legacy", HibernateObservationDiagnostic.Reason.METAMODEL_UNAVAILABLE)));
        } catch (RuntimeException | LinkageError ex) {
            return new HibernateAdvisorObservation(
                    List.of(),
                    null,
                    List.of(new HibernateObservationDiagnostic(
                            "application", HibernateObservationDiagnostic.Reason.SOURCE_UNAVAILABLE)));
        }
    }

    private HibernateReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> entityPackages,
            int entitiesAnalyzed,
            int rulesEvaluated,
            List<HibernateRuleResultDto> results) {
        return report(
                status,
                message,
                scannedAt,
                entityPackages,
                entitiesAnalyzed,
                rulesEvaluated,
                results,
                results.stream().anyMatch(HibernateScanner::isViolation));
    }

    private HibernateReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> entityPackages,
            int entitiesAnalyzed,
            int rulesEvaluated,
            List<HibernateRuleResultDto> results,
            boolean usable) {
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
                violations,
                new AdvisorAssessmentEvidenceDto(usable, "PARTIAL".equals(status)));
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
                marked,
                report.assessmentEvidence());
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
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                results,
                HibernateScanner::isViolation,
                HibernateRuleResultDto::severity,
                HibernateRuleResultDto::violationCount);
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
