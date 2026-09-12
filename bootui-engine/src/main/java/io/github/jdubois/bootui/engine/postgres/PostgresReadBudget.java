package io.github.jdubois.bootui.engine.postgres;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The wall-clock budget one PostgreSQL read runs under, shared by every datasource in that read.
 *
 * <p>It is checked between units of work (per datasource, before each collector) rather than interrupting a
 * running JDBC call: the panel never spawns a thread, so the only honest ways to bound it are stopping
 * between steps and pinning {@code statement_timeout}/{@code lock_timeout} on the session it owns. Whatever
 * was read before the budget ran out is kept, and the read reports the truncation.</p>
 */
final class PostgresReadBudget {

    private final long deadlineNanos;
    private final LongSupplier nanoTime;

    private PostgresReadBudget(long deadlineNanos, LongSupplier nanoTime) {
        this.deadlineNanos = deadlineNanos;
        this.nanoTime = nanoTime;
    }

    static PostgresReadBudget of(Duration budget) {
        return of(budget, System::nanoTime);
    }

    /** Test seam: a budget driven by an explicit clock instead of {@code System.nanoTime()}. */
    static PostgresReadBudget of(Duration budget, LongSupplier nanoTime) {
        return new PostgresReadBudget(nanoTime.getAsLong() + budget.toNanos(), nanoTime);
    }

    boolean exhausted() {
        return nanoTime.getAsLong() - deadlineNanos >= 0;
    }

    /** The seconds left, clamped to at least one, so a statement is never given more time than the read has. */
    int remainingSecondsAtMost(int cap) {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - nanoTime.getAsLong());
        if (seconds <= 0) {
            return 1;
        }
        return (int) Math.min(cap, seconds);
    }
}
