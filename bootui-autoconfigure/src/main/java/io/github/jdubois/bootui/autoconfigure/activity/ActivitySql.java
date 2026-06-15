package io.github.jdubois.bootui.autoconfigure.activity;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Shared helpers for the Live Activity panel so the stream service and the per-request correlator
 * normalize statements, timestamps, and correlation windows identically (e.g. when grouping for N+1
 * detection or attributing a signal to the request that produced it).
 */
final class ActivitySql {

    /** Slack (ms) applied to a request's time window when attributing correlated signals to it. */
    static final long WINDOW_SLACK_MS = 50L;

    /**
     * Tight slack (ms) for pinning a security audit event to a serving thread: the capture and the
     * displayed event come from the same {@code AuditEvent}, so their timestamps are effectively equal.
     */
    static final long SECURITY_THREAD_SLACK_MS = 2L;

    private ActivitySql() {}

    /** Parse an ISO-8601 instant to epoch millis, returning {@code 0} for null/blank/unparseable input. */
    static long parseEpochMillis(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException ex) {
            return 0L;
        }
    }

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
