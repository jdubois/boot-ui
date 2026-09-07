package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorDataSourceDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorDiagnosticDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorScanStatusDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorSeverityCountDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bounded, on-demand Database Advisor: introspects the host application's physical schema through plain JDBC
 * {@code DatabaseMetaData} (with PostgreSQL/MySQL/MariaDB catalog augmentation) and, when a Hibernate
 * metamodel is also available for the same application, cross-references it against the mapped entities. It
 * never executes DDL, never queries application data, and never intercepts runtime queries.
 *
 * <p>The scan is honest about what it could not do. A datasource that refused a connection, a table whose
 * metadata could not be read, a catalog view a role cannot see, a bound that truncated the analysis, and a
 * rule that was skipped or errored all reach the report as diagnostics and as an explicit per-datasource
 * status — never as a passing check. That is why {@code DISABLED} now means only "there is no datasource to
 * inspect": a scan where every datasource failed reports {@code ERROR}.</p>
 */
public final class DatabaseAdvisorScanner {

    private static final String ANALYZER = "BootUI Database Advisor";
    private static final String DISCLAIMER =
            "Read-only JDBC schema introspection (tables, columns, primary/foreign keys, indexes) via "
                    + "DatabaseMetaData, with PostgreSQL, MySQL/MariaDB, and Oracle catalog augmentation, plus "
                    + "cross-reference checks against the Hibernate metamodel when both are available. These checks "
                    + "are review prompts, not verdicts.";
    private static final Comparator<DatabaseAdvisorRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (DatabaseAdvisorRuleResultDto result) -> SeverityOrder.rank(result.severity()))
            .thenComparing(Comparator.comparingInt(DatabaseAdvisorRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(DatabaseAdvisorRuleResultDto::id);

    private final Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier;
    private final Supplier<EntityDiscovery> entityDiscoverySupplier;
    private final Supplier<List<SqlTraceEntryDto>> observedStatementSupplier;
    private final Clock clock;
    private final DatabaseAdvisorLimits limits;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    public static DatabaseAdvisorScanner using(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Clock clock) {
        return using(dataSourceSupplier, entityDiscoverySupplier, List::of, clock);
    }

    /**
     * The scanner with runtime statement evidence. {@code observedStatementSupplier} hands over the
     * statements SQL Trace has already retained so the runtime-SQL rules have something to reason about;
     * supply {@link List#of()} when SQL tracing is not available, and those rules skip rather than pass.
     */
    public static DatabaseAdvisorScanner using(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Supplier<List<SqlTraceEntryDto>> observedStatementSupplier,
            Clock clock) {
        return new DatabaseAdvisorScanner(
                discoveryFrom(dataSourceSupplier),
                entityDiscoverySupplier,
                observedStatementSupplier,
                clock,
                DatabaseAdvisorLimits.DEFAULTS);
    }

    /** Preserves readable candidates and individual bean-discovery failures from native adapters. */
    public static DatabaseAdvisorScanner usingDiscovery(
            Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Supplier<List<SqlTraceEntryDto>> observedStatementSupplier,
            Clock clock) {
        return new DatabaseAdvisorScanner(
                dataSourceSupplier,
                entityDiscoverySupplier,
                observedStatementSupplier,
                clock,
                DatabaseAdvisorLimits.DEFAULTS);
    }

    private static Supplier<DatabaseAdvisorDataSourceDiscovery> discoveryFrom(
            Supplier<List<NamedDataSource>> supplier) {
        return () -> new DatabaseAdvisorDataSourceDiscovery(supplier.get(), List.of());
    }

    private DatabaseAdvisorScanner(
            Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Supplier<List<SqlTraceEntryDto>> observedStatementSupplier,
            Clock clock,
            DatabaseAdvisorLimits limits) {
        this.dataSourceSupplier = dataSourceSupplier;
        this.entityDiscoverySupplier = entityDiscoverySupplier;
        this.observedStatementSupplier = observedStatementSupplier;
        this.clock = clock;
        this.limits = limits;
    }

    /** Test seam: the same scanner running under explicit bounds. */
    static DatabaseAdvisorScanner using(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Clock clock,
            DatabaseAdvisorLimits limits) {
        return new DatabaseAdvisorScanner(
                discoveryFrom(dataSourceSupplier), entityDiscoverySupplier, List::of, clock, limits);
    }

    /** Test seam: explicit bounds plus runtime statement evidence. */
    static DatabaseAdvisorScanner using(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Supplier<List<SqlTraceEntryDto>> observedStatementSupplier,
            Clock clock,
            DatabaseAdvisorLimits limits) {
        return new DatabaseAdvisorScanner(
                discoveryFrom(dataSourceSupplier), entityDiscoverySupplier, observedStatementSupplier, clock, limits);
    }

    public DatabaseAdvisorReport initialReport() {
        return new ReportBuilder()
                .status("NOT_SCANNED")
                .message("Database Advisor has not run yet. Click Run Database checks to inspect the physical schema.")
                .build();
    }

    public DatabaseAdvisorReport scan() {
        return singleFlight.run(ActionOperations.DATABASE_ADVISOR_SCAN, this::doScan);
    }

    private DatabaseAdvisorReport doScan() {
        DatabaseAdvisorDataSourceDiscovery discovery = discoverDataSources();
        List<NamedDataSource> dataSources = discovery.dataSources();
        if (dataSources.isEmpty() && discovery.failures().isEmpty()) {
            return new ReportBuilder()
                    .status("DISABLED")
                    .message("No DataSource beans were found to inspect.")
                    .scannedAt(clock.millis())
                    .build();
        }

        ScanBudget budget = ScanBudget.of(limits.scanBudget());
        List<SchemaSnapshot> schemas = new ArrayList<>(dataSources.stream()
                .map(dataSource ->
                        SchemaIntrospector.introspect(dataSource.name(), dataSource.dataSource(), budget, limits))
                .toList());
        discovery.failures().forEach(failure -> schemas.add(SchemaSnapshot.failed(failure.name(), failure.message())));
        List<String> sourceNames = schemas.stream()
                .map(SchemaSnapshot::dataSourceName)
                .distinct()
                .sorted()
                .toList();
        List<SchemaSnapshot> available =
                schemas.stream().filter(SchemaSnapshot::available).toList();

        List<DatabaseAdvisorDiagnosticDto> diagnostics = new ArrayList<>(schemaDiagnostics(schemas));
        List<DatabaseAdvisorDataSourceDto> dataSourceStatuses = dataSourceStatuses(schemas);
        boolean truncated = schemas.stream().anyMatch(SchemaSnapshot::truncated);

        if (available.isEmpty()) {
            return new ReportBuilder()
                    .status("ERROR")
                    .message(
                            "No physical schema could be read. See diagnostics for datasource discovery and read failures.")
                    .scannedAt(clock.millis())
                    .dataSourceNames(sourceNames)
                    .dataSources(dataSourceStatuses)
                    .diagnostics(diagnostics)
                    .truncated(truncated)
                    .build();
        }

        EntityDiscovery entityDiscovery = safeEntityDiscovery(diagnostics);
        boolean hibernateAvailable = !entityDiscovery.entities().isEmpty();
        List<MappedEntityFacts> mappedEntities = mappedEntities(entityDiscovery, diagnostics);

        DatabaseAdvisorContext context = new DatabaseAdvisorContext(
                schemas, hibernateAvailable, mappedEntities, safeObservedStatements(diagnostics));
        List<DatabaseAdvisorRuleResultDto> results = DatabaseAdvisorRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        diagnostics.addAll(ruleDiagnostics(results));
        for (SchemaDiagnostic diagnostic : context.evaluationDiagnostics()) {
            diagnostics.add(
                    new DatabaseAdvisorDiagnosticDto(diagnostic.source(), diagnostic.level(), diagnostic.message()));
        }

        int tablesAnalyzed = context.tableCount();
        int rulesSkipped = countStatus(results, DatabaseAdvisorRuleSupport.SKIPPED);
        int rulesErrored = countStatus(results, DatabaseAdvisorRuleSupport.ERROR);
        boolean allAvailable = available.size() == schemas.size();
        boolean allComplete = available.stream().allMatch(SchemaSnapshot::complete);
        boolean evidenceFailed = diagnostics.stream()
                .anyMatch(diagnostic -> SchemaDiagnostic.WARNING.equals(diagnostic.level())
                        || SchemaDiagnostic.ERROR.equals(diagnostic.level()));
        boolean complete = allAvailable && allComplete && !truncated && rulesErrored == 0 && !evidenceFailed;

        StringBuilder message = new StringBuilder("Database Advisor completed against ")
                .append(tablesAnalyzed)
                .append(tablesAnalyzed == 1 ? " table" : " tables")
                .append(" across ")
                .append(available.size())
                .append(available.size() == 1 ? " datasource" : " datasources")
                .append('.');
        if (!allAvailable) {
            message.append(" ").append(schemas.size() - available.size()).append(" datasource(s) could not be read.");
        }
        if (truncated) {
            message.append(" A scan bound was reached, so some findings may be missing.");
        } else if (!allComplete) {
            message.append(" Some schema or catalog metadata could not be read; see the diagnostics.");
        }
        if (rulesErrored > 0) {
            message.append(" ").append(rulesErrored).append(" rule(s) failed to evaluate.");
        }
        if (evidenceFailed && allAvailable && allComplete && rulesErrored == 0) {
            message.append(" Some advisor evidence could not be read; see the diagnostics.");
        }

        return new ReportBuilder()
                .status(complete ? "SCANNED" : "PARTIAL")
                .message(message.toString())
                .scannedAt(clock.millis())
                .dataSourceNames(sourceNames)
                .dataSources(dataSourceStatuses)
                .tablesAnalyzed(tablesAnalyzed)
                .results(results)
                .rulesSkipped(rulesSkipped)
                .rulesErrored(rulesErrored)
                .truncated(truncated)
                .diagnostics(diagnostics)
                .build();
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
                report.dataSources(),
                report.tablesAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                report.rulesSkipped(),
                report.rulesErrored(),
                report.truncated(),
                severityCounts(active),
                updatedScan,
                marked,
                report.diagnostics());
    }

    private List<DatabaseAdvisorDiagnosticDto> schemaDiagnostics(List<SchemaSnapshot> schemas) {
        List<DatabaseAdvisorDiagnosticDto> diagnostics = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            for (SchemaDiagnostic diagnostic : schema.diagnostics()) {
                diagnostics.add(new DatabaseAdvisorDiagnosticDto(
                        diagnostic.source(), diagnostic.level(), diagnostic.message()));
            }
        }
        return diagnostics;
    }

    /**
     * Rules that could not run are reported too: a skipped vendor rule is normal and informational, while a
     * rule that threw is a warning — neither may silently look like a passing check.
     */
    private List<DatabaseAdvisorDiagnosticDto> ruleDiagnostics(List<DatabaseAdvisorRuleResultDto> results) {
        List<DatabaseAdvisorDiagnosticDto> diagnostics = new ArrayList<>();
        for (DatabaseAdvisorRuleResultDto result : results) {
            String reason = result.sampleViolations().isEmpty()
                    ? "No reason was reported."
                    : result.sampleViolations().get(0);
            if (DatabaseAdvisorRuleSupport.SKIPPED.equals(result.status())) {
                diagnostics.add(new DatabaseAdvisorDiagnosticDto(result.id(), SchemaDiagnostic.INFO, reason));
            } else if (DatabaseAdvisorRuleSupport.ERROR.equals(result.status())) {
                diagnostics.add(new DatabaseAdvisorDiagnosticDto(result.id(), SchemaDiagnostic.WARNING, reason));
            }
        }
        return diagnostics;
    }

    private List<DatabaseAdvisorDataSourceDto> dataSourceStatuses(List<SchemaSnapshot> schemas) {
        List<DatabaseAdvisorDataSourceDto> statuses = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            String status;
            String message = null;
            if (!schema.available()) {
                status = "FAILED";
                message = schema.error();
            } else if (!schema.complete()) {
                status = "PARTIAL";
                message = firstProblem(schema);
            } else {
                status = "AVAILABLE";
            }
            statuses.add(new DatabaseAdvisorDataSourceDto(
                    schema.dataSourceName(),
                    schema.available() ? schema.describeProduct() : null,
                    schema.dialect().label(),
                    schema.identifierCase(),
                    status,
                    message,
                    DatabaseAdvisorContext.physicalTables(schema).size(),
                    schema.truncated()));
        }
        return statuses;
    }

    private String firstProblem(SchemaSnapshot schema) {
        return schema.diagnostics().stream()
                .filter(diagnostic -> !SchemaDiagnostic.INFO.equals(diagnostic.level()))
                .map(SchemaDiagnostic::message)
                .findFirst()
                .orElse(null);
    }

    private DatabaseAdvisorDataSourceDiscovery discoverDataSources() {
        try {
            DatabaseAdvisorDataSourceDiscovery discovery = dataSourceSupplier.get();
            if (discovery == null) {
                throw new IllegalStateException("Datasource discovery returned no outcome.");
            }
            return discovery;
        } catch (RuntimeException | LinkageError ex) {
            return new DatabaseAdvisorDataSourceDiscovery(
                    List.of(),
                    List.of(new DatabaseAdvisorDataSourceDiscovery.Failure(
                            "datasource discovery", "Datasource discovery failed: " + ex.getMessage())));
        }
    }

    private EntityDiscovery safeEntityDiscovery(List<DatabaseAdvisorDiagnosticDto> diagnostics) {
        try {
            EntityDiscovery discovery = entityDiscoverySupplier.get();
            if (discovery == null) {
                throw new IllegalStateException("Entity discovery returned no outcome.");
            }
            for (String error : discovery.errors()) {
                evidenceDiagnostic(
                        diagnostics,
                        "Hibernate metadata",
                        discovery.entities().isEmpty() ? SchemaDiagnostic.INFO : SchemaDiagnostic.WARNING,
                        error);
            }
            return discovery;
        } catch (RuntimeException | LinkageError ex) {
            evidenceDiagnostic(
                    diagnostics,
                    "Hibernate metadata",
                    SchemaDiagnostic.WARNING,
                    "Entity discovery failed: " + ex.getMessage());
            return EntityDiscovery.empty("Entity discovery failed.");
        }
    }

    private List<MappedEntityFacts> mappedEntities(
            EntityDiscovery discovery, List<DatabaseAdvisorDiagnosticDto> diagnostics) {
        try {
            return HibernateSchemaBridge.toMappedEntities(discovery.entities());
        } catch (RuntimeException | LinkageError ex) {
            evidenceDiagnostic(
                    diagnostics,
                    "Hibernate metadata",
                    SchemaDiagnostic.WARNING,
                    "Mapping declarations could not be read: " + ex.getMessage());
            return List.of();
        }
    }

    /**
     * Runtime statement evidence, fully guarded: SQL Trace being absent, disabled or failing must degrade the
     * runtime-SQL rules to {@code SKIPPED}, never abort a schema scan that is otherwise perfectly readable.
     */
    private List<SqlTraceEntryDto> safeObservedStatements(List<DatabaseAdvisorDiagnosticDto> diagnostics) {
        try {
            List<SqlTraceEntryDto> statements = observedStatementSupplier.get();
            if (statements == null) {
                throw new IllegalStateException("SQL Trace returned no evidence collection.");
            }
            return List.copyOf(statements);
        } catch (RuntimeException | LinkageError ex) {
            evidenceDiagnostic(
                    diagnostics,
                    "SQL Trace",
                    SchemaDiagnostic.WARNING,
                    "Retained SQL evidence could not be read: " + ex.getMessage());
            return List.of();
        }
    }

    private static void evidenceDiagnostic(
            List<DatabaseAdvisorDiagnosticDto> diagnostics, String source, String level, String message) {
        SchemaDiagnostic diagnostic = SchemaDiagnostic.WARNING.equals(level)
                ? SchemaDiagnostic.warning(source, message)
                : SchemaDiagnostic.info(source, message);
        diagnostics.add(
                new DatabaseAdvisorDiagnosticDto(diagnostic.source(), diagnostic.level(), diagnostic.message()));
    }

    private static List<DatabaseAdvisorSeverityCountDto> severityCounts(List<DatabaseAdvisorRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.occurrenceCounts(
                results,
                DatabaseAdvisorScanner::isViolation,
                DatabaseAdvisorRuleResultDto::severity,
                DatabaseAdvisorRuleResultDto::violationCount);
        return counts.entrySet().stream()
                .map(entry -> new DatabaseAdvisorSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<DatabaseAdvisorRuleResultDto> violationResults(List<DatabaseAdvisorRuleResultDto> results) {
        return results.stream()
                .filter(DatabaseAdvisorScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static int countStatus(List<DatabaseAdvisorRuleResultDto> results, String status) {
        return (int) results.stream()
                .filter(result -> status.equals(result.status()))
                .count();
    }

    private static boolean isViolation(DatabaseAdvisorRuleResultDto result) {
        return DatabaseAdvisorRuleSupport.VIOLATION.equals(result.status());
    }

    /** Assembles the wire report so every exit path reports the same shape. */
    private static final class ReportBuilder {

        private String status = "NOT_SCANNED";
        private String message;
        private Long scannedAt;
        private List<String> dataSourceNames = List.of();
        private List<DatabaseAdvisorDataSourceDto> dataSources = List.of();
        private int tablesAnalyzed;
        private List<DatabaseAdvisorRuleResultDto> results = List.of();
        private int rulesSkipped;
        private int rulesErrored;
        private boolean truncated;
        private List<DatabaseAdvisorDiagnosticDto> diagnostics = List.of();

        ReportBuilder status(String value) {
            this.status = value;
            return this;
        }

        ReportBuilder message(String value) {
            this.message = value;
            return this;
        }

        ReportBuilder scannedAt(Long value) {
            this.scannedAt = value;
            return this;
        }

        ReportBuilder dataSourceNames(List<String> value) {
            this.dataSourceNames = value;
            return this;
        }

        ReportBuilder dataSources(List<DatabaseAdvisorDataSourceDto> value) {
            this.dataSources = value;
            return this;
        }

        ReportBuilder tablesAnalyzed(int value) {
            this.tablesAnalyzed = value;
            return this;
        }

        ReportBuilder results(List<DatabaseAdvisorRuleResultDto> value) {
            this.results = value;
            return this;
        }

        ReportBuilder rulesSkipped(int value) {
            this.rulesSkipped = value;
            return this;
        }

        ReportBuilder rulesErrored(int value) {
            this.rulesErrored = value;
            return this;
        }

        ReportBuilder truncated(boolean value) {
            this.truncated = value;
            return this;
        }

        ReportBuilder diagnostics(List<DatabaseAdvisorDiagnosticDto> value) {
            this.diagnostics = value;
            return this;
        }

        DatabaseAdvisorReport build() {
            List<DatabaseAdvisorRuleResultDto> violations = violationResults(results);
            DatabaseAdvisorScanStatusDto scan = new DatabaseAdvisorScanStatusDto(
                    ANALYZER, status, message, scannedAt, results.size(), tablesAnalyzed, violations.size());
            return new DatabaseAdvisorReport(
                    true,
                    DISCLAIMER,
                    List.copyOf(dataSourceNames),
                    List.copyOf(dataSources),
                    tablesAnalyzed,
                    results.size(),
                    violations.size(),
                    rulesSkipped,
                    rulesErrored,
                    truncated,
                    severityCounts(violations),
                    scan,
                    violations,
                    List.copyOf(diagnostics));
        }
    }
}
