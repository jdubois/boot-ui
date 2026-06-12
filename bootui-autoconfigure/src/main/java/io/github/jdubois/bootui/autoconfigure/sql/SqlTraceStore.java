package io.github.jdubois.bootui.autoconfigure.sql;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.SqlTraceQueryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, thread-safe in-memory store for SQL executions captured by {@link SqlTraceQueryListener}.
 *
 * <p>Executions are appended to a fixed-capacity ring buffer; once the buffer is full the oldest
 * entry is evicted. The store also tracks lifetime counters (total captured and evicted) and the
 * set of {@code DataSource} bean names that were wrapped for tracing.</p>
 *
 * <p>All SQL hits the database from many threads, so every mutation and snapshot is guarded by an
 * intrinsic lock. Recording can be paused at runtime without unwrapping the {@code DataSource}.</p>
 */
public class SqlTraceStore {

    static final int HARD_MAX_QUERIES = 5_000;
    static final int TOP_STATEMENTS_LIMIT = 20;
    static final int N_PLUS_ONE_THRESHOLD = 5;

    private final int maxQueries;
    private final boolean captureParameters;
    private final long slowQueryThresholdMillis;

    private final Object lock = new Object();
    private final Deque<SqlTraceQueryDto> buffer = new ArrayDeque<>();
    private final Set<String> dataSourceNames = new ConcurrentSkipListSet<>();
    private final AtomicBoolean recording;

    private long sequence;
    private long captured;
    private long evicted;

    public SqlTraceStore(BootUiProperties.SqlTrace config) {
        this.maxQueries = clamp(config.getMaxQueries(), 1, HARD_MAX_QUERIES);
        this.captureParameters = config.isCaptureParameters();
        this.slowQueryThresholdMillis =
                Math.max(0, config.getSlowQueryThreshold().toMillis());
        this.recording = new AtomicBoolean(config.isRecording());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public int maxQueries() {
        return maxQueries;
    }

    public boolean isCaptureParameters() {
        return captureParameters;
    }

    public long slowQueryThresholdMillis() {
        return slowQueryThresholdMillis;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public void setRecording(boolean enabled) {
        recording.set(enabled);
    }

    public void registerDataSource(String name) {
        if (name != null && !name.isBlank()) {
            dataSourceNames.add(name);
        }
    }

    public List<String> dataSourceNames() {
        return List.copyOf(dataSourceNames);
    }

    public boolean hasWrappedDataSource() {
        return !dataSourceNames.isEmpty();
    }

    /**
     * Append an execution to the buffer, assigning it the next sequence id and evicting the oldest
     * entry if the buffer is at capacity. The {@code id} carried by {@code query} is ignored.
     */
    public void add(SqlTraceQueryDto query) {
        if (query == null || !recording.get()) {
            return;
        }
        synchronized (lock) {
            SqlTraceQueryDto stored = withId(query, ++sequence);
            buffer.addLast(stored);
            captured++;
            while (buffer.size() > maxQueries) {
                buffer.removeFirst();
                evicted++;
            }
        }
    }

    /** Remove every buffered execution and reset lifetime counters. Returns the number removed. */
    public int clear() {
        synchronized (lock) {
            int cleared = buffer.size();
            buffer.clear();
            captured = 0;
            evicted = 0;
            return cleared;
        }
    }

    /** Snapshot of buffered executions (insertion order) plus lifetime counters. */
    public Snapshot snapshot() {
        synchronized (lock) {
            List<SqlTraceQueryDto> queries = new ArrayList<>(buffer);
            return new Snapshot(queries, captured, evicted);
        }
    }

    private static SqlTraceQueryDto withId(SqlTraceQueryDto q, long id) {
        return new SqlTraceQueryDto(
                id,
                q.timestamp(),
                q.dataSource(),
                q.connectionId(),
                q.type(),
                q.category(),
                q.batch(),
                q.batchSize(),
                q.elapsedMillis(),
                q.success(),
                q.slow(),
                q.error(),
                q.thread(),
                q.statements(),
                q.parameters());
    }

    /** Compute aggregate statistics over a buffer snapshot (insertion order). */
    static SqlTraceStatsDto computeStats(List<SqlTraceQueryDto> buffered, long captured, long evicted) {
        long totalElapsed = 0;
        long maxElapsed = 0;
        int slow = 0;
        int failed = 0;
        int batch = 0;
        int select = 0;
        int insert = 0;
        int update = 0;
        int delete = 0;
        int other = 0;
        for (SqlTraceQueryDto q : buffered) {
            totalElapsed += q.elapsedMillis();
            maxElapsed = Math.max(maxElapsed, q.elapsedMillis());
            if (q.slow()) {
                slow++;
            }
            if (!q.success()) {
                failed++;
            }
            if (q.batch()) {
                batch++;
            }
            switch (q.category()) {
                case "SELECT" -> select++;
                case "INSERT" -> insert++;
                case "UPDATE" -> update++;
                case "DELETE" -> delete++;
                default -> other++;
            }
        }
        int recorded = buffered.size();
        double avg = recorded == 0 ? 0.0 : (double) totalElapsed / recorded;
        return new SqlTraceStatsDto(
                recorded,
                captured,
                evicted,
                totalElapsed,
                maxElapsed,
                avg,
                slow,
                failed,
                batch,
                select,
                insert,
                update,
                delete,
                other);
    }

    /**
     * Group buffered executions by exact statement text, ordered by execution count descending, and
     * flag repeated {@code SELECT}s that look like an N+1 access pattern.
     */
    static List<SqlTraceGroupDto> computeGroups(List<SqlTraceQueryDto> buffered, int limit) {
        Map<String, Aggregate> byStatement = new LinkedHashMap<>();
        for (SqlTraceQueryDto q : buffered) {
            String sql = q.statements().isEmpty() ? "" : q.statements().get(0);
            Aggregate aggregate = byStatement.computeIfAbsent(sql, key -> new Aggregate(key, q.category()));
            aggregate.executions++;
            aggregate.totalElapsed += q.elapsedMillis();
            aggregate.maxElapsed = Math.max(aggregate.maxElapsed, q.elapsedMillis());
        }
        return byStatement.values().stream()
                .sorted(Comparator.comparingInt((Aggregate a) -> a.executions)
                        .reversed()
                        .thenComparing(a -> a.sql))
                .limit(limit)
                .map(a -> new SqlTraceGroupDto(
                        a.sql,
                        a.category,
                        a.executions,
                        a.totalElapsed,
                        a.maxElapsed,
                        "SELECT".equals(a.category) && a.executions >= N_PLUS_ONE_THRESHOLD))
                .toList();
    }

    private static final class Aggregate {
        private final String sql;
        private final String category;
        private int executions;
        private long totalElapsed;
        private long maxElapsed;

        private Aggregate(String sql, String category) {
            this.sql = sql;
            this.category = category;
        }
    }

    /** Immutable point-in-time view of the buffer used to build the report. */
    public record Snapshot(List<SqlTraceQueryDto> queries, long captured, long evicted) {}
}
