package io.github.jdubois.bootui.core.dto;

/**
 * A single captured transaction boundary (begin through commit/rollback).
 *
 * <p>Populated by BootUI's hand-written transaction listener wiring (no third-party
 * transaction-observability library). Nesting is expressed through {@code parentId}: a child physical
 * transaction or savepoint records the enclosing boundary on the same thread, letting the panel render a
 * call tree without implying shared physical ownership.</p>
 *
 * @param id sequence number, increasing in begin order
 * @param methodName the transactional boundary's declared name, typically {@code
 *     ClassName.methodName} for {@code @Transactional} methods
 * @param propagation observed boundary classification: {@code NEW} for a physical transaction without a known
 *     enclosing boundary, {@code REQUIRES_NEW} for a child physical transaction, {@code NESTED} for a savepoint,
 *     or {@code UNKNOWN} for a failed begin or unverified physical-versus-savepoint ownership. These do not prove declared annotation values. A joining method
 *     creates no boundary. Older servers may emit the misleading legacy {@code PARTICIPATING} value.
 * @param isolation the JDBC isolation level active when the transaction began (e.g. {@code
 *     READ_COMMITTED}), or {@code UNKNOWN} when it could not be determined
 * @param status {@code COMMITTED}, {@code ROLLED_BACK}, or {@code UNKNOWN} (begin failed, or the
 *     outcome could not be observed)
 * @param startTimestamp epoch milliseconds when the transaction began
 * @param endTimestamp epoch milliseconds when the transaction completed
 * @param durationMillis wall-clock duration from begin to completion
 * @param parentId id of the transaction active on the same thread when this one began, or {@code
 *     null} for a root transaction
 * @param thread name of the thread that ran the transaction
 * @param traceId Micrometer/W3C trace id active when the transaction began, or {@code null} when no
 *     tracer was present
 * @param sqlStatementCount number of SQL Trace executions correlated to this transaction's thread and
 *     time window, or {@code 0} when SQL tracing is unavailable
 * @param connectionCount number of distinct JDBC connections correlated to this transaction, or
 *     {@code 0} when SQL tracing is unavailable
 * @param readOnly whether the transaction was defined as read-only
 * @param slow whether the duration exceeded the configured slow-transaction threshold
 * @param connectionHeld whether the duration exceeded the configured connection-hold threshold,
 *     flagging a transaction that likely held a pooled connection too long
 * @param errorMessage populated when {@code status} is {@code UNKNOWN} because starting or completing
 *     the transaction threw
 */
public record TransactionEntryDto(
        long id,
        String methodName,
        String propagation,
        String isolation,
        String status,
        long startTimestamp,
        long endTimestamp,
        long durationMillis,
        Long parentId,
        String thread,
        String traceId,
        int sqlStatementCount,
        int connectionCount,
        boolean readOnly,
        boolean slow,
        boolean connectionHeld,
        String errorMessage) {}
