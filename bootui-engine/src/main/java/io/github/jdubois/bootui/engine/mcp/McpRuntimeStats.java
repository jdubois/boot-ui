package io.github.jdubois.bootui.engine.mcp;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe operational counters for the local MCP server. */
public final class McpRuntimeStats {

    private final LongAdder callCount = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();
    private final LongAdder capacityRefusals = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder responseLimitRefusals = new LongAdder();
    private final LongAdder cancellations = new LongAdder();
    private final LongAdder progressDropped = new LongAdder();

    void recordCall(long latencyNanos) {
        callCount.increment();
        totalLatencyNanos.add(Math.max(0, latencyNanos));
    }

    void recordCapacityRefusal() {
        capacityRefusals.increment();
    }

    void recordTimeout() {
        timeouts.increment();
    }

    void recordCancellation() {
        cancellations.increment();
    }

    public void recordResponseLimitRefusal() {
        responseLimitRefusals.increment();
    }

    /** A progress notification not sent because it would exceed {@code bootui.mcp.max-response-bytes}. */
    public void recordProgressDropped() {
        progressDropped.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                callCount.sum(),
                totalLatencyNanos.sum() / 1_000_000,
                capacityRefusals.sum(),
                timeouts.sum(),
                responseLimitRefusals.sum(),
                cancellations.sum(),
                progressDropped.sum());
    }

    public record Snapshot(
            long callCount,
            long totalLatencyMillis,
            long capacityRefusals,
            long timeouts,
            long responseLimitRefusals,
            long cancellations,
            long progressDropped) {}
}
