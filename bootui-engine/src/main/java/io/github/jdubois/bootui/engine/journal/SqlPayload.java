package io.github.jdubois.bootui.engine.journal;

/**
 * A SQL statement's payload: its text as SQL Trace retained it, never its bind values, the application call site and
 * up to four application frames above it, the datasource, and whether it failed. The journal aggregates statements by
 * their literal-free fingerprint, computed on its dispatcher thread.
 */
public record SqlPayload(String sql, String callSite, String dataSource, boolean failed, ApplicationFrames frames)
        implements RuntimeEventPayload {

    /** A statement without application frames. */
    public SqlPayload(String sql, String callSite, String dataSource, boolean failed) {
        this(sql, callSite, dataSource, failed, null);
    }

    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return frames == null
                ? this
                : new SqlPayload(sql, sharedCallSite(dictionary), dataSource, failed, frames.interned(dictionary));
    }

    private String sharedCallSite(JournalDictionary dictionary) {
        String shared = callSite == null ? null : dictionary.canonical(callSite);
        return shared == null ? callSite : shared;
    }

    @Override
    public int estimatedBytes() {
        return 16
                + RuntimeEvent.stringBytes(sql)
                + (frames == null ? RuntimeEvent.stringBytes(callSite) : 8)
                + RuntimeEvent.stringBytes(dataSource)
                + (frames == null ? 0 : frames.estimatedBytes());
    }
}
