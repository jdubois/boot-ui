package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorDataSourceDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorDiagnosticDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorScanStatusDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorSeverityCountDto;
import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.advisor.AdvisorFindingIdentity;
import io.github.jdubois.bootui.engine.advisor.AdvisorScanState;
import io.github.jdubois.bootui.engine.advisor.AdvisorSuppression;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolation;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.DataSourceConfiguration;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider.JdbcBatching;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
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
    private final DatabaseAdvisorEvidenceProvider evidenceProvider;
    private final Clock clock;
    private final DatabaseAdvisorLimits limits;
    private final SingleFlightAction singleFlight = new SingleFlightAction();
    private final AdvisorScanState<DatabaseAdvisorReport> state = new AdvisorScanState<>(
                    DatabaseAdvisorReport::withViolationDetails,
                    () -> AdvisorScanState.ruleIds(
                            DatabaseAdvisorRuleRegistry.activeRules(),
                            rule -> rule.definition().id()))
            .trackingChanges();

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
        return usingDiscovery(dataSourceSupplier, entityDiscoverySupplier, observedStatementSupplier, null, clock);
    }

    /**
     * The scanner with JDBC driver evidence. {@code evidenceProvider} supplies what a connection cannot report (the
     * pool's driver properties and Hibernate's effective JDBC batching) and may be {@code null}, in which case the
     * driver rules judge the URL alone and skip whatever needs batching.
     */
    public static DatabaseAdvisorScanner usingDiscovery(
            Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Supplier<List<SqlTraceEntryDto>> observedStatementSupplier,
            DatabaseAdvisorEvidenceProvider evidenceProvider,
            Clock clock) {
        return new DatabaseAdvisorScanner(
                dataSourceSupplier,
                entityDiscoverySupplier,
                observedStatementSupplier,
                evidenceProvider,
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
        this(dataSourceSupplier, entityDiscoverySupplier, observedStatementSupplier, null, clock, limits);
    }

    private DatabaseAdvisorScanner(
            Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Supplier<List<SqlTraceEntryDto>> observedStatementSupplier,
            DatabaseAdvisorEvidenceProvider evidenceProvider,
            Clock clock,
            DatabaseAdvisorLimits limits) {
        this.dataSourceSupplier = dataSourceSupplier;
        this.entityDiscoverySupplier = entityDiscoverySupplier;
        this.observedStatementSupplier = observedStatementSupplier;
        this.evidenceProvider = evidenceProvider;
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
                .message("Database Advisor has not run yet. Run Database checks in the panel, or call"
                        + " database_advisor_scan (bootui db scan), to inspect the physical schema.")
                .build();
    }

    public DatabaseAdvisorReport scan() {
        return singleFlight.run(ActionOperations.DATABASE_ADVISOR_SCAN, () -> {
            AdvisorViolationCollector collector = state.collector();
            return state.publish(doScan(collector), collector);
        });
    }

    public DatabaseAdvisorReport lastReport() {
        return state.currentReport(this::initialReport);
    }

    public AdvisorRuleViolationsDto ruleViolations(String ruleId, String scanId, Integer offset, Integer limit) {
        return state.ruleViolations(ruleId, scanId, offset, limit);
    }

    /** One page of retained findings, flagged where the developer dismissed the finding. */
    public AdvisorRuleViolationsDto ruleViolations(
            String ruleId, String scanId, Integer offset, Integer limit, List<DismissedFindingDto> dismissedFindings) {
        return state.ruleViolations(ruleId, scanId, offset, limit, dismissedFindings);
    }

    public void setViolationRetentionLimit(IntSupplier limit) {
        state.setRetentionLimit(limit);
    }

    private DatabaseAdvisorReport doScan(AdvisorViolationCollector collector) {
        DatabaseAdvisorDataSourceDiscovery discovery = discoverDataSources();
        List<NamedDataSource> dataSources = discovery.dataSources();
        if (dataSources.isEmpty() && discovery.failures().isEmpty()) {
            collector.markScanUnverified("No datasource was found, so no rule could run.");
            return new ReportBuilder()
                    .status("DISABLED")
                    .message("No DataSource beans were found to inspect.")
                    .scannedAt(clock.millis())
                    .build();
        }

        List<DatabaseAdvisorDiagnosticDto> evidenceDiagnostics = new ArrayList<>();
        DriverEvidenceInputs driverEvidence = safeDriverEvidence(dataSources, evidenceDiagnostics);

        ScanBudget budget = ScanBudget.of(limits.scanBudget());
        List<SchemaSnapshot> schemas = new ArrayList<>(dataSources.stream()
                .map(dataSource -> SchemaIntrospector.introspect(
                        dataSource.name(),
                        dataSource.dataSource(),
                        budget,
                        limits,
                        driverEvidence.poolDriverProperties(dataSource.name())))
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
        diagnostics.addAll(evidenceDiagnostics);
        List<DatabaseAdvisorDataSourceDto> dataSourceStatuses = dataSourceStatuses(schemas);
        boolean truncated = schemas.stream().anyMatch(SchemaSnapshot::truncated);

        for (SchemaSnapshot schema : schemas) {
            collector.markScopeObserved(AdvisorFindingIdentity.encode("ds=" + schema.dataSourceName()));
            // Only a dialect with partitioned tables can lose relations when the inventory falls back to plain tables.
            boolean partialInventory = schema.dialect() == Dialect.POSTGRESQL && !schema.relationInventoryComplete();
            if (!schema.available() || schema.truncated() || partialInventory) {
                collector.markScopeUnverified(
                        AdvisorFindingIdentity.encode("ds=" + schema.dataSourceName()),
                        "Datasource " + schema.dataSourceName()
                                + (schema.available() ? " was read only in part." : " could not be read."));
            }
        }
        if (available.isEmpty()) {
            collector.markScanUnverified("No physical schema could be read, so no rule could run.");
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

        int diagnosticsBeforeMappings = diagnostics.size();
        EntityDiscovery entityDiscovery = safeEntityDiscovery(diagnostics);
        boolean hibernateAvailable = !entityDiscovery.entities().isEmpty();
        List<MappedEntityFacts> mappedEntities = mappedEntities(entityDiscovery, diagnostics);
        // A mapping that was lost leaves its findings unreported, so the cross-reference rules conclude nothing.
        boolean mappingsComplete = diagnostics.size() == diagnosticsBeforeMappings;

        DatabaseAdvisorContext context = new DatabaseAdvisorContext(
                schemas,
                hibernateAvailable,
                mappedEntities,
                safeObservedStatements(diagnostics),
                List.of(),
                null,
                driverEvidence.jdbcBatching());
        List<DatabaseAdvisorRuleResultDto> results = new ArrayList<>();
        boolean usable = false;
        for (DatabaseAdvisorRule rule : DatabaseAdvisorRuleRegistry.activeRules()) {
            // Each rule gets its own gap journal: a bounded shared journal cannot prove later rules completed.
            DatabaseAdvisorContext evaluation = new DatabaseAdvisorContext(
                    schemas,
                    hibernateAvailable,
                    mappedEntities,
                    context.observedStatements(),
                    List.of(),
                    collector,
                    context.jdbcBatching());
            DatabaseAdvisorRuleResultDto result = rule.evaluate(evaluation);
            results.add(result);
            if (rule.comparableAcrossScans()
                    && (mappingsComplete || !rule.readsMappings())
                    && (DatabaseAdvisorRuleSupport.PASS.equals(result.status())
                            || isViolation(result)
                            || DatabaseAdvisorRuleSupport.noTargets(result))
                    && evaluation.evaluationDiagnostics().isEmpty()) {
                collector.markConcluded(rule.definition().id(), AdvisorViolationCollector.ALL_SCOPES);
            }
            usable |= isViolation(result)
                    || evaluation.evaluationDiagnostics().isEmpty()
                            && DatabaseAdvisorRuleSupport.PASS.equals(result.status());
            for (SchemaDiagnostic diagnostic : evaluation.evaluationDiagnostics()) {
                context.unknown(diagnostic.source(), diagnostic.message());
            }
        }
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
                .evidence(new AdvisorEvidenceDto(
                        usable,
                        complete,
                        complete
                                ? List.of()
                                : diagnostics.stream()
                                        .filter(diagnostic -> SchemaDiagnostic.WARNING.equals(diagnostic.level())
                                                || SchemaDiagnostic.ERROR.equals(diagnostic.level()))
                                        .map(diagnostic -> diagnostic.source() + ": " + diagnostic.message())
                                        .distinct()
                                        .limit(20)
                                        .toList()))
                .build();
    }

    public DatabaseAdvisorReport applyDismissals(DatabaseAdvisorReport report, Set<String> dismissedIds) {
        return applyDismissals(report, dismissedIds, List.of());
    }

    /**
     * Applies what the developer dismissed to the report at read time: whole rules, and single findings of the same
     * completed scan the report describes. A rule left without an active finding counts like a dismissed one, but
     * stays listed (with {@code suppressedCount}) so its findings can be restored. The retained findings and the
     * comparison with the previous scan are never touched.
     */
    public DatabaseAdvisorReport applyDismissals(
            DatabaseAdvisorReport report, Set<String> dismissedIds, List<DismissedFindingDto> dismissedFindings) {
        boolean rules = dismissedIds != null && !dismissedIds.isEmpty();
        boolean findings = dismissedFindings != null && !dismissedFindings.isEmpty();
        if (report == null || (!rules && !findings)) {
            return report;
        }
        Set<String> ruleIds = rules ? dismissedIds : Set.of();
        AdvisorSuppression suppression = findings
                ? state.suppression(report.violationDetails().scanId(), ruleIds, dismissedFindings, "DB-")
                : null;
        return applyDismissals(report, ruleIds, suppression);
    }

    /**
     * The latest report with everything the developer dismissed applied, from one snapshot of the latest completed
     * scan: the report and the retained findings it is projected from always belong to the same scan.
     */
    public DatabaseAdvisorReport dismissedReport(
            Set<String> dismissedIds, List<DismissedFindingDto> dismissedFindings) {
        boolean rules = dismissedIds != null && !dismissedIds.isEmpty();
        boolean findings = dismissedFindings != null && !dismissedFindings.isEmpty();
        if (!rules && !findings) {
            return lastReport();
        }
        Set<String> ruleIds = rules ? dismissedIds : Set.of();
        if (!findings) {
            return applyDismissals(lastReport(), ruleIds, (AdvisorSuppression) null);
        }
        AdvisorScanState.Current<DatabaseAdvisorReport> current =
                state.current(this::initialReport, ruleIds, dismissedFindings, "DB-");
        return applyDismissals(current.report(), ruleIds, current.suppression());
    }

    private DatabaseAdvisorReport applyDismissals(
            DatabaseAdvisorReport report, Set<String> ruleIds, AdvisorSuppression suppression) {
        List<DatabaseAdvisorRuleResultDto> marked =
                new ArrayList<>(report.results().size());
        boolean projected = false;
        for (DatabaseAdvisorRuleResultDto result : report.results()) {
            DatabaseAdvisorRuleResultDto next = result.withDismissed(ruleIds.contains(result.id()));
            AdvisorSuppression.Rule rule =
                    suppression == null || next.dismissed() ? null : suppression.rule(result.id());
            if (rule != null) {
                next = suppress(next, rule);
                projected = true;
            }
            marked.add(next);
        }
        if (projected) {
            marked.sort(IMPORTANCE_ORDER);
        }
        List<DatabaseAdvisorRuleResultDto> active = marked.stream()
                .filter(result -> !result.dismissed() && result.violationCount() > 0)
                .toList();
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
        return report.withSuppression(
                violationsFound,
                severityCounts(active),
                updatedScan,
                marked,
                suppression == null ? report.suppression() : suppression.dto());
    }

    private static DatabaseAdvisorRuleResultDto suppress(
            DatabaseAdvisorRuleResultDto result, AdvisorSuppression.Rule rule) {
        AdvisorSuppression.Sample sample =
                rule.sample(result.sampleViolations(), DatabaseAdvisorRuleSupport.MAX_SAMPLE_VIOLATIONS);
        List<String> texts = new ArrayList<>();
        for (int index : sample.kept()) {
            texts.add(result.sampleViolations().get(index));
        }
        for (AdvisorViolation added : sample.added()) {
            texts.add(added.text());
        }
        return result.withSuppression(rule.suppressedCount(), result.violationCount() - rule.suppressedCount(), texts);
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

    /** The pool's driver properties per datasource and the effective JDBC batching, as the scan will use them. */
    private record DriverEvidenceInputs(
            Map<String, DataSourceConfiguration> configurations, Map<String, JdbcBatching> jdbcBatching) {

        static final DriverEvidenceInputs NONE = new DriverEvidenceInputs(Map.of(), Map.of());

        /** The pool's driver properties, or {@code null} when they were not read for this datasource. */
        Map<String, String> poolDriverProperties(String dataSourceName) {
            DataSourceConfiguration configuration = configurations.get(dataSourceName);
            return configuration == null || !configuration.driverPropertiesKnown()
                    ? null
                    : configuration.driverProperties();
        }
    }

    /**
     * Driver evidence from the adapter, fully guarded: a failing provider degrades the driver rules to skipped, never
     * the schema scan. Diagnostics use fixed text because a provider failure can quote connection settings. A name
     * that is ambiguous (twice discovered or twice configured) gets no configuration rather than a guessed one.
     */
    private DriverEvidenceInputs safeDriverEvidence(
            List<NamedDataSource> dataSources, List<DatabaseAdvisorDiagnosticDto> diagnostics) {
        if (evidenceProvider == null) {
            return DriverEvidenceInputs.NONE;
        }
        Map<String, DataSourceConfiguration> configurations = new HashMap<>();
        try {
            Set<String> discovered = new HashSet<>();
            Set<String> ambiguous = new HashSet<>();
            dataSources.forEach(dataSource -> {
                if (!discovered.add(dataSource.name())) {
                    ambiguous.add(dataSource.name());
                }
            });
            List<DataSourceConfiguration> reported = evidenceProvider.dataSourceConfigurations();
            for (DataSourceConfiguration configuration :
                    reported == null ? List.<DataSourceConfiguration>of() : reported) {
                if (configuration != null && discovered.contains(configuration.dataSourceName())) {
                    if (configurations.put(configuration.dataSourceName(), configuration) != null) {
                        ambiguous.add(configuration.dataSourceName());
                    }
                }
            }
            ambiguous.forEach(configurations::remove);
        } catch (RuntimeException | LinkageError ex) {
            configurations.clear();
            evidenceDiagnostic(
                    diagnostics,
                    "JDBC driver evidence",
                    SchemaDiagnostic.WARNING,
                    "The connection pool's driver properties could not be read; settings configured there are not"
                            + " evaluated.");
        }
        Map<String, JdbcBatching> batching = Map.of();
        try {
            Map<String, JdbcBatching> reported = evidenceProvider.jdbcBatching();
            if (reported != null) {
                Map<String, JdbcBatching> valid = new HashMap<>();
                reported.forEach((name, value) -> {
                    if (name != null && value != null && value.status() != null) {
                        valid.put(name, value);
                    }
                });
                batching = valid;
            }
        } catch (RuntimeException | LinkageError ex) {
            evidenceDiagnostic(
                    diagnostics,
                    "JDBC driver evidence",
                    SchemaDiagnostic.WARNING,
                    "Hibernate's JDBC batching could not be read; batching-dependent driver checks are skipped.");
        }
        return new DriverEvidenceInputs(configurations, batching);
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
        private AdvisorEvidenceDto evidence = AdvisorEvidenceDto.unknown();

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

        ReportBuilder evidence(AdvisorEvidenceDto value) {
            this.evidence = value;
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
                    List.copyOf(diagnostics),
                    evidence);
        }
    }
}
