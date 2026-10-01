package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RequestPhase;

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

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return frames == null
                ? this
                : new SqlPayload(
                        sql,
                        sharedCallSite(dictionary),
                        dataSource,
                        failed,
                        frames.interned(dictionary),
                        phase,
                        completedNanos);
    }

    private String sharedCallSite(JournalDictionary dictionary) {
        String shared = callSite == null ? null : dictionary.canonical(callSite);
        return shared == null ? callSite : shared;
    }

    @Override
    public int estimatedBytes() {
        return 32
                + RuntimeEvent.stringBytes(sql)
                + (frames == null ? RuntimeEvent.stringBytes(callSite) : 8)
                + RuntimeEvent.stringBytes(dataSource)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
