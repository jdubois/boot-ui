package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import java.util.List;

/**
 * What the {@code get_live_memory} and {@code get_jvm_tuning} MCP tools return, each its own panel's part of the
 * report both panels read, so the two tools never answer the same payload: Live Memory's current usage, and JVM
 * Tuning's sizing calculation and recommendations.
 */
public final class MemoryAgentViews {

    private MemoryAgentViews() {}

    /** Heap, non-heap, and per-pool usage now, without the sizing calculation or the JVM's arguments. */
    public static LiveMemoryReport liveMemory(LiveMemoryReport report) {
        if (report == null) {
            return null;
        }
        return new LiveMemoryReport(report.heap(), report.nonHeap(), report.pools(), List.of(), null, null, null);
    }

    /**
     * The JVM's arguments, the sizing calculation, the suggested options, and the Kubernetes recommendation, with heap
     * and non-heap usage for context and without the per-pool rows.
     */
    public static LiveMemoryReport jvmTuning(LiveMemoryReport report) {
        if (report == null) {
            return null;
        }
        return new LiveMemoryReport(
                report.heap(),
                report.nonHeap(),
                List.of(),
                report.jvmInputArguments(),
                report.suggestedJvmOptions(),
                report.calculation(),
                report.kubernetes());
    }
}
