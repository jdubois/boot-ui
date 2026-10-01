package io.github.jdubois.bootui.engine.journal;

/**
 * A SQL statement's payload: its text as SQL Trace retained it, never its bind values, the application call site, the
 * datasource, and whether it failed. The journal aggregates statements by their literal-free fingerprint, computed on
 * its dispatcher thread.
 */
public record SqlPayload(String sql, String callSite, String dataSource, boolean failed)
        implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 16
                + RuntimeEvent.stringBytes(sql)
                + RuntimeEvent.stringBytes(callSite)
                + RuntimeEvent.stringBytes(dataSource);
    }
}
