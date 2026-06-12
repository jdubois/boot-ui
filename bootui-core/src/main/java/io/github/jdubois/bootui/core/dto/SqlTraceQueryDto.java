package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A single SQL execution captured by the SQL Trace panel.
 *
 * @param id sequence number assigned by the recorder (monotonically increasing)
 * @param timestamp epoch milliseconds when the execution completed
 * @param dataSource name of the {@code DataSource} bean that ran the statement
 * @param connectionId opaque id of the JDBC connection used
 * @param type statement kind: {@code STATEMENT}, {@code PREPARED}, or {@code CALLABLE}
 * @param category coarse SQL category derived from the statement: {@code SELECT}, {@code INSERT},
 *     {@code UPDATE}, {@code DELETE}, {@code DDL}, or {@code OTHER}
 * @param batch whether this was a batched execution
 * @param batchSize number of statements in the batch ({@code 0} when not batched)
 * @param elapsedMillis wall-clock execution time in milliseconds
 * @param success whether the execution completed without throwing
 * @param slow whether {@code elapsedMillis} reached the configured slow-query threshold
 * @param error failure message when {@code success} is {@code false}, otherwise {@code null}
 * @param thread name of the thread that ran the statement
 * @param statements the SQL text(s) executed (more than one only for multi-statement batches)
 * @param parameters formatted bound parameters, or {@code null} when capture is disabled
 */
public record SqlTraceQueryDto(
        long id,
        long timestamp,
        String dataSource,
        String connectionId,
        String type,
        String category,
        boolean batch,
        int batchSize,
        long elapsedMillis,
        boolean success,
        boolean slow,
        String error,
        String thread,
        List<String> statements,
        List<String> parameters) {}
