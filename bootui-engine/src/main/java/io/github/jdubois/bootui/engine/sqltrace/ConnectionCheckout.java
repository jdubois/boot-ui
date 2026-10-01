package io.github.jdubois.bootui.engine.sqltrace;

import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One logical connection the application obtained from a traced data source, from checkout to release
 * ({@code docs/PLAN-v2.md} §5.2). It remembers the request or execution that checked it out, so a connection released
 * on another thread still belongs to it, and counts the statements that ran on it.
 *
 * @param dataSource the data source's name, or {@code null} when unknown
 * @param epochMillis when the connection was obtained
 * @param obtainedNanos {@link System#nanoTime()} when the connection was obtained
 * @param waitNanos how long obtaining it took
 * @param context the correlation current when it was obtained
 * @param thread the thread that obtained it
 * @param threadKind that thread's kind
 */
public record ConnectionCheckout(
        String dataSource,
        long epochMillis,
        long obtainedNanos,
        long waitNanos,
        CorrelationContext context,
        String thread,
        ThreadKind threadKind,
        AtomicInteger statements,
        AtomicBoolean released) {

    /** Counts one statement execution on this connection. */
    public void statementExecuted() {
        statements.incrementAndGet();
    }

    /** Marks the connection released, returning {@code false} if it already was, so a double close counts once. */
    boolean release() {
        return released.compareAndSet(false, true);
    }
}
