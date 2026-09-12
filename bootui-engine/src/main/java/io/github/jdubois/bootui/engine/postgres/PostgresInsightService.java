package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.PostgresChangeDto;
import io.github.jdubois.bootui.core.dto.PostgresDatabaseDto;
import io.github.jdubois.bootui.core.dto.PostgresDiagnosticDto;
import io.github.jdubois.bootui.core.dto.PostgresFindingDto;
import io.github.jdubois.bootui.core.dto.PostgresInsightReport;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresSeverityCountDto;
import io.github.jdubois.bootui.core.dto.PostgresTableDto;
import io.github.jdubois.bootui.core.dto.PostgresVitalSignsDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseVersion;
import io.github.jdubois.bootui.engine.databaseadvisor.Dialect;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bounded, on-demand, read-only "vital signs" read of the application's own PostgreSQL database.
 *
 * <p>It asks the database what it thinks of its own health — cache, transactions, connections, locks,
 * wraparound, statements, indexes, relations, autovacuum, replication and a curated set of settings — which
 * includes work done by every other client of that database. That is what separates it from the Database
 * Advisor (structural schema checks) and SQL Trace (statements this JVM executed).</p>
 *
 * <p>Every guarantee here is a refusal. It never writes, never cancels or terminates a backend, never reads
 * application rows, never persists a baseline to disk, and never runs on page render. Each read runs inside
 * one read-only transaction per datasource with {@code statement_timeout} and {@code lock_timeout} pinned on
 * the session, under a cooperative wall-clock budget, with every list query bounded by row count. A section
 * the role or the server cannot supply is reported as {@code SKIPPED} or {@code FAILED} with its reason —
 * never as a passing check.</p>
 */
public final class PostgresInsightService {

    private static final String DISCLAIMER =
            "Read-only reads of PostgreSQL's own pg_stat_* and pg_catalog views, bounded by row count and a "
                    + "wall-clock budget. Statistics are cumulative since the last reset and cover every client of "
                    + "the database, not only this application. These checks are review prompts, not verdicts.";

    private static final Comparator<PostgresFindingDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (PostgresFindingDto finding) -> SeverityOrder.rank(finding.severity()))
            .thenComparing(PostgresFindingDto::dataSource, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PostgresFindingDto::id);

    private static final List<PostgresCollector> COLLECTORS = List.of(
            new PostgresSettingsCollector(),
            new PostgresVitalSignsCollector(),
            new PostgresStatementCollector(),
            new PostgresIndexCollector(),
            new PostgresTableCollector(),
            new PostgresVacuumCollector(),
            new PostgresReplicationCollector());

    private final Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier;
    private final ExposurePolicy exposure;
    private final Clock clock;
    private final PostgresInsightLimits limits;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    /**
     * The previous read of this process, kept in memory only so a second read can show what moved. BootUI is
     * a local developer console, not a monitoring platform: nothing here is written to disk, and restarting
     * the application starts over.
     */
    private final Map<String, List<PostgresMetric>> previousMetrics = new ConcurrentHashMap<>();

    public static PostgresInsightService using(
            Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier, ExposurePolicy exposure, Clock clock) {
        return new PostgresInsightService(dataSourceSupplier, exposure, clock, PostgresInsightLimits.DEFAULTS);
    }

    /** Test seam: the same service running under explicit bounds. */
    static PostgresInsightService using(
            Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier,
            ExposurePolicy exposure,
            Clock clock,
            PostgresInsightLimits limits) {
        return new PostgresInsightService(dataSourceSupplier, exposure, clock, limits);
    }

    private PostgresInsightService(
            Supplier<DatabaseAdvisorDataSourceDiscovery> dataSourceSupplier,
            ExposurePolicy exposure,
            Clock clock,
            PostgresInsightLimits limits) {
        this.dataSourceSupplier = dataSourceSupplier;
        this.exposure = exposure;
        this.clock = clock;
        this.limits = limits;
    }

    /** The report served before the user has asked for anything; nothing has touched the database yet. */
    public PostgresInsightReport initialReport() {
        return report(
                "NOT_READ",
                "PostgreSQL vital signs have not been read yet. Click Run PostgreSQL read to query the database's "
                        + "own statistics views.",
                null,
                List.of(),
                List.of(),
                List.of(),
                false);
    }

    /** Reads every PostgreSQL datasource once. Single-flighted: a concurrent read is refused, never queued. */
    public PostgresInsightReport read() {
        return singleFlight.run(ActionOperations.POSTGRESQL_READ, this::doRead);
    }

    private PostgresInsightReport doRead() {
        DatabaseAdvisorDataSourceDiscovery discovery = discover();
        List<PostgresDiagnosticDto> diagnostics = new ArrayList<>();
        for (DatabaseAdvisorDataSourceDiscovery.Failure failure : discovery.failures()) {
            diagnostics.add(new PostgresDiagnosticDto(
                    failure.name(), "ERROR", CredentialRedaction.redact(String.valueOf(failure.message()))));
        }
        if (discovery.dataSources().isEmpty() && discovery.failures().isEmpty()) {
            return report(
                    "DISABLED",
                    "No DataSource beans were found to inspect.",
                    clock.millis(),
                    List.of(),
                    List.of(),
                    diagnostics,
                    false);
        }

        PostgresReadBudget budget = PostgresReadBudget.of(limits.readBudget());
        List<PostgresDatabaseDto> databases = new ArrayList<>();
        List<PostgresFindingDto> findings = new ArrayList<>();
        // Datasources the read never reached. They are neither successes nor failures, but leaving them out
        // of the status would let an exhausted budget produce a clean-looking report over a partial read.
        List<String> unread = new ArrayList<>();
        int postgresCandidates = 0;
        for (NamedDataSource dataSource : discovery.dataSources()) {
            DatabaseRead read = readDataSource(dataSource, budget, diagnostics, unread);
            if (read == null) {
                continue;
            }
            postgresCandidates++;
            databases.add(read.database());
            findings.addAll(read.findings());
        }

        if (postgresCandidates == 0) {
            boolean failed = !discovery.failures().isEmpty() || !unread.isEmpty();
            String message;
            if (!unread.isEmpty()) {
                message = "The read budget ran out before any datasource was read, so nothing was inspected.";
            } else if (discovery.failures().isEmpty()) {
                message = "No PostgreSQL datasource was found. This panel reads PostgreSQL's own statistics views, "
                        + "so it has nothing to report for other database products.";
            } else {
                message = "No PostgreSQL datasource could be read.";
            }
            return report(
                    failed ? "ERROR" : "DISABLED", message, clock.millis(), List.of(), List.of(), diagnostics, false);
        }

        findings.sort(IMPORTANCE_ORDER);
        boolean truncated = databases.stream().anyMatch(PostgresDatabaseDto::truncated);
        boolean anyError = databases.stream().anyMatch(database -> "ERROR".equals(database.status()));
        boolean anyPartial = databases.stream().anyMatch(database -> "PARTIAL".equals(database.status()));
        // A datasource that could not even be discovered is already an ERROR diagnostic. Leaving it out of
        // the status would let a read that reached only some of the application's datasources look clean.
        boolean discoveryFailed = !discovery.failures().isEmpty();
        String status = anyError && databases.stream().allMatch(database -> "ERROR".equals(database.status()))
                ? "ERROR"
                : (anyError || anyPartial || truncated || discoveryFailed || !unread.isEmpty() ? "PARTIAL" : "READ");
        String message =
                switch (status) {
                    case "ERROR" -> "No PostgreSQL datasource could be read.";
                    case "PARTIAL" -> {
                        if (!unread.isEmpty()) {
                            yield "The read budget ran out before every datasource was read; see the diagnostics.";
                        }
                        yield discoveryFailed
                                ? "Some datasources could not be discovered; see the diagnostics."
                                : "Some subsystems could not be read; see the section status and diagnostics.";
                    }
                    default -> null;
                };
        List<String> limitations = new ArrayList<>(unread);
        for (DatabaseAdvisorDataSourceDiscovery.Failure failure : discovery.failures()) {
            limitations.add(failure.name() + ": this datasource could not be discovered.");
        }
        return report(status, message, clock.millis(), databases, findings, diagnostics, truncated, limitations);
    }

    private DatabaseAdvisorDataSourceDiscovery discover() {
        try {
            DatabaseAdvisorDataSourceDiscovery discovery = dataSourceSupplier.get();
            return discovery == null ? new DatabaseAdvisorDataSourceDiscovery(List.of(), List.of()) : discovery;
        } catch (RuntimeException ex) {
            return new DatabaseAdvisorDataSourceDiscovery(
                    List.of(),
                    List.of(new DatabaseAdvisorDataSourceDiscovery.Failure(
                            "datasources", CredentialRedaction.redact(String.valueOf(ex.getMessage())))));
        }
    }

    /** Returns {@code null} when the datasource is not PostgreSQL, which is not a failure. */
    private DatabaseRead readDataSource(
            NamedDataSource dataSource,
            PostgresReadBudget budget,
            List<PostgresDiagnosticDto> diagnostics,
            List<String> unread) {
        if (budget.exhausted()) {
            String reason = "The read budget ran out before this datasource was read.";
            diagnostics.add(new PostgresDiagnosticDto(dataSource.name(), "WARNING", reason));
            unread.add(dataSource.name() + ": " + reason);
            return null;
        }
        try (Connection connection = dataSource.dataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            Dialect dialect = Dialect.detect(
                    metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion(), metaData.getURL());
            if (dialect != Dialect.POSTGRESQL) {
                diagnostics.add(new PostgresDiagnosticDto(
                        dataSource.name(),
                        "INFO",
                        "Skipped: this datasource is " + dialect.label() + ", not PostgreSQL."));
                return null;
            }
            DatabaseVersion version =
                    DatabaseVersion.of(safeMajor(metaData), safeMinor(metaData), safeProductVersion(metaData));
            return readDatabase(dataSource.name(), connection, version, budget, diagnostics);
        } catch (SQLException | RuntimeException ex) {
            String reason = CredentialRedaction.redact(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            diagnostics.add(new PostgresDiagnosticDto(dataSource.name(), "ERROR", reason));
            return new DatabaseRead(
                    new PostgresDatabaseDto(
                            dataSource.name(),
                            null,
                            null,
                            -1,
                            null,
                            false,
                            "ERROR",
                            reason,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            null,
                            List.of(),
                            List.of(),
                            false),
                    List.of());
        }
    }

    private DatabaseRead readDatabase(
            String name,
            Connection connection,
            DatabaseVersion version,
            PostgresReadBudget budget,
            List<PostgresDiagnosticDto> diagnostics) {
        PostgresDatabaseData data = new PostgresDatabaseData(name);
        PostgresReadContext context = new PostgresReadContext(connection, version, budget, limits, exposure);

        boolean originalAutoCommit = true;
        boolean originalReadOnly = false;
        boolean autoCommitChanged = false;
        boolean readOnlyChanged = false;
        Role role = new Role(null, false);
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            autoCommitChanged = true;
            originalReadOnly = connection.isReadOnly();
            connection.setReadOnly(true);
            readOnlyChanged = true;
            data.markSessionUnpinned(pinSession(connection, name, diagnostics));
            role = readRole(context);
            for (PostgresCollector collector : COLLECTORS) {
                if (budget.exhausted()) {
                    data.addSection(new PostgresSectionDto(
                            collector.id(),
                            collector.title(),
                            "SKIPPED",
                            "The read budget ran out before this section was read.",
                            null,
                            0,
                            0,
                            true));
                    data.markTruncated();
                    continue;
                }
                data.addSection(collector.collect(context, data));
            }
        } catch (SQLException | RuntimeException ex) {
            String reason = CredentialRedaction.redact(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            diagnostics.add(new PostgresDiagnosticDto(name, "ERROR", reason));
        } finally {
            restore(
                    connection,
                    originalAutoCommit,
                    autoCommitChanged,
                    originalReadOnly,
                    readOnlyChanged,
                    name,
                    diagnostics);
        }

        List<PostgresFindingDto> findings = evaluate(name, data, diagnostics);
        Map<String, Integer> countsBySection = new LinkedHashMap<>();
        for (PostgresFindingDto finding : findings) {
            countsBySection.merge(finding.sectionId(), 1, Integer::sum);
        }
        data.recordFindingCounts(countsBySection);
        for (PostgresSectionDto section : data.sections()) {
            if (!"AVAILABLE".equals(section.status())) {
                diagnostics.add(new PostgresDiagnosticDto(
                        name + "/" + section.id(),
                        "FAILED".equals(section.status()) ? "ERROR" : "WARNING",
                        section.reason()));
            } else if (section.reason() != null) {
                diagnostics.add(new PostgresDiagnosticDto(name + "/" + section.id(), "INFO", section.reason()));
            }
        }

        List<PostgresChangeDto> changes = changesFor(name, data);
        String status = statusOf(data);
        return new DatabaseRead(
                new PostgresDatabaseDto(
                        name,
                        data.vitalSigns() == null ? null : data.vitalSigns().databaseName(),
                        version.describe(),
                        version.major(),
                        role.name(),
                        role.monitoring(),
                        status,
                        data.unpinnedReason(),
                        data.vitalSigns(),
                        data.sections(),
                        data.statements(),
                        data.indexes(),
                        data.tables(),
                        data.vacuum(),
                        data.replication(),
                        data.settings(),
                        changes,
                        data.truncated()),
                findings);
    }

    private static String statusOf(PostgresDatabaseData data) {
        boolean anyAvailable = data.sections().stream().anyMatch(section -> "AVAILABLE".equals(section.status()));
        if (!anyAvailable) {
            return "ERROR";
        }
        boolean complete = data.sections().stream()
                .allMatch(section -> "AVAILABLE".equals(section.status()) && section.reason() == null);
        return complete && !data.truncated() && data.unpinnedReason() == null ? "SCANNED" : "PARTIAL";
    }

    private List<PostgresFindingDto> evaluate(
            String dataSourceName, PostgresDatabaseData data, List<PostgresDiagnosticDto> diagnostics) {
        List<PostgresFindingDto> findings = new ArrayList<>();
        for (PostgresRule rule : PostgresRuleRegistry.rules()) {
            PostgresRuleDefinition definition = rule.definition();
            if (!data.sectionAvailable(definition.sectionId())) {
                continue;
            }
            PostgresRuleMatch match;
            try {
                match = rule.evaluate(data);
            } catch (RuntimeException ex) {
                diagnostics.add(new PostgresDiagnosticDto(
                        dataSourceName + "/" + definition.id(),
                        "ERROR",
                        "The check could not be evaluated: "
                                + CredentialRedaction.redact(String.valueOf(ex.getMessage()))));
                continue;
            }
            if (match == null) {
                continue;
            }
            findings.add(new PostgresFindingDto(
                    definition.id(),
                    dataSourceName,
                    definition.sectionId(),
                    definition.title(),
                    definition.category(),
                    definition.severity(),
                    definition.description(),
                    match.evidence(),
                    match.samples(),
                    definition.recommendation(),
                    definition.caveat(),
                    definition.learnMoreUrl()));
        }
        return findings;
    }

    private String pinSession(Connection connection, String name, List<PostgresDiagnosticDto> diagnostics) {
        List<String> pins = List.of(
                "set transaction read only",
                "set local statement_timeout = '" + limits.statementTimeout().toMillis() + "ms'",
                "set local lock_timeout = '" + limits.lockTimeout().toMillis() + "ms'",
                "set local idle_in_transaction_session_timeout = '"
                        + limits.readBudget().toMillis() + "ms'");
        String unpinned = null;
        for (String pin : pins) {
            String reason = PostgresQuery.pin(connection, pin);
            if (reason != null) {
                String message = "The session could not be pinned with \"" + pin + "\": " + reason;
                diagnostics.add(new PostgresDiagnosticDto(name, "WARNING", message));
                if (unpinned == null) {
                    unpinned = message;
                }
            }
        }
        return unpinned;
    }

    private Role readRole(PostgresReadContext context) {
        PostgresRows<Role> rows = PostgresQuery.readOne(
                context,
                "Connected role",
                "select current_user as role_name, pg_has_role(current_user, 'pg_monitor', 'member') as monitoring",
                resultSet -> new Role(resultSet.getString("role_name"), resultSet.getBoolean("monitoring")));
        return rows.available() && !rows.empty() ? rows.rows().get(0) : new Role(null, false);
    }

    private void restore(
            Connection connection,
            boolean originalAutoCommit,
            boolean autoCommitChanged,
            boolean originalReadOnly,
            boolean readOnlyChanged,
            String name,
            List<PostgresDiagnosticDto> diagnostics) {
        try {
            if (autoCommitChanged) {
                connection.rollback();
            }
        } catch (SQLException | RuntimeException ex) {
            diagnostics.add(new PostgresDiagnosticDto(
                    name,
                    "WARNING",
                    "The read-only transaction could not be rolled back: "
                            + CredentialRedaction.redact(String.valueOf(ex.getMessage()))));
        }
        try {
            if (readOnlyChanged) {
                connection.setReadOnly(originalReadOnly);
            }
        } catch (SQLException | RuntimeException ex) {
            diagnostics.add(new PostgresDiagnosticDto(
                    name,
                    "WARNING",
                    "The connection's read-only state could not be restored: "
                            + CredentialRedaction.redact(String.valueOf(ex.getMessage()))));
        }
        try {
            if (autoCommitChanged) {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException | RuntimeException ex) {
            diagnostics.add(new PostgresDiagnosticDto(
                    name,
                    "WARNING",
                    "The connection's auto-commit state could not be restored: "
                            + CredentialRedaction.redact(String.valueOf(ex.getMessage()))));
        }
    }

    private List<PostgresChangeDto> changesFor(String name, PostgresDatabaseData data) {
        List<PostgresMetric> current = metricsOf(data);
        if (current.isEmpty()) {
            // A read that produced no comparable metric — a refused role, a failed connection — must not
            // replace the baseline. Overwriting it would silently discard the comparison the next healthy
            // read is supposed to make.
            return List.of();
        }
        List<PostgresMetric> previous = previousMetrics.put(name, current);
        if (previous == null) {
            return List.of();
        }
        Map<String, PostgresMetric> before = new HashMap<>();
        for (PostgresMetric metric : previous) {
            before.put(metric.name(), metric);
        }
        List<PostgresChangeDto> changes = new ArrayList<>();
        for (PostgresMetric metric : current) {
            PostgresMetric earlier = before.get(metric.name());
            if (earlier == null || earlier.value() == null || metric.value() == null) {
                continue;
            }
            int comparison = Double.compare(metric.value(), earlier.value());
            if (comparison == 0) {
                continue;
            }
            changes.add(new PostgresChangeDto(
                    metric.name(), earlier.rendered(), metric.rendered(), comparison > 0 ? "UP" : "DOWN"));
        }
        return changes;
    }

    private static List<PostgresMetric> metricsOf(PostgresDatabaseData data) {
        List<PostgresMetric> metrics = new ArrayList<>();
        PostgresVitalSignsDto vitals = data.vitalSigns();
        if (vitals != null) {
            add(metrics, "Cache hit ratio", vitals.cacheHitRatio(), PostgresFormat.percent(vitals.cacheHitRatio()));
            add(metrics, "Rollback ratio", vitals.rollbackRatio(), PostgresFormat.percent(vitals.rollbackRatio()));
            add(
                    metrics,
                    "Connections",
                    vitals.connections() == null ? null : vitals.connections().doubleValue(),
                    String.valueOf(vitals.connections()));
            add(
                    metrics,
                    "Deadlocks",
                    vitals.deadlocks() == null ? null : vitals.deadlocks().doubleValue(),
                    PostgresFormat.count(vitals.deadlocks()));
            add(
                    metrics,
                    "Database size",
                    vitals.databaseSizeBytes() == null
                            ? null
                            : vitals.databaseSizeBytes().doubleValue(),
                    PostgresFormat.bytes(vitals.databaseSizeBytes()));
            add(
                    metrics,
                    "Transaction id age",
                    vitals.transactionIdAge() == null
                            ? null
                            : vitals.transactionIdAge().doubleValue(),
                    PostgresFormat.count(vitals.transactionIdAge()));
        }
        // Only the largest relations are retained, so this is explicitly a subset total rather than a
        // database-wide one. A truncated read is skipped outright: the membership of the list would differ
        // between reads, and comparing two different sets of tables would manufacture a movement.
        PostgresSectionDto tables = data.section(PostgresSectionIds.TABLES);
        boolean comparable = tables != null && "AVAILABLE".equals(tables.status()) && !tables.truncated();
        long deadTuples = 0;
        boolean anyTable = false;
        for (PostgresTableDto table : data.tables()) {
            if (table.deadTuples() != null) {
                deadTuples += table.deadTuples();
                anyTable = true;
            }
        }
        if (comparable && anyTable) {
            add(metrics, "Dead tuples in the largest relations", (double) deadTuples, PostgresFormat.count(deadTuples));
        }
        return List.copyOf(metrics);
    }

    private static void add(List<PostgresMetric> metrics, String name, Double value, String rendered) {
        if (value != null) {
            metrics.add(new PostgresMetric(name, value, rendered));
        }
    }

    private PostgresInsightReport report(
            String status,
            String message,
            Long readAt,
            List<PostgresDatabaseDto> databases,
            List<PostgresFindingDto> findings,
            List<PostgresDiagnosticDto> diagnostics,
            boolean truncated) {
        return report(status, message, readAt, databases, findings, diagnostics, truncated, List.of());
    }

    private PostgresInsightReport report(
            String status,
            String message,
            Long readAt,
            List<PostgresDatabaseDto> databases,
            List<PostgresFindingDto> findings,
            List<PostgresDiagnosticDto> diagnostics,
            boolean truncated,
            List<String> readLimitations) {
        List<PostgresSeverityCountDto> severityCounts =
                SeverityOrder.counts(findings, PostgresFindingDto::severity).entrySet().stream()
                        .map(entry -> new PostgresSeverityCountDto(entry.getKey(), entry.getValue()))
                        .toList();
        return new PostgresInsightReport(
                true,
                DISCLAIMER,
                status,
                message,
                readAt,
                databases.size(),
                findings.size(),
                truncated,
                databases,
                findings,
                severityCounts,
                diagnostics,
                evidenceOf(status, databases, truncated, readLimitations));
    }

    private static AdvisorEvidenceDto evidenceOf(
            String status, List<PostgresDatabaseDto> databases, boolean truncated, List<String> readLimitations) {
        if (!"READ".equals(status) && !"PARTIAL".equals(status)) {
            return new AdvisorEvidenceDto(
                    false,
                    false,
                    List.of(
                            "NOT_READ".equals(status)
                                    ? "The database has not been read yet."
                                    : "No PostgreSQL statistics were read."));
        }
        List<String> limitations = new ArrayList<>(readLimitations);
        boolean complete = !truncated && readLimitations.isEmpty();
        for (PostgresDatabaseDto database : databases) {
            for (PostgresSectionDto section : database.sections()) {
                if (!"AVAILABLE".equals(section.status())) {
                    complete = false;
                    limitations.add(database.name() + "/" + section.id() + ": " + section.reason());
                } else if (section.reason() != null) {
                    complete = false;
                    limitations.add(database.name() + "/" + section.id() + ": " + section.reason());
                }
            }
            if (database.truncated()) {
                complete = false;
                limitations.add(database.name() + ": a row bound truncated the read.");
            }
            if ("PARTIAL".equals(database.status()) && database.message() != null) {
                complete = false;
                limitations.add(database.name() + ": " + database.message());
            }
        }
        return new AdvisorEvidenceDto(true, complete, limitations);
    }

    private static int safeMajor(DatabaseMetaData metaData) {
        try {
            return metaData.getDatabaseMajorVersion();
        } catch (SQLException | RuntimeException ex) {
            return -1;
        }
    }

    private static int safeMinor(DatabaseMetaData metaData) {
        try {
            return metaData.getDatabaseMinorVersion();
        } catch (SQLException | RuntimeException ex) {
            return -1;
        }
    }

    private static String safeProductVersion(DatabaseMetaData metaData) {
        try {
            return metaData.getDatabaseProductVersion();
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
    }

    private record Role(String name, boolean monitoring) {}

    private record DatabaseRead(PostgresDatabaseDto database, List<PostgresFindingDto> findings) {}
}
