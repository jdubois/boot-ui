package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A single captured JDBC statement execution.
 *
 * <p>Populated by BootUI's hand-written JDBC tracing proxy (no third-party
 * database-proxy library). Parameter bindings are only present when parameter
 * capture is explicitly enabled and value exposure permits it.</p>
 *
 * @param id sequence number, increasing in execution order
 * @param timestamp epoch milliseconds when the statement completed
 * @param sql the executed SQL text (or a {@code ;}-joined batch)
 * @param statementType {@code STATEMENT}, {@code PREPARED}, or {@code CALLABLE}
 * @param category coarse SQL category: {@code SELECT}, {@code INSERT}, {@code UPDATE},
 *     {@code DELETE}, {@code DDL}, or {@code OTHER}
 * @param durationMicros wall-clock execution time in microseconds, the exact recorded value every
 *     aggregate is summed from; a statement that runs in 310 µs on a local database is {@code 310} here
 *     rather than collapsing to a whole millisecond
 * @param durationMillis wall-clock execution time in milliseconds, derived from {@link #durationMicros()}
 *     by rounding; kept for compatibility, so sub-millisecond executions read {@code 0} or {@code 1} here
 *     and callers that need real resolution read {@link #durationMicros()}
 * @param success whether the execution returned without throwing
 * @param errorMessage the failure message when {@code success} is {@code false}
 * @param affectedRows update count, when known
 * @param batchSize number of statements in a batch execution, or {@code 0}
 * @param connectionId stable identifier for the originating JDBC connection
 * @param thread name of the thread that ran the statement
 * @param slow whether the execution exceeded the configured slow-query threshold
 * @param parameters ordered, stringified parameter bindings (may be empty)
 * @param traceId Micrometer/W3C trace id active when the statement ran, or {@code null} when no
 *     tracer was present; used to correlate the statement to its originating request exactly
 * @param callSite the first application stack frame above the JDBC call, formatted as {@code
 *     ClassName.methodName(File.java:42)}, or {@code null} when call-site capture is disabled or no
 *     application frame was found; never gated by value exposure since it names the application's own
 *     code, never a bound value
 */
public record SqlTraceEntryDto(
        long id,
        long timestamp,
        String sql,
        String statementType,
        String category,
        long durationMicros,
        long durationMillis,
        boolean success,
        String errorMessage,
        Long affectedRows,
        int batchSize,
        String connectionId,
        String thread,
        boolean slow,
        List<String> parameters,
        String traceId,
        String callSite) {

    public SqlTraceEntryDto {
        parameters = DtoCollections.immutableCopy(parameters);
    }
}
