package io.github.jdubois.bootui.engine.mysql;

import io.github.jdubois.bootui.core.dto.MySqlCapabilityDto;
import io.github.jdubois.bootui.core.dto.MySqlDataSourceDto;
import io.github.jdubois.bootui.core.dto.MySqlIndexDto;
import io.github.jdubois.bootui.core.dto.MySqlLockWaitDto;
import io.github.jdubois.bootui.core.dto.MySqlMetricDto;
import io.github.jdubois.bootui.core.dto.MySqlReplicationChannelDto;
import io.github.jdubois.bootui.core.dto.MySqlSectionDto;
import io.github.jdubois.bootui.core.dto.MySqlSessionDto;
import io.github.jdubois.bootui.core.dto.MySqlSettingDto;
import io.github.jdubois.bootui.core.dto.MySqlStatementDto;
import io.github.jdubois.bootui.core.dto.MySqlTableDto;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MySQL-native fixed collectors. Failed independent subreads retain their siblings' evidence. */
final class MySqlCollectors {
    static final String SERVER = "SERVER";
    static final String SCHEMA = "SELECTED_SCHEMA";
    static final String ASSOCIATED = "DEFAULT_SCHEMA_ASSOCIATED";
    static final String STATUS_NAMES =
            "'Uptime','Threads_connected','Threads_running','Max_used_connections','Connections','Aborted_connects',"
                    + "'Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Innodb_buffer_pool_pages_total',"
                    + "'Innodb_buffer_pool_pages_data','Innodb_buffer_pool_pages_dirty','Innodb_buffer_pool_pages_free',"
                    + "'Innodb_buffer_pool_wait_free','Innodb_row_lock_current_waits','Innodb_row_lock_waits',"
                    + "'Innodb_row_lock_time','Innodb_log_waits'";
    static final List<String> SETTINGS = List.of(
            "max_connections",
            "innodb_buffer_pool_size",
            "innodb_flush_log_at_trx_commit",
            "sync_binlog",
            "log_bin",
            "read_only",
            "super_read_only",
            "transaction_isolation",
            "transaction_read_only",
            "innodb_lock_wait_timeout",
            "performance_schema",
            "performance_schema_digests_size",
            "performance_schema_max_digest_length",
            "max_digest_length",
            "information_schema_stats_expiry",
            "tmp_table_size",
            "sql_mode");

    private final Connection connection;
    private final MySqlReadBudget budget;
    private final MySqlRowLimits limits;
    private final MySqlValues.Policy policy;
    private final Clock clock;
    private final String name;
    private final long started;
    private final Map<String, String> identity;
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final List<MySqlCapabilityDto> capabilities = new ArrayList<>();
    private final List<MySqlMetricDto> vitalSigns = new ArrayList<>();
    private final List<MySqlSessionDto> sessions = new ArrayList<>();
    private final List<MySqlLockWaitDto> locks = new ArrayList<>();
    private final List<MySqlStatementDto> statements = new ArrayList<>();
    private final List<MySqlIndexDto> indexes = new ArrayList<>();
    private final List<MySqlTableDto> tables = new ArrayList<>();
    private final List<MySqlMetricDto> innodb = new ArrayList<>();
    private final List<MySqlReplicationChannelDto> replication = new ArrayList<>();
    private final List<MySqlSettingDto> settings = new ArrayList<>();
    private boolean fatal;
    private String statementCollection = "UNKNOWN";
    private String statementTiming = "UNKNOWN";
    private String tableCollection = "UNKNOWN";
    private String tableTiming = "UNKNOWN";
    private String metadataLockCollection = "UNKNOWN";
    private MySqlObjectInstrumentation objectInstrumentation;
    private String objectInstrumentationProblem;
    private final Set<String> catalogObjectNames = new java.util.LinkedHashSet<>();
    private MySqlCounterSample counterSample;

    MySqlCollectors(
            Connection connection,
            MySqlReadBudget budget,
            MySqlRowLimits limits,
            MySqlValues.Policy policy,
            Clock clock,
            String name,
            Map<String, String> identity) {
        this.connection = connection;
        this.budget = budget;
        this.limits = limits;
        this.policy = policy;
        this.clock = clock;
        this.name = name;
        this.identity = identity;
        started = clock.millis();
        section("vital-signs", "Vital signs", SERVER);
        section("sessions", "Sessions and blocking", ASSOCIATED);
        section("statements", "Statement ranking", ASSOCIATED);
        section("indexes", "Index handler operations", SCHEMA);
        section("tables", "Table estimates", SCHEMA);
        section("innodb", "InnoDB", SERVER);
        section("replication", "Replication", SERVER);
        section("settings", "Settings", SERVER);
    }

    private void section(String id, String title, String scope) {
        sections.put(id, new Section(id, title, scope));
    }

    MySqlDataSourceDto collect() {
        capabilities();
        status();
        sessions();
        statements();
        tables();
        indexes();
        innodb();
        replication();
        settings();
        sections.get("vital-signs").rowCount = vitalSigns.size();
        sections.get("sessions").rowCount = sessions.size() + locks.size();
        sections.get("statements").rowCount = statements.size();
        sections.get("indexes").rowCount = indexes.size();
        sections.get("tables").rowCount = tables.size();
        sections.get("innodb").rowCount = innodb.size();
        sections.get("replication").rowCount = replication.size();
        sections.get("settings").rowCount = settings.size();
        return report(null);
    }

    MySqlCounterSample counterSample() {
        return counterSample;
    }

    MySqlDataSourceDto report(String cleanupFailure) {
        List<MySqlSectionDto> outcomes =
                sections.values().stream().map(Section::dto).toList();
        boolean usable = sections.values().stream().anyMatch(s -> s.success);
        boolean incomplete =
                cleanupFailure != null || sections.values().stream().anyMatch(s -> !s.reasons.isEmpty());
        boolean partial = incomplete || outcomes.stream().anyMatch(MySqlSectionDto::truncated);
        String status = !usable ? "ERROR" : partial ? "PARTIAL" : "READ";
        return new MySqlDataSourceDto(
                name,
                MySqlValues.text(schema()),
                MySqlValues.text(identity.get("version")),
                "ORACLE_MYSQL",
                MySqlValues.exposed("mysql.account", identity.get("account"), policy),
                status,
                cleanupFailure != null
                        ? cleanupFailure
                        : incomplete ? "Some evidence is incomplete; see section reasons." : null,
                started,
                clock.millis(),
                capabilities,
                outcomes,
                vitalSigns,
                sessions,
                locks,
                statements,
                indexes,
                tables,
                innodb,
                replication,
                settings,
                List.of(),
                outcomes.stream().anyMatch(MySqlSectionDto::truncated));
    }

    private String schema() {
        return identity.get("schema_name");
    }

    private boolean schema(Section section) {
        if (schema() == null || schema().isBlank()) {
            section.skipped = true;
            section.reason("No selected schema; BootUI did not enumerate other schemas.");
            return false;
        }
        return true;
    }

    private boolean performanceSchema(Section section) {
        if (!"1".equals(identity.get("performance_schema"))) {
            section.skipped = true;
            section.reason("Performance Schema is disabled; instrumentation was not enabled by BootUI.");
            return false;
        }
        return true;
    }

    private MySqlQuery.Rows query(
            Section section, String source, String sql, int cap, String collection, String timing, Object... args) {
        if (fatal || budget.exhausted()) {
            section.reason(
                    fatal
                            ? "Connection unusable; this source was not queried."
                            : "Read budget exhausted before this source; missing evidence is unknown.");
            return null;
        }
        try {
            MySqlQuery.Rows result = MySqlQuery.read(connection, budget, sql, cap, args);
            section.success |= result.reason() == null || !result.values().isEmpty();
            section.truncated |= result.truncated();
            if (result.reason() != null) {
                section.reason(source + ": " + result.reason());
            }
            capabilities.add(new MySqlCapabilityDto(
                    source, source, sourceScope(section, source), "READABLE", collection, timing, result.reason()));
            return result;
        } catch (SQLException ex) {
            fatal |= MySqlQuery.fatal(ex);
            String reason = MySqlQuery.reason(ex);
            section.reason(source + ": " + reason);
            capabilities.add(new MySqlCapabilityDto(
                    source,
                    source,
                    sourceScope(section, source),
                    MySqlQuery.denied(ex) ? "DENIED" : "UNKNOWN",
                    collection,
                    timing,
                    reason));
            return null;
        }
    }

    private static String sourceScope(Section section, String source) {
        if ("digest-overflow".equals(source)) {
            return SERVER;
        }
        return source.startsWith("performance_schema.data_lock") || source.equals("performance_schema.metadata_locks")
                ? SCHEMA
                : section.scope;
    }

    private void capabilities() {
        Section section = new Section("capabilities", "Capabilities", SERVER);
        capabilities.add(new MySqlCapabilityDto(
                "performance-schema",
                "@@global.performance_schema",
                SERVER,
                "READABLE",
                "1".equals(identity.get("performance_schema")) ? "ENABLED" : "DISABLED",
                "NOT_APPLICABLE",
                null));
        if (!"1".equals(identity.get("performance_schema"))) {
            statementCollection = "DISABLED";
            statementTiming = "DISABLED";
            tableCollection = "DISABLED";
            tableTiming = "DISABLED";
            metadataLockCollection = "DISABLED";
            return;
        }
        String global = "UNKNOWN";
        MySqlQuery.Rows consumers = query(
                section,
                "performance_schema.setup_consumers",
                "SELECT NAME AS name, ENABLED AS enabled FROM performance_schema.setup_consumers"
                        + " WHERE NAME IN ('statements_digest','global_instrumentation','thread_instrumentation')"
                        + " ORDER BY NAME LIMIT ?",
                3,
                "NOT_APPLICABLE",
                "NOT_APPLICABLE");
        if (consumers != null) {
            Map<String, String> values = map(consumers, "name", "enabled");
            global = MySqlObjectInstrumentation.flag(values.get("global_instrumentation"));
            if (values.containsKey("global_instrumentation") && values.containsKey("statements_digest")) {
                // Digest aggregation is global and does not depend on per-thread instrumentation.
                statementCollection = "YES".equals(values.get("global_instrumentation"))
                                && "YES".equals(values.get("statements_digest"))
                        ? "ENABLED"
                        : "DISABLED";
            }
            tableCollection = MySqlObjectInstrumentation.combine(global, "UNKNOWN");
            tableTiming = tableCollection;
            metadataLockCollection = tableCollection;
        }
        MySqlQuery.Rows instruments = query(
                section,
                "performance_schema.setup_instruments",
                "SELECT CASE WHEN NAME LIKE 'statement/sql/%' THEN 'statement'"
                        + " WHEN NAME='wait/io/table/sql/handler' THEN 'table' ELSE 'metadata-lock' END AS category,"
                        + " COUNT(*) AS instruments,"
                        + " SUM(ENABLED='YES') AS enabled, SUM(TIMED='YES' AND ENABLED='YES') AS timed"
                        + " FROM performance_schema.setup_instruments"
                        + " WHERE NAME LIKE 'statement/sql/%' OR NAME IN"
                        + " ('wait/io/table/sql/handler','wait/lock/metadata/sql/mdl')"
                        + " GROUP BY category ORDER BY category LIMIT ?",
                3,
                "NOT_APPLICABLE",
                "NOT_APPLICABLE");
        if (instruments != null) {
            for (Map<String, String> row : instruments.values()) {
                String collection = instrumentState(row, "enabled");
                String timing = instrumentState(row, "timed");
                if ("statement".equals(row.get("category"))) {
                    statementTiming = timing;
                    if (!row.get("instruments").equals(row.get("enabled"))) {
                        statementCollection = "UNKNOWN";
                    }
                } else if ("table".equals(row.get("category"))) {
                    tableCollection = MySqlObjectInstrumentation.combine(global, collection);
                    tableTiming = MySqlObjectInstrumentation.combine(tableCollection, timing);
                } else if ("metadata-lock".equals(row.get("category"))) {
                    metadataLockCollection = MySqlObjectInstrumentation.combine(global, collection);
                }
            }
        }
    }

    private static String instrumentState(Map<String, String> row, String column) {
        String count = row.get(column);
        if ("0".equals(count)) {
            return "DISABLED";
        }
        return count != null && count.equals(row.get("instruments")) ? "ENABLED" : "UNKNOWN";
    }

    private void status() {
        Section section = sections.get("vital-signs");
        long sampleStarted = clock.millis();
        MySqlQuery.Rows rows = query(
                section,
                "performance_schema.global_status",
                "SELECT VARIABLE_NAME AS name, VARIABLE_VALUE AS value FROM performance_schema.global_status"
                        + " WHERE VARIABLE_NAME IN (" + STATUS_NAMES + ") ORDER BY VARIABLE_NAME LIMIT ?",
                17,
                "ENABLED",
                "NOT_APPLICABLE");
        if (rows == null && !fatal && !budget.exhausted()) {
            try {
                sampleStarted = clock.millis();
                MySqlQuery.Rows fallback = MySqlQuery.status(connection, budget);
                rows = new MySqlQuery.Rows(
                        fallback.values().stream()
                                .map(row -> {
                                    Map<String, String> normalized = new LinkedHashMap<>();
                                    normalized.put("name", row.get("variable_name"));
                                    normalized.put("value", row.get("value"));
                                    return normalized;
                                })
                                .map(row -> (Map<String, String>) row)
                                .toList(),
                        fallback.truncated(),
                        fallback.reason());
                section.success |=
                        fallback.reason() == null || !fallback.values().isEmpty();
                if (fallback.reason() != null) {
                    section.reason("SHOW GLOBAL STATUS: " + fallback.reason());
                }
                capabilities.add(new MySqlCapabilityDto(
                        "show-global-status",
                        "SHOW GLOBAL STATUS (17 fixed names)",
                        SERVER,
                        "READABLE",
                        "ENABLED",
                        "NOT_APPLICABLE",
                        "Network guard bounds this fixed-name SHOW fallback."));
            } catch (SQLException ex) {
                fatal |= MySqlQuery.fatal(ex);
                section.reason("SHOW GLOBAL STATUS: " + MySqlQuery.reason(ex));
            }
        }
        if (rows != null) {
            long observedAt = clock.millis();
            for (Map<String, String> row : rows.values()) {
                String id = row.get("name");
                String value = MySqlValues.counter(row.get("value"));
                vitalSigns.add(new MySqlMetricDto(id, metricLabel(id), value, statusUnit(id), SERVER, "global_status"));
                if (value == null) {
                    section.reason("Some status values are absent or not nonnegative counters.");
                }
            }
            if (rows.values().size() != 17) {
                section.reason("Some requested server status variables are absent; absent does not mean zero.");
            }
            counterSample = new MySqlCounterSample(schema(), sampleStarted, observedAt, vitalSigns);
        }
    }

    private static String statusUnit(String id) {
        if ("Uptime".equalsIgnoreCase(id)) {
            return "seconds";
        }
        if ("Innodb_row_lock_time".equalsIgnoreCase(id)) {
            return "milliseconds";
        }
        return id.toLowerCase(java.util.Locale.ROOT).contains("pages_") ? "pages" : "count";
    }

    private static String metricLabel(String id) {
        return switch (id) {
            case "Uptime" -> "Server uptime";
            case "Threads_connected" -> "Connected clients";
            case "Threads_running" -> "Non-sleeping threads";
            case "Max_used_connections" -> "Peak simultaneous connections";
            case "Connections" -> "Connection attempts";
            case "Aborted_connects" -> "Failed connection attempts";
            case "Innodb_buffer_pool_reads" -> "Buffer-pool disk reads";
            case "Innodb_buffer_pool_read_requests" -> "Buffer-pool logical read requests";
            case "Innodb_buffer_pool_pages_total" -> "Total buffer-pool pages";
            case "Innodb_buffer_pool_pages_data" -> "Buffer-pool data pages";
            case "Innodb_buffer_pool_pages_dirty" -> "Dirty buffer-pool pages";
            case "Innodb_buffer_pool_pages_free" -> "Free buffer-pool pages";
            case "Innodb_buffer_pool_wait_free" -> "Buffer-pool free-page waits";
            case "Innodb_row_lock_current_waits" -> "Current row-lock waits";
            case "Innodb_row_lock_waits" -> "Cumulative row-lock waits";
            case "Innodb_row_lock_time" -> "Cumulative row-lock wait time";
            case "Innodb_log_waits" -> "Redo log buffer waits";
            case "lock_deadlocks" -> "InnoDB deadlocks";
            case "trx_rseg_history_len" -> "InnoDB history-list length";
            default -> MySqlValues.text(id);
        };
    }

    private void sessions() {
        Section section = sections.get("sessions");
        if (!schema(section) || !performanceSchema(section)) {
            return;
        }
        MySqlQuery.Rows rows = query(
                section,
                "performance_schema.threads",
                "SELECT THREAD_ID AS thread_id, PROCESSLIST_ID AS connection_id, PROCESSLIST_USER AS user,"
                        + " PROCESSLIST_HOST AS host, PROCESSLIST_DB AS db, PROCESSLIST_COMMAND AS command,"
                        + " PROCESSLIST_STATE AS state, PROCESSLIST_TIME AS state_seconds"
                        + " FROM performance_schema.threads WHERE TYPE='FOREGROUND' AND PROCESSLIST_DB=?"
                        + " AND PROCESSLIST_ID<>? ORDER BY (PROCESSLIST_COMMAND='Sleep'), PROCESSLIST_TIME DESC,"
                        + " THREAD_ID LIMIT ?",
                limits.maxSessions(),
                "UNKNOWN",
                "NOT_APPLICABLE",
                schema(),
                identity.get("connection_id"));
        Map<String, Map<String, String>> transactions = new LinkedHashMap<>();
        if (rows != null && !rows.values().isEmpty()) {
            List<Object> ids = rows.values().stream()
                    .map(row -> (Object) row.get("connection_id"))
                    .toList();
            MySqlQuery.Rows tx = query(
                    section,
                    "information_schema.innodb_trx",
                    "SELECT TRX_MYSQL_THREAD_ID AS connection_id, TRX_STATE AS state,"
                            + " TIMESTAMPDIFF(SECOND,TRX_STARTED,NOW()) AS age_seconds,"
                            + " TRX_ROWS_LOCKED AS rows_locked, TRX_ROWS_MODIFIED AS rows_modified"
                            + " FROM information_schema.innodb_trx WHERE TRX_MYSQL_THREAD_ID IN ("
                            + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
                            + ") ORDER BY TRX_MYSQL_THREAD_ID LIMIT ?",
                    limits.maxSessions(),
                    "ENABLED",
                    "NOT_APPLICABLE",
                    ids.toArray());
            if (tx != null) {
                for (Map<String, String> row : tx.values()) {
                    transactions.put(row.get("connection_id"), row);
                }
            }
        }
        if (rows != null) {
            for (Map<String, String> row : rows.values()) {
                Map<String, String> tx = transactions.getOrDefault(row.get("connection_id"), Map.of());
                sessions.add(new MySqlSessionDto(
                        counter(row, "thread_id"),
                        counter(row, "connection_id"),
                        MySqlValues.exposed("mysql.user", row.get("user"), policy),
                        MySqlValues.exposed("mysql.host", row.get("host"), policy),
                        text(row, "db"),
                        text(row, "command"),
                        text(row, "state"),
                        MySqlValues.number(row.get("state_seconds")),
                        text(tx, "state"),
                        MySqlValues.number(tx.get("age_seconds")),
                        counter(tx, "rows_locked"),
                        counter(tx, "rows_modified")));
            }
        }
        MySqlQuery.Rows edges = query(
                section,
                "performance_schema.data_lock_waits",
                "SELECT e.REQUESTING_THREAD_ID AS requesting_thread, e.BLOCKING_THREAD_ID AS blocking_thread,"
                        + " e.OBJECT_SCHEMA AS schema_name, e.OBJECT_NAME AS object_name, e.INDEX_NAME AS index_name,"
                        + " e.LOCK_MODE AS requested_mode, b.LOCK_MODE AS blocking_mode"
                        + " FROM (SELECT w.ENGINE,w.REQUESTING_THREAD_ID,w.BLOCKING_THREAD_ID,"
                        + " w.BLOCKING_ENGINE_LOCK_ID,w.REQUESTING_ENGINE_LOCK_ID,"
                        + " r.OBJECT_SCHEMA,r.OBJECT_NAME,r.INDEX_NAME,r.LOCK_MODE"
                        + " FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r"
                        + " ON r.ENGINE=w.ENGINE AND r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID"
                        + " WHERE r.OBJECT_SCHEMA=? ORDER BY w.REQUESTING_THREAD_ID,w.BLOCKING_THREAD_ID,"
                        + " w.REQUESTING_ENGINE_LOCK_ID LIMIT ?) e"
                        + " LEFT JOIN performance_schema.data_locks b"
                        + " ON b.ENGINE=e.ENGINE AND b.ENGINE_LOCK_ID=e.BLOCKING_ENGINE_LOCK_ID"
                        + " ORDER BY e.REQUESTING_THREAD_ID,e.BLOCKING_THREAD_ID,e.REQUESTING_ENGINE_LOCK_ID LIMIT ?",
                limits.maxLockWaits(),
                "ENABLED",
                "NOT_APPLICABLE",
                schema(),
                limits.maxLockWaits() + 1);
        if (edges != null) {
            for (Map<String, String> row : edges.values()) {
                locks.add(new MySqlLockWaitDto(
                        "ROW",
                        counter(row, "requesting_thread"),
                        counter(row, "blocking_thread"),
                        text(row, "schema_name"),
                        text(row, "object_name"),
                        text(row, "index_name"),
                        text(row, "requested_mode"),
                        text(row, "blocking_mode"),
                        "WAITING"));
            }
        }
        int remaining = limits.maxLockWaits() - locks.size();
        if (!"ENABLED".equals(metadataLockCollection)) {
            section.reason("Metadata-lock instrumentation is "
                    + metadataLockCollection.toLowerCase(java.util.Locale.ROOT)
                    + "; an empty metadata-lock list does not establish absence.");
        }
        MySqlQuery.Rows metadata = query(
                section,
                "performance_schema.metadata_locks",
                "SELECT OWNER_THREAD_ID AS requesting_thread, OBJECT_SCHEMA AS schema_name,"
                        + " OBJECT_NAME AS object_name, LOCK_TYPE AS requested_mode"
                        + " FROM performance_schema.metadata_locks WHERE OBJECT_SCHEMA=? AND LOCK_STATUS='PENDING'"
                        + " ORDER BY OWNER_THREAD_ID,OBJECT_NAME,LOCK_TYPE LIMIT ?",
                Math.max(1, remaining),
                metadataLockCollection,
                "NOT_APPLICABLE",
                schema());
        if (metadata != null) {
            for (Map<String, String> row : metadata.values()) {
                if (locks.size() >= limits.maxLockWaits()) {
                    section.truncated = true;
                    break;
                }
                locks.add(new MySqlLockWaitDto(
                        "METADATA",
                        counter(row, "requesting_thread"),
                        null,
                        text(row, "schema_name"),
                        text(row, "object_name"),
                        null,
                        text(row, "requested_mode"),
                        null,
                        "PENDING"));
            }
        }
    }

    private void statements() {
        Section section = sections.get("statements");
        if (!schema(section) || !performanceSchema(section)) {
            return;
        }
        if ("DISABLED".equals(statementCollection)) {
            section.skipped = true;
            section.reason("Statement digest collection is disabled; no empty-workload conclusion is possible.");
            return;
        }
        boolean timed = "ENABLED".equals(statementTiming);
        if (!"ENABLED".equals(statementCollection) || !timed) {
            section.reason("Digest collection or timing coverage is incomplete/unknown. Unknown timing is not zero;"
                    + " ranking uses call count when timing is unavailable.");
        }
        MySqlQuery.Rows rows = query(
                section,
                "performance_schema.events_statements_summary_by_digest",
                "SELECT DIGEST AS digest, SCHEMA_NAME AS schema_name, LEFT(DIGEST_TEXT,400) AS digest_text, COUNT_STAR"
                        + " AS calls, SUM_TIMER_WAIT AS total_time, AVG_TIMER_WAIT AS average_time, MAX_TIMER_WAIT AS"
                        + " max_time, SUM_ROWS_EXAMINED AS rows_examined, SUM_ROWS_SENT AS rows_sent, SUM_ERRORS AS"
                        + " errors, SUM_CREATED_TMP_TABLES AS temporary_tables, SUM_CREATED_TMP_DISK_TABLES AS"
                        + " temporary_disk_tables FROM performance_schema.events_statements_summary_by_digest WHERE"
                        + " SCHEMA_NAME=? AND DIGEST IS NOT NULL ORDER BY "
                        + (timed ? "SUM_TIMER_WAIT" : "COUNT_STAR") + " DESC,DIGEST LIMIT ?",
                limits.maxStatements(),
                statementCollection,
                statementTiming,
                schema());
        if (rows != null) {
            for (Map<String, String> row : rows.values()) {
                statements.add(new MySqlStatementDto(
                        text(row, "digest"),
                        text(row, "schema_name"),
                        MySqlValues.digest(row.get("digest_text"), policy),
                        counter(row, "calls"),
                        millis(row, "total_time", timed),
                        millis(row, "average_time", timed),
                        millis(row, "max_time", timed),
                        counter(row, "rows_examined"),
                        counter(row, "rows_sent"),
                        counter(row, "errors"),
                        counter(row, "temporary_tables"),
                        counter(row, "temporary_disk_tables")));
            }
        }
        MySqlQuery.Rows overflow = query(
                section,
                "digest-overflow",
                "SELECT COUNT_STAR AS calls FROM performance_schema.events_statements_summary_by_digest"
                        + " WHERE SCHEMA_NAME IS NULL AND DIGEST IS NULL LIMIT ?",
                1,
                statementCollection,
                "NOT_APPLICABLE");
        if (overflow != null
                && overflow.values().stream().anyMatch(row -> {
                    String calls = counter(row, "calls");
                    return calls != null && !"0".equals(calls);
                })) {
            section.reason("MySQL's server-wide null-digest overflow bucket contains calls."
                    + " These cannot be attributed to this schema and are separate from BootUI row caps.");
        }
    }

    private void tables() {
        Section section = sections.get("tables");
        if (!schema(section)) {
            return;
        }
        MySqlQuery.Rows catalog = query(
                section,
                "information_schema.tables",
                "SELECT TABLE_SCHEMA AS schema_name,TABLE_NAME AS table_name,"
                        + objectKey("TABLE_NAME") + " AS instrumentation_name,ENGINE AS engine,"
                        + " TABLE_ROWS AS estimated_rows,DATA_LENGTH AS data_bytes,INDEX_LENGTH AS index_bytes"
                        + " FROM information_schema.tables WHERE TABLE_SCHEMA=? AND "
                        + objectKey("TABLE_SCHEMA") + "=? AND TABLE_TYPE='BASE TABLE'"
                        + " ORDER BY TABLE_NAME LIMIT ?",
                limits.maxTables(),
                "ENABLED",
                "NOT_APPLICABLE",
                schema(),
                instrumentationSchema());
        if (catalog == null) {
            return;
        }
        Map<String, Map<String, String>> io = new LinkedHashMap<>();
        if (performanceSchema(section)) {
            MySqlQuery.Rows usage = query(
                    section,
                    "performance_schema.table_io_waits_summary_by_table",
                    "SELECT OBJECT_NAME AS table_name," + objectKey("OBJECT_NAME")
                            + " AS instrumentation_name,COUNT_READ AS read_ops,COUNT_WRITE AS write_ops,SUM_TIMER_WAIT"
                            + " AS time FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA=?"
                            + " ORDER BY OBJECT_NAME LIMIT ?",
                    limits.maxTables(),
                    tableCollection,
                    tableTiming,
                    instrumentationSchema());
            if (usage != null) {
                for (Map<String, String> row : usage.values()) {
                    io.put(row.get("instrumentation_name"), row);
                }
            }
        }
        List<MySqlObjectInstrumentation.State> states = new ArrayList<>();
        for (Map<String, String> row : catalog.values()) {
            String object = row.get("instrumentation_name");
            if (object != null) {
                catalogObjectNames.add(object);
            }
            MySqlObjectInstrumentation.State state = tableState(object);
            states.add(state);
            Map<String, String> usage = io.getOrDefault(object, Map.of());
            tables.add(new MySqlTableDto(
                    text(row, "schema_name"),
                    text(row, "table_name"),
                    text(row, "engine"),
                    counter(row, "estimated_rows"),
                    counter(row, "data_bytes"),
                    counter(row, "index_bytes"),
                    "ENABLED".equals(state.collection()) ? counter(usage, "read_ops") : null,
                    "ENABLED".equals(state.collection()) ? counter(usage, "write_ops") : null,
                    millis(usage, "time", "ENABLED".equals(state.timing()))));
        }
        qualifyTableInstrumentation(section, "performance_schema.table_io_waits_summary_by_table", states);
    }

    private void indexes() {
        Section section = sections.get("indexes");
        if (!schema(section) || !performanceSchema(section)) {
            return;
        }
        MySqlQuery.Rows rows = query(
                section,
                "performance_schema.table_io_waits_summary_by_index_usage",
                "SELECT OBJECT_SCHEMA AS schema_name,OBJECT_NAME AS table_name,"
                        + objectKey("OBJECT_NAME") + " AS instrumentation_name,INDEX_NAME AS index_name, COUNT_READ AS"
                        + " read_ops,COUNT_WRITE AS write_ops,COUNT_FETCH AS fetches,COUNT_INSERT AS inserts, COUNT_UPDATE"
                        + " AS updates,COUNT_DELETE AS deletes,SUM_TIMER_WAIT AS time FROM"
                        + " performance_schema.table_io_waits_summary_by_index_usage WHERE OBJECT_SCHEMA=? ORDER BY"
                        + " OBJECT_NAME,INDEX_NAME LIMIT ?",
                limits.maxIndexes(),
                tableCollection,
                tableTiming,
                instrumentationSchema());
        if (rows != null) {
            List<MySqlObjectInstrumentation.State> states = new ArrayList<>();
            for (String object : catalogObjectNames) {
                states.add(tableState(object));
            }
            for (Map<String, String> row : rows.values()) {
                MySqlObjectInstrumentation.State state = tableState(row.get("instrumentation_name"));
                states.add(state);
                boolean collected = "ENABLED".equals(state.collection());
                indexes.add(new MySqlIndexDto(
                        text(row, "schema_name"),
                        text(row, "table_name"),
                        text(row, "index_name"),
                        collected ? counter(row, "read_ops") : null,
                        collected ? counter(row, "write_ops") : null,
                        collected ? counter(row, "fetches") : null,
                        collected ? counter(row, "inserts") : null,
                        collected ? counter(row, "updates") : null,
                        collected ? counter(row, "deletes") : null,
                        millis(row, "time", "ENABLED".equals(state.timing()))));
            }
            qualifyTableInstrumentation(section, "performance_schema.table_io_waits_summary_by_index_usage", states);
        }
    }

    private String instrumentationSchema() {
        return identity.get("instrumentation_schema");
    }

    private String objectKey(String column) {
        String value = "CONVERT(" + column + " USING utf8mb4) COLLATE utf8mb4_0900_bin";
        return "0".equals(identity.get("lower_case_table_names")) ? value : "LOWER(" + value + ")";
    }

    private MySqlObjectInstrumentation.State tableState(String object) {
        if (!"ENABLED".equals(tableCollection)) {
            return new MySqlObjectInstrumentation.State(tableCollection, tableTiming);
        }
        if (objectInstrumentation == null) {
            Section evidence = new Section("object-instrumentation", "Object instrumentation", SCHEMA);
            MySqlQuery.Rows rules = query(
                    evidence,
                    "performance_schema.setup_objects",
                    "SELECT OBJECT_SCHEMA AS schema_name,OBJECT_NAME AS object_name,ENABLED AS enabled,TIMED AS timed"
                            + " FROM performance_schema.setup_objects WHERE OBJECT_TYPE='TABLE'"
                            + " AND (OBJECT_SCHEMA=? OR (OBJECT_SCHEMA='%' AND OBJECT_NAME='%'))"
                            + " ORDER BY OBJECT_SCHEMA,OBJECT_NAME LIMIT ?",
                    MySqlObjectInstrumentation.MAX_RULES,
                    "NOT_APPLICABLE",
                    "NOT_APPLICABLE",
                    instrumentationSchema());
            if (!evidence.reasons.isEmpty()) {
                objectInstrumentationProblem = String.join(" ", evidence.reasons);
            } else if (rules != null && rules.truncated()) {
                objectInstrumentationProblem = "Object instrumentation rules reached the inspection bound;"
                        + " unmatched rules remain unknown.";
            }
            objectInstrumentation = new MySqlObjectInstrumentation(instrumentationSchema(), rules);
        }
        MySqlObjectInstrumentation.State local = objectInstrumentation.forObject(object);
        return new MySqlObjectInstrumentation.State(
                MySqlObjectInstrumentation.combine(tableCollection, local.collection()),
                MySqlObjectInstrumentation.combine(tableTiming, local.timing()));
    }

    private void qualifyTableInstrumentation(
            Section section, String source, List<MySqlObjectInstrumentation.State> states) {
        String collection = aggregateState(
                states.stream()
                        .map(MySqlObjectInstrumentation.State::collection)
                        .toList(),
                tableCollection);
        String timing = aggregateState(
                states.stream().map(MySqlObjectInstrumentation.State::timing).toList(), tableTiming);
        String reason = !"ENABLED".equals(collection)
                ? "Table I/O collection is disabled or unknown for retained objects; affected operation counts are withheld."
                : !"ENABLED".equals(timing)
                        ? "Table I/O timing is disabled or unknown for retained objects; affected durations are withheld."
                        : null;
        if (reason != null) {
            section.reason(reason);
            if (objectInstrumentationProblem != null) {
                section.reason(objectInstrumentationProblem);
            }
        }
        for (int index = 0; index < capabilities.size(); index++) {
            MySqlCapabilityDto capability = capabilities.get(index);
            if (source.equals(capability.id()) && "READABLE".equals(capability.readability())) {
                capabilities.set(
                        index,
                        new MySqlCapabilityDto(
                                capability.id(),
                                capability.source(),
                                capability.scope(),
                                capability.readability(),
                                collection,
                                timing,
                                capability.reason() == null ? reason : capability.reason()));
            }
        }
    }

    private static String aggregateState(List<String> states, String empty) {
        return states.isEmpty() ? empty : states.stream().distinct().count() == 1 ? states.get(0) : "UNKNOWN";
    }

    private void innodb() {
        Section section = sections.get("innodb");
        for (MySqlMetricDto metric : vitalSigns) {
            if (metric.id().startsWith("Innodb_")) {
                innodb.add(metric);
                section.success = true;
            }
        }
        MySqlQuery.Rows rows = query(
                section,
                "information_schema.innodb_metrics",
                "SELECT NAME AS name,COUNT AS value,STATUS AS status FROM information_schema.innodb_metrics"
                        + " WHERE NAME IN ('lock_deadlocks','trx_rseg_history_len') ORDER BY NAME LIMIT ?",
                2,
                "UNKNOWN",
                "NOT_APPLICABLE");
        if (rows != null) {
            for (Map<String, String> row : rows.values()) {
                boolean enabled = "enabled".equalsIgnoreCase(row.get("status"));
                innodb.add(new MySqlMetricDto(
                        text(row, "name"),
                        metricLabel(row.get("name")),
                        enabled ? counter(row, "value") : null,
                        "count",
                        SERVER,
                        "information_schema.innodb_metrics"));
                if (!enabled) {
                    section.reason("Some InnoDB counters are disabled; BootUI did not enable them.");
                }
            }
            if (rows.values().size() != 2) {
                section.reason("Some allow-listed InnoDB counters were not reported.");
            }
        }
    }

    private void replication() {
        Section section = sections.get("replication");
        MySqlQuery.Rows receivers = query(
                section,
                "performance_schema.replication_connection_status",
                "SELECT CHANNEL_NAME AS channel,SERVICE_STATE AS state,LAST_ERROR_NUMBER AS error"
                        + " FROM performance_schema.replication_connection_status ORDER BY CHANNEL_NAME LIMIT ?",
                limits.maxReplicationChannels(),
                "ENABLED",
                "NOT_APPLICABLE");
        MySqlQuery.Rows appliers = query(
                section,
                "performance_schema.replication_applier_status",
                "SELECT CHANNEL_NAME AS channel,SERVICE_STATE AS state"
                        + " FROM performance_schema.replication_applier_status ORDER BY CHANNEL_NAME LIMIT ?",
                limits.maxReplicationChannels(),
                "ENABLED",
                "NOT_APPLICABLE");
        MySqlQuery.Rows coordinators = query(
                section,
                "performance_schema.replication_applier_status_by_coordinator",
                "SELECT CHANNEL_NAME AS channel,LAST_ERROR_NUMBER AS error"
                        + " FROM performance_schema.replication_applier_status_by_coordinator"
                        + " ORDER BY CHANNEL_NAME LIMIT ?",
                limits.maxReplicationChannels(),
                "ENABLED",
                "NOT_APPLICABLE");
        // Aggregate at the source; never materialize an unbounded nested worker list.
        MySqlQuery.Rows workers = query(
                section,
                "performance_schema.replication_applier_status_by_worker",
                "SELECT CHANNEL_NAME AS channel,COUNT(*) AS workers,SUM(LAST_ERROR_NUMBER<>0) AS errors,"
                        + " MAX(LAST_ERROR_NUMBER) AS error FROM performance_schema.replication_applier_status_by_worker"
                        + " GROUP BY CHANNEL_NAME ORDER BY CHANNEL_NAME LIMIT ?",
                limits.maxReplicationChannels(),
                "ENABLED",
                "NOT_APPLICABLE");
        Map<String, Map<String, String>> receiverMap = keyed(receivers, "channel");
        Map<String, Map<String, String>> applierMap = keyed(appliers, "channel");
        Map<String, Map<String, String>> coordinatorMap = keyed(coordinators, "channel");
        Map<String, Map<String, String>> workerMap = keyed(workers, "channel");
        Set<String> names = new java.util.TreeSet<>();
        names.addAll(receiverMap.keySet());
        names.addAll(applierMap.keySet());
        names.addAll(coordinatorMap.keySet());
        names.addAll(workerMap.keySet());
        for (String channel : names) {
            if (replication.size() == limits.maxReplicationChannels()) {
                section.truncated = true;
                break;
            }
            Map<String, String> receiver = receiverMap.getOrDefault(channel, Map.of());
            Map<String, String> applier = applierMap.getOrDefault(channel, Map.of());
            Map<String, String> coordinator = coordinatorMap.getOrDefault(channel, Map.of());
            Map<String, String> worker = workerMap.getOrDefault(channel, Map.of());
            Integer error = integer(receiver.get("error"));
            Integer workerError = integer(worker.get("error"));
            if (workerError != null && (error == null || workerError > error)) {
                error = workerError;
            }
            Integer coordinatorError = integer(coordinator.get("error"));
            if (coordinatorError != null && (error == null || coordinatorError > error)) {
                error = coordinatorError;
            }
            if (Integer.valueOf(0).equals(error)
                    && !(channelRead(receivers, receiverMap, channel)
                            && channelRead(coordinators, coordinatorMap, channel)
                            && channelRead(workers, workerMap, channel))) {
                error = null;
            }
            replication.add(new MySqlReplicationChannelDto(
                    MySqlValues.text(channel),
                    text(receiver, "state"),
                    text(applier, "state"),
                    counter(worker, "workers"),
                    counter(worker, "errors"),
                    error));
        }
    }

    private static boolean channelRead(
            MySqlQuery.Rows rows, Map<String, Map<String, String>> observed, String channel) {
        return observed.containsKey(channel) || (rows != null && rows.reason() == null && !rows.truncated());
    }

    private void settings() {
        Section section = sections.get("settings");
        // System-variable SELECTs need no performance_schema SELECT privilege; fixed names only.
        String sql = String.join(
                        " UNION ALL ",
                        SETTINGS.stream()
                                .map(setting -> "SELECT '" + setting + "' AS name,CAST(@@global." + setting
                                        + " AS CHAR) AS value")
                                .toList())
                + " LIMIT ?";
        MySqlQuery.Rows rows =
                query(section, "global-system-variables", sql, limits.maxSettings(), "ENABLED", "NOT_APPLICABLE");
        if (rows != null) {
            for (Map<String, String> row : rows.values()) {
                settings.add(new MySqlSettingDto(
                        row.get("name"),
                        MySqlValues.exposed(row.get("name"), row.get("value"), policy),
                        "GLOBAL",
                        "global-system-variables"));
            }
        }
    }

    private static Map<String, String> map(MySqlQuery.Rows rows, String key, String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows.values()) {
            result.put(row.get(key), row.get(value));
        }
        return result;
    }

    private static Map<String, Map<String, String>> keyed(MySqlQuery.Rows rows, String key) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (rows != null) {
            for (Map<String, String> row : rows.values()) {
                result.put(row.get(key), row);
            }
        }
        return result;
    }

    private static String text(Map<String, String> row, String key) {
        return MySqlValues.text(row.get(key));
    }

    private static String counter(Map<String, String> row, String key) {
        return MySqlValues.counter(row.get(key));
    }

    private static Double millis(Map<String, String> row, String key, boolean timed) {
        return MySqlValues.millis(row.get(key), timed);
    }

    private static Integer integer(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static final class Section {
        final String id;
        final String title;
        final String scope;
        final List<String> reasons = new ArrayList<>();
        boolean success;
        boolean skipped;
        boolean truncated;
        int rowCount;

        Section(String id, String title, String scope) {
            this.id = id;
            this.title = title;
            this.scope = scope;
        }

        void reason(String reason) {
            if (reasons.size() < 8 && !reasons.contains(reason)) {
                reasons.add(reason);
            }
        }

        MySqlSectionDto dto() {
            return new MySqlSectionDto(
                    id,
                    title,
                    success ? "AVAILABLE" : skipped ? "SKIPPED" : "FAILED",
                    reasons.isEmpty() ? null : String.join(" ", reasons),
                    reasons.isEmpty()
                            ? null
                            : "Check source grants and enabled instrumentation; BootUI changes neither.",
                    scope,
                    rowCount,
                    truncated);
        }
    }
}
