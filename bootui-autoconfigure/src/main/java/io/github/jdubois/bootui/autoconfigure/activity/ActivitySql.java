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

    /**
     * Build a stream summary for a SQL statement. The category prefix (e.g. {@code DDL},
     * {@code OTHER}) is only kept when it adds information; for statements that already begin with
     * their category keyword ({@code SELECT}, {@code INSERT}, ...) it is dropped to avoid redundant
     * "SELECT select ..." text.
     */
    static String summarize(String category, String normalizedSql) {
        String sql = normalizedSql == null ? "" : normalizedSql;
        if (category == null || category.isBlank()) {
            return sql;
        }
        if (sql.length() >= category.length() && sql.regionMatches(true, 0, category, 0, category.length())) {
            return sql;
        }
        return sql.isEmpty() ? category : category + " " + sql;
    }
}
