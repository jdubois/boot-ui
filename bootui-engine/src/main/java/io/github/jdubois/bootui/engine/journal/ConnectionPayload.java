package io.github.jdubois.bootui.engine.journal;

/**
 * A logical connection's payload, published when the application releases it ({@code docs/PLAN-v2.md} §5.2): the
 * data source it came from, how long the application waited to obtain it, and how many statements ran on it. The
 * event's duration is how long the application held it.
 */
public record ConnectionPayload(String dataSource, long waitNanos, int statements) implements RuntimeEventPayload {

    @Override
    public int estimatedBytes() {
        return 24 + RuntimeEvent.stringBytes(dataSource);
    }
}
