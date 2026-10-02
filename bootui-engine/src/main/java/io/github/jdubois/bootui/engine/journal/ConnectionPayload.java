package io.github.jdubois.bootui.engine.journal;

/**
 * A logical connection's payload, published when the application releases it ({@code docs/PLAN-v2.md} §5.2): the
 * data source it came from, how long the application waited to obtain it, and how many statements ran on it. The
 * event's duration is how long the application held it.
 *
 * @param checkoutNanos the {@link System#nanoTime()} when the application obtained it, or {@code -1} when unknown, which
 *     orders a request's connections exactly, below the millisecond ({@code docs/PLAN-v2.md} §5.5)
 */
public record ConnectionPayload(String dataSource, long waitNanos, int statements, long checkoutNanos)
        implements RuntimeEventPayload {

    /** A connection whose monotonic checkout time is unknown. */
    public ConnectionPayload(String dataSource, long waitNanos, int statements) {
        this(dataSource, waitNanos, statements, -1);
    }

    /** This connection with its data source replaced by the run's shared copy. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new ConnectionPayload(dictionary.shared(dataSource), waitNanos, statements, checkoutNanos);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 24 + JournalDictionary.retained(dictionary, dataSource);
    }
}
