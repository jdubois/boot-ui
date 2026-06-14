package io.github.jdubois.bootui.autoconfigure.activity;

/**
 * Shared SQL helpers for the Live Activity panel so the stream service and the per-request
 * correlator normalize statements identically (e.g. when grouping for N+1 detection).
 */
final class ActivitySql {

    private ActivitySql() {}

    /** Collapse runs of whitespace into single spaces and trim, returning "" for null. */
    static String normalize(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }
}
