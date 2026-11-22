package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.activity.BootUiJdbcCaptureGuard;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Direct-JDBC storage for lossless runtime journal runs and events.
 *
 * <p>The store owns only the three tables derived from its prefix ({@code <prefix>_runs},
 * {@code <prefix>_events}, and {@code <prefix>_details}). It never inspects or migrates the legacy activity table. All
 * of its JDBC work is
 * capture-suppressed so using the same datasource for the journal and SQL trace cannot create a persistence loop.
 *
 * <p>Event identity is the client-provided complete key
 * {@code (namespace_id, application_id, run_id, journal_sequence)}. Inserts are transactional and retry-safe: an identical
 * byte payload is accepted again, while the same key carrying different bytes fails explicitly.
 */
public final class JdbcDurableJournalStore implements DurableJournalStore {

    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_PREFIX_LENGTH = 40;
    private static final String INTERNAL_NAMESPACE = "__bootui_internal__";
    private static final String INTERNAL_APPLICATION = "global-sequence";
    private static final String INTERNAL_RUN = "counter";
    public static final int MAX_PROJECTION_LENGTH = DurableJournalStore.MAX_PROJECTION_LENGTH;
    public static final int MAX_TEXT_PROJECTION_LENGTH = DurableJournalStore.MAX_TEXT_PROJECTION_LENGTH;
    private static final int EVENT_COLUMN_COUNT = 33;
    private static final int MAX_BADGES_PROJECTION_LENGTH = 512;

    private final DataSource dataSource;
    private final String tablePrefix;
    private final String runsTable;
    private final String eventsTable;
    private final String detailsTable;
    private final boolean autoCreateSchema;
    private final boolean readOnly;
    private final AtomicBoolean schemaReady = new AtomicBoolean();
    private volatile Dialect dialect;

    /** Writable store whose tables are created when first used. */
    public JdbcDurableJournalStore(DataSource dataSource, String tablePrefix) {
        this(dataSource, tablePrefix, true, false);
    }

    /**
     * Creates a store in either normal writable mode or read-only/no-DDL mode.
     *
     * @param readOnly when {@code true}, reads require all three tables to exist already and every write is rejected
     */
    public JdbcDurableJournalStore(DataSource dataSource, String tablePrefix, boolean readOnly) {
        this(dataSource, tablePrefix, !readOnly, readOnly);
    }

    /**
     * Creates a store with independent schema-management and write policy.
     *
     * <p>This is useful for deployments that provision DDL out of band while keeping writes enabled.
     */
    public JdbcDurableJournalStore(
            DataSource dataSource, String tablePrefix, boolean autoCreateSchema, boolean readOnly) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.tablePrefix = validateTablePrefix(tablePrefix);
        this.runsTable = this.tablePrefix + "_runs";
        this.eventsTable = this.tablePrefix + "_events";
        this.detailsTable = this.tablePrefix + "_details";
        this.autoCreateSchema = autoCreateSchema;
        this.readOnly = readOnly;
    }

    public String tablePrefix() {
        return tablePrefix;
    }

    public String runsTableName() {
        return runsTable;
    }

    public String eventsTableName() {
        return eventsTable;
    }

    public String detailsTableName() {
        return detailsTable;
    }

    public boolean readOnly() {
        return readOnly;
    }

    /** Eagerly verifies all three tables, creating them only when this store owns schema management. */
    public void verifySchema() {
        guarded("verify durable journal schema", this::ensureSchema);
    }

    /** Inserts or replaces the complete metadata row of {@code run}. */
    @Override
    public DurableJournalRun upsertRun(DurableJournalRun run) {
        requireWritable();
        DurableJournalRun value = Objects.requireNonNull(run, "run must not be null");
        return guarded("upsert durable journal run " + value.scope(), () -> {
            ensureSchema();
            try (Connection connection = dataSource.getConnection()) {
                inTransaction(connection, () -> {
                    upsertRun(connection, value);
                });
            }
            try (Connection connection = dataSource.getConnection()) {
                return selectRun(connection, value.scope());
            }
        });
    }

    /** The durable metadata for {@code scope}, or {@code null} if that run has not been inserted. */
    public DurableJournalRun findRun(DurableJournalScope scope) {
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        return guarded("read durable journal run " + key, () -> {
            ensureSchema();
            try (Connection connection = dataSource.getConnection()) {
                return selectRun(connection, key);
            }
        });
    }

    /** Decodes the optional binary metadata for {@code scope}, rejecting malformed stored bytes. */
    @Override
    public DurableJournalRunMetadata findRunMetadata(DurableJournalScope scope) {
        var archived = findArchivedMetadata(scope);
        return archived == null ? null : archived.metadata();
    }

    @Override
    public DurableJournalRunMetadataCodec.ArchivedMetadata findArchivedMetadata(DurableJournalScope scope) {
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        return guarded("read durable journal run metadata " + key, () -> {
            ensureSchema();
            try (Connection connection = dataSource.getConnection()) {
                DurableJournalRun run = selectRun(connection, key);
                byte[] payload = run == null ? null : run.metadataPayload();
                if (payload == null) {
                    return new DurableJournalRunMetadataCodec.ArchivedMetadata(null, 0);
                }
                try {
                    return DurableJournalRunMetadataCodec.decodeArchive(payload);
                } catch (IllegalArgumentException ex) {
                    throw new DurableJournalStoreException(
                            "Stored durable journal run metadata is malformed for " + key, ex);
                }
            }
        });
    }

    /**
     * Advances the durable consumer checkpoint for {@code scope}. A lower or repeated checkpoint is idempotent.
     *
     * @throws DurableJournalStoreException if the run does not exist
     */
    @Override
    public DurableJournalRun checkpoint(DurableJournalScope scope, long sequence) {
        requireWritable();
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        if (sequence < 0) {
            throw new IllegalArgumentException("checkpoint must not be negative");
        }
        return guarded("checkpoint durable journal run " + key, () -> {
            ensureSchema();
            try (Connection connection = dataSource.getConnection()) {
                return inTransaction(connection, () -> {
                    if (advanceCheckpoint(connection, key, sequence) == 0) {
                        throw new DurableJournalStoreException("Durable journal run does not exist: " + key);
                    }
                    return selectRun(connection, key);
                });
            }
        });
    }

    /** Deletes this run's auxiliary details and events through {@code sequence} in one transaction. */
    @Override
    public DurableJournalDeletionCounts clearRunThrough(DurableJournalScope scope, long sequence) {
        requireWritable();
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        return guarded("clear durable journal run " + key + " through sequence " + sequence, () -> {
            ensureSchema();
            try (Connection connection = dataSource.getConnection()) {
                return inTransaction(connection, () -> clearRunThrough(connection, key, sequence));
            }
        });
    }

    /** Prunes every retained run partition owned by the supplied namespace and application. */
    @Override
    public DurableJournalDeletionCounts prune(DurableJournalScope scope, long cutoffEpochMillis) {
        return prune(scope, cutoffEpochMillis, Integer.MAX_VALUE);
    }

    /** Prunes expired data and old empty runs from at most {@code maxRuns} run partitions in one transaction. */
    @Override
    public DurableJournalDeletionCounts prune(DurableJournalScope scope, long cutoffEpochMillis, int maxRuns) {
        requireWritable();
        DurableJournalScope partition = Objects.requireNonNull(scope, "scope must not be null");
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("maxRuns must be positive");
        }
        return guarded(
                "prune durable journal partition " + partition.namespace() + "/" + partition.application(), () -> {
                    ensureSchema();
                    try (Connection connection = dataSource.getConnection()) {
                        return inTransaction(connection, () -> {
                            List<String> runIds = selectPruneRunIds(connection, partition, cutoffEpochMillis, maxRuns);
                            long deletedEvents = 0;
                            long deletedDetails = 0;
                            long deletedRuns = 0;
                            for (String runId : runIds) {
                                DurableJournalScope runScope =
                                        new DurableJournalScope(partition.namespace(), partition.application(), runId);
                                deletedDetails += deleteDetailsBefore(connection, runScope, cutoffEpochMillis);
                                deletedEvents += deleteEventsBefore(connection, runScope, cutoffEpochMillis);
                                deletedRuns += deleteOrphanRun(connection, runScope, cutoffEpochMillis);
                            }
                            return new DurableJournalDeletionCounts(deletedEvents, deletedDetails, deletedRuns);
                        });
                    }
                });
    }

    /** Runs newest-first for the namespace/application pair. */
    public List<DurableJournalRun> listRuns(String namespace, String application) {
        return listRuns(namespace, application, DurableJournalQuery.MAX_PAGE_SIZE);
    }

    /** Runs newest-first for the namespace/application pair, bounded by {@code limit}. */
    public List<DurableJournalRun> listRuns(String namespace, String application, int limit) {
        String ns = DurableJournalScope.bounded("namespace", namespace, DurableJournalScope.MAX_NAMESPACE_LENGTH);
        String app =
                DurableJournalScope.bounded("application", application, DurableJournalScope.MAX_APPLICATION_LENGTH);
        if (INTERNAL_NAMESPACE.equals(ns)) {
            throw new IllegalArgumentException("namespace is reserved for durable journal storage");
        }
        return guarded("list durable journal runs for " + ns + "/" + app, () -> {
            ensureSchema();
            String sql = "SELECT namespace_id, application_id, run_id, started_at, updated_at, status, metadata,"
                    + " metadata_payload, checkpoint, instance_id FROM "
                    + runsTable
                    + " WHERE namespace_id = ? AND application_id = ?"
                    + " ORDER BY started_at DESC, updated_at DESC, run_id DESC";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            sql + dialect(connection).rowLimitClause())) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setString(1, ns);
                statement.setString(2, app);
                statement.setInt(3, normalizedLimit(limit));
                try (ResultSet result = statement.executeQuery()) {
                    List<DurableJournalRun> runs = new ArrayList<>();
                    while (result.next()) {
                        runs.add(toRun(result));
                    }
                    return List.copyOf(runs);
                }
            }
        });
    }

    /** All runs in a namespace/application, using only the partition portion of {@code scope}. */
    public List<DurableJournalRun> listRuns(DurableJournalScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        return listRuns(scope.namespace(), scope.application());
    }

    /** Appends one event, returning {@code true} only when a new row was inserted. */
    public boolean append(DurableJournalScope scope, JournalEntry entry) {
        return appendBatch(scope, List.of(Objects.requireNonNull(entry, "entry must not be null"))) == 1;
    }

    /** Appends one event with an optional masked Live Activity projection. */
    public boolean append(DurableJournalScope scope, DurableJournalEvent event) {
        return appendEvents(scope, List.of(Objects.requireNonNull(event, "event must not be null"))) == 1;
    }

    /** Appends one event with an explicit ordering time usable by a future cross-writer archive reader. */
    public boolean append(DurableJournalScope scope, JournalEntry entry, long recordedAtEpochMillis) {
        return appendBatch(
                        scope, List.of(Objects.requireNonNull(entry, "entry must not be null")), recordedAtEpochMillis)
                == 1;
    }

    public boolean append(DurableJournalScope scope, DurableJournalEvent event, long recordedAtEpochMillis) {
        return appendEvents(
                        scope, List.of(Objects.requireNonNull(event, "event must not be null")), recordedAtEpochMillis)
                == 1;
    }

    /** Appends events in one transaction and returns the number of newly inserted rows. */
    @Override
    public int appendBatch(DurableJournalScope scope, List<JournalEntry> entries) {
        return appendBatch(scope, entries, System.currentTimeMillis());
    }

    /** Appends journal events while retaining their optional Live Activity projections. */
    public int appendEvents(DurableJournalScope scope, List<DurableJournalEvent> events) {
        return appendEvents(scope, events, System.currentTimeMillis());
    }

    /** Appends events with one durable global-order clock tick. */
    public int appendBatch(DurableJournalScope scope, List<JournalEntry> entries, long recordedAtEpochMillis) {
        Objects.requireNonNull(entries, "entries must not be null");
        List<DurableJournalEvent> events = new ArrayList<>(entries.size());
        for (JournalEntry entry : entries) {
            events.add(new DurableJournalEvent(Objects.requireNonNull(entry, "entries must not contain null"), null));
        }
        return appendEvents(scope, events, recordedAtEpochMillis);
    }

    /** Appends event/projection pairs with one durable global-order clock tick. */
    public int appendEvents(DurableJournalScope scope, List<DurableJournalEvent> events, long recordedAtEpochMillis) {
        requireWritable();
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(events, "events must not be null");
        if (events.isEmpty()) {
            return 0;
        }
        List<EncodedEntry> encoded = new ArrayList<>(events.size());
        for (DurableJournalEvent event : events) {
            DurableJournalEvent value = Objects.requireNonNull(event, "events must not contain null");
            if (value.entry().sequence() < 0) {
                throw new IllegalArgumentException("journal sequence must not be negative");
            }
            JournalEntry diskValue = JournalEntryDiskView.of(value.entry());
            validateProjection(diskValue);
            ActivityEntryDto activity = JournalEntryDiskView.activity(diskValue, value.activity());
            validateActivityProjection(activity);
            encoded.add(new EncodedEntry(diskValue, activity, JournalEntryCodec.encode(diskValue)));
        }
        return guarded("append " + encoded.size() + " durable journal events to " + key, () -> {
            ensureSchema();
            try (Connection connection = dataSource.getConnection()) {
                return inTransaction(connection, () -> {
                    requireRun(connection, key);
                    int inserted = 0;
                    long globalEnd = reserveGlobalSequences(connection, encoded.size());
                    long globalStart = globalEnd - encoded.size() + 1;
                    inserted = insertBatchIdempotent(connection, key, encoded, recordedAtEpochMillis, globalStart);
                    return inserted;
                });
            }
        });
    }

    /** The first page of events in sequence order. */
    public DurableJournalPage query(DurableJournalScope scope) {
        return query(DurableJournalQuery.firstPage(scope));
    }

    /** A page of events in sequence order, with an object cursor. */
    public DurableJournalPage query(DurableJournalScope scope, DurableJournalCursor cursor, int pageSize) {
        return query(new DurableJournalQuery(scope, cursor, pageSize));
    }

    /** A page of events in sequence order, with an encoded cursor. */
    public DurableJournalPage query(DurableJournalScope scope, String cursor, int pageSize) {
        return query(new DurableJournalQuery(scope, cursor, pageSize));
    }

    /** Queries a durable run using its indexed projections and a forward sequence cursor. */
    public DurableJournalPage query(DurableJournalQuery query) {
        DurableJournalQuery value = Objects.requireNonNull(query, "query must not be null");
        return guardedHistoricalRead("query durable journal events in " + value.scope(), () -> {
            ensureSchema();
            return runQuery(value);
        });
    }

    /** Queries masked Live Activity projections newest-first across the selected durable scopes. */
    @Override
    public DurableJournalFeedPage queryFeed(DurableJournalFeedQuery query) {
        DurableJournalFeedQuery value = Objects.requireNonNull(query, "query must not be null");
        return guardedHistoricalRead("query durable Live Activity", () -> {
            ensureSchema();
            StringBuilder sql = new StringBuilder(
                    "SELECT e.namespace_id, e.application_id, e.run_id, e.journal_sequence, e.global_sequence,"
                            + " e.source, e.occurred_at, e.entry_id, e.entry_type, e.severity, e.summary, e.detail,"
                            + " e.method, e.path, e.route, e.status, e.duration_ms, e.parent_entry_id,"
                            + " e.correlation_id, e.profileable, e.sql_n_plus_one_suspected, e.badges,"
                            + " e.exception_group_id, e.thread_name, e.projection_available, r.instance_id"
                            + " FROM "
                            + eventsTable
                            + " e LEFT JOIN "
                            + runsTable
                            + " r ON r.namespace_id = e.namespace_id AND r.application_id = e.application_id"
                            + " AND r.run_id = e.run_id WHERE 1 = 1");
            List<Object> parameters = new ArrayList<>();
            appendFeedEquality(sql, parameters, "e.namespace_id", value.namespace());
            appendFeedEquality(sql, parameters, "e.application_id", value.application());
            appendFeedEquality(sql, parameters, "e.run_id", value.run());
            appendFeedEquality(sql, parameters, "r.instance_id", value.instanceId());
            appendFeedEquality(sql, parameters, "e.source", value.source());
            appendFeedCaseInsensitive(sql, parameters, "e.entry_type", value.type());
            appendFeedCaseInsensitive(sql, parameters, "e.severity", value.severity());
            if (value.requestId() != null) {
                sql.append(" AND e.request_id = ?");
                parameters.add(value.requestId());
            }
            if (value.noRequest()) {
                sql.append(" AND e.request_id IS NULL");
            }
            if (value.since() != null) {
                sql.append(" AND e.occurred_at >= ?");
                parameters.add(value.since());
            }
            if (value.until() != null) {
                sql.append(" AND e.occurred_at <= ?");
                parameters.add(value.until());
            }
            if (value.route() != null) {
                sql.append(" AND (e.route = ? OR (e.request_id IS NOT NULL AND EXISTS"
                        + " (SELECT 1 FROM "
                        + eventsTable
                        + " p WHERE p.namespace_id = e.namespace_id AND p.application_id = e.application_id"
                        + " AND p.run_id = e.run_id AND p.entry_type = 'REQUEST' AND p.request_id = e.request_id"
                        + " AND p.route = ?)))");
                parameters.add(value.route());
                parameters.add(value.route());
            }
            if (value.text() != null) {
                String needle = "%" + value.text().toUpperCase(Locale.ROOT) + "%";
                sql.append(" AND (UPPER(e.summary) LIKE ? OR UPPER(e.detail) LIKE ? OR UPPER(e.path) LIKE ?"
                        + " OR UPPER(e.method) LIKE ?)");
                parameters.add(needle);
                parameters.add(needle);
                parameters.add(needle);
                parameters.add(needle);
            }
            if (value.cursor() != null) {
                sql.append(" AND e.global_sequence < ?");
                parameters.add(value.cursor().globalSequence());
            }
            sql.append(" ORDER BY e.global_sequence DESC");
            parameters.add(value.pageSize() + 1);

            List<DurableJournalFeedItem> matches = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            sql + dialect(connection).rowLimitClause())) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setObject(i + 1, parameters.get(i));
                }
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        matches.add(toFeedItem(result));
                    }
                }
            }
            boolean hasMore = matches.size() > value.pageSize();
            List<DurableJournalFeedItem> page =
                    hasMore ? List.copyOf(matches.subList(0, value.pageSize())) : List.copyOf(matches);
            DurableJournalGlobalCursor cursor = hasMore
                    ? new DurableJournalGlobalCursor(page.get(page.size() - 1).globalSequence())
                    : null;
            return new DurableJournalFeedPage(page, cursor, hasMore);
        });
    }

    private static void appendFeedEquality(StringBuilder sql, List<Object> parameters, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            parameters.add(value);
        }
    }

    private static void appendFeedCaseInsensitive(
            StringBuilder sql, List<Object> parameters, String column, String value) {
        if (value != null) {
            sql.append(" AND UPPER(").append(column).append(") = ?");
            parameters.add(value.toUpperCase(Locale.ROOT));
        }
    }

    private static DurableJournalFeedItem toFeedItem(ResultSet result) throws SQLException {
        boolean projectionAvailable = result.getInt("projection_available") == 1;
        String type = result.getString("entry_type");
        ActivityEntryDto activity = null;
        if (projectionAvailable) {
            if (result.getString("entry_id") == null
                    || type == null
                    || result.getString("severity") == null
                    || result.getString("summary") == null
                    || result.getObject("profileable") == null
                    || result.getObject("sql_n_plus_one_suspected") == null) {
                throw new DurableJournalStoreException("Stored durable journal projection is incomplete");
            }
            activity = new ActivityEntryDto(
                    result.getString("entry_id"),
                    type,
                    result.getLong("occurred_at"),
                    result.getString("severity"),
                    result.getString("summary"),
                    result.getString("detail"),
                    nullableLong(result, "duration_ms"),
                    result.getString("correlation_id"),
                    result.getString("method"),
                    result.getString("path"),
                    nullableInteger(result, "status"),
                    result.getString("thread_name"),
                    result.getInt("profileable") == 1,
                    result.getString("parent_entry_id"),
                    null,
                    result.getInt("sql_n_plus_one_suspected") == 1,
                    decodeBadges(result.getString("badges")),
                    result.getString("exception_group_id"));
        } else if (result.getObject("entry_id") != null
                || type != null
                || result.getObject("severity") != null
                || result.getObject("summary") != null
                || result.getObject("detail") != null
                || result.getObject("method") != null
                || result.getObject("path") != null
                || result.getObject("route") != null
                || result.getObject("status") != null
                || result.getObject("duration_ms") != null
                || result.getObject("parent_entry_id") != null
                || result.getObject("correlation_id") != null
                || result.getObject("profileable") != null
                || result.getObject("sql_n_plus_one_suspected") != null
                || result.getObject("badges") != null
                || result.getObject("exception_group_id") != null) {
            throw new DurableJournalStoreException("Stored durable journal projection availability is inconsistent");
        }
        DurableJournalScope scope = new DurableJournalScope(
                result.getString("namespace_id"), result.getString("application_id"), result.getString("run_id"));
        return new DurableJournalFeedItem(
                new DurableJournalOrigin(scope, result.getString("instance_id")),
                result.getLong("global_sequence"),
                result.getLong("journal_sequence"),
                result.getLong("occurred_at"),
                result.getString("source"),
                activity);
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static List<String> decodeBadges(String badges) {
        if (badges == null || badges.isBlank()) {
            return List.of();
        }
        String[] values = badges.split("\\u001f", -1);
        if (Arrays.stream(values).anyMatch(String::isBlank)) {
            throw new DurableJournalStoreException("Stored durable journal badges are malformed");
        }
        return List.of(values);
    }

    /** The globally ordered cursor of one already-stored event, for a future cross-writer archive reader. */
    public DurableJournalGlobalCursor globalCursor(DurableJournalScope scope, long journalSequence) {
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        if (journalSequence < 0) {
            throw new IllegalArgumentException("journalSequence must not be negative");
        }
        return guarded("read durable journal global cursor for " + key, () -> {
            ensureSchema();
            String sql = "SELECT global_sequence FROM " + eventsTable
                    + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND journal_sequence = ?";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                bindScope(statement, key);
                statement.setLong(4, journalSequence);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? new DurableJournalGlobalCursor(result.getLong(1)) : null;
                }
            }
        });
    }

    /** Stores one caller-masked detail shape, accepting an identical full-key retry. */
    public boolean upsertDetail(DurableJournalDetail detail) {
        requireWritable();
        DurableJournalDetail value = Objects.requireNonNull(detail, "detail must not be null");
        byte[] bytes = DurableJournalDetailCodec.encode(value);
        return guarded("upsert durable journal detail " + value.scope(), () -> {
            ensureSchema();
            try (Connection connection = dataSource.getConnection()) {
                return inTransaction(connection, () -> {
                    requireRun(connection, value.scope());
                    requireEvent(connection, value.scope(), value.journalSequence());
                    return insertDetailIdempotent(connection, value, bytes);
                });
            }
        });
    }

    /** Lists every masked auxiliary detail in sequence and kind order for one scope. */
    public List<DurableJournalDetail> listDetails(DurableJournalScope scope) {
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        return guarded("list durable journal details for " + key, () -> {
            ensureSchema();
            String sql = "SELECT journal_sequence, detail_version, detail_payload FROM " + detailsTable
                    + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?"
                    + " ORDER BY journal_sequence ASC, detail_kind ASC";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                bindScope(statement, key);
                try (ResultSet result = statement.executeQuery()) {
                    List<DurableJournalDetail> details = new ArrayList<>();
                    while (result.next()) {
                        details.add(toDetail(key, result));
                    }
                    return List.copyOf(details);
                }
            }
        });
    }

    /** Lists a bounded suffix of the details, without decoding older rows. */
    @Override
    public List<DurableJournalDetail> listDetails(DurableJournalScope scope, long firstJournalSequence, int limit) {
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        if (firstJournalSequence < 0) {
            throw new IllegalArgumentException("firstJournalSequence must not be negative");
        }
        int bounded = limit <= 0 ? DurableJournalQuery.DEFAULT_PAGE_SIZE : Math.min(limit, MAX_DETAILS_PER_QUERY);
        return guarded("list bounded durable journal details for " + key, () -> {
            ensureSchema();
            String sql = "SELECT journal_sequence, detail_version, detail_payload FROM " + detailsTable
                    + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND journal_sequence >= ?"
                    + " ORDER BY journal_sequence ASC, detail_kind ASC";
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            sql + dialect(connection).rowLimitClause())) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                bindScope(statement, key);
                statement.setLong(4, firstJournalSequence);
                statement.setInt(5, bounded);
                try (ResultSet result = statement.executeQuery()) {
                    List<DurableJournalDetail> details = new ArrayList<>();
                    while (result.next()) {
                        details.add(toDetail(key, result));
                    }
                    return List.copyOf(details);
                }
            }
        });
    }

    private static DurableJournalDetail toDetail(DurableJournalScope scope, ResultSet result) throws SQLException {
        DurableJournalDetail detail = DurableJournalDetailCodec.decode(
                scope, result.getLong("journal_sequence"), result.getBytes("detail_payload"));
        if (detail.version() != result.getInt("detail_version")) {
            throw new DurableJournalStoreException("Stored durable journal detail version does not match its payload");
        }
        return detail;
    }

    private DurableJournalPage runQuery(DurableJournalQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT journal_sequence, event_payload FROM " + eventsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.scope().namespace());
        parameters.add(query.scope().application());
        parameters.add(query.scope().run());
        if (query.source() != null) {
            sql.append(" AND source = ?");
            parameters.add(query.source().propertyName());
        }
        if (query.failedOrSlowOnly()) {
            sql.append(" AND failed_or_slow = 1");
        }
        if (query.sinceEpochMillis() != null) {
            sql.append(" AND occurred_at >= ?");
            parameters.add(query.sinceEpochMillis());
        }
        if (query.untilEpochMillis() != null) {
            sql.append(" AND occurred_at <= ?");
            parameters.add(query.untilEpochMillis());
        }
        if (query.requestId() != null) {
            sql.append(" AND request_id = ?");
            parameters.add(query.requestId());
        }
        if (query.executionId() != null) {
            sql.append(" AND execution_id = ?");
            parameters.add(query.executionId());
        }
        if (query.traceId() != null) {
            sql.append(" AND trace_id = ?");
            parameters.add(query.traceId());
        }
        if (query.cursor() != null) {
            sql.append(" AND journal_sequence > ?");
            parameters.add(query.cursor().journalSequence());
        }
        sql.append(" ORDER BY journal_sequence ASC");
        parameters.add(query.pageSize() + 1);

        List<JournalEntry> matches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql + dialect(connection).rowLimitClause())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long sequence = result.getLong("journal_sequence");
                    JournalEntry entry = JournalEntryCodec.decode(result.getBytes("event_payload"));
                    if (entry.sequence() != sequence) {
                        throw new DurableJournalStoreException(
                                "Stored durable journal sequence does not match its payload at " + sequence);
                    }
                    matches.add(entry);
                }
            }
        }
        boolean hasMore = matches.size() > query.pageSize();
        List<JournalEntry> page = hasMore ? matches.subList(0, query.pageSize()) : matches;
        DurableJournalCursor cursor =
                hasMore ? new DurableJournalCursor(page.get(page.size() - 1).sequence()) : null;
        return new DurableJournalPage(page, cursor, hasMore);
    }

    private void ensureSchema() throws SQLException {
        if (schemaReady.get()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            dialect(connection);
            boolean runsExist = tableExists(connection, runsTable);
            boolean eventsExist = tableExists(connection, eventsTable);
            boolean detailsExist = tableExists(connection, detailsTable);
            if (autoCreateSchema) {
                if (!runsExist) {
                    createTable(connection, createRunsTableSql());
                    runsExist = true;
                }
                if (!eventsExist) {
                    createTable(connection, createEventsTableSql());
                    eventsExist = true;
                }
                if (!detailsExist) {
                    createTable(connection, createDetailsTableSql());
                    detailsExist = true;
                }
                createIndex(
                        connection,
                        indexName("runs_started"),
                        runsTable + " (namespace_id, application_id, started_at)");
                createIndex(
                        connection,
                        indexName("runs_retention"),
                        runsTable + " (namespace_id, application_id, updated_at, run_id)");
                createIndex(
                        connection,
                        indexName("events_time"),
                        eventsTable + " (namespace_id, application_id, run_id, occurred_at, journal_sequence)");
                createIndex(
                        connection,
                        indexName("events_source"),
                        eventsTable + " (namespace_id, application_id, run_id, source, journal_sequence)");
                createIndex(
                        connection,
                        indexName("events_failure"),
                        eventsTable + " (namespace_id, application_id, run_id, failed_or_slow, journal_sequence)");
                createIndex(
                        connection,
                        indexName("events_request"),
                        eventsTable + " (namespace_id, application_id, run_id, request_id, journal_sequence)");
                createIndex(
                        connection,
                        indexName("events_execution"),
                        eventsTable + " (namespace_id, application_id, run_id, execution_id, journal_sequence)");
                createIndex(
                        connection,
                        indexName("events_trace"),
                        eventsTable + " (namespace_id, application_id, run_id, trace_id, journal_sequence)");
                createIndex(connection, indexName("events_recorded"), eventsTable + " (recorded_at)");
                createIndex(
                        connection,
                        indexName("events_retention"),
                        eventsTable + " (namespace_id, application_id, run_id, recorded_at, journal_sequence)");
                createIndex(
                        connection,
                        indexName("events_feed_scope"),
                        eventsTable + " (namespace_id, application_id, run_id, global_sequence)");
                createIndex(connection, indexName("events_type"), eventsTable + " (entry_type, global_sequence)");
                createIndex(connection, indexName("events_severity"), eventsTable + " (severity, global_sequence)");
                createIndex(connection, indexName("events_route"), eventsTable + " (route, global_sequence)");
                createIndex(connection, indexName("events_entry"), eventsTable + " (entry_id, global_sequence)");
                createIndex(connection, indexName("events_global_source"), eventsTable + " (source, global_sequence)");
                createIndex(
                        connection, indexName("events_global_request"), eventsTable + " (request_id, global_sequence)");
                createIndex(
                        connection, indexName("events_parent"), eventsTable + " (parent_entry_id, global_sequence)");
                createIndex(
                        connection,
                        indexName("events_correlation"),
                        eventsTable + " (correlation_id, global_sequence)");
                createIndex(
                        connection,
                        indexName("events_exception"),
                        eventsTable + " (exception_group_id, global_sequence)");
                createIndex(
                        connection,
                        indexName("details_key"),
                        detailsTable + " (namespace_id, application_id, run_id, journal_sequence, detail_kind)");
            }
            if (!runsExist || !eventsExist || !detailsExist) {
                List<String> missing = new ArrayList<>();
                if (!runsExist) {
                    missing.add(runsTable);
                }
                if (!eventsExist) {
                    missing.add(eventsTable);
                }
                if (!detailsExist) {
                    missing.add(detailsTable);
                }
                throw new DurableJournalStoreException(
                        "Durable journal tables are missing and schema creation is disabled: "
                                + String.join(", ", missing));
            }
            if (!readOnly) {
                ensureGlobalCounter(connection);
            }
            if (!originalAutoCommit) {
                connection.commit();
            }
            schemaReady.set(true);
        }
    }

    private String createRunsTableSql() {
        Dialect sqlDialect = dialectType();
        String bigInt = sqlDialect.bigIntType();
        return "CREATE TABLE "
                + runsTable
                + " (namespace_id VARCHAR(128) NOT NULL, application_id VARCHAR(128) NOT NULL, "
                + "run_id VARCHAR(128) NOT NULL, started_at " + bigInt + " NOT NULL, updated_at " + bigInt
                + " NOT NULL, status VARCHAR(32) NOT NULL, metadata VARCHAR(4000), metadata_payload "
                + sqlDialect.blobType() + ", "
                + "instance_id VARCHAR(128), checkpoint " + bigInt
                + " NOT NULL, PRIMARY KEY (namespace_id, application_id, run_id))";
    }

    private String createEventsTableSql() {
        Dialect sqlDialect = dialectType();
        String bigInt = sqlDialect.bigIntType();
        return "CREATE TABLE "
                + eventsTable
                + " (namespace_id VARCHAR(128) NOT NULL, application_id VARCHAR(128) NOT NULL, "
                + "run_id VARCHAR(128) NOT NULL, journal_sequence " + bigInt + " NOT NULL, "
                + "global_sequence " + bigInt + " NOT NULL UNIQUE, source VARCHAR(32) NOT NULL, "
                + "occurred_at " + bigInt + " NOT NULL, recorded_at " + bigInt + " NOT NULL, "
                + "duration_nanos " + bigInt + " NOT NULL, request_id VARCHAR(128), "
                + "execution_id VARCHAR(128), trace_id VARCHAR(128), thread_name VARCHAR(128), "
                + "thread_kind VARCHAR(32), failed_or_slow INTEGER NOT NULL, "
                + "entry_id VARCHAR(128), entry_type VARCHAR(128), severity VARCHAR(32), summary VARCHAR(2000),"
                + " detail VARCHAR(2000), method VARCHAR(32), path VARCHAR(2000), route VARCHAR(2000),"
                + " status INTEGER, duration_ms " + bigInt + ", "
                + "parent_entry_id VARCHAR(128), correlation_id VARCHAR(128), profileable INTEGER, "
                + "sql_n_plus_one_suspected INTEGER, badges VARCHAR(512), exception_group_id VARCHAR(128), "
                + "projection_available INTEGER NOT NULL, "
                + "event_payload " + sqlDialect.blobType() + " NOT NULL, "
                + "PRIMARY KEY (namespace_id, application_id, run_id, journal_sequence))";
    }

    private String createDetailsTableSql() {
        Dialect sqlDialect = dialectType();
        String bigInt = sqlDialect.bigIntType();
        return "CREATE TABLE "
                + detailsTable
                + " (namespace_id VARCHAR(128) NOT NULL, application_id VARCHAR(128) NOT NULL, "
                + "run_id VARCHAR(128) NOT NULL, journal_sequence " + bigInt + " NOT NULL, "
                + "detail_kind VARCHAR(32) NOT NULL, detail_version INTEGER NOT NULL, "
                + "detail_payload " + sqlDialect.blobType() + " NOT NULL, "
                + "PRIMARY KEY (namespace_id, application_id, run_id, journal_sequence, detail_kind))";
    }

    private Dialect dialectType() {
        Dialect detected = dialect;
        if (detected == null) {
            throw new IllegalStateException("JDBC dialect has not been detected");
        }
        return detected;
    }

    private void createTable(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try {
                statement.executeUpdate(sql);
            } catch (SQLException createFailed) {
                String table = sql.startsWith("CREATE TABLE " + runsTable)
                        ? runsTable
                        : (sql.startsWith("CREATE TABLE " + eventsTable) ? eventsTable : detailsTable);
                if (!tableExists(connection, table)) {
                    throw createFailed;
                }
            }
        }
    }

    private void createIndex(Connection connection, String index, String target) {
        String sql = "CREATE INDEX " + index + " ON " + target;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.executeUpdate(sql);
        } catch (SQLException ignored) {
            // A concurrent creator may already own the index. Indexes optimize reads and do not define correctness.
        }
    }

    private boolean tableExists(Connection connection, String table) {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet ignored = statement.executeQuery("SELECT 1 FROM " + table + " WHERE 1 = 0")) {
                return true;
            }
        } catch (SQLException missing) {
            return false;
        }
    }

    private Dialect dialect(Connection connection) throws SQLException {
        Dialect detected = dialect;
        if (detected == null) {
            detected = Dialect.detect(connection.getMetaData().getDatabaseProductName());
            dialect = detected;
        }
        return detected;
    }

    private void insertRun(Connection connection, DurableJournalRun run) throws SQLException {
        String sql = "INSERT INTO " + runsTable
                + " (namespace_id, application_id, run_id, started_at, updated_at, status, metadata, metadata_payload,"
                + " checkpoint, instance_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, run.scope());
            statement.setLong(4, run.startedAtEpochMillis());
            statement.setLong(5, run.updatedAtEpochMillis());
            statement.setString(6, run.status());
            statement.setString(7, run.metadata());
            statement.setBytes(8, run.metadataPayload());
            statement.setLong(9, run.checkpoint());
            statement.setString(10, run.instanceId());
            statement.executeUpdate();
        }
    }

    private void ensureGlobalCounter(Connection connection) throws SQLException {
        String select =
                "SELECT checkpoint FROM " + runsTable + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindInternalScope(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }
        String insert = "INSERT INTO " + runsTable
                + " (namespace_id, application_id, run_id, started_at, updated_at, status, metadata, checkpoint,"
                + " instance_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindInternalScope(statement);
            statement.setLong(4, 0);
            statement.setLong(5, 0);
            statement.setString(6, "INTERNAL");
            statement.setString(7, null);
            statement.setLong(8, 0);
            statement.setString(9, null);
            statement.executeUpdate();
        } catch (SQLException insertFailed) {
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                bindInternalScope(statement);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return;
                    }
                }
            }
            throw insertFailed;
        }
    }

    private long reserveGlobalSequences(Connection connection, int count) throws SQLException {
        String update = "UPDATE " + runsTable + " SET checkpoint = checkpoint + ?"
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setLong(1, count);
            statement.setString(2, INTERNAL_NAMESPACE);
            statement.setString(3, INTERNAL_APPLICATION);
            statement.setString(4, INTERNAL_RUN);
            if (statement.executeUpdate() != 1) {
                throw new DurableJournalStoreException("Durable journal global sequence counter is missing");
            }
        }
        String select =
                "SELECT checkpoint FROM " + runsTable + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindInternalScope(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DurableJournalStoreException("Durable journal global sequence counter is missing");
                }
                return result.getLong(1);
            }
        }
    }

    private static void bindInternalScope(PreparedStatement statement) throws SQLException {
        statement.setString(1, INTERNAL_NAMESPACE);
        statement.setString(2, INTERNAL_APPLICATION);
        statement.setString(3, INTERNAL_RUN);
    }

    private void upsertRun(Connection connection, DurableJournalRun run) throws SQLException {
        DurableJournalRun existing = selectRun(connection, run.scope());
        if (existing != null) {
            updateRun(connection, run);
            return;
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            insertRun(connection, run);
        } catch (SQLException insertFailed) {
            connection.rollback(savepoint);
            if (selectRun(connection, run.scope()) == null) {
                throw insertFailed;
            }
            updateRun(connection, run);
        } finally {
            try {
                connection.releaseSavepoint(savepoint);
            } catch (SQLException ignored) {
                // The driver may already have discarded the savepoint after rollback.
            }
        }
    }

    private void updateRun(Connection connection, DurableJournalRun run) throws SQLException {
        String sql = "UPDATE " + runsTable
                + " SET started_at = ?, updated_at = ?, status = ?, metadata = ?, metadata_payload = ?, instance_id = ?,"
                + " checkpoint = CASE WHEN checkpoint > ? THEN checkpoint ELSE ? END"
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setLong(1, run.startedAtEpochMillis());
            statement.setLong(2, run.updatedAtEpochMillis());
            statement.setString(3, run.status());
            statement.setString(4, run.metadata());
            statement.setBytes(5, run.metadataPayload());
            statement.setString(6, run.instanceId());
            statement.setLong(7, run.checkpoint());
            statement.setLong(8, run.checkpoint());
            statement.setString(9, run.scope().namespace());
            statement.setString(10, run.scope().application());
            statement.setString(11, run.scope().run());
            statement.executeUpdate();
        }
    }

    private int advanceCheckpoint(Connection connection, DurableJournalScope scope, long sequence) throws SQLException {
        long now = System.currentTimeMillis();
        String sql = "UPDATE " + runsTable
                + " SET checkpoint = CASE WHEN checkpoint < ? THEN ? ELSE checkpoint END,"
                + " updated_at = CASE WHEN updated_at < ? THEN ? ELSE updated_at END"
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setLong(1, sequence);
            statement.setLong(2, sequence);
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.setString(5, scope.namespace());
            statement.setString(6, scope.application());
            statement.setString(7, scope.run());
            return statement.executeUpdate();
        }
    }

    private DurableJournalDeletionCounts clearRunThrough(
            Connection connection, DurableJournalScope scope, long sequence) throws SQLException {
        String detailsSql = "DELETE FROM " + detailsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND journal_sequence <= ?";
        long deletedDetails;
        try (PreparedStatement statement = connection.prepareStatement(detailsSql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            statement.setLong(4, sequence);
            deletedDetails = statement.executeLargeUpdate();
        }
        String eventsSql = "DELETE FROM " + eventsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND journal_sequence <= ?";
        long deletedEvents;
        try (PreparedStatement statement = connection.prepareStatement(eventsSql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            statement.setLong(4, sequence);
            deletedEvents = statement.executeLargeUpdate();
        }
        return new DurableJournalDeletionCounts(deletedEvents, deletedDetails, 0);
    }

    private List<String> selectPruneRunIds(
            Connection connection, DurableJournalScope partition, long cutoffEpochMillis, int maxRuns)
            throws SQLException {
        String sql = "SELECT r.run_id FROM " + runsTable + " r"
                + " WHERE r.namespace_id = ? AND r.application_id = ? AND (EXISTS"
                + " (SELECT 1 FROM " + eventsTable + " old_event"
                + " WHERE old_event.namespace_id = r.namespace_id"
                + " AND old_event.application_id = r.application_id"
                + " AND old_event.run_id = r.run_id AND old_event.recorded_at < ?)"
                + " OR (r.updated_at < ? AND NOT EXISTS (SELECT 1 FROM " + eventsTable + " retained_event"
                + " WHERE retained_event.namespace_id = r.namespace_id"
                + " AND retained_event.application_id = r.application_id"
                + " AND retained_event.run_id = r.run_id)))"
                + " ORDER BY r.updated_at ASC, r.started_at ASC, r.run_id ASC";
        try (PreparedStatement statement =
                connection.prepareStatement(sql + dialect(connection).rowLimitClause())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, partition.namespace());
            statement.setString(2, partition.application());
            statement.setLong(3, cutoffEpochMillis);
            statement.setLong(4, cutoffEpochMillis);
            statement.setInt(5, maxRuns);
            try (ResultSet result = statement.executeQuery()) {
                List<String> runIds = new ArrayList<>();
                while (result.next()) {
                    runIds.add(result.getString(1));
                }
                return runIds;
            }
        }
    }

    private long deleteDetailsBefore(Connection connection, DurableJournalScope scope, long cutoffEpochMillis)
            throws SQLException {
        String sql = "DELETE FROM " + detailsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND EXISTS"
                + " (SELECT 1 FROM " + eventsTable + " retained_event"
                + " WHERE retained_event.namespace_id = " + detailsTable + ".namespace_id"
                + " AND retained_event.application_id = " + detailsTable + ".application_id"
                + " AND retained_event.run_id = " + detailsTable + ".run_id"
                + " AND retained_event.journal_sequence = " + detailsTable + ".journal_sequence"
                + " AND retained_event.recorded_at < ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            statement.setLong(4, cutoffEpochMillis);
            return statement.executeLargeUpdate();
        }
    }

    private long deleteEventsBefore(Connection connection, DurableJournalScope scope, long cutoffEpochMillis)
            throws SQLException {
        String sql = "DELETE FROM " + eventsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND recorded_at < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            statement.setLong(4, cutoffEpochMillis);
            return statement.executeLargeUpdate();
        }
    }

    private long deleteOrphanRun(Connection connection, DurableJournalScope scope, long cutoffEpochMillis)
            throws SQLException {
        String sql = "DELETE FROM " + runsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND updated_at < ?"
                + " AND NOT EXISTS (SELECT 1 FROM " + eventsTable + " retained_event"
                + " WHERE retained_event.namespace_id = " + runsTable + ".namespace_id"
                + " AND retained_event.application_id = " + runsTable + ".application_id"
                + " AND retained_event.run_id = " + runsTable + ".run_id)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            statement.setLong(4, cutoffEpochMillis);
            return statement.executeLargeUpdate();
        }
    }

    private DurableJournalRun selectRun(Connection connection, DurableJournalScope scope) throws SQLException {
        String sql = "SELECT namespace_id, application_id, run_id, started_at, updated_at, status, metadata,"
                + " metadata_payload, checkpoint, instance_id FROM "
                + runsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? toRun(result) : null;
            }
        }
    }

    private DurableJournalRun toRun(ResultSet result) throws SQLException {
        return new DurableJournalRun(
                new DurableJournalScope(
                        result.getString("namespace_id"),
                        result.getString("application_id"),
                        result.getString("run_id")),
                result.getLong("started_at"),
                result.getLong("updated_at"),
                result.getString("status"),
                result.getString("metadata"),
                result.getBytes("metadata_payload"),
                result.getLong("checkpoint"),
                result.getString("instance_id"));
    }

    private boolean insertIdempotent(
            Connection connection,
            DurableJournalScope scope,
            EncodedEntry encoded,
            long recordedAtEpochMillis,
            long globalSequence)
            throws SQLException {
        byte[] existing = selectEventBytes(connection, scope, encoded.entry().sequence());
        if (existing != null) {
            acceptOrConflict(scope, encoded.entry().sequence(), existing, encoded.bytes());
            acceptProjectionOrConflict(connection, scope, encoded.entry().sequence(), encoded.activity());
            return false;
        }

        Savepoint savepoint = connection.setSavepoint();
        try {
            insertEvent(connection, scope, encoded, recordedAtEpochMillis, globalSequence);
            return true;
        } catch (SQLException insertFailed) {
            connection.rollback(savepoint);
            existing = selectEventBytes(connection, scope, encoded.entry().sequence());
            if (existing == null) {
                throw insertFailed;
            }
            acceptOrConflict(scope, encoded.entry().sequence(), existing, encoded.bytes());
            acceptProjectionOrConflict(connection, scope, encoded.entry().sequence(), encoded.activity());
            return false;
        } finally {
            try {
                connection.releaseSavepoint(savepoint);
            } catch (SQLException ignored) {
                // The driver may already have discarded the savepoint after rollback.
            }
        }
    }

    private int insertBatchIdempotent(
            Connection connection,
            DurableJournalScope scope,
            List<EncodedEntry> entries,
            long recordedAtEpochMillis,
            long globalStart)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            Dialect database = dialectType();
            int maximumRows = database.maxMultiRowInsert();
            if (maximumRows > 1) {
                for (int from = 0; from < entries.size(); from += maximumRows) {
                    int to = Math.min(entries.size(), from + maximumRows);
                    try (PreparedStatement statement = connection.prepareStatement(insertEventSql(to - from))) {
                        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        for (int index = from; index < to; index++) {
                            bindEvent(
                                    statement,
                                    scope,
                                    entries.get(index),
                                    recordedAtEpochMillis,
                                    globalStart + index,
                                    (index - from) * EVENT_COLUMN_COUNT);
                        }
                        statement.executeUpdate();
                    }
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(insertEventSql())) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    long global = globalStart;
                    for (EncodedEntry entry : entries) {
                        bindEvent(statement, scope, entry, recordedAtEpochMillis, global++, 0);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
            return entries.size();
        } catch (SQLException insertFailed) {
            connection.rollback(savepoint);
            int inserted = 0;
            long global = globalStart;
            for (EncodedEntry entry : entries) {
                inserted += insertIdempotent(connection, scope, entry, recordedAtEpochMillis, global++) ? 1 : 0;
            }
            return inserted;
        } finally {
            try {
                connection.releaseSavepoint(savepoint);
            } catch (SQLException ignored) {
                // The driver may already have discarded the savepoint after rollback.
            }
        }
    }

    private String insertEventSql() {
        return insertEventPrefix()
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?)";
    }

    private String insertEventSql(int rows) {
        String values = " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?)";
        return insertEventPrefix() + " VALUES" + String.join(",", Collections.nCopies(rows, values));
    }

    private String insertEventPrefix() {
        return "INSERT INTO " + eventsTable
                + " (namespace_id, application_id, run_id, journal_sequence, global_sequence, source, occurred_at, recorded_at,"
                + " duration_nanos, request_id, execution_id, trace_id, thread_name, thread_kind, failed_or_slow,"
                + " entry_id, entry_type, severity, summary, detail, method, path, route, status, duration_ms,"
                + " parent_entry_id, correlation_id, profileable, sql_n_plus_one_suspected, badges,"
                + " exception_group_id, projection_available, event_payload)";
    }

    private void insertEvent(
            Connection connection,
            DurableJournalScope scope,
            EncodedEntry encoded,
            long recordedAtEpochMillis,
            long globalSequence)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(insertEventSql())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindEvent(statement, scope, encoded, recordedAtEpochMillis, globalSequence, 0);
            statement.executeUpdate();
        }
    }

    private static void bindEvent(
            PreparedStatement statement,
            DurableJournalScope scope,
            EncodedEntry encoded,
            long recordedAtEpochMillis,
            long globalSequence,
            int offset)
            throws SQLException {
        RuntimeEvent event = encoded.entry().event();
        bindScope(statement, scope, offset);
        statement.setLong(offset + 4, encoded.entry().sequence());
        statement.setLong(offset + 5, globalSequence);
        statement.setString(offset + 6, event.source().propertyName());
        statement.setLong(offset + 7, event.epochMillis());
        statement.setLong(offset + 8, recordedAtEpochMillis);
        statement.setLong(offset + 9, event.durationNanos());
        statement.setString(offset + 10, event.requestId());
        statement.setString(offset + 11, event.executionId());
        statement.setString(offset + 12, event.traceId());
        statement.setString(offset + 13, event.thread());
        statement.setString(
                offset + 14,
                event.threadKind() == null ? null : event.threadKind().name());
        statement.setInt(offset + 15, event.failedOrSlow() ? 1 : 0);
        ActivityEntryDto activity = encoded.activity();
        statement.setString(offset + 16, activity == null ? null : activity.id());
        statement.setString(offset + 17, activity == null ? null : activity.type());
        statement.setString(offset + 18, activity == null ? null : activity.severity());
        statement.setString(offset + 19, activity == null ? null : activity.summary());
        statement.setString(offset + 20, activity == null ? null : activity.detail());
        statement.setString(offset + 21, activity == null ? null : activity.method());
        statement.setString(offset + 22, activity == null ? null : activity.path());
        statement.setString(offset + 23, activity == null ? null : projectionRoute(activity));
        statement.setObject(offset + 24, activity == null ? null : activity.status());
        statement.setObject(offset + 25, activity == null ? null : activity.durationMs());
        statement.setString(offset + 26, activity == null ? null : activity.parentId());
        statement.setString(offset + 27, activity == null ? null : activity.correlationId());
        statement.setObject(offset + 28, activity == null ? null : activity.profileable() ? 1 : 0);
        statement.setObject(offset + 29, activity == null ? null : activity.sqlNPlusOneSuspected() ? 1 : 0);
        statement.setString(offset + 30, encodeBadges(activity));
        statement.setString(offset + 31, activity == null ? null : activity.exceptionGroupId());
        statement.setInt(offset + 32, activity == null ? 0 : 1);
        statement.setBytes(offset + 33, encoded.bytes());
    }

    private static String projectionRoute(ActivityEntryDto activity) {
        if (!JournalActivityFeed.TYPE_REQUEST.equals(activity.type())) {
            return null;
        }
        return activity.detail() == null ? activity.path() : activity.detail();
    }

    private static String encodeBadges(ActivityEntryDto activity) {
        if (activity == null || activity.badges().isEmpty()) {
            return null;
        }
        return String.join("\u001f", activity.badges());
    }

    private void insertDetail(Connection connection, DurableJournalDetail detail, byte[] bytes) throws SQLException {
        String sql = "INSERT INTO " + detailsTable
                + " (namespace_id, application_id, run_id, journal_sequence, detail_kind, detail_version, detail_payload)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, detail.scope());
            statement.setLong(4, detail.journalSequence());
            statement.setString(5, detail.kind().name());
            statement.setInt(6, detail.version());
            statement.setBytes(7, bytes);
            statement.executeUpdate();
        }
    }

    private boolean insertDetailIdempotent(Connection connection, DurableJournalDetail detail, byte[] bytes)
            throws SQLException {
        byte[] existing = selectDetailBytes(connection, detail.scope(), detail.journalSequence(), detail.kind());
        if (existing != null) {
            acceptOrConflict(detail.scope(), detail.journalSequence(), existing, bytes);
            return false;
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            insertDetail(connection, detail, bytes);
            return true;
        } catch (SQLException insertFailed) {
            connection.rollback(savepoint);
            existing = selectDetailBytes(connection, detail.scope(), detail.journalSequence(), detail.kind());
            if (existing == null) {
                throw insertFailed;
            }
            acceptOrConflict(detail.scope(), detail.journalSequence(), existing, bytes);
            return false;
        } finally {
            try {
                connection.releaseSavepoint(savepoint);
            } catch (SQLException ignored) {
                // The driver may already have discarded the savepoint after rollback.
            }
        }
    }

    private byte[] selectDetailBytes(
            Connection connection, DurableJournalScope scope, long journalSequence, DurableJournalDetailKind kind)
            throws SQLException {
        String sql = "SELECT detail_payload FROM " + detailsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ?"
                + " AND journal_sequence = ? AND detail_kind = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            statement.setLong(4, journalSequence);
            statement.setString(5, kind.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBytes(1) : null;
            }
        }
    }

    private void requireRun(Connection connection, DurableJournalScope scope) throws SQLException {
        if (selectRun(connection, scope) == null) {
            throw new DurableJournalStoreException("Durable journal run does not exist: " + scope);
        }
    }

    private void requireEvent(Connection connection, DurableJournalScope scope, long sequence) throws SQLException {
        if (selectEventBytes(connection, scope, sequence) == null) {
            throw new DurableJournalStoreException(
                    "Durable journal event does not exist for " + scope + " at sequence " + sequence);
        }
    }

    private byte[] selectEventBytes(Connection connection, DurableJournalScope scope, long sequence)
            throws SQLException {
        String sql = "SELECT event_payload FROM " + eventsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND journal_sequence = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            statement.setLong(4, sequence);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBytes(1) : null;
            }
        }
    }

    private static void acceptOrConflict(DurableJournalScope scope, long sequence, byte[] existing, byte[] attempted) {
        if (!Arrays.equals(existing, attempted)) {
            throw new DurableJournalConflictException(scope, sequence);
        }
    }

    private void acceptProjectionOrConflict(
            Connection connection, DurableJournalScope scope, long sequence, ActivityEntryDto attempted)
            throws SQLException {
        String sql = "SELECT entry_id, entry_type, severity, summary, detail, method, path, route, status,"
                + " duration_ms, parent_entry_id, correlation_id, profileable, sql_n_plus_one_suspected, badges,"
                + " exception_group_id, projection_available FROM "
                + eventsTable
                + " WHERE namespace_id = ? AND application_id = ? AND run_id = ? AND journal_sequence = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindScope(statement, scope);
            statement.setLong(4, sequence);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !storedProjectionMatches(result, attempted)) {
                    throw new DurableJournalConflictException(scope, sequence);
                }
            }
        }
    }

    private static boolean storedProjectionMatches(ResultSet result, ActivityEntryDto attempted) throws SQLException {
        Object[] actual = {
            result.getString("entry_id"),
            result.getString("entry_type"),
            result.getString("severity"),
            result.getString("summary"),
            result.getString("detail"),
            result.getString("method"),
            result.getString("path"),
            result.getString("route"),
            nullableInteger(result, "status"),
            nullableLong(result, "duration_ms"),
            result.getString("parent_entry_id"),
            result.getString("correlation_id"),
            nullableInteger(result, "profileable"),
            nullableInteger(result, "sql_n_plus_one_suspected"),
            result.getString("badges"),
            result.getString("exception_group_id"),
            result.getInt("projection_available")
        };
        Object[] expected = attempted == null
                ? new Object[] {
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0
                }
                : new Object[] {
                    attempted.id(),
                    attempted.type(),
                    attempted.severity(),
                    attempted.summary(),
                    attempted.detail(),
                    attempted.method(),
                    attempted.path(),
                    projectionRoute(attempted),
                    attempted.status(),
                    attempted.durationMs(),
                    attempted.parentId(),
                    attempted.correlationId(),
                    attempted.profileable() ? 1 : 0,
                    attempted.sqlNPlusOneSuspected() ? 1 : 0,
                    encodeBadges(attempted),
                    attempted.exceptionGroupId(),
                    1
                };
        return Arrays.equals(actual, expected);
    }

    private static void bindScope(PreparedStatement statement, DurableJournalScope scope) throws SQLException {
        bindScope(statement, scope, 0);
    }

    private static void bindScope(PreparedStatement statement, DurableJournalScope scope, int offset)
            throws SQLException {
        statement.setString(offset + 1, scope.namespace());
        statement.setString(offset + 2, scope.application());
        statement.setString(offset + 3, scope.run());
    }

    private static void validateProjection(JournalEntry entry) {
        RuntimeEvent event = Objects.requireNonNull(entry.event(), "entry.event must not be null");
        projected("requestId", event.requestId());
        projected("executionId", event.executionId());
        projected("traceId", event.traceId());
        projected("thread", event.thread());
    }

    private static void validateActivityProjection(ActivityEntryDto activity) {
        if (activity == null) {
            return;
        }
        projectedRequired("entryId", activity.id());
        projectedRequired("entryType", activity.type());
        projectedRequired("severity", activity.severity());
        projected("method", activity.method());
        projectedTextRequired("summary", activity.summary());
        projectedText("detail", activity.detail());
        projectedText("path", activity.path());
        projectedText("route", projectionRoute(activity));
        projected("parentEntryId", activity.parentId());
        projected("correlationId", activity.correlationId());
        projected("thread", activity.thread());
        projected("exceptionGroupId", activity.exceptionGroupId());
        String badges = encodeBadges(activity);
        if (badges != null && badges.length() > MAX_BADGES_PROJECTION_LENGTH) {
            throw new IllegalArgumentException("badges exceeds " + MAX_BADGES_PROJECTION_LENGTH + " characters");
        }
    }

    private static void projected(String field, String value) {
        if (value != null && value.length() > MAX_PROJECTION_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_PROJECTION_LENGTH + " characters");
        }
    }

    private static void projectedRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank for an available projection");
        }
        projected(field, value);
    }

    private static void projectedText(String field, String value) {
        if (value != null && value.length() > MAX_TEXT_PROJECTION_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_TEXT_PROJECTION_LENGTH + " characters");
        }
    }

    private static void projectedTextRequired(String field, String value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null for an available projection");
        }
        projectedText(field, value);
    }

    private void requireWritable() {
        if (readOnly) {
            throw new DurableJournalStoreException("Durable journal store is read-only");
        }
    }

    private int normalizedLimit(int limit) {
        return limit <= 0 ? DurableJournalQuery.DEFAULT_PAGE_SIZE : Math.min(limit, DurableJournalQuery.MAX_PAGE_SIZE);
    }

    private String indexName(String purpose) {
        return "bj_" + Integer.toUnsignedString((tablePrefix + "_" + purpose).hashCode(), 36) + "_idx";
    }

    private static String validateTablePrefix(String prefix) {
        if (prefix == null || prefix.length() > MAX_PREFIX_LENGTH || !prefix.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("durable journal table prefix must be a plain SQL identifier of at most "
                    + MAX_PREFIX_LENGTH + " characters, was: " + prefix);
        }
        return prefix;
    }

    private static <T> T inTransaction(Connection connection, Transaction<T> action) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (originalAutoCommit) {
            connection.setAutoCommit(false);
        }
        try {
            T result = action.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException | Error ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailed) {
                ex.addSuppressed(rollbackFailed);
            }
            throw ex;
        } finally {
            if (originalAutoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void inTransaction(Connection connection, TransactionVoid action) throws SQLException {
        inTransaction(connection, () -> {
            action.run();
            return null;
        });
    }

    private static <T> T guarded(String operation, CheckedSupplier<T> action) {
        try {
            return BootUiJdbcCaptureGuard.runSuppressed(action::get);
        } catch (DurableJournalStoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DurableJournalStoreException("Failed to " + operation, ex);
        }
    }

    private static <T> T guardedHistoricalRead(String operation, CheckedSupplier<T> action) {
        try {
            return BootUiJdbcCaptureGuard.runSuppressed(action::get);
        } catch (DurableJournalUnavailableException ex) {
            throw ex;
        } catch (DurableJournalStoreException ex) {
            throw new DurableJournalUnavailableException(
                    "Durable journal history is unavailable: failed to " + operation + ": " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new DurableJournalUnavailableException(
                    "Durable journal history is unavailable: failed to " + operation, ex);
        }
    }

    private static void guarded(String operation, CheckedRunnable action) {
        guarded(operation, () -> {
            action.run();
            return null;
        });
    }

    private enum Dialect {
        MYSQL_FAMILY("BIGINT", "MEDIUMBLOB", " LIMIT ?", 128),
        SQLITE("INTEGER", "BLOB", " LIMIT ?", 64),
        ORACLE("NUMBER(19)", "BLOB", " OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY", 1),
        POSTGRESQL("BIGINT", "BYTEA", " OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY", 128),
        SQL_SERVER("BIGINT", "VARBINARY(MAX)", " OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY", 50),
        STANDARD("BIGINT", "BLOB", " OFFSET 0 ROWS FETCH FIRST ? ROWS ONLY", 64);

        private final String bigIntType;
        private final String blobType;
        private final String rowLimitClause;
        private final int maxMultiRowInsert;

        Dialect(String bigIntType, String blobType, String rowLimitClause, int maxMultiRowInsert) {
            this.bigIntType = bigIntType;
            this.blobType = blobType;
            this.rowLimitClause = rowLimitClause;
            this.maxMultiRowInsert = maxMultiRowInsert;
        }

        String bigIntType() {
            return bigIntType;
        }

        String blobType() {
            return blobType;
        }

        String rowLimitClause() {
            return rowLimitClause;
        }

        int maxMultiRowInsert() {
            return maxMultiRowInsert;
        }

        static Dialect detect(String productName) {
            String product = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
            if (product.contains("mysql") || product.contains("mariadb")) {
                return MYSQL_FAMILY;
            }
            if (product.contains("sqlite")) {
                return SQLITE;
            }
            if (product.contains("oracle")) {
                return ORACLE;
            }
            if (product.contains("postgres")) {
                return POSTGRESQL;
            }
            if (product.contains("microsoft sql server")) {
                return SQL_SERVER;
            }
            return STANDARD;
        }
    }

    private record EncodedEntry(JournalEntry entry, ActivityEntryDto activity, byte[] bytes) {}

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T run() throws SQLException;
    }

    @FunctionalInterface
    private interface TransactionVoid {
        void run() throws SQLException;
    }
}
