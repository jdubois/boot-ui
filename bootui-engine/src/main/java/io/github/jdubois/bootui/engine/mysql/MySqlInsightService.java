package io.github.jdubois.bootui.engine.mysql;

import io.github.jdubois.bootui.core.dto.MySqlChangeDto;
import io.github.jdubois.bootui.core.dto.MySqlDataSourceDto;
import io.github.jdubois.bootui.core.dto.MySqlDiagnosticDto;
import io.github.jdubois.bootui.core.dto.MySqlInsightReport;
import io.github.jdubois.bootui.core.dto.MySqlSectionDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Explicit, single-flight observations through existing JDBC datasources. This service owns publication:
 * all transports see one sanitized cache, with no SQL on construction, report reads or invalidation.
 */
public final class MySqlInsightService {
    private static final String DISCLAIMER =
            "Explicit, bounded READ ONLY observations of MySQL's own metadata and statistics, not application rows."
                    + " Collection is not an atomic snapshot. Server counters include other clients; default-schema"
                    + " association is not exhaustive schema access. Pool acquisition and network timeouts are separate"
                    + " from the cooperative 15-second read budget. No health score or complete-history claim.";
    private static final int MAX_SOURCES = 32;
    private final Supplier<DatabaseAdvisorDataSourceDiscovery> discovery;
    private final ExposurePolicy exposure;
    private final Clock clock;
    private final MySqlRowLimits limits;
    private final LongSupplier ticker;
    private final Duration duration;
    private final SingleFlightAction admission = new SingleFlightAction();
    private final MySqlComparisons comparisons = new MySqlComparisons();
    private MySqlValues.Policy cachePolicy;
    private MySqlInsightReport cached;
    private long policyGeneration;
    private long baselineGeneration;
    // At most one quarantined logical handle; never return contaminated state to the application pool.
    private Connection quarantined;

    public static MySqlInsightService using(
            Supplier<DatabaseAdvisorDataSourceDiscovery> discovery,
            ExposurePolicy exposure,
            Clock clock,
            MySqlRowLimits limits) {
        return new MySqlInsightService(discovery, exposure, clock, limits, System::nanoTime, Duration.ofSeconds(15));
    }

    public static MySqlInsightService using(
            Supplier<DatabaseAdvisorDataSourceDiscovery> discovery, ExposurePolicy exposure, Clock clock) {
        return using(discovery, exposure, clock, MySqlRowLimits.defaults());
    }

    MySqlInsightService(
            Supplier<DatabaseAdvisorDataSourceDiscovery> discovery,
            ExposurePolicy exposure,
            Clock clock,
            MySqlRowLimits limits,
            LongSupplier ticker,
            Duration duration) {
        this.discovery = Objects.requireNonNull(discovery);
        this.exposure = exposure;
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        this.ticker = Objects.requireNonNull(ticker);
        this.duration = Objects.requireNonNull(duration);
        cachePolicy = MySqlValues.Policy.of(exposure);
        cached = initial("No MySQL read has run. Run MySQL read explicitly to contact existing JDBC datasources.");
    }

    /** Cached GET, MCP and CLI report. Reading live policy is the only work performed here. */
    public synchronized MySqlInsightReport report() {
        invalidateChangedPolicy();
        return cached;
    }

    public MySqlInsightReport initialReport() {
        return report();
    }

    public MySqlInsightReport read() {
        return admission.run(ActionOperations.MYSQL_READ, this::readAndPublish);
    }

    private MySqlInsightReport readAndPublish() {
        MySqlValues.Policy policy;
        long generation;
        synchronized (this) {
            invalidateChangedPolicy();
            policy = cachePolicy;
            generation = policyGeneration;
            if (baselineGeneration != generation) {
                comparisons.clear();
                baselineGeneration = generation;
            }
        }
        MySqlInsightReport result = collect(policy);
        synchronized (this) {
            invalidateChangedPolicy();
            if (!policy.equals(cachePolicy) || generation != policyGeneration) {
                comparisons.clear();
                cached =
                        initial("Exposure policy changed during collection; its result was discarded. Run a new read.");
            } else {
                cached = result;
            }
            return cached;
        }
    }

    private void invalidateChangedPolicy() {
        MySqlValues.Policy current = MySqlValues.Policy.of(exposure);
        if (!current.equals(cachePolicy)) {
            cachePolicy = current;
            policyGeneration++;
            // Baselines contain only numeric evidence, but must not cross an invalidated snapshot.
            cached = initial("Exposure policy changed; the cached report was invalidated without contacting MySQL.");
        }
    }

    private MySqlInsightReport initial(String message) {
        return new MySqlInsightReport(
                true, DISCLAIMER, "NOT_READ", message, null, null, 0, List.of(), List.of(), List.of(), false);
    }

    private MySqlInsightReport collect(MySqlValues.Policy policy) {
        long started = clock.millis();
        if (quarantined != null) {
            return new MySqlInsightReport(
                    true,
                    DISCLAIMER,
                    "DISABLED",
                    "A connection could not be safely restored or aborted. Restart its pool and BootUI before"
                            + " retrying.",
                    started,
                    clock.millis(),
                    0,
                    List.of(),
                    List.of(),
                    List.of(),
                    false);
        }
        List<MySqlDiagnosticDto> diagnostics = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        DatabaseAdvisorDataSourceDiscovery inventory;
        try {
            inventory = discovery.get();
            if (inventory == null) {
                inventory = new DatabaseAdvisorDataSourceDiscovery(
                        List.of(), List.of(new DatabaseAdvisorDataSourceDiscovery.Failure("datasources", null)));
            }
        } catch (RuntimeException ex) {
            inventory = new DatabaseAdvisorDataSourceDiscovery(
                    List.of(), List.of(new DatabaseAdvisorDataSourceDiscovery.Failure("datasources", null)));
        }
        for (DatabaseAdvisorDataSourceDiscovery.Failure failure :
                inventory.failures().stream().limit(MAX_SOURCES).toList()) {
            diagnostics.add(new MySqlDiagnosticDto(
                    MySqlValues.text(failure.name()),
                    "ERROR",
                    "Datasource discovery failed. Exception text is withheld because it may contain credentials."));
        }
        if (!inventory.failures().isEmpty()) {
            limitations.add("Some datasource declarations could not be discovered.");
        }
        if (inventory.dataSources().size() > MAX_SOURCES || inventory.failures().size() > MAX_SOURCES) {
            limitations.add("Datasource discovery/collection is limited to 32 targets and 32 failure summaries.");
        }
        MySqlReadBudget budget = new MySqlReadBudget(duration, ticker);
        List<MySqlDataSourceDto> sources = new ArrayList<>();
        Set<DataSource> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> baselineKeys = new HashSet<>();
        for (NamedDataSource source :
                inventory.dataSources().stream().limit(MAX_SOURCES).toList()) {
            if (!seen.add(source.dataSource())) {
                continue;
            }
            String declaration = MySqlDataSourceDetection.jdbcUrlOf(source.dataSource());
            if (declaration != null && !MySqlDataSourceDetection.isMySqlJdbcUrl(declaration)) {
                diagnostics.add(new MySqlDiagnosticDto(
                        MySqlValues.text(source.name()),
                        "INFO",
                        "A non-MySQL JDBC declaration was skipped without borrowing a connection."));
                continue;
            }
            if (budget.exhausted() || quarantined != null) {
                limitations.add(MySqlValues.text(source.name()) + ": not reached because collection stopped.");
                continue;
            }
            baselineKeys.add(source.name());
            MySqlDataSourceDto result = readSource(source, budget, policy, diagnostics);
            if (result != null) {
                sources.add(result);
            }
        }
        comparisons.retain(baselineKeys);
        boolean truncated = sources.stream().anyMatch(MySqlDataSourceDto::truncated);
        int usable = (int) sources.stream()
                .filter(source -> !"ERROR".equals(source.status()))
                .count();
        boolean incomplete =
                !limitations.isEmpty() || sources.stream().anyMatch(source -> !"READ".equals(source.status()));
        for (MySqlDataSourceDto source : sources) {
            for (MySqlSectionDto section : source.sections()) {
                if (section.truncated()) {
                    limitations.add(source.name() + ": " + section.title() + " retained " + section.rowCount()
                            + " rows; additional rows were omitted by BootUI caps.");
                }
            }
        }
        String status = usable > 0
                ? incomplete || truncated ? "PARTIAL" : "READ"
                : !sources.isEmpty() || !limitations.isEmpty() ? "ERROR" : "DISABLED";
        String message =
                switch (status) {
                    case "DISABLED" -> "No supported MySQL JDBC datasource was found.";
                    case "ERROR" -> "No MySQL datasource produced usable observations.";
                    case "PARTIAL" -> "Some evidence is incomplete; inspect section reasons and collection limits.";
                    default -> null;
                };
        return new MySqlInsightReport(
                true,
                DISCLAIMER,
                status,
                message,
                started,
                clock.millis(),
                usable,
                sources,
                diagnostics,
                limitations,
                truncated);
    }

    private MySqlDataSourceDto readSource(
            NamedDataSource named,
            MySqlReadBudget budget,
            MySqlValues.Policy policy,
            List<MySqlDiagnosticDto> diagnostics) {
        Connection connection = null;
        MySqlReadContext context = null;
        MySqlCollectors collector = null;
        Map<String, String> identity = Map.of();
        MySqlDataSourceDto result = null;
        String cleanupFailure = null;
        boolean unsupported = false;
        try {
            connection = named.dataSource().getConnection();
            context = new MySqlReadContext(connection, budget);
            // Only product metadata is used here; JDBC URLs and vendor exception messages never escape.
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.equalsIgnoreCase("MySQL")) {
                throw new SQLException("UNSUPPORTED_VENDOR", "BUI03");
            }
            context.open();
            identity = MySqlQuery.requiredRow(
                    connection,
                    budget,
                    "SELECT @@version AS version,@@version_comment AS flavor,DATABASE() AS schema_name,"
                            + " CURRENT_USER() AS account,@@server_uuid AS server_id,CONNECTION_ID() AS"
                            + " connection_id, @@global.performance_schema AS performance_schema,"
                            + " @@lower_case_table_names AS lower_case_table_names LIMIT ?");
            String version = identity.getOrDefault("version", "").toLowerCase(Locale.ROOT);
            String flavor = identity.getOrDefault("flavor", "").toLowerCase(Locale.ROOT);
            if (!version.startsWith("8.4.")
                    || version.contains("mariadb")
                    || !(flavor.contains("mysql community") || flavor.contains("mysql enterprise"))) {
                throw new SQLException("UNVERIFIED_SERVER", "BUI03");
            }
            String caseMode = identity.get("lower_case_table_names");
            if (!"0".equals(caseMode) && !"1".equals(caseMode) && !"2".equals(caseMode)) {
                throw new SQLException("Identifier matching mode was not reported.", "BUI04");
            }
            identity = new java.util.LinkedHashMap<>(identity);
            String instrumentationSchema = "0".equals(caseMode)
                    ? identity.get("schema_name")
                    : MySqlQuery.requiredRow(
                                    connection,
                                    budget,
                                    "SELECT LOWER(CONVERT(DATABASE() USING utf8mb4) COLLATE utf8mb4_0900_bin)"
                                            + " AS instrumentation_schema LIMIT ?")
                            .get("instrumentation_schema");
            if (identity.get("schema_name") != null && instrumentationSchema == null) {
                throw new SQLException("Normalized schema identity was not reported.", "BUI04");
            }
            identity.put("instrumentation_schema", instrumentationSchema);
            collector = new MySqlCollectors(
                    connection, budget, limits, policy, clock, MySqlValues.text(named.name()), identity);
            result = collector.collect();
        } catch (SQLException ex) {
            unsupported = "BUI03".equals(ex.getSQLState());
            String reason =
                    switch (String.valueOf(ex.getSQLState())) {
                        case "BUI01" ->
                            "The borrowed connection is already in manual-commit mode. BootUI did not inspect,"
                                    + " commit or roll back the application's transaction.";
                        case "BUI02" -> "Server-enforced READ ONLY could not be established; collection was refused.";
                        case "BUI03" ->
                            "This server is not the tested Oracle MySQL 8.4 line; no compatibility is claimed.";
                        default -> MySqlQuery.reason(ex);
                    };
            result = failed(named.name(), reason);
        } catch (RuntimeException ex) {
            result = failed(named.name(), "Collection failed; driver/error text is withheld for safety.");
        } finally {
            if (context != null) {
                MySqlReadContext.Cleanup cleanup = context.finish();
                cleanupFailure = cleanup.reason();
                if (cleanup.quarantined()) {
                    quarantined = connection;
                }
            } else if (connection != null) {
                try {
                    // No state was changed, and notably no rollback on manual-commit refusal.
                    connection.close();
                } catch (SQLException ex) {
                    cleanupFailure = "The unmodified borrowed connection could not be closed.";
                }
            }
        }
        if (cleanupFailure != null) {
            diagnostics.add(new MySqlDiagnosticDto(MySqlValues.text(named.name()), "ERROR", cleanupFailure));
            result = collector == null ? failed(named.name(), cleanupFailure) : collector.report(cleanupFailure);
        }
        if (unsupported && cleanupFailure == null) {
            diagnostics.add(new MySqlDiagnosticDto(MySqlValues.text(named.name()), "INFO", result.message()));
            return null;
        }
        if (result == null) {
            result = failed(named.name(), "No observation was produced.");
        }
        if (!"ERROR".equals(result.status())) {
            List<MySqlChangeDto> changes = comparisons.observe(
                    named.name(), identity.get("server_id"), collector == null ? null : collector.counterSample());
            result = withChanges(result, changes);
        }
        return result;
    }

    private MySqlDataSourceDto failed(String name, String message) {
        return new MySqlDataSourceDto(
                MySqlValues.text(name),
                null,
                null,
                null,
                null,
                "ERROR",
                message,
                null,
                clock.millis(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false);
    }

    private static MySqlDataSourceDto withChanges(MySqlDataSourceDto source, List<MySqlChangeDto> changes) {
        return new MySqlDataSourceDto(
                source.name(),
                source.schemaName(),
                source.serverVersion(),
                source.serverFlavor(),
                source.account(),
                source.status(),
                source.message(),
                source.readStartedAt(),
                source.readAt(),
                source.capabilities(),
                source.sections(),
                source.vitalSigns(),
                source.sessions(),
                source.lockWaits(),
                source.statements(),
                source.indexes(),
                source.tables(),
                source.innodb(),
                source.replication(),
                source.settings(),
                changes,
                source.truncated());
    }
}
