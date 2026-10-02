package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;

/**
 * A SQL statement's payload: its text as SQL Trace retained it, never its bind values, the application call site and
 * up to four application frames above it, the datasource, and whether it failed. The journal aggregates statements by
 * their literal-free fingerprint, computed on its dispatcher thread.
 *
 * @param phase the part of its request it ran in, or {@code null} when unknown or outside a request
 * @param completedNanos the {@link System#nanoTime()} when it completed, or {@code -1} when unknown, which places it
 *     inside or outside a transaction of its thread below the millisecond ({@code docs/PLAN-v2.md} §5.5)
 */
public record SqlPayload(
        String sql,
        String callSite,
        String dataSource,
        boolean failed,
        ApplicationFrames frames,
        RequestPhase phase,
        long completedNanos)
        implements RuntimeEventPayload {

    /** A statement without its request phase or monotonic completion. */
    public SqlPayload(String sql, String callSite, String dataSource, boolean failed, ApplicationFrames frames) {
        this(sql, callSite, dataSource, failed, frames, null, -1);
    }

    /** A statement without application frames. */
    public SqlPayload(String sql, String callSite, String dataSource, boolean failed) {
        this(sql, callSite, dataSource, failed, null);
    }

    /**
     * This statement with its SQL, call site, data source, and frames replaced by the run's shared copies, so a
     * statement run many times is stored once ({@code docs/PLAN-v2.md} §5.2). Its SQL is shared only when no literal
     * sits where a concatenated value would ({@link SqlShapes#shareable}), since such statements would fill the
     * dictionary with one-off strings; it then keeps, and is counted for, its own copy.
     */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new SqlPayload(
                SqlShapes.shareable(sql) ? dictionary.shared(sql) : sql,
                dictionary.shared(callSite),
                dictionary.shared(dataSource),
                failed,
                frames == null ? null : frames.interned(dictionary),
                phase,
                completedNanos);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 32
                + JournalDictionary.retained(dictionary, sql)
                + JournalDictionary.retained(dictionary, callSite)
                + JournalDictionary.retained(dictionary, dataSource)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
