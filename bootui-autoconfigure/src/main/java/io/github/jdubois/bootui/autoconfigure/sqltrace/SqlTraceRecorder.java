package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.core.dto.SqlTraceStatsDto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, bounded buffer of recently executed JDBC statements.
 *
 * <p>This is the hand-written replacement for the listener/registry that a
 * third-party JDBC proxy library (such as datasource-proxy or p6spy) would
 * provide. It is thread-safe, capped at {@code maxEntries}, and evicts the
 * oldest execution once full so it never grows unbounded.</p>
 */
public final class SqlTraceRecorder {

    /** Kind of JDBC statement the execution originated from. */
    public enum StatementType {
        STATEMENT,
        PREPARED,
        CALLABLE
    }

    /** Coarse classification of what the execution did. */
    public enum Operation {
        QUERY,
        UPDATE,
        BATCH,
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
            Operation operation,
            long durationMillis,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId,
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

    private final Deque<CapturedStatement> buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong totalCaptured = new AtomicLong();

    public SqlTraceRecorder(
            boolean enabled,
            boolean captureParameters,
            int maxEntries,
            long slowQueryThresholdMillis,
            int maxSqlLength,
            int maxParameterLength) {
        this.enabled = enabled;
        this.captureParameters = captureParameters;
        this.maxEntries = Math.max(1, maxEntries);
        this.slowQueryThresholdMillis = Math.max(0, slowQueryThresholdMillis);
        this.maxSqlLength = Math.max(16, maxSqlLength);
        this.maxParameterLength = Math.max(8, maxParameterLength);
    }

    public boolean isEnabled() {
        return enabled;
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

    /** Records one execution, truncating oversized SQL and evicting the oldest entry when full. */
    public void record(
            StatementType statementType,
            Operation operation,
            String sql,
            List<String> parameters,
            long durationMillis,
            boolean success,
            String errorMessage,
            Long affectedRows,
            int batchSize,
            String connectionId) {
        if (!enabled) {
            return;
        }
        CapturedStatement entry = new CapturedStatement(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                truncate(sql, maxSqlLength),
                statementType == null ? StatementType.STATEMENT : statementType,
                operation == null ? Operation.OTHER : operation,
                Math.max(0, durationMillis),
                success,
                errorMessage,
                affectedRows,
                Math.max(0, batchSize),
                connectionId,
                captureParameters ? List.copyOf(parameters == null ? List.of() : parameters) : List.of());
        synchronized (lock) {
            buffer.addLast(entry);
            while (buffer.size() > maxEntries) {
                buffer.removeFirst();
            }
        }
        totalCaptured.incrementAndGet();
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

    public void clear() {
        synchronized (lock) {
            buffer.clear();
        }
    }

    /** Computes aggregate counters over the retained buffer. */
    public SqlTraceStatsDto stats() {
        long total = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        long slow = 0;
        long failed = 0;
        long selects = 0;
        long updates = 0;
        long batches = 0;
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
            switch (entry.operation()) {
                case QUERY -> selects++;
                case UPDATE -> updates++;
                case BATCH -> batches++;
                default -> others++;
            }
        }
        double avg = total == 0 ? 0 : (double) totalDuration / total;
        return new SqlTraceStatsDto(
                total, totalDuration, maxDuration, avg, slow, failed, selects, updates, batches, others);
    }

    String truncateParameter(String value) {
        return truncate(value, maxParameterLength);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "…";
    }
}
