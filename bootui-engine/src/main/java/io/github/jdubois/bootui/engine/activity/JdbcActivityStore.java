package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Direct-JDBC {@link ActivityStore} implementation: a portable schema (no dialect-specific DDL), one
 * table shared by every BootUI instance pointed at the same database (the {@code instance_id} column is
 * the multi-tenant partition key), and auto-creation on first use.
 *
 * <p>Every JDBC call this class makes runs inside {@link BootUiJdbcCaptureGuard#runSuppressed}, so it
 * can safely run over a {@code DataSource} that {@code SqlTraceRecorder} is also wrapping without
 * appearing in the SQL Trace panel or, worse, being captured back into this very store.</p>
 *
 * <p>Every statement is bounded by {@link #QUERY_TIMEOUT_SECONDS}: {@link BufferedActivityStore}'s
 * flush/prune scheduler is single-threaded, so one statement that hangs (for example a database that
 * stops responding mid-query rather than cleanly refusing the connection) would otherwise wedge every
 * future flush and prune forever, not just the one in progress; it also keeps the bounded final flush
 * {@link BufferedActivityStore#close()} attempts on shutdown meaningfully bounded rather than depending
 * on the JDBC driver alone to give up in time.</p>
 *
 * <p>The primary key is the client-assigned pair {@code (instance_id, seq)} rather than a database
 * identity/auto-increment column, so the same DDL works unmodified across H2, PostgreSQL, MySQL, Oracle
 * and SQL Server. Pagination is keyset-based on {@code (occurred_at, seq)}, not {@code OFFSET}, so it
 * stays stable and fast on a table that is continuously appended to.</p>
 */
public final class JdbcActivityStore implements ActivityStore {

    /**
     * Upper bound, in seconds, on any single statement this store executes (probe, DDL, insert,
     * select, delete). See the class-level note on why this matters beyond just this one call.
     */
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final DataSource dataSource;
    private final String tableName;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public JdbcActivityStore(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = validateTableName(tableName);
    }

    @Override
    public void appendBatch(List<StoredActivityEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        try {
            BootUiJdbcCaptureGuard.runSuppressed(() -> {
                ensureSchema();
                insertBatch(entries);
                return null;
            });
        } catch (Exception ex) {
            throw new ActivityStoreException("Failed to append " + entries.size() + " activity entries", ex);
        }
    }

    @Override
    public ActivityPage query(ActivityQuery query) {
        try {
            return BootUiJdbcCaptureGuard.runSuppressed(() -> {
                ensureSchema();
                return runQuery(query);
            });
        } catch (Exception ex) {
            throw new ActivityStoreException("Failed to query activity entries", ex);
        }
    }

    @Override
    public void prune(String instanceId, long olderThanEpochMillis) {
        try {
            BootUiJdbcCaptureGuard.runSuppressed(() -> {
                ensureSchema();
                String sql = "DELETE FROM " + tableName + " WHERE instance_id = ? AND occurred_at < ?";
                try (Connection connection = dataSource.getConnection();
                        PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.setString(1, instanceId);
                    statement.setLong(2, olderThanEpochMillis);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (Exception ex) {
            throw new ActivityStoreException("Failed to prune activity entries", ex);
        }
    }

    /** Probes for the table and creates it (and its supporting index) if missing, racing safely. */
    private void ensureSchema() throws SQLException {
        if (schemaReady.get()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (tableExists(connection)) {
                schemaReady.set(true);
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.executeUpdate(createTableSql());
            } catch (SQLException createFailed) {
                // Another instance may have created the table concurrently (multi-tenant startup race).
                // Re-probe before giving up: the desired end state (table exists) is what matters, not
                // which instance won the race.
                if (!tableExists(connection)) {
                    throw createFailed;
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.executeUpdate(createIndexSql());
            } catch (SQLException indexFailed) {
                // The index is a performance aid, not a correctness requirement (e.g. it may already
                // exist from a concurrent creator, or the dialect names indexes differently); never fail
                // the whole store over it.
            }
            schemaReady.set(true);
        }
    }

    private boolean tableExists(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet ignored = statement.executeQuery("SELECT 1 FROM " + tableName + " WHERE 1 = 0")) {
                return true;
            }
        } catch (SQLException notFound) {
            return false;
        }
    }

    private String createTableSql() {
        return "CREATE TABLE "
                + tableName
                + " (" + "instance_id VARCHAR(64) NOT NULL, "
                + "seq BIGINT NOT NULL, "
                + "entry_id VARCHAR(128) NOT NULL, "
                + "entry_type VARCHAR(16) NOT NULL, "
                + "occurred_at BIGINT NOT NULL, "
                + "severity VARCHAR(16), "
                + "summary VARCHAR(1000), "
                + "detail VARCHAR(2000), "
                + "duration_ms BIGINT, "
                + "correlation_id VARCHAR(64), "
                + "http_method VARCHAR(10), "
                + "path VARCHAR(512), "
                + "status_code INTEGER, "
                + "thread_name VARCHAR(128), "
                + "profileable INTEGER NOT NULL, "
                + "parent_entry_id VARCHAR(128), "
                + "secured_principal VARCHAR(256), "
                + "PRIMARY KEY (instance_id, seq))";
    }

    private String createIndexSql() {
        return "CREATE INDEX idx_" + tableName + "_time ON " + tableName + " (instance_id, occurred_at, seq)";
    }

    private void insertBatch(List<StoredActivityEntry> entries) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (instance_id, seq, entry_id, entry_type, occurred_at, "
                + "severity, summary, detail, duration_ms, correlation_id, http_method, path, status_code, "
                + "thread_name, profileable, parent_entry_id, secured_principal) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            for (StoredActivityEntry stored : entries) {
                ActivityEntryDto entry = stored.entry();
                int i = 1;
                statement.setString(i++, stored.instanceId());
                statement.setLong(i++, stored.seq());
                statement.setString(i++, entry.id());
                statement.setString(i++, entry.type());
                statement.setLong(i++, entry.timestamp());
                statement.setString(i++, entry.severity());
                statement.setString(i++, entry.summary());
                statement.setString(i++, entry.detail());
                setNullableLong(statement, i++, entry.durationMs());
                statement.setString(i++, entry.correlationId());
                statement.setString(i++, entry.method());
                statement.setString(i++, entry.path());
                setNullableInt(statement, i++, entry.status());
                statement.setString(i++, entry.thread());
                statement.setInt(i++, entry.profileable() ? 1 : 0);
                statement.setString(i++, entry.parentId());
                statement.setString(i++, entry.securedPrincipal());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private ActivityPage runQuery(ActivityQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT instance_id, seq, entry_id, entry_type, occurred_at, "
                + "severity, summary, detail, duration_ms, correlation_id, http_method, path, status_code, "
                + "thread_name, profileable, parent_entry_id, secured_principal FROM "
                + tableName
                + " WHERE instance_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(query.instanceId());

        if (query.normalizedType() != null) {
            sql.append(" AND UPPER(entry_type) = ?");
            params.add(query.normalizedType().toUpperCase(java.util.Locale.ROOT));
        }
        if (query.normalizedSeverity() != null) {
            sql.append(" AND UPPER(severity) = ?");
            params.add(query.normalizedSeverity().toUpperCase(java.util.Locale.ROOT));
        }
        if (query.normalizedText() != null) {
            String needle = "%" + query.normalizedText().toUpperCase(java.util.Locale.ROOT) + "%";
            sql.append(" AND (UPPER(summary) LIKE ? OR UPPER(detail) LIKE ? OR UPPER(path) LIKE ? "
                    + "OR UPPER(http_method) LIKE ?)");
            params.add(needle);
            params.add(needle);
            params.add(needle);
            params.add(needle);
        }
        if (query.since() != null) {
            sql.append(" AND occurred_at > ?");
            params.add(query.since());
        }
        if (query.until() != null) {
            sql.append(" AND occurred_at <= ?");
            params.add(query.until());
        }
        ActivityCursor cursor = ActivityCursor.decode(query.cursor());
        if (cursor != null) {
            sql.append(" AND (occurred_at < ? OR (occurred_at = ? AND seq < ?))");
            params.add(cursor.timestamp());
            params.add(cursor.timestamp());
            params.add(cursor.seq());
        }
        sql.append(" ORDER BY occurred_at DESC, seq DESC OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY");
        int limit = query.pageSize() + 1;
        params.add(limit);

        List<StoredActivityEntry> matches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    matches.add(toStoredEntry(rs));
                }
            }
        }

        boolean hasMore = matches.size() > query.pageSize();
        List<StoredActivityEntry> page = hasMore ? matches.subList(0, query.pageSize()) : matches;
        String nextCursor = null;
        if (hasMore) {
            StoredActivityEntry last = page.get(page.size() - 1);
            nextCursor = new ActivityCursor(last.entry().timestamp(), last.seq()).encode();
        }
        return new ActivityPage(page, nextCursor, hasMore);
    }

    private StoredActivityEntry toStoredEntry(ResultSet rs) throws SQLException {
        String instanceId = rs.getString("instance_id");
        long seq = rs.getLong("seq");
        ActivityEntryDto entry = new ActivityEntryDto(
                rs.getString("entry_id"),
                rs.getString("entry_type"),
                rs.getLong("occurred_at"),
                rs.getString("severity"),
                rs.getString("summary"),
                rs.getString("detail"),
                getNullableLong(rs, "duration_ms"),
                rs.getString("correlation_id"),
                rs.getString("http_method"),
                rs.getString("path"),
                getNullableInt(rs, "status_code"),
                rs.getString("thread_name"),
                rs.getInt("profileable") != 0,
                rs.getString("parent_entry_id"),
                rs.getString("secured_principal"));
        return new StoredActivityEntry(instanceId, seq, entry);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * Guards against SQL injection through a misconfigured {@code bootui.activity.persistence.table-name}:
     * only identifier characters are allowed, since the table name is concatenated directly into DDL/DML
     * (JDBC has no parameter-binding for identifiers).
     */
    private static String validateTableName(String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "bootui.activity.persistence.table-name must be a plain SQL identifier, was: " + tableName);
        }
        return tableName;
    }
}
