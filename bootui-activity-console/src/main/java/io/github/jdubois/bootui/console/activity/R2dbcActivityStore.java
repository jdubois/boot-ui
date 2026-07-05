package io.github.jdubois.bootui.console.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.ActivityCursor;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStoreException;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive, Spring Data R2DBC-backed {@link ReactiveActivityStore} implementation for the BootUI Activity
 * Console: the same portable schema and keyset-pagination approach as the engine's
 * {@code JdbcActivityStore} (one table shared by every instance the console has received data from, with
 * {@code instance_id} as the multi-tenant partition key), but every statement runs through {@link
 * DatabaseClient} so nothing ever blocks the Netty event loop the console's WebFlux handlers run on.
 *
 * <p>Two deliberate differences from {@code JdbcActivityStore}, both because the console's situation
 * differs from a host application's:
 *
 * <ul>
 *   <li>No {@code BootUiJdbcCaptureGuard}-style suppression: that guard exists so a host application's
 *       own SQL Trace capture doesn't loop back on {@code JdbcActivityStore}'s bookkeeping statements when
 *       both share one {@code DataSource}. The console has no such host application and no SQL Trace
 *       panel of its own to suppress — its R2DBC connection factory is dedicated purely to this table.
 *   <li>Batched inserts run as {@code N} sequential statements (via {@link Flux#concatMap}) instead of one
 *       driver-level batch round trip. {@link DatabaseClient}'s named-parameter binding (the whole reason
 *       queries here stay portable across R2DBC drivers, exactly like the dialect-neutral DDL/DML below)
 *       has no batching equivalent — only the raw {@code io.r2dbc.spi.Statement.add()} escape hatch does,
 *       and that requires driver-specific positional placeholder syntax in the SQL text, which would
 *       break portability across drivers. For the console's ingest volumes (bounded buffer flushes from a
 *       handful of local instances) this trade is a better fit than losing that portability.
 * </ul>
 *
 * <p>Unlike {@code JdbcActivityStore}, this store also serves an {@code instance_id}-agnostic read (
 * {@link #queryAllInstances}), backed by its own supporting index ({@link #createGlobalIndexSql()}):
 * the console's whole reason to exist is a single merged feed across every instance forwarding to it, so
 * unlike a host application's own single-instance panel, its primary query has no instance to scope to.
 */
public final class R2dbcActivityStore implements ReactiveActivityStore {

    /** Upper bound on any single operation (schema probe/create plus the statement itself). */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    private static final String SELECT_COLUMNS = "SELECT instance_id, seq, entry_id, entry_type, occurred_at, "
            + "severity, summary, detail, duration_ms, correlation_id, http_method, path, status_code, "
            + "thread_name, profileable, parent_entry_id, secured_principal, sql_n_plus_one_suspected";

    private final DatabaseClient databaseClient;
    private final String tableName;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public R2dbcActivityStore(DatabaseClient databaseClient, String tableName) {
        this.databaseClient = databaseClient;
        this.tableName = validateTableName(tableName);
    }

    @Override
    public Mono<Void> appendBatch(List<StoredActivityEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Mono.empty();
        }
        return ensureSchema()
                .then(insertBatch(entries))
                .timeout(QUERY_TIMEOUT)
                .onErrorMap(ex ->
                        new ActivityStoreException("Failed to append " + entries.size() + " activity entries", ex));
    }

    @Override
    public Mono<ActivityPage> query(ActivityQuery query) {
        return ensureSchema()
                .then(runQuery(query))
                .timeout(QUERY_TIMEOUT)
                .onErrorMap(ex -> new ActivityStoreException("Failed to query activity entries", ex));
    }

    @Override
    public Mono<ActivityPage> queryAllInstances(ActivityQuery query) {
        return ensureSchema()
                .then(runGlobalQuery(query))
                .timeout(QUERY_TIMEOUT)
                .onErrorMap(ex -> new ActivityStoreException("Failed to query activity entries across instances", ex));
    }

    @Override
    public Mono<List<StoredActivityEntry>> queryByCorrelationId(String correlationId, int limit) {
        if (correlationId == null || correlationId.isBlank()) {
            return Mono.just(List.of());
        }
        return ensureSchema()
                .then(runCorrelationQuery(correlationId, Math.max(1, limit)))
                .timeout(QUERY_TIMEOUT)
                .onErrorMap(ex -> new ActivityStoreException("Failed to query activity entries by correlation id", ex));
    }

    @Override
    public Mono<StoredActivityEntry> findByEntryId(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return Mono.empty();
        }
        return ensureSchema()
                .then(databaseClient
                        .sql(SELECT_COLUMNS + " FROM " + tableName + " WHERE entry_id = :entryId "
                                + "ORDER BY occurred_at DESC, seq DESC OFFSET 0 ROWS FETCH FIRST 1 ROW ONLY")
                        .bind("entryId", entryId)
                        .map(this::toStoredEntry)
                        .first())
                .timeout(QUERY_TIMEOUT)
                .onErrorMap(ex -> new ActivityStoreException("Failed to find activity entry by id", ex));
    }

    @Override
    public Mono<Void> prune(String instanceId, long olderThanEpochMillis) {
        return ensureSchema()
                .then(databaseClient
                        .sql("DELETE FROM " + tableName + " WHERE instance_id = :instanceId AND occurred_at < :cutoff")
                        .bind("instanceId", instanceId)
                        .bind("cutoff", olderThanEpochMillis)
                        .fetch()
                        .rowsUpdated())
                .timeout(QUERY_TIMEOUT)
                .onErrorMap(ex -> new ActivityStoreException("Failed to prune activity entries", ex))
                .then();
    }

    /**
     * Eagerly probes for and creates this store's table (if missing), for callers that want to fail fast
     * with a clear error before committing to this store — the reactive counterpart of
     * {@code JdbcActivityStore#verifySchema()}. Idempotent: safe to call even when the schema is already
     * known-ready.
     */
    public Mono<Void> verifySchema() {
        return ensureSchema()
                .timeout(QUERY_TIMEOUT)
                .onErrorMap(ex -> new ActivityStoreException("Failed to verify/create the activity table", ex));
    }

    /** Probes for the table and creates it (and its supporting indexes) if missing, racing safely. */
    private Mono<Void> ensureSchema() {
        if (schemaReady.get()) {
            return Mono.empty();
        }
        return tableExists()
                .flatMap(exists -> exists ? Mono.<Void>empty() : createSchema())
                .doOnSuccess(ignored -> schemaReady.set(true));
    }

    private Mono<Boolean> tableExists() {
        return databaseClient
                .sql("SELECT 1 FROM " + tableName + " WHERE 1 = 0")
                .fetch()
                .all()
                .then(Mono.just(Boolean.TRUE))
                .onErrorReturn(Boolean.FALSE);
    }

    private Mono<Void> createSchema() {
        return execute(createTableSql())
                // Another instance may have created the table concurrently (multi-tenant startup race).
                // Re-probe before giving up: the desired end state (table exists) is what matters, not
                // which instance won the race.
                .onErrorResume(createFailed ->
                        tableExists().flatMap(exists -> exists ? Mono.empty() : Mono.error(createFailed)))
                // The indexes are performance aids, not correctness requirements (e.g. one may already
                // exist from a concurrent creator); never fail the whole store over either of them.
                .then(execute(createIndexSql()).onErrorResume(ignored -> Mono.empty()))
                .then(execute(createGlobalIndexSql()).onErrorResume(ignored -> Mono.empty()))
                .then(execute(createCorrelationIndexSql()).onErrorResume(ignored -> Mono.empty()));
    }

    private Mono<Void> execute(String sql) {
        return databaseClient.sql(sql).fetch().rowsUpdated().then();
    }

    private String createTableSql() {
        return "CREATE TABLE " + tableName + " (" + "instance_id VARCHAR(64) NOT NULL, "
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
                + "sql_n_plus_one_suspected INTEGER NOT NULL, "
                + "PRIMARY KEY (instance_id, seq))";
    }

    private String createIndexSql() {
        return "CREATE INDEX idx_" + tableName + "_time ON " + tableName + " (instance_id, occurred_at, seq)";
    }

    /**
     * Supports {@link #queryAllInstances}, the console's primary read: a keyset-paginated scan with no
     * {@code instance_id} predicate at all, which cannot benefit from {@link #createIndexSql()}'s
     * composite index since that index's leading column is {@code instance_id}.
     */
    private String createGlobalIndexSql() {
        return "CREATE INDEX idx_" + tableName + "_time_global ON " + tableName + " (occurred_at, seq)";
    }

    /**
     * Supports {@link #queryByCorrelationId}, the one query in this store that is deliberately not scoped
     * by {@code instance_id} and so cannot benefit from {@link #createIndexSql()}'s composite index, whose
     * leading column is {@code instance_id}.
     */
    private String createCorrelationIndexSql() {
        return "CREATE INDEX idx_" + tableName + "_correlation ON " + tableName + " (correlation_id)";
    }

    private Mono<Void> insertBatch(List<StoredActivityEntry> entries) {
        return Flux.fromIterable(entries).concatMap(this::insertOne).then();
    }

    private Mono<Void> insertOne(StoredActivityEntry stored) {
        ActivityEntryDto entry = stored.entry();
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql("INSERT INTO " + tableName + " (instance_id, seq, entry_id, entry_type, occurred_at, "
                        + "severity, summary, detail, duration_ms, correlation_id, http_method, path, "
                        + "status_code, thread_name, profileable, parent_entry_id, secured_principal, "
                        + "sql_n_plus_one_suspected) VALUES (:instanceId, :seq, :entryId, :entryType, "
                        + ":occurredAt, :severity, :summary, :detail, :durationMs, :correlationId, :method, "
                        + ":path, :statusCode, :thread, :profileable, :parentId, :securedPrincipal, :nPlusOne)")
                .bind("instanceId", stored.instanceId())
                .bind("seq", stored.seq())
                .bind("entryId", entry.id())
                .bind("entryType", entry.type())
                .bind("occurredAt", entry.timestamp())
                .bind("profileable", entry.profileable() ? 1 : 0)
                .bind("nPlusOne", entry.sqlNPlusOneSuspected() ? 1 : 0);
        spec = bindNullableString(spec, "severity", entry.severity());
        spec = bindNullableString(spec, "summary", entry.summary());
        spec = bindNullableString(spec, "detail", entry.detail());
        spec = bindNullableLong(spec, "durationMs", entry.durationMs());
        spec = bindNullableString(spec, "correlationId", entry.correlationId());
        spec = bindNullableString(spec, "method", entry.method());
        spec = bindNullableString(spec, "path", entry.path());
        spec = bindNullableInt(spec, "statusCode", entry.status());
        spec = bindNullableString(spec, "thread", entry.thread());
        spec = bindNullableString(spec, "parentId", entry.parentId());
        spec = bindNullableString(spec, "securedPrincipal", entry.securedPrincipal());
        return spec.fetch().rowsUpdated().then();
    }

    private Mono<ActivityPage> runQuery(ActivityQuery query) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append(" FROM ")
                .append(tableName)
                .append(" WHERE instance_id = :instanceId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("instanceId", query.instanceId());
        return runFilteredQuery(sql, params, query);
    }

    /**
     * Backs {@link #queryAllInstances}: identical filter/pagination handling to {@link #runQuery}, minus
     * the {@code instance_id} predicate (and so minus {@code query.instanceId()}, which is ignored).
     */
    private Mono<ActivityPage> runGlobalQuery(ActivityQuery query) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append(" FROM ")
                .append(tableName)
                .append(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        return runFilteredQuery(sql, params, query);
    }

    /**
     * Shared tail of {@link #runQuery}/{@link #runGlobalQuery}: appends the optional type/severity/text/
     * since/until/cursor filters (identical for both), then the newest-first keyset pagination, executes,
     * and builds the resulting page. {@code sql}/{@code params} already carry whichever instance predicate
     * (or lack of one) distinguishes the two callers.
     */
    private Mono<ActivityPage> runFilteredQuery(StringBuilder sql, Map<String, Object> params, ActivityQuery query) {
        String type = blankToNull(query.type());
        if (type != null) {
            sql.append(" AND UPPER(entry_type) = :type");
            params.put("type", type.toUpperCase(Locale.ROOT));
        }
        String severity = blankToNull(query.severity());
        if (severity != null) {
            sql.append(" AND UPPER(severity) = :severity");
            params.put("severity", severity.toUpperCase(Locale.ROOT));
        }
        String text = blankToNull(query.text());
        if (text != null) {
            sql.append(" AND (UPPER(summary) LIKE :needle OR UPPER(detail) LIKE :needle "
                    + "OR UPPER(path) LIKE :needle OR UPPER(http_method) LIKE :needle)");
            params.put("needle", "%" + text.toUpperCase(Locale.ROOT) + "%");
        }
        if (query.since() != null) {
            sql.append(" AND occurred_at > :since");
            params.put("since", query.since());
        }
        if (query.until() != null) {
            sql.append(" AND occurred_at <= :until");
            params.put("until", query.until());
        }
        ActivityCursor cursor = ActivityCursor.decode(query.cursor());
        if (cursor != null) {
            sql.append(" AND (occurred_at < :cursorTs OR (occurred_at = :cursorTs AND seq < :cursorSeq))");
            params.put("cursorTs", cursor.timestamp());
            params.put("cursorSeq", cursor.seq());
        }
        sql.append(" ORDER BY occurred_at DESC, seq DESC OFFSET 0 ROWS FETCH FIRST :limit ROWS ONLY");
        params.put("limit", query.pageSize() + 1);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString());
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.bind(param.getKey(), param.getValue());
        }
        return spec.map(this::toStoredEntry).all().collectList().map(matches -> buildPage(matches, query.pageSize()));
    }

    /**
     * Backs {@link #queryByCorrelationId}: the one query in this store with no {@code instance_id}
     * predicate, deliberately, so it returns rows written by every instance sharing this table.
     */
    private Mono<List<StoredActivityEntry>> runCorrelationQuery(String correlationId, int limit) {
        return databaseClient
                .sql(SELECT_COLUMNS + " FROM " + tableName + " WHERE correlation_id = :correlationId "
                        + "ORDER BY occurred_at DESC, seq DESC OFFSET 0 ROWS FETCH FIRST :limit ROWS ONLY")
                .bind("correlationId", correlationId)
                .bind("limit", limit)
                .map(this::toStoredEntry)
                .all()
                .collectList();
    }

    private ActivityPage buildPage(List<StoredActivityEntry> matches, int pageSize) {
        boolean hasMore = matches.size() > pageSize;
        List<StoredActivityEntry> page = hasMore ? new ArrayList<>(matches.subList(0, pageSize)) : matches;
        String nextCursor = null;
        if (hasMore) {
            StoredActivityEntry last = page.get(page.size() - 1);
            nextCursor = new ActivityCursor(last.entry().timestamp(), last.seq()).encode();
        }
        return new ActivityPage(page, nextCursor, hasMore);
    }

    private StoredActivityEntry toStoredEntry(Row row, RowMetadata metadata) {
        ActivityEntryDto entry = new ActivityEntryDto(
                row.get("entry_id", String.class),
                row.get("entry_type", String.class),
                requireLong(row, "occurred_at"),
                row.get("severity", String.class),
                row.get("summary", String.class),
                row.get("detail", String.class),
                row.get("duration_ms", Long.class),
                row.get("correlation_id", String.class),
                row.get("http_method", String.class),
                row.get("path", String.class),
                row.get("status_code", Integer.class),
                row.get("thread_name", String.class),
                requireInt(row, "profileable") != 0,
                row.get("parent_entry_id", String.class),
                row.get("secured_principal", String.class),
                requireInt(row, "sql_n_plus_one_suspected") != 0);
        return new StoredActivityEntry(row.get("instance_id", String.class), requireLong(row, "seq"), entry);
    }

    private static long requireLong(Row row, String column) {
        Long value = row.get(column, Long.class);
        return value == null ? 0L : value;
    }

    private static int requireInt(Row row, String column) {
        Integer value = row.get(column, Integer.class);
        return value == null ? 0 : value;
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableString(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableLong(
            DatabaseClient.GenericExecuteSpec spec, String name, Long value) {
        return value == null ? spec.bindNull(name, Long.class) : spec.bind(name, value);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableInt(
            DatabaseClient.GenericExecuteSpec spec, String name, Integer value) {
        return value == null ? spec.bindNull(name, Integer.class) : spec.bind(name, value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Guards against SQL injection through a misconfigured table name: only identifier characters are
     * allowed, since the table name is concatenated directly into DDL/DML (neither JDBC nor R2DBC support
     * parameter-binding for identifiers). Same rule {@code JdbcActivityStore} enforces.
     */
    private static String validateTableName(String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "The activity table name must be a plain SQL identifier, was: " + tableName);
        }
        return tableName;
    }
}
