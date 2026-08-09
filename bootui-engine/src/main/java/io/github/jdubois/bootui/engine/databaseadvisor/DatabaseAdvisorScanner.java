package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorScanStatusDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bounded, on-demand Database Advisor: introspects the host application's physical schema through plain
 * JDBC {@code DatabaseMetaData} (with a small amount of PostgreSQL/MySQL-specific catalog augmentation)
 * and, when a Hibernate metamodel is also available for the same application, cross-references it against
 * the mapped entities. It never executes DDL, never queries application data, and never intercepts
 * runtime queries.
 */
public final class DatabaseAdvisorScanner {

    private static final String ANALYZER = "BootUI Database Advisor";
    private static final String DISCLAIMER =
            "Read-only JDBC schema introspection (tables, columns, primary/foreign keys, indexes) via "
                    + "DatabaseMetaData, with a small amount of PostgreSQL/MySQL-specific catalog augmentation, plus "
                    + "cross-reference checks against the Hibernate metamodel when both are available. These checks "
                    + "are review prompts, not verdicts.";
    private static final Comparator<DatabaseAdvisorRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (DatabaseAdvisorRuleResultDto result) -> SeverityOrder.rank(result.severity()))
            .thenComparing(Comparator.comparingInt(DatabaseAdvisorRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(DatabaseAdvisorRuleResultDto::id);

    private final Supplier<List<NamedDataSource>> dataSourceSupplier;
    private final Supplier<EntityDiscovery> entityDiscoverySupplier;
    private final Clock clock;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    public static DatabaseAdvisorScanner using(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Clock clock) {
        return new DatabaseAdvisorScanner(dataSourceSupplier, entityDiscoverySupplier, clock);
    }

    private DatabaseAdvisorScanner(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Clock clock) {
        this.dataSourceSupplier = dataSourceSupplier;
        this.entityDiscoverySupplier = entityDiscoverySupplier;
        this.clock = clock;
    }

    public DatabaseAdvisorReport initialReport() {
        return report(
                "NOT_SCANNED",
                "Database Advisor has not run yet. Click Run Database checks to inspect the physical schema.",
                null,
                List.of(),
                0,
                List.of());
    }

    public DatabaseAdvisorReport scan() {
        return singleFlight.run(ActionOperations.DATABASE_ADVISOR_SCAN, this::doScan);
    }

    private DatabaseAdvisorReport doScan() {
        List<NamedDataSource> dataSources = safeDataSources();
        if (dataSources.isEmpty()) {
            return report(
                    "DISABLED", "No DataSource beans were found to inspect.", clock.millis(), List.of(), 0, List.of());
        }

        List<SchemaSnapshot> schemas = dataSources.stream()
                .map(ds -> SchemaIntrospector.introspect(ds.name(), ds.dataSource()))
                .toList();
        List<SchemaSnapshot> available =
                schemas.stream().filter(SchemaSnapshot::available).toList();
        if (available.isEmpty()) {
            String errors = schemas.stream()
                    .map(SchemaSnapshot::error)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Unknown error.");
            return report(
                    "DISABLED",
                    "The physical schema could not be read for any datasource: " + errors,
                    clock.millis(),
                    dataSourceNames(dataSources),
                    0,
                    List.of());
        }

        EntityDiscovery entityDiscovery = safeEntityDiscovery();
        boolean hibernateAvailable = !entityDiscovery.entities().isEmpty();
        List<MappedEntityFacts> mappedEntities = HibernateSchemaBridge.toMappedEntities(entityDiscovery.entities());

        DatabaseAdvisorContext context = new DatabaseAdvisorContext(schemas, hibernateAvailable, mappedEntities);
        List<DatabaseAdvisorRuleResultDto> results = DatabaseAdvisorRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();

        int tablesAnalyzed = context.tableCount();
        boolean allAvailable = available.size() == schemas.size();
        String status = allAvailable ? "SCANNED" : "PARTIAL";
        String message = "Database Advisor completed against " + tablesAnalyzed + " table"
                + (tablesAnalyzed == 1 ? "" : "s") + " across " + available.size() + " datasource"
                + (available.size() == 1 ? "" : "s") + ".";
        if (!allAvailable) {
            message += " Some datasources could not be read.";
        }
        return report(status, message, clock.millis(), dataSourceNames(dataSources), tablesAnalyzed, results);
    }

    private DatabaseAdvisorReport report(
            String status,
            String message,
            Long scannedAt,
            List<String> dataSourceNames,
            int tablesAnalyzed,
            List<DatabaseAdvisorRuleResultDto> results) {
        List<DatabaseAdvisorRuleResultDto> violations = violationResults(results);
        int violationsFound = violations.size();
        DatabaseAdvisorScanStatusDto scan = new DatabaseAdvisorScanStatusDto(
                ANALYZER, status, message, scannedAt, results.size(), tablesAnalyzed, violationsFound);
        return new DatabaseAdvisorReport(
                true,
                DISCLAIMER,
                dataSourceNames,
                tablesAnalyzed,
                results.size(),
                violationsFound,
                severityCounts(violations),
                scan,
                violations);
    }

    public DatabaseAdvisorReport applyDismissals(DatabaseAdvisorReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<DatabaseAdvisorRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<DatabaseAdvisorRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        DatabaseAdvisorScanStatusDto scan = report.scan();
        DatabaseAdvisorScanStatusDto updatedScan = new DatabaseAdvisorScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.tablesAnalyzed(),
                violationsFound);
        return new DatabaseAdvisorReport(
                report.localOnly(),
                report.disclaimer(),
                report.dataSourceNames(),
                report.tablesAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked);
    }

    private List<NamedDataSource> safeDataSources() {
        try {
            List<NamedDataSource> dataSources = dataSourceSupplier.get();
            return dataSources == null ? List.of() : dataSources;
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
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

    private List<DatabaseAdvisorSeverityCountDto> severityCounts(List<DatabaseAdvisorRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.counts(
                results, DatabaseAdvisorScanner::isViolation, DatabaseAdvisorRuleResultDto::severity);
        return counts.entrySet().stream()
                .map(entry -> new DatabaseAdvisorSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<DatabaseAdvisorRuleResultDto> violationResults(List<DatabaseAdvisorRuleResultDto> results) {
        return results.stream()
                .filter(DatabaseAdvisorScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static List<String> dataSourceNames(List<NamedDataSource> dataSources) {
        return dataSources.stream()
                .map(NamedDataSource::name)
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean isViolation(DatabaseAdvisorRuleResultDto result) {
        return DatabaseAdvisorRuleSupport.VIOLATION.equals(result.status());
    }
}
