package io.github.jdubois.bootui.engine.memory;

import java.util.Locale;

/**
 * Classifies the JVM's {@code GarbageCollectorMXBean}s, for the Memory panel and for §5.11's resource correlation
 * ({@code docs/PLAN-v2.md}), so both count pauses the same way.
 */
public final class GcCollectorKinds {

    private GcCollectorKinds() {}

    /**
     * Returns {@code true} for GarbageCollectorMXBeans that report concurrent (non-STW) cycle time.
     * Their collection time runs while the application is still executing, so including it in an
     * "overhead" percentage produces inflated, misleading results for concurrent collectors such as
     * ZGC, Shenandoah, and G1 concurrent marking.
     */
    public static boolean isConcurrentCycleBean(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // "cycles" covers: ZGC Cycles, ZGC Major Cycles, ZGC Minor Cycles, Shenandoah Cycles.
        // G1 Concurrent GC records its remark/cleanup VM operations, so its elapsed time remains
        // relevant to a stop-the-world overhead metric.
        return lower.contains("cycles") || "concurrentmarksweep".equals(lower);
    }
}
