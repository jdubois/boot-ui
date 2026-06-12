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
 * @param durationMillis wall-clock execution time in milliseconds
 * @param success whether the execution returned without throwing
 * @param errorMessage the failure message when {@code success} is {@code false}
 * @param affectedRows update count, when known
 * @param batchSize number of statements in a batch execution, or {@code 0}
 * @param connectionId stable identifier for the originating JDBC connection
 * @param thread name of the thread that ran the statement
 * @param slow whether the execution exceeded the configured slow-query threshold
 * @param parameters ordered, stringified parameter bindings (may be empty)
 */
public record SqlTraceEntryDto(
        long id,
        long timestamp,
        String sql,
        String statementType,
        String category,
        long durationMillis,
        boolean success,
        String errorMessage,
        Long affectedRows,
        int batchSize,
        String connectionId,
        String thread,
        boolean slow,
        List<String> parameters) {

    public SqlTraceEntryDto {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
