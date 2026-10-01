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
 */
public record TransactionPayload(String method, boolean rolledBack, boolean nested, boolean savepoint, long startNanos)
        implements RuntimeEventPayload {

    /** A transaction whose place among its thread's transactions is unknown. */
    public TransactionPayload(String method, boolean rolledBack) {
        this(method, rolledBack, false, false, -1);
    }

    /** Whether it commits on its own: a physical transaction rather than a savepoint. */
    public boolean independent() {
        return !savepoint;
    }

    @Override
    public int estimatedBytes() {
        return 24 + RuntimeEvent.stringBytes(method);
    }
}
