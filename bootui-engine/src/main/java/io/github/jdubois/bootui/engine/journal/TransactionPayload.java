package io.github.jdubois.bootui.engine.journal;

/**
 * A transaction's payload: the transactional method that began it, whether it rolled back, and where it sits among the
 * transactions of its thread ({@code docs/PLAN-v2.md} §5.5). Only boundaries that begin a physical transaction or a
 * savepoint are recorded; a method that joins the current transaction records none.
 *
 * @param method the transactional method
 * @param rolledBack whether it rolled back
 * @param nested whether another recorded transaction was already active on its thread when it began, as with
 *     {@code REQUIRES_NEW} or {@code NESTED}
 * @param savepoint whether it is a savepoint inside the enclosing transaction, which commits with it, rather than its
 *     own physical transaction
 * @param startNanos the {@link System#nanoTime()} when it began, or {@code -1} when unknown, which places the work of
 *     its thread inside or outside it below the millisecond
 * @param readOnly whether it was declared read-only
 * @param isolation its JDBC isolation level, such as {@code REPEATABLE_READ}, or {@code null} when the data source's
 *     default applies or it is unknown
 * @param rollbackOnly whether it was marked rollback-only before it completed, as a participating method that failed or
 *     {@code setRollbackOnly()} does, rather than rolled back by the exception leaving it
 * @param failureClass the exception class that failed its commit or rollback, such as {@code
 *     UnexpectedRollbackException}, or {@code null}; Spring does not report the exception that caused a rollback, which
 *     the request's own exception events name
 */
public record TransactionPayload(
        String method,
        boolean rolledBack,
        boolean nested,
        boolean savepoint,
        long startNanos,
        boolean readOnly,
        String isolation,
        boolean rollbackOnly,
        String failureClass)
        implements RuntimeEventPayload {

    /** A transaction whose place among its thread's transactions is unknown. */
    public TransactionPayload(String method, boolean rolledBack) {
        this(method, rolledBack, false, false, -1);
    }

    /** A transaction whose declared attributes are unknown. */
    public TransactionPayload(String method, boolean rolledBack, boolean nested, boolean savepoint, long startNanos) {
        this(method, rolledBack, nested, savepoint, startNanos, false, null, false, null);
    }

    /**
     * Its effective propagation, from where it began ({@code docs/PLAN-v2.md} §5.18): {@code NESTED} for a savepoint,
     * {@code REQUIRES_NEW} for a physical transaction begun while another was active on its thread, and {@code
     * REQUIRED} for the outermost one. A method that joined the current transaction records no event.
     */
    public String propagation() {
        return savepoint ? "NESTED" : nested ? "REQUIRES_NEW" : "REQUIRED";
    }

    /** Whether it commits on its own: a physical transaction rather than a savepoint. */
    public boolean independent() {
        return !savepoint;
    }

    /** This transaction with its method replaced by the run's shared copy. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new TransactionPayload(
                dictionary.shared(method),
                rolledBack,
                nested,
                savepoint,
                startNanos,
                readOnly,
                dictionary.shared(isolation),
                rollbackOnly,
                dictionary.shared(failureClass));
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 32
                + JournalDictionary.retained(dictionary, method)
                + JournalDictionary.retained(dictionary, isolation)
                + JournalDictionary.retained(dictionary, failureClass);
    }
}
