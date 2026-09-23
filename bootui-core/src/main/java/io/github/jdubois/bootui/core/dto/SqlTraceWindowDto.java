package io.github.jdubois.bootui.core.dto;

/**
 * The bounded retained window every SQL Trace ranking and attribution figure is computed over.
 *
 * <p>SQL Trace keeps only the most recent executions in a capped in-memory buffer, so these are
 * diagnostic evidence about a visible slice of activity, never lifetime database metrics. Stating the
 * window explicitly lets the panel say so, and lets a reader reconcile every ranked total against the
 * retained statements.</p>
 *
 * @param retainedStatements number of executions currently retained and therefore analyzed
 * @param bufferSize maximum number of executions the buffer retains
 * @param evicted executions dropped from the buffer since startup, so a reader knows the window is partial
 * @param totalCaptured executions seen since startup, which may far exceed {@code retainedStatements}
 * @param oldestTimestamp epoch millis of the oldest retained execution, or {@code null} when none is retained
 * @param newestTimestamp epoch millis of the newest retained execution, or {@code null} when none is retained
 * @param totalDurationMillis summed duration of every retained execution, in fractional milliseconds summed
 *     from microsecond-resolution executions; the denominator for every share
 */
public record SqlTraceWindowDto(
        int retainedStatements,
        int bufferSize,
        long evicted,
        long totalCaptured,
        Long oldestTimestamp,
        Long newestTimestamp,
        double totalDurationMillis) {

    public static SqlTraceWindowDto empty() {
        return new SqlTraceWindowDto(0, 0, 0, 0, null, null, 0);
    }
}
