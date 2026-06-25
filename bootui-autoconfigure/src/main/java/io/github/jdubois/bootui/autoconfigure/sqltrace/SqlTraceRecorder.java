package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.autoconfigure.idle.IdleReclaimable;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, bounded buffer of recently executed JDBC statements.
 *
 * <p>This is the hand-written replacement for the listener/registry that a
 * third-party JDBC proxy library (such as datasource-proxy or p6spy) would
 * provide. It is thread-safe, capped at {@code maxEntries}, and evicts the
 * oldest execution once full so it never grows unbounded.</p>
 *
 * <p>Recording can be paused and resumed at runtime without unwrapping the
 * {@code DataSource}: {@link #setRecording(boolean)} flips a flag that
 * {@link #record} honours. The recorder also tracks which {@code DataSource}
 * beans were actually wrapped, so the panel can distinguish "no data source"
 * from "tracing disabled".</p>
 */
public final class SqlTraceRecorder implements IdleReclaimable {

    static final int TOP_STATEMENTS_LIMIT = 20;

    /** Kind of JDBC statement the execution originated from. */
    public enum StatementType {
        STATEMENT,
        PREPARED,
        CALLABLE
    }

    /** Coarse SQL classification derived from the statement text. */
    public enum Category {
        SELECT,
        INSERT,
        UPDATE,
        DELETE,
        DDL,
        OTHER
    }

    /**
     * A single immutable captured execution. Parameter bindings are only retained
     * when capture is enabled; callers decide whether to expose them.
     */
    public record CapturedStatement(
            long id,
            long timestamp,
            String sql,
            StatementType statementType,
            Category category,
            long durationMillis,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId,
            String thread,
            String traceId,
            List<String> parameters) {

        public CapturedStatement {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    private final boolean enabled;
    private final boolean captureParameters;
    private final int maxEntries;
    private final long slowQueryThresholdMillis;
    private final int maxSqlLength;
    private final int maxParameterLength;
    private final int nPlusOneThreshold;

    private final Deque<CapturedStatement> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicBoolean recording;
    private volatile boolean idleSuspended = false;
    private final Set<String> dataSourceNames = new ConcurrentSkipListSet<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public SqlTraceRecorder(
            boolean enabled,
            boolean recording,
            boolean captureParameters,
            int maxEntries,
            long slowQueryThresholdMillis,
            int maxSqlLength,
            int maxParameterLength,
            int nPlusOneThreshold) {
        this.enabled = enabled;
        this.recording = new AtomicBoolean(recording);
        this.captureParameters = captureParameters;
        this.maxEntries = Math.max(1, maxEntries);
        this.slowQueryThresholdMillis = Math.max(0, slowQueryThresholdMillis);
        this.maxSqlLength = Math.max(16, maxSqlLength);
        this.maxParameterLength = Math.max(8, maxParameterLength);
        this.nPlusOneThreshold = Math.max(2, nPlusOneThreshold);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public void setRecording(boolean value) {
        boolean changed = recording.getAndSet(value) != value;
        if (changed) {
            notifyListeners();
        }
    }

    public boolean isCaptureParameters() {
        return captureParameters;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public long getSlowQueryThresholdMillis() {
        return slowQueryThresholdMillis;
    }

    public boolean isSlow(long durationMillis) {
        return slowQueryThresholdMillis > 0 && durationMillis >= slowQueryThresholdMillis;
    }

    /** Remembers a {@code DataSource} bean that BootUI wrapped for tracing. */
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

    /** Records one execution, truncating oversized SQL and evicting the oldest entry when full. */
    public void record(
            StatementType statementType,
            Category category,
            String sql,
            List<String> parameters,
            long durationMillis,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId,
            String thread) {
        if (!enabled || idleSuspended || !recording.get()) {
            return;
        }
        CapturedStatement entry = new CapturedStatement(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                truncate(sql, maxSqlLength),
                statementType == null ? StatementType.STATEMENT : statementType,
                category == null ? Category.OTHER : category,
                Math.max(0, durationMillis),
                success,
                errorMessage,
                affectedRows,
                Math.max(0, batchSize),
                connectionId,
                thread,
                currentTraceId(),
                captureParameters ? List.copyOf(parameters == null ? List.of() : parameters) : List.of());
        synchronized (lock) {
            buffer.addLast(entry);
            while (buffer.size() > maxEntries) {
                buffer.removeFirst();
                evicted.incrementAndGet();
            }
        }
        totalCaptured.incrementAndGet();
        notifyListeners();
    }

    /** Returns the retained executions, most recent first. */
    public List<CapturedStatement> recent() {
        synchronized (lock) {
            List<CapturedStatement> snapshot = new ArrayList<>(buffer);
            java.util.Collections.reverse(snapshot);
            return snapshot;
        }
    }

    public long totalCaptured() {
        return totalCaptured.get();
    }

    public long evicted() {
        return evicted.get();
    }

    public void clear() {
        synchronized (lock) {
            buffer.clear();
        }
        notifyListeners();
    }

    @Override
    public void suspendForIdle() {
        idleSuspended = true;
        clear();
    }

    @Override
    public void resumeFromIdle() {
        idleSuspended = false;
    }

    /**
     * Registers a listener invoked (with no payload) whenever the trace changes, i.e. on a recorded
     * statement, a {@link #clear()}, or a recording pause/resume. Returns a handle that removes the
     * listener when run. Listener failures are isolated so they cannot disrupt query execution.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A misbehaving stream subscriber must never disrupt query execution.
            }
        }
    }

    /** Computes aggregate counters over the retained buffer. */
    public SqlTraceStatsDto stats() {
        long total = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        long slow = 0;
        long failed = 0;
        long batches = 0;
        long selects = 0;
        long inserts = 0;
        long updates = 0;
        long deletes = 0;
        long others = 0;
        List<CapturedStatement> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }
        for (CapturedStatement entry : snapshot) {
            total++;
            totalDuration += entry.durationMillis();
            maxDuration = Math.max(maxDuration, entry.durationMillis());
            if (isSlow(entry.durationMillis())) {
                slow++;
            }
            if (!entry.success()) {
                failed++;
            }
            if (entry.batchSize() > 0) {
                batches++;
            }
            switch (entry.category()) {
                case SELECT -> selects++;
                case INSERT -> inserts++;
                case UPDATE -> updates++;
                case DELETE -> deletes++;
                default -> others++;
            }
        }
        double avg = total == 0 ? 0 : (double) totalDuration / total;
        return new SqlTraceStatsDto(
                total,
                totalDuration,
                maxDuration,
                avg,
                slow,
                failed,
                batches,
                selects,
                inserts,
                updates,
                deletes,
                others,
                evicted.get());
    }

    /**
     * Groups buffered executions by exact statement text, ordered by execution count descending,
     * and flags repeated {@code SELECT}s that look like an N+1 access pattern.
     */
    public List<SqlTraceGroupDto> topStatements() {
        List<CapturedStatement> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }
        Map<String, Aggregate> byStatement = new LinkedHashMap<>();
        for (CapturedStatement entry : snapshot) {
            String sql = entry.sql() == null ? "" : entry.sql();
            Aggregate aggregate = byStatement.computeIfAbsent(sql, key -> new Aggregate(key, entry.category()));
            aggregate.executions++;
            aggregate.totalDuration += entry.durationMillis();
            aggregate.maxDuration = Math.max(aggregate.maxDuration, entry.durationMillis());
        }
        return byStatement.values().stream()
                .sorted(Comparator.comparingLong((Aggregate a) -> a.executions)
                        .reversed()
                        .thenComparing(a -> a.sql))
                .limit(TOP_STATEMENTS_LIMIT)
                .map(a -> new SqlTraceGroupDto(
                        a.sql,
                        a.category.name(),
                        a.executions,
                        a.totalDuration,
                        a.maxDuration,
                        a.category == Category.SELECT && a.executions >= nPlusOneThreshold))
                .toList();
    }

    String truncateParameter(String value) {
        return truncate(value, maxParameterLength);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() <= max) {
            return stripped;
        }
        return stripped.substring(0, max) + "…";
    }

    /**
     * Best-effort current trace id, read from the SLF4J MDC where Micrometer Tracing publishes it
     * (the {@code traceId} correlation key). Returns {@code null} when no tracer is active or the key
     * is absent, in which case downstream correlation falls back to its time-window heuristic. The
     * lookup is fully guarded so SQL execution is never disrupted by a missing or misbehaving MDC.
     */
    private static String currentTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final class Aggregate {
        private final String sql;
        private final Category category;
        private long executions;
        private long totalDuration;
        private long maxDuration;

        private Aggregate(String sql, Category category) {
            this.sql = sql;
            this.category = category;
        }
    }
}
