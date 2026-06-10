package io.github.jdubois.bootui.autoconfigure.memory;

import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.MemoryData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.ThreadData;
import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Static Memory Advisor rules. Each rule reads only from {@link MemoryContext} and returns a
 * health finding with a severity; rules never collect data or mutate runtime state.
 */
abstract class AbstractMemoryRule implements MemoryRule {

    private final MemoryRuleDefinition definition;

    AbstractMemoryRule(MemoryRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final MemoryRuleDefinition definition() {
        return definition;
    }

    abstract io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context);

    @Override
    public final io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluate(MemoryContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return MemoryRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto pass() {
        return MemoryRuleSupport.pass(definition);
    }

    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto skipped(String reason) {
        return MemoryRuleSupport.skipped(definition, reason);
    }

    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : MemoryRuleSupport.violation(definition, details);
    }

    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto violation(String detail) {
        return violation(List.of(detail));
    }

    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto violation(String severityOverride, List<String> details) {
        return details.isEmpty() ? pass() : MemoryRuleSupport.violation(definition, severityOverride, details);
    }

    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto violation(String severityOverride, String detail) {
        return violation(severityOverride, List.of(detail));
    }
}

/**
 * Shared formatting and threshold helpers for Memory Advisor rules.
 */
final class MemoryFormat {

    static final long KILOBYTE = 1024L;
    static final long MEGABYTE = 1024L * 1024;
    static final long GIGABYTE = 1024L * 1024 * 1024;

    private MemoryFormat() {}

    static String bytes(long value) {
        if (value < 0) {
            return "unbounded";
        }
        if (value >= GIGABYTE) {
            return String.format(Locale.ROOT, "%.2f GiB", value / (double) GIGABYTE);
        }
        if (value >= MEGABYTE) {
            return String.format(Locale.ROOT, "%.1f MiB", value / (double) MEGABYTE);
        }
        if (value >= KILOBYTE) {
            return String.format(Locale.ROOT, "%.1f KiB", value / (double) KILOBYTE);
        }
        return value + " B";
    }

    static int percentOf(long part, long whole) {
        if (whole <= 0) {
            return 0;
        }
        return (int) Math.min(100, part * 100L / whole);
    }
}

// ---------------------------------------------------------------------------
// Heap pressure
// ---------------------------------------------------------------------------

final class HighHeapUtilizationRule extends AbstractMemoryRule {

    private static final int THRESHOLD_PERCENT = 95;

    HighHeapUtilizationRule() {
        super(
                new MemoryRuleDefinition(
                        "MEM-HEAP-001",
                        "Heap utilization is critically high",
                        MemoryCategory.HEAP_PRESSURE,
                        "HIGH",
                        "Flags when live heap usage is very close to the maximum heap. The scan re-reads the heap after the histogram's full GC so it can tell sustained retained pressure (still high after a GC, reported HIGH) from transient garbage the collection reclaims. A reading that stays this high after a GC risks long GC pauses or OutOfMemoryError; a single pre-GC snapshot is reported at MEDIUM until it is confirmed to persist.",
                        "Increase -Xmx (or MaxRAMPercentage), reduce retained objects, or profile the heap to find the growth source.",
                        "https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.heapMax() <= 0) {
            return skipped("Maximum heap size is not reported by this JVM.");
        }
        MemoryContext.PostGcHeapData postGc = context.postGcHeap();
        if (postGc.heapAvailable()) {
            int postPercent = MemoryFormat.percentOf(postGc.heapUsed(), memory.heapMax());
            if (postPercent >= THRESHOLD_PERCENT) {
                return violation(
                        MemoryRuleSupport.HIGH,
                        "Heap is still " + postPercent + "% full (" + MemoryFormat.bytes(postGc.heapUsed()) + " of "
                                + MemoryFormat.bytes(memory.heapMax())
                                + ") after a full GC, indicating sustained heap pressure.");
            }
            // The pre-GC reading may have been high, but a full GC reclaimed it: no retained pressure.
            return pass();
        }
        int percent = MemoryFormat.percentOf(memory.heapUsed(), memory.heapMax());
        if (percent >= THRESHOLD_PERCENT) {
            return violation(
                    MemoryRuleSupport.MEDIUM,
                    "Heap is " + percent + "% full (" + MemoryFormat.bytes(memory.heapUsed()) + " of "
                            + MemoryFormat.bytes(memory.heapMax())
                            + ") in a single snapshot that may include not-yet-collected garbage; confirm it persists after a GC.");
        }
        return pass();
    }
}

final class OldGenerationNearMaxRule extends AbstractMemoryRule {

    private static final int THRESHOLD_PERCENT = 85;

    OldGenerationNearMaxRule() {
        super(new MemoryRuleDefinition(
                "MEM-HEAP-002",
                "Old generation is near its maximum",
                MemoryCategory.HEAP_PRESSURE,
                "MEDIUM",
                "Flags when the tenured/old-generation pool is nearly full, a common precursor to full GCs and promotion failures. The scan prefers the pool's post-GC occupancy (after the histogram's full GC) so it reflects long-lived retention rather than reclaimable garbage.",
                "Investigate long-lived object retention; consider raising the heap size or tuning the generation sizes for the active collector.",
                "https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        Optional<MemoryPoolSnapshot> oldGen = context.memory().oldGenerationPool();
        if (oldGen.isEmpty()) {
            return skipped("No old-generation memory pool is exposed by the active garbage collector.");
        }
        MemoryPoolSnapshot pool = oldGen.get();
        if (pool.max() <= 0) {
            return skipped("Old-generation pool '" + pool.name() + "' does not report a maximum size.");
        }
        MemoryContext.PostGcHeapData postGc = context.postGcHeap();
        if (postGc.oldGenAvailable() && postGc.oldGenUsed() >= 0) {
            int postPercent = MemoryFormat.percentOf(postGc.oldGenUsed(), pool.max());
            if (postPercent >= THRESHOLD_PERCENT) {
                return violation("Old-generation pool '" + pool.name() + "' is still " + postPercent + "% full ("
                        + MemoryFormat.bytes(postGc.oldGenUsed()) + " of " + MemoryFormat.bytes(pool.max())
                        + ") after a full GC.");
            }
            return pass();
        }
        if (pool.usedPercent() >= THRESHOLD_PERCENT) {
            return violation("Old-generation pool '" + pool.name() + "' is " + pool.usedPercent() + "% full ("
                    + MemoryFormat.bytes(pool.used()) + " of " + MemoryFormat.bytes(pool.max()) + ").");
        }
        return pass();
    }
}

final class UnsetOrSmallMaxHeapRule extends AbstractMemoryRule {

    private static final long MIN_CONTAINER_LIMIT = MemoryFormat.GIGABYTE;
    private static final int SMALL_HEAP_PERCENT = 15;
    private static final int PRESSURE_PERCENT = 80;

    UnsetOrSmallMaxHeapRule() {
        super(new MemoryRuleDefinition(
                "MEM-HEAP-003",
                "Maximum heap is unset or capped well below the container limit",
                MemoryCategory.HEAP_PRESSURE,
                "LOW",
                "Flags when -Xmx is effectively unbounded, or when a small max heap is already under pressure while a much larger container memory limit is available to grow into.",
                "Set an explicit -Xmx or -XX:MaxRAMPercentage that lets the heap use a sensible share of the container memory limit instead of staying small while under pressure.",
                "https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.heapMax() <= 0) {
            // HotSpot effectively always reports a bounded max heap; skip rather than fire a
            // spurious violation when getMax() returns -1 on unusual JVMs or startup edge-cases.
            return skipped("Maximum heap size is not reported by this JVM.");
        }
        Long containerLimit = memory.containerMemoryLimitBytes();
        if (containerLimit == null || containerLimit < MIN_CONTAINER_LIMIT) {
            return pass();
        }
        boolean smallHeap = memory.heapMax() < containerLimit * SMALL_HEAP_PERCENT / 100;
        // Prefer post-GC used% to distinguish retained pressure from reclaimable garbage,
        // consistent with MEM-HEAP-001 and MEM-HEAP-002.
        int usedPercent;
        MemoryContext.PostGcHeapData postGc = context.postGcHeap();
        if (postGc.heapAvailable() && memory.heapMax() > 0) {
            usedPercent = MemoryFormat.percentOf(postGc.heapUsed(), memory.heapMax());
        } else {
            usedPercent = context.heapUsedPercent();
        }
        if (smallHeap && usedPercent >= PRESSURE_PERCENT) {
            int percent = MemoryFormat.percentOf(memory.heapMax(), containerLimit);
            return violation("Max heap " + MemoryFormat.bytes(memory.heapMax()) + " is only " + percent
                    + "% of the container memory limit " + MemoryFormat.bytes(containerLimit) + " and is already "
                    + usedPercent + "% full; raising the heap could use the available container memory.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Memory pools
// ---------------------------------------------------------------------------

final class MetaspaceSaturationRule extends AbstractMemoryRule {

    private static final int THRESHOLD_PERCENT = 85;

    MetaspaceSaturationRule() {
        super(new MemoryRuleDefinition(
                "MEM-POOL-001",
                "Metaspace is close to its maximum",
                MemoryCategory.MEMORY_POOLS,
                "MEDIUM",
                "Flags when the Metaspace pool is nearly full, which can cause OutOfMemoryError: Metaspace, often from classloader leaks or excessive dynamic class generation.",
                "Raise -XX:MaxMetaspaceSize, or investigate classloader leaks and runtime class generation (proxies, scripting).",
                "https://docs.oracle.com/en/java/javase/21/vm/class-metadata.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        Optional<MemoryPoolSnapshot> metaspace = context.memory().metaspacePool();
        if (metaspace.isEmpty() || metaspace.get().max() <= 0) {
            return skipped("Metaspace has no configured maximum (effectively unbounded).");
        }
        MemoryPoolSnapshot pool = metaspace.get();
        if (pool.usedPercent() >= THRESHOLD_PERCENT) {
            return violation("Metaspace is " + pool.usedPercent() + "% full (" + MemoryFormat.bytes(pool.used())
                    + " of " + MemoryFormat.bytes(pool.max()) + ").");
        }
        return pass();
    }
}

final class CodeCacheSaturationRule extends AbstractMemoryRule {

    private static final int THRESHOLD_PERCENT = 90;
    private static final int MAX_REPORTED = 5;

    CodeCacheSaturationRule() {
        super(new MemoryRuleDefinition(
                "MEM-POOL-002",
                "Code cache is close to its maximum",
                MemoryCategory.MEMORY_POOLS,
                "MEDIUM",
                "Flags when any JIT code-cache segment is nearly full. With tiered compilation the cache is split into separate segments (non-nmethods, profiled, non-profiled); a single saturated segment can stop the JIT even when the aggregate looks healthy, after which the application falls back to slower interpreted execution.",
                "Increase -XX:ReservedCodeCacheSize, or reduce the amount of compiled code (fewer megamorphic call sites, less code).",
                "https://docs.oracle.com/en/java/javase/21/vm/codecache.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        List<MemoryPoolSnapshot> segments = context.memory().codeCachePools();
        if (segments.isEmpty()) {
            return skipped("No code-cache pool is exposed by this JVM.");
        }
        List<String> details = new ArrayList<>();
        for (MemoryPoolSnapshot pool : segments) {
            if (pool.max() > 0 && pool.usedPercent() >= THRESHOLD_PERCENT && details.size() < MAX_REPORTED) {
                details.add("Code-cache segment '" + pool.name() + "' is " + pool.usedPercent() + "% full ("
                        + MemoryFormat.bytes(pool.used()) + " of " + MemoryFormat.bytes(pool.max()) + ").");
            }
        }
        return violation(details);
    }
}

final class DirectBufferGrowthRule extends AbstractMemoryRule {

    private static final double LIMIT_FRACTION = 0.8;
    private static final long UNCAPPED_WARN_BYTES = 512L * MemoryFormat.MEGABYTE;
    private static final int UNCAPPED_CONTAINER_PERCENT = 10;

    DirectBufferGrowthRule() {
        super(new MemoryRuleDefinition(
                "MEM-POOL-003",
                "Direct buffer usage is high",
                MemoryCategory.MEMORY_POOLS,
                "LOW",
                "Flags java.nio direct (off-heap) buffer capacity that is near an explicit -XX:MaxDirectMemorySize cap, or that is large relative to the effective HotSpot default cap (which equals max heap when -XX:MaxDirectMemorySize is unset). Direct memory is not bounded by -Xmx and can leak native memory.",
                "Audit direct ByteBuffer allocations and pooling; set or raise -XX:MaxDirectMemorySize and ensure buffers are released.",
                "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        long capacity = memory.directBufferCapacity();
        long max = memory.maxDirectMemoryBytes();
        if (max > 0) {
            if (capacity >= (long) (max * LIMIT_FRACTION)) {
                int percent = MemoryFormat.percentOf(capacity, max);
                return violation("Direct buffers reserve " + MemoryFormat.bytes(capacity) + " (" + percent
                        + "% of -XX:MaxDirectMemorySize " + MemoryFormat.bytes(max) + ") across "
                        + memory.directBufferCount() + " buffers.");
            }
            return pass();
        }
        // -XX:MaxDirectMemorySize is unset; HotSpot defaults the cap to max heap (-Xmx).
        // Compare against that effective cap to avoid false positives on large-heap apps.
        long effectiveCap = memory.heapMax() > 0 ? memory.heapMax() : -1;
        if (effectiveCap > 0) {
            if (capacity >= (long) (effectiveCap * LIMIT_FRACTION)) {
                int percent = MemoryFormat.percentOf(capacity, effectiveCap);
                return violation("Direct buffers reserve " + MemoryFormat.bytes(capacity) + " (" + percent
                        + "% of the effective default cap " + MemoryFormat.bytes(effectiveCap)
                        + ", which equals max heap since -XX:MaxDirectMemorySize is unset) across "
                        + memory.directBufferCount() + " buffers; monitor for native-memory growth.");
            }
            return pass();
        }
        // Neither explicit cap nor heap max is known; fall back to heuristic thresholds.
        long containerThreshold = memory.containerMemoryLimitBytes() == null
                ? Long.MAX_VALUE
                : memory.containerMemoryLimitBytes() * UNCAPPED_CONTAINER_PERCENT / 100;
        if (capacity >= Math.min(UNCAPPED_WARN_BYTES, containerThreshold)) {
            return violation(
                    "Direct buffer pool reserves " + MemoryFormat.bytes(capacity)
                            + " of off-heap memory across " + memory.directBufferCount()
                            + " buffers; -XX:MaxDirectMemorySize is unset and max heap is unknown, so there is no effective cap.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// GC configuration
// ---------------------------------------------------------------------------

final class MissingHeapSizingInContainerRule extends AbstractMemoryRule {

    MissingHeapSizingInContainerRule() {
        super(new MemoryRuleDefinition(
                "MEM-GC-001",
                "Heap sizing is left to default container ergonomics",
                MemoryCategory.GC_CONFIGURATION,
                "INFO",
                "Notes a detected container memory limit with neither -Xmx nor -XX:MaxRAMPercentage set. The JVM is container-aware and defaults the max heap to about 25% of the limit, which is safe but conservative and easy to overlook.",
                "Set -XX:MaxRAMPercentage (or an explicit -Xmx) if you want the heap sized deliberately rather than at the ~25% default.",
                "https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.containerMemoryLimitBytes() == null) {
            return skipped("No container memory limit was detected.");
        }
        boolean explicitMaxHeap = memory.hasJvmArgumentPrefix("-Xmx");
        boolean ramPercentage = memory.hasJvmArgumentPrefix("-XX:MaxRAMPercentage");
        if (!explicitMaxHeap && !ramPercentage) {
            return violation(
                    "Container memory limit " + MemoryFormat.bytes(memory.containerMemoryLimitBytes())
                            + " detected but neither -Xmx nor -XX:MaxRAMPercentage is set; the heap defaults to ~25% of the limit.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Threads
// ---------------------------------------------------------------------------

final class DeadlockDetectedRule extends AbstractMemoryRule {

    DeadlockDetectedRule() {
        super(
                new MemoryRuleDefinition(
                        "MEM-THREAD-001",
                        "Thread deadlock detected",
                        MemoryCategory.THREADS,
                        "CRITICAL",
                        "Detects platform threads blocked in a cycle of lock acquisition; deadlocked threads make no progress and can hang request processing.",
                        "Inspect the deadlocked threads in the Threads panel, then establish a consistent global lock-ordering or use tryLock with timeouts.",
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html#findDeadlockedThreads()"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        ThreadData threads = context.threads();
        if (!threads.deadlockDetected()) {
            return pass();
        }
        return violation("Deadlock detected involving thread id(s): " + threads.deadlockedThreadIds() + ".");
    }
}

final class HighBlockedThreadRatioRule extends AbstractMemoryRule {

    private static final int MIN_BLOCKED = 5;
    private static final double RATIO_THRESHOLD = 0.25;
    private static final int ABSOLUTE_BLOCKED = 20;
    /**
     * Minimum ratio required when the absolute threshold triggers. Without this floor an app with
     * hundreds of threads could fire on 20 blocked threads that represent only a tiny fraction of
     * total threads — a false positive in large thread pools.
     */
    private static final double MIN_RATIO_FOR_ABSOLUTE = 0.10;

    HighBlockedThreadRatioRule() {
        super(new MemoryRuleDefinition(
                "MEM-THREAD-002",
                "High proportion of BLOCKED threads",
                MemoryCategory.THREADS,
                "MEDIUM",
                "Flags when a large share of live threads are BLOCKED waiting for monitors, indicating lock contention that limits throughput. Two trigger paths: (1) ratio path — at least 5 BLOCKED threads and >=25% of all live threads; (2) absolute path — at least 20 BLOCKED threads and >=10% of all live threads (prevents false positives in large pools). Both paths apply to a single snapshot, so a transient burst can trigger the rule; confirm the finding persists before acting.",
                "Identify the contended lock in the Threads panel and reduce the critical section, shard the lock, or use lock-free structures.",
                "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.State.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        ThreadData threads = context.threads();
        if (threads.total() <= 0) {
            return skipped("No thread snapshot is available.");
        }
        int blocked = context.blockedThreadCount();
        double ratio = (double) blocked / threads.total();
        boolean highRatio = blocked >= MIN_BLOCKED && ratio >= RATIO_THRESHOLD;
        boolean absoluteWithRatio = blocked >= ABSOLUTE_BLOCKED && ratio >= MIN_RATIO_FOR_ABSOLUTE;
        if (highRatio || absoluteWithRatio) {
            int percent = MemoryFormat.percentOf(blocked, threads.total());
            return violation(blocked + " of " + threads.total() + " threads (" + percent
                    + "%) are BLOCKED waiting for a monitor, indicating lock contention.");
        }
        return pass();
    }
}

final class ThreadPoolExhaustionGapRule extends AbstractMemoryRule {

    private static final int MIN_GAP = 50;

    ThreadPoolExhaustionGapRule() {
        super(
                new MemoryRuleDefinition(
                        "MEM-THREAD-003",
                        "Peak thread count was far above the current count",
                        MemoryCategory.THREADS,
                        "INFO",
                        "Notes a large gap between the all-time peak platform-thread count and the current live count. The peak is monotonic since JVM start, so this reflects a past burst (pool churn or a transient spike) rather than a current leak; treat it as historical context to correlate with a live thread trend, not as evidence of a present problem.",
                        "Review thread-pool sizing and lifecycle; bound pool sizes and ensure short-lived threads are not created per request if these bursts recur.",
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        ThreadData threads = context.threads();
        if (threads.total() <= 0) {
            return skipped("No thread snapshot is available.");
        }
        int gap = threads.peak() - threads.total();
        if (threads.peak() >= 2 * threads.total() && gap >= MIN_GAP) {
            return violation("Peak threads " + threads.peak() + " was well above the current " + threads.total()
                    + " live threads (gap " + gap
                    + ") at some point since JVM start; this is historical churn, not necessarily a current leak.");
        }
        return pass();
    }
}

final class RunawayCpuThreadRule extends AbstractMemoryRule {

    private static final long CPU_THRESHOLD_MILLIS = 60_000L;
    private static final double UPTIME_FRACTION = 0.5;
    private static final int MAX_REPORTED = 5;

    RunawayCpuThreadRule() {
        super(
                new MemoryRuleDefinition(
                        "MEM-THREAD-004",
                        "Runnable threads with very high lifetime CPU usage",
                        MemoryCategory.THREADS,
                        "INFO",
                        "Highlights RUNNABLE threads whose accumulated CPU time is a large fraction of the JVM's uptime, i.e. they have kept a core busy for much of the process's life. CPU time is cumulative since the thread started, so this is a hot-loop candidate to investigate, not a confirmed problem.",
                        "Correlate with two consecutive thread snapshots; if CPU keeps climbing for the same thread, profile its stack for a hot or spinning loop.",
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        ThreadData threads = context.threads();
        if (!threads.cpuTimeSupported()) {
            return skipped("Per-thread CPU timing is not supported or not enabled on this JVM.");
        }
        long uptimeMillis = context.runtime().uptimeMillis();
        if (uptimeMillis <= 0) {
            return skipped("JVM uptime is not available to normalize thread CPU time.");
        }
        long minCpuMillis = Math.max(CPU_THRESHOLD_MILLIS, (long) (uptimeMillis * UPTIME_FRACTION));
        List<ThreadInfoDto> hot = new ArrayList<>();
        for (ThreadInfoDto thread : threads.threads()) {
            if ("RUNNABLE".equalsIgnoreCase(thread.state())
                    && thread.cpuTimeMillis() != null
                    && thread.cpuTimeMillis() >= minCpuMillis) {
                hot.add(thread);
            }
        }
        if (hot.isEmpty()) {
            return pass();
        }
        hot.sort((left, right) -> Long.compare(right.cpuTimeMillis(), left.cpuTimeMillis()));
        List<String> details = new ArrayList<>();
        for (ThreadInfoDto thread : hot.subList(0, Math.min(MAX_REPORTED, hot.size()))) {
            int percent = MemoryFormat.percentOf(thread.cpuTimeMillis(), uptimeMillis);
            details.add("Thread '" + thread.name() + "' (id " + thread.id() + ") has used "
                    + (thread.cpuTimeMillis() / 1000) + "s of CPU while RUNNABLE (" + percent + "% of JVM uptime).");
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// Heap content
// ---------------------------------------------------------------------------

final class BigObjectsRule extends AbstractMemoryRule {

    private static final long BYTES_PER_INSTANCE_THRESHOLD = 512L * MemoryFormat.KILOBYTE;
    private static final long MIN_TOTAL_BYTES = 10L * MemoryFormat.MEGABYTE;
    private static final int MAX_REPORTED = 5;

    BigObjectsRule() {
        super(new MemoryRuleDefinition(
                "MEM-CONTENT-001",
                "Classes with very large average instance size",
                MemoryCategory.HEAP_CONTENT,
                "INFO",
                "Surfaces classes whose average shallow size per instance is large; these big objects dominate allocation, can become G1 humongous allocations, and may fragment the heap.",
                "Review whether these objects can be streamed, paged, or pooled instead of held whole in memory.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        if (!context.heapContent().available()) {
            return skipped("No class histogram is available; run Heap Dump analysis or re-scan to collect one.");
        }
        List<HeapClassHistogramEntryDto> candidates = new ArrayList<>();
        for (HeapClassHistogramEntryDto entry : context.heapContent().histogram()) {
            if (entry.instances() <= 0 || entry.bytes() < MIN_TOTAL_BYTES) {
                continue;
            }
            if (entry.bytes() / entry.instances() >= BYTES_PER_INSTANCE_THRESHOLD) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return pass();
        }
        candidates.sort((left, right) -> Long.compare(
                right.bytes() / Math.max(1, right.instances()), left.bytes() / Math.max(1, left.instances())));
        List<String> details = new ArrayList<>();
        for (HeapClassHistogramEntryDto entry : candidates.subList(0, Math.min(MAX_REPORTED, candidates.size()))) {
            long perInstance = entry.bytes() / entry.instances();
            details.add(entry.className() + " averages " + MemoryFormat.bytes(perInstance) + "/instance across "
                    + entry.instances() + " instances (" + MemoryFormat.bytes(entry.bytes()) + " total).");
        }
        return violation(details);
    }
}

final class CollectionBloatRule extends AbstractMemoryRule {

    private static final long ABSOLUTE_THRESHOLD = 50L * MemoryFormat.MEGABYTE;
    private static final int SHARE_PERCENT_THRESHOLD = 10;
    private static final long MEDIUM_ABSOLUTE_THRESHOLD = 100L * MemoryFormat.MEGABYTE;
    private static final int MEDIUM_SHARE_PERCENT = 25;
    private static final int MAX_REPORTED = 5;

    private static final List<String> COLLECTION_CLASS_PREFIXES = List.of(
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.IdentityHashMap",
            "java.util.WeakHashMap",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.ArrayDeque",
            "java.util.PriorityQueue",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.concurrent.CopyOnWriteArraySet",
            "java.util.concurrent.LinkedBlockingQueue",
            "java.util.concurrent.ArrayBlockingQueue");

    CollectionBloatRule() {
        super(new MemoryRuleDefinition(
                "MEM-CONTENT-002",
                "Collections occupy a large share of the heap",
                MemoryCategory.HEAP_CONTENT,
                "MEDIUM",
                "Flags JDK collection or map classes (including their node/entry backing structures) that occupy a large amount of heap by shallow histogram bytes. Severity is raised when the combined collection footprint is a large share of the sampled heap and softened when it is a single shallow contributor, since a large collection is not necessarily an unbounded leak. Array backing storage (for example ArrayList's Object[]) is reported separately by MEM-CONTENT-004.",
                "Confirm whether the offending collection is bounded; if it is meant to be a cache, give it an eviction policy or size limit and verify entries are removed.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        if (!context.heapContent().available()) {
            return skipped("No class histogram is available; run Heap Dump analysis or re-scan to collect one.");
        }
        long totalBytes = context.heapContent().totalBytes();
        List<HeapClassHistogramEntryDto> candidates = new ArrayList<>();
        long candidateBytes = 0;
        for (HeapClassHistogramEntryDto entry : context.heapContent().histogram()) {
            if (!isCollectionClass(entry.className())) {
                continue;
            }
            int sharePercent = MemoryFormat.percentOf(entry.bytes(), totalBytes);
            if (entry.bytes() >= ABSOLUTE_THRESHOLD || sharePercent >= SHARE_PERCENT_THRESHOLD) {
                candidates.add(entry);
                candidateBytes += entry.bytes();
            }
        }
        if (candidates.isEmpty()) {
            return pass();
        }
        candidates.sort((left, right) -> Long.compare(right.bytes(), left.bytes()));
        long largest = candidates.get(0).bytes();
        int combinedSharePercent = MemoryFormat.percentOf(candidateBytes, totalBytes);
        boolean corroborated = largest >= MEDIUM_ABSOLUTE_THRESHOLD || combinedSharePercent >= MEDIUM_SHARE_PERCENT;
        String severity = corroborated ? MemoryRuleSupport.MEDIUM : MemoryRuleSupport.LOW;
        List<String> details = new ArrayList<>();
        for (HeapClassHistogramEntryDto entry : candidates.subList(0, Math.min(MAX_REPORTED, candidates.size()))) {
            int sharePercent = MemoryFormat.percentOf(entry.bytes(), totalBytes);
            details.add(entry.className() + " occupies " + MemoryFormat.bytes(entry.bytes()) + " (" + sharePercent
                    + "% of histogram bytes, shallow) across " + entry.instances()
                    + " instances; confirm this collection is bounded.");
        }
        return violation(severity, details);
    }

    private static boolean isCollectionClass(String className) {
        for (String prefix : COLLECTION_CLASS_PREFIXES) {
            if (className.equals(prefix)) {
                return true;
            }
            if (className.length() > prefix.length()
                    && className.charAt(prefix.length()) == '$'
                    && className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

final class DominantClassRule extends AbstractMemoryRule {

    private static final int SHARE_PERCENT_THRESHOLD = 25;

    DominantClassRule() {
        super(new MemoryRuleDefinition(
                "MEM-CONTENT-003",
                "A single class dominates the sampled heap",
                MemoryCategory.HEAP_CONTENT,
                "LOW",
                "Flags when one class (excluding all array classes — primitive arrays such as byte[]/char[] and Object[] or other reference arrays — which are routinely dominant and are reported in aggregate by MEM-CONTENT-004) occupies a large fraction of the sampled heap by shallow bytes; a strongly dominant top class is worth understanding even if expected.",
                "Confirm the dominant class is expected; if not, trace its references to find what keeps the instances alive.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        if (!context.heapContent().available()) {
            return skipped("No class histogram is available; run Heap Dump analysis or re-scan to collect one.");
        }
        long totalBytes = context.heapContent().totalBytes();
        if (totalBytes <= 0) {
            return skipped("The sampled heap histogram is empty.");
        }
        return context.heapContent().histogram().stream()
                .filter(entry -> !isArrayClass(entry.className()))
                .findFirst()
                .filter(entry -> MemoryFormat.percentOf(entry.bytes(), totalBytes) >= SHARE_PERCENT_THRESHOLD)
                .map(entry -> violation(entry.className() + " occupies "
                        + MemoryFormat.percentOf(entry.bytes(), totalBytes) + "% of the sampled heap, shallow ("
                        + MemoryFormat.bytes(entry.bytes()) + ")."))
                .orElseGet(this::pass);
    }

    /**
     * Returns {@code true} for any array class. After histogram normalisation every array type has
     * a name ending in {@code []} (e.g. {@code byte[]}, {@code Object[]}, {@code com.example.Foo[]}).
     * Internal JVM descriptor forms starting with {@code [} are also matched as a safety net.
     */
    static boolean isArrayClass(String className) {
        return className != null && (className.endsWith("[]") || className.startsWith("["));
    }
}

// ---------------------------------------------------------------------------
// Class loading
// ---------------------------------------------------------------------------

final class ExcessiveLoadedClassesRule extends AbstractMemoryRule {

    private static final int LOADED_THRESHOLD = 50_000;
    private static final int UNLOAD_RATIO_DIVISOR = 100;

    ExcessiveLoadedClassesRule() {
        super(new MemoryRuleDefinition(
                "MEM-CLASS-001",
                "Very large number of loaded classes with little unloading",
                MemoryCategory.CLASS_LOADING,
                "INFO",
                "Flags a high loaded-class count combined with little or no class unloading, which can indicate a classloader leak or runaway dynamic class generation and pressures Metaspace. A large class count that is matched by active unloading is treated as a legitimately large application instead.",
                "If the application does not legitimately use this many classes, look for classloader leaks (redeploys, scripting, proxy generation).",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.ClassLoadingData classLoading = context.classLoading();
        boolean manyLoaded = classLoading.loadedClasses() >= LOADED_THRESHOLD;
        boolean littleUnloading = classLoading.unloadedClasses() < classLoading.loadedClasses() / UNLOAD_RATIO_DIVISOR;
        if (manyLoaded && littleUnloading) {
            return violation(classLoading.loadedClasses() + " classes are currently loaded ("
                    + classLoading.totalLoadedClasses() + " loaded and " + classLoading.unloadedClasses()
                    + " unloaded since start); watch for classloader leaks and Metaspace pressure.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Native memory
// ---------------------------------------------------------------------------

final class CommittedFootprintNearContainerLimitRule extends AbstractMemoryRule {

    private static final int THRESHOLD_PERCENT = 90;

    CommittedFootprintNearContainerLimitRule() {
        super(new MemoryRuleDefinition(
                "MEM-FOOTPRINT-001",
                "Committed native footprint is close to the container limit",
                MemoryCategory.NATIVE_MEMORY,
                "HIGH",
                "Estimates the JVM's committed native-memory budget (committed heap, committed non-heap such as Metaspace and code cache, direct buffers, and an approximate thread-stack reservation) against the detected container memory limit. When this estimate approaches the limit the container can be OOM-killed by the kernel even though the heap alone looks healthy. The estimate is approximate and excludes some JVM/native overhead (GC structures, JIT, native libraries).",
                "Lower -Xmx/-XX:MaxRAMPercentage, reduce thread counts or direct-buffer use, or raise the container memory limit so the total committed footprint keeps headroom.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        Long limit = memory.containerMemoryLimitBytes();
        if (limit == null || limit <= 0) {
            return skipped("No container memory limit was detected.");
        }
        long stacks = (long) context.threads().total() * context.runtime().threadStackBytes();
        long footprint = Math.max(0, memory.heapCommitted())
                + Math.max(0, memory.nonHeapCommitted())
                + Math.max(0, memory.directBufferCapacity())
                + Math.max(0, stacks);
        if (MemoryFormat.percentOf(footprint, limit) >= THRESHOLD_PERCENT) {
            int percent = MemoryFormat.percentOf(footprint, limit);
            return violation("Estimated committed footprint " + MemoryFormat.bytes(footprint) + " is " + percent
                    + "% of the container memory limit " + MemoryFormat.bytes(limit) + " (committed heap "
                    + MemoryFormat.bytes(memory.heapCommitted()) + " + committed non-heap "
                    + MemoryFormat.bytes(memory.nonHeapCommitted()) + " + direct buffers "
                    + MemoryFormat.bytes(memory.directBufferCapacity()) + " + ~"
                    + context.threads().total()
                    + " thread stacks " + MemoryFormat.bytes(stacks)
                    + "); the container risks an out-of-memory kill.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// GC configuration (lifetime overhead and sizing)
// ---------------------------------------------------------------------------

final class HighGcOverheadRule extends AbstractMemoryRule {

    private static final long MIN_UPTIME_MILLIS = 600_000L;
    private static final int THRESHOLD_PERCENT = 10;

    HighGcOverheadRule() {
        super(
                new MemoryRuleDefinition(
                        "MEM-GC-002",
                        "Cumulative GC time is a large share of uptime",
                        MemoryCategory.GC_CONFIGURATION,
                        "MEDIUM",
                        "Compares total time spent in garbage collection since JVM start against the JVM uptime. A high lifetime ratio is a classic sign of an undersized heap or an excessive allocation rate. This is a cumulative average and can be skewed by a one-off startup spike, so corroborate with live GC metrics.",
                        "Increase the heap (-Xmx/-XX:MaxRAMPercentage), reduce the allocation rate, or review the collector choice if GC consistently consumes this much time.",
                        "https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.RuntimeData runtime = context.runtime();
        if (runtime.uptimeMillis() < MIN_UPTIME_MILLIS) {
            return skipped("JVM uptime is too short to assess lifetime GC overhead.");
        }
        if (runtime.gcCollectionTimeMillis() < 0) {
            return skipped("Cumulative GC time is not reported by this JVM.");
        }
        int percent = MemoryFormat.percentOf(runtime.gcCollectionTimeMillis(), runtime.uptimeMillis());
        if (percent >= THRESHOLD_PERCENT) {
            return violation("GC has used " + (runtime.gcCollectionTimeMillis() / 1000) + "s across "
                    + runtime.gcCollectionCount() + " collections, " + percent + "% of the "
                    + (runtime.uptimeMillis() / 1000) + "s uptime; the heap may be undersized or allocation-heavy.");
        }
        return pass();
    }
}

final class UnequalInitialAndMaxHeapRule extends AbstractMemoryRule {

    UnequalInitialAndMaxHeapRule() {
        super(new MemoryRuleDefinition(
                "MEM-HEAP-005",
                "Initial and maximum heap differ for a low-latency collector",
                MemoryCategory.GC_CONFIGURATION,
                "INFO",
                "For low-latency collectors (ZGC, Shenandoah), a smaller -Xms than -Xmx makes the JVM grow and re-commit the heap on demand, which can add latency and commit/uncommit churn. Equal -Xms and -Xmx keep the heap fully committed for steady-state, latency-sensitive services. When -Xms is unset, the JVM's reported initial heap size (ergonomic default, typically ~1/64 of RAM) is used as the effective -Xms.",
                "For latency-sensitive services using ZGC or Shenandoah, set -Xms equal to -Xmx so the heap is fully committed up front; also consider -XX:+AlwaysPreTouch to touch every heap page at startup and avoid OS demand-paging latency during warmup.",
                "https://docs.oracle.com/en/java/javase/21/gctuning/z-garbage-collector.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        boolean lowLatency = memory.usesGarbageCollector("zgc")
                || memory.usesGarbageCollector("z generational")
                || memory.usesGarbageCollector("shenandoah");
        if (!lowLatency) {
            return skipped("The active collector is not a low-latency collector (ZGC/Shenandoah).");
        }
        long initial = context.runtime().initialHeapBytes();
        if (initial <= 0 || memory.heapMax() <= 0) {
            return skipped("Initial or maximum heap size is not available.");
        }
        if (initial < memory.heapMax()) {
            return violation("-Xms " + MemoryFormat.bytes(initial) + " is smaller than -Xmx "
                    + MemoryFormat.bytes(memory.heapMax())
                    + "; for a low-latency collector, setting them equal avoids heap-resize latency.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Heap pressure (sizing thresholds)
// ---------------------------------------------------------------------------

final class CompressedOopsCliffRule extends AbstractMemoryRule {

    private static final long DEFAULT_ALIGNMENT_BYTES = 8L;
    private static final long COMPRESSED_OOPS_HEAP_PER_ALIGNMENT_BYTE = 4L * MemoryFormat.GIGABYTE;

    CompressedOopsCliffRule() {
        super(new MemoryRuleDefinition(
                "MEM-HEAP-004",
                "Max heap is just above the compressed-oops threshold",
                MemoryCategory.HEAP_PRESSURE,
                "INFO",
                "Notes a max heap just above the boundary where the JVM disables compressed ordinary object pointers, after which 64-bit references take more space and a heap just over the boundary can hold fewer live objects than one capped just below it. The boundary defaults to ~32 GiB but scales with -XX:ObjectAlignmentInBytes. The note is skipped for ZGC (which does not use compressed oops) and when compressed oops are explicitly disabled.",
                "Either cap the heap just below the compressed-oops boundary, or grow it well past this range (and scale out) when a larger heap is genuinely required.",
                "https://wiki.openjdk.org/display/HotSpot/CompressedOops"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        long heapMax = memory.heapMax();
        if (heapMax <= 0) {
            return pass();
        }
        if (memory.usesGarbageCollector("zgc") || memory.usesGarbageCollector("z generational")) {
            return skipped("ZGC does not use compressed object pointers, so the heap-size cliff does not apply.");
        }
        // Ground-truth check: try the live VM option before falling back to the arg heuristic.
        Boolean useCompressedOops = context.runtime().useCompressedOops();
        if (useCompressedOops != null) {
            if (!useCompressedOops) {
                return skipped("Compressed object pointers are disabled (UseCompressedOops=false).");
            }
        } else if (memory.hasJvmArgument("-XX:-UseCompressedOops")) {
            return skipped("Compressed object pointers are explicitly disabled (-XX:-UseCompressedOops).");
        }
        long alignment = parseObjectAlignmentBytes(memory.inputArguments());
        long boundary = alignment * COMPRESSED_OOPS_HEAP_PER_ALIGNMENT_BYTE;
        long upperBound = boundary + boundary / 2;
        if (heapMax > boundary && heapMax <= upperBound) {
            return violation("Max heap " + MemoryFormat.bytes(heapMax) + " is just above the ~"
                    + MemoryFormat.bytes(boundary) + " compressed-oops boundary"
                    + (alignment == DEFAULT_ALIGNMENT_BYTES ? "" : " (object alignment " + alignment + " bytes)")
                    + "; a heap at or just below the boundary may hold more objects for the same memory.");
        }
        return pass();
    }

    private static long parseObjectAlignmentBytes(List<String> inputArguments) {
        String prefix = "-XX:ObjectAlignmentInBytes=";
        for (String arg : inputArguments) {
            if (arg != null && arg.startsWith(prefix)) {
                try {
                    long parsed = Long.parseLong(arg.substring(prefix.length()).trim());
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to the default alignment
                }
            }
        }
        return DEFAULT_ALIGNMENT_BYTES;
    }
}

final class PendingFinalizationBacklogRule extends AbstractMemoryRule {

    private static final int THRESHOLD = 1_000;

    PendingFinalizationBacklogRule() {
        super(new MemoryRuleDefinition(
                "MEM-HEAP-006",
                "Objects are backing up awaiting finalization",
                MemoryCategory.HEAP_PRESSURE,
                "LOW",
                "Flags a large backlog of objects pending finalization. The finalizer thread cannot keep up, so these objects (and any native resources they hold) are retained longer than expected. Finalization is deprecated for removal (JEP 421); a backlog usually points to legacy finalizers.",
                "Replace finalizers with try-with-resources, java.lang.ref.Cleaner, or explicit close() methods, and ensure resources are released promptly.",
                "https://openjdk.org/jeps/421"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        int pending = context.runtime().objectPendingFinalizationCount();
        if (pending >= THRESHOLD) {
            return violation(pending + " objects are pending finalization; the finalizer thread is not keeping up"
                    + " and is retaining memory and native resources.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Memory pools (Metaspace ceiling)
// ---------------------------------------------------------------------------

final class UnboundedMetaspaceInContainerRule extends AbstractMemoryRule {

    private static final long MIN_USED = 128L * MemoryFormat.MEGABYTE;

    UnboundedMetaspaceInContainerRule() {
        super(new MemoryRuleDefinition(
                "MEM-POOL-004",
                "Metaspace is unbounded inside a memory-limited container",
                MemoryCategory.MEMORY_POOLS,
                "LOW",
                "Flags a container memory limit with no -XX:MaxMetaspaceSize while Metaspace is already sizable. Unbounded Metaspace can grow until the container is OOM-killed by the kernel instead of failing with a graceful OutOfMemoryError: Metaspace.",
                "Set -XX:MaxMetaspaceSize to a sensible ceiling so class-metadata growth fails fast inside the JVM rather than triggering a kernel OOM kill.",
                "https://docs.oracle.com/en/java/javase/21/vm/class-metadata.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.containerMemoryLimitBytes() == null) {
            return skipped("No container memory limit was detected.");
        }
        Optional<MemoryPoolSnapshot> metaspace = memory.metaspacePool();
        if (metaspace.isEmpty()) {
            return skipped("No Metaspace pool is exposed by this JVM.");
        }
        MemoryPoolSnapshot pool = metaspace.get();
        if (pool.max() <= 0 && pool.used() >= MIN_USED) {
            return violation("Metaspace has no -XX:MaxMetaspaceSize and already uses " + MemoryFormat.bytes(pool.used())
                    + " inside a container limited to " + MemoryFormat.bytes(memory.containerMemoryLimitBytes())
                    + "; unbounded growth risks a kernel OOM kill.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Class loading (churn)
// ---------------------------------------------------------------------------

final class ClassLoadingChurnRule extends AbstractMemoryRule {

    private static final long UNLOAD_THRESHOLD = 50_000L;
    private static final long MIN_UPTIME_MILLIS = 1_800_000L;
    private static final long UNLOAD_RATE_PER_MIN = 1_000L;

    ClassLoadingChurnRule() {
        super(new MemoryRuleDefinition(
                "MEM-CLASS-002",
                "High class-loading churn",
                MemoryCategory.CLASS_LOADING,
                "LOW",
                "Flags heavy class unloading, either a large absolute count or a high sustained unload rate over the JVM's lifetime. Persistent churn points to dynamic proxy/CGLIB generation, scripting, or redeploy-style classloader cycling that strains Metaspace and the GC.",
                "Identify the source of dynamic class generation (proxies, scripting engines, repeated context refreshes) and cache or bound it.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        long unloaded = context.classLoading().unloadedClasses();
        if (unloaded >= UNLOAD_THRESHOLD) {
            return violation(unloaded + " classes have been unloaded since start, indicating heavy classloader churn.");
        }
        long uptimeMillis = context.runtime().uptimeMillis();
        if (uptimeMillis >= MIN_UPTIME_MILLIS) {
            long minutes = uptimeMillis / 60_000L;
            long ratePerMin = minutes > 0 ? unloaded / minutes : 0;
            if (ratePerMin >= UNLOAD_RATE_PER_MIN) {
                return violation("Classes are unloading at about " + ratePerMin + "/min (" + unloaded + " over "
                        + minutes + " min), indicating sustained classloader churn.");
            }
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Native memory (thread stacks)
// ---------------------------------------------------------------------------

final class PlatformThreadStackReservationRule extends AbstractMemoryRule {

    private static final long ABSOLUTE_THRESHOLD = MemoryFormat.GIGABYTE;
    private static final int CONTAINER_PERCENT_THRESHOLD = 20;

    PlatformThreadStackReservationRule() {
        super(new MemoryRuleDefinition(
                "MEM-FOOTPRINT-002",
                "Platform thread stacks reserve a large amount of native memory",
                MemoryCategory.NATIVE_MEMORY,
                "HIGH",
                "Estimates the native memory reserved for platform thread stacks (live platform threads times the -Xss/-XX:ThreadStackSize reservation) and flags when stacks alone are a large contributor to the off-heap footprint. This is reservation, not necessarily resident memory, and it is the stacks-only early warning behind the broader MEM-FOOTPRINT-001 total-footprint estimate. Virtual threads are excluded because their stacks live on the heap. Severity is HIGH when a container memory limit is detected (reservation directly competes with the cgroup limit) and MEDIUM otherwise (virtual-memory reservation rarely equals resident set size without a container ceiling).",
                "Reduce the platform thread count (bound pools, prefer virtual threads or async I/O) or lower an oversized -Xss so thread stacks do not dominate native memory.",
                "https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        int platformThreads = context.threads().total();
        if (platformThreads <= 0) {
            return skipped("No thread snapshot is available.");
        }
        long stackBytes = context.runtime().threadStackBytes();
        long reserved = (long) platformThreads * stackBytes;
        Long limit = context.memory().containerMemoryLimitBytes();
        boolean relativeBreach = limit != null && limit > 0 && reserved >= limit * CONTAINER_PERCENT_THRESHOLD / 100;
        boolean absoluteBreach = reserved >= ABSOLUTE_THRESHOLD;
        if (relativeBreach || absoluteBreach) {
            String relativeNote = limit != null && limit > 0
                    ? " (" + MemoryFormat.percentOf(reserved, limit) + "% of the container memory limit "
                            + MemoryFormat.bytes(limit) + ")"
                    : "";
            // Reservation without a container limit is virtual memory, not necessarily resident;
            // downgrade to MEDIUM to avoid over-alarming on apps that simply have many threads.
            String severity = relativeBreach ? MemoryRuleSupport.HIGH : MemoryRuleSupport.MEDIUM;
            return violation(
                    severity,
                    platformThreads + " platform threads reserve about " + MemoryFormat.bytes(reserved)
                            + " of stack memory at " + MemoryFormat.bytes(stackBytes) + " each" + relativeNote
                            + "; thread stacks are a large contributor to the native footprint.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Heap content (array dominance)
// ---------------------------------------------------------------------------

final class ArrayDominanceRule extends AbstractMemoryRule {

    private static final int SHARE_PERCENT_THRESHOLD = 50;
    private static final int MAX_REPORTED = 5;

    ArrayDominanceRule() {
        super(new MemoryRuleDefinition(
                "MEM-CONTENT-004",
                "Arrays dominate the sampled heap",
                MemoryCategory.HEAP_CONTENT,
                "INFO",
                "Flags when array classes (primitive arrays such as byte[]/char[], Object[], and map-node arrays) together occupy a large share of the post-GC histogram bytes. Array dominance is often normal (byte[] backs strings and I/O buffers, Object[] backs lists and maps), but it complements the collection view in MEM-CONTENT-002 and the single-dominant-class view in MEM-CONTENT-003 by surfacing aggregate backing storage that those rules exclude.",
                "Inspect the top array classes below; if growth is unexpected, trace what retains the backing arrays (oversized buffers, unbounded lists/maps, or duplicated byte[]/char[] data).",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        if (!context.heapContent().available()) {
            return skipped("No class histogram is available; run Heap Dump analysis or re-scan to collect one.");
        }
        long totalBytes = context.heapContent().totalBytes();
        if (totalBytes <= 0) {
            return skipped("The sampled heap histogram is empty.");
        }
        List<HeapClassHistogramEntryDto> arrays = new ArrayList<>();
        long arrayBytes = 0;
        for (HeapClassHistogramEntryDto entry : context.heapContent().histogram()) {
            if (entry.className() != null && entry.className().endsWith("[]")) {
                arrays.add(entry);
                arrayBytes += entry.bytes();
            }
        }
        int sharePercent = MemoryFormat.percentOf(arrayBytes, totalBytes);
        if (arrays.isEmpty() || sharePercent < SHARE_PERCENT_THRESHOLD) {
            return pass();
        }
        arrays.sort((left, right) -> Long.compare(right.bytes(), left.bytes()));
        List<String> details = new ArrayList<>();
        details.add("Array classes occupy " + sharePercent + "% of the sampled heap (" + MemoryFormat.bytes(arrayBytes)
                + " of " + MemoryFormat.bytes(totalBytes) + ", shallow).");
        for (HeapClassHistogramEntryDto entry : arrays.subList(0, Math.min(MAX_REPORTED, arrays.size()))) {
            details.add(entry.className() + ": " + MemoryFormat.bytes(entry.bytes()) + " across " + entry.instances()
                    + " instances.");
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// GC configuration (recent overhead)
// ---------------------------------------------------------------------------

final class RecentGcOverheadRule extends AbstractMemoryRule {

    private static final long MIN_WINDOW_MILLIS = 10_000L;
    private static final int HIGH_THRESHOLD_PERCENT = 25;
    private static final int THRESHOLD_PERCENT = 10;

    RecentGcOverheadRule() {
        super(
                new MemoryRuleDefinition(
                        "MEM-GC-003",
                        "Recent GC overhead is high",
                        MemoryCategory.GC_CONFIGURATION,
                        "MEDIUM",
                        "Compares time spent in garbage collection against wall-clock time over the interval between the last two scans, so it reflects current allocation and heap pressure rather than the lifetime average reported by MEM-GC-002. The scan's own forced histogram GC is excluded from the window. The first scan only establishes a baseline.",
                        "Re-run the scan after a representative workload; if recent GC overhead stays high, increase the heap (-Xmx/-XX:MaxRAMPercentage), reduce the allocation rate, or review the collector choice.",
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/GarbageCollectorMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.GcTrend trend = context.gcTrend();
        if (!trend.available()) {
            return skipped("No previous scan to compare; re-run the scan to measure recent GC overhead.");
        }
        if (trend.deltaUptimeMillis() < MIN_WINDOW_MILLIS) {
            return skipped("Too little time has passed since the last scan to measure recent GC overhead.");
        }
        int percent = MemoryFormat.percentOf(trend.deltaGcTimeMillis(), trend.deltaUptimeMillis());
        if (percent < THRESHOLD_PERCENT) {
            return pass();
        }
        String detail = "GC used " + trend.deltaGcTimeMillis() + " ms (" + percent + "%) of the last "
                + (trend.deltaUptimeMillis() / 1000) + "s across " + trend.deltaGcCount()
                + " collections since the previous scan; the heap may be undersized or allocation-heavy.";
        String severity = percent >= HIGH_THRESHOLD_PERCENT ? MemoryRuleSupport.HIGH : MemoryRuleSupport.MEDIUM;
        return violation(severity, detail);
    }
}

// ---------------------------------------------------------------------------
// Memory pools (Compressed Class Space)
// ---------------------------------------------------------------------------

final class CompressedClassSpaceRule extends AbstractMemoryRule {

    private static final int THRESHOLD_PERCENT = 85;

    CompressedClassSpaceRule() {
        super(new MemoryRuleDefinition(
                "MEM-POOL-005",
                "Compressed Class Space is close to its maximum",
                MemoryCategory.MEMORY_POOLS,
                "MEDIUM",
                "Flags when the Compressed Class Space pool reaches 85% of its cap. This space holds class metadata in the compressed-oops range and defaults to 1 GiB; unlike Metaspace it has a hard cap even when -XX:MaxMetaspaceSize is unset. Exhaustion causes OutOfMemoryError: Compressed class space.",
                "Increase -XX:CompressedClassSpaceSize (or reduce dynamic class generation); also set -XX:MaxMetaspaceSize so the broader Metaspace growth is bounded.",
                "https://docs.oracle.com/en/java/javase/21/vm/class-metadata.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        Optional<MemoryContext.MemoryPoolSnapshot> pool = context.memory().compressedClassSpacePool();
        if (pool.isEmpty()) {
            return skipped(
                    "No Compressed Class Space pool is exposed by this JVM (ZGC and non-HotSpot JVMs may not use it).");
        }
        MemoryContext.MemoryPoolSnapshot ccs = pool.get();
        if (ccs.max() <= 0) {
            return skipped("Compressed Class Space does not report a maximum size on this JVM.");
        }
        if (ccs.usedPercent() >= THRESHOLD_PERCENT) {
            return violation("Compressed Class Space is " + ccs.usedPercent() + "% full ("
                    + MemoryFormat.bytes(ccs.used()) + " of " + MemoryFormat.bytes(ccs.max())
                    + "); exhaustion causes OutOfMemoryError: Compressed class space.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Native memory (container current usage)
// ---------------------------------------------------------------------------

final class ContainerMemoryPressureRule extends AbstractMemoryRule {

    private static final int THRESHOLD_PERCENT = 90;

    ContainerMemoryPressureRule() {
        super(new MemoryRuleDefinition(
                "MEM-FOOTPRINT-003",
                "Container memory usage is near the cgroup limit",
                MemoryCategory.NATIVE_MEMORY,
                "HIGH",
                "Reads the current cgroup memory usage (memory.current for cgroup v2, memory.usage_in_bytes for v1) and compares it against the detected container memory limit. When usage approaches the limit the container is at risk of an immediate OOM kill by the kernel, which is abrupt and does not trigger JVM OutOfMemoryError handling.",
                "Lower -Xmx/-XX:MaxRAMPercentage, reduce non-heap memory (thread stacks, Metaspace, direct buffers), or raise the container memory limit to restore headroom.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        Long limit = memory.containerMemoryLimitBytes();
        if (limit == null || limit <= 0) {
            return skipped("No container memory limit was detected.");
        }
        Long current = memory.containerMemoryCurrentBytes();
        if (current == null || current <= 0) {
            return skipped("Current container memory usage is not available (no cgroup files readable).");
        }
        int percent = MemoryFormat.percentOf(current, limit);
        if (percent >= THRESHOLD_PERCENT) {
            return violation("Container memory usage is " + percent + "% of the cgroup limit ("
                    + MemoryFormat.bytes(current) + " of " + MemoryFormat.bytes(limit)
                    + "); the process is at immediate risk of an OOM kill.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// GC configuration (Serial GC on multi-core)
// ---------------------------------------------------------------------------

final class SerialGcOnMultiCoreRule extends AbstractMemoryRule {

    SerialGcOnMultiCoreRule() {
        super(new MemoryRuleDefinition(
                "MEM-GC-004",
                "Serial GC selected on a multi-core system",
                MemoryCategory.GC_CONFIGURATION,
                "LOW",
                "Detects the Serial GC collector (bean names 'Copy' and/or 'MarkSweepCompact') running on a JVM with two or more available processors. Serial GC is single-threaded and can be selected by container ergonomics when the JVM sees only one CPU, but it underutilises multi-core hosts and causes long STW pauses at scale.",
                "Switch to G1 (-XX:+UseG1GC), ZGC (-XX:+UseZGC), or Parallel GC (-XX:+UseParallelGC) to use all available cores, unless binary size or footprint constraints explicitly require Serial.",
                "https://docs.oracle.com/en/java/javase/21/gctuning/available-collectors.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        boolean serialGc = memory.usesGarbageCollector("copy") || memory.usesGarbageCollector("marksweepcompact");
        if (!serialGc) {
            return pass();
        }
        int cpus = context.runtime().availableProcessors();
        if (cpus < 2) {
            return skipped("Serial GC is expected on a single-CPU system.");
        }
        return violation("Serial GC is active ('Copy'/'MarkSweepCompact') on a " + cpus
                + "-CPU system; Serial GC is single-threaded and will leave cores idle during collection pauses.");
    }
}

// ---------------------------------------------------------------------------
// GC configuration (G1 Full GC frequency)
// ---------------------------------------------------------------------------

final class G1FullGcFrequencyRule extends AbstractMemoryRule {

    G1FullGcFrequencyRule() {
        super(new MemoryRuleDefinition(
                "MEM-GC-005",
                "G1 Full GC occurred between scans",
                MemoryCategory.GC_CONFIGURATION,
                "MEDIUM",
                "Detects an increase in the 'G1 Old Generation' (Full GC) collection count between two consecutive scans. G1 Full GCs are single-threaded STW stop-the-world pauses triggered by to-space exhaustion, humongous-allocation failure, or concurrent mark failure; even one Full GC per scan window is a sign of heap or tuning pressure. The first scan only establishes a baseline.",
                "Increase -Xmx or tune -XX:G1HeapRegionSize to reduce humongous allocations; consider -XX:G1ReservePercent and -XX:InitiatingHeapOccupancyPercent to give G1 more head room for concurrent marking.",
                "https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.GcTrend trend = context.gcTrend();
        if (!trend.available()) {
            return skipped("No previous scan to compare; re-run the scan to measure G1 Full GC frequency.");
        }
        long fullGcDelta = trend.perCollectorDeltas().getOrDefault("G1 Old Generation", 0L);
        if (fullGcDelta <= 0) {
            return pass();
        }
        return violation("G1 Full GC occurred " + fullGcDelta + " time(s) since the last scan (G1 Old Generation"
                + " collection count increased); Full GCs are single-threaded stop-the-world events"
                + " caused by to-space exhaustion, humongous-allocation failure, or concurrent mark failure.");
    }
}

// ---------------------------------------------------------------------------
// Heap pressure (over-provisioned heap)
// ---------------------------------------------------------------------------

final class OverProvisionedHeapRule extends AbstractMemoryRule {

    private static final long MIN_SLACK_BYTES = MemoryFormat.GIGABYTE;
    private static final int COMMITTED_TO_USED_RATIO = 2;
    private static final long MIN_UPTIME_MILLIS = 600_000L;

    OverProvisionedHeapRule() {
        super(
                new MemoryRuleDefinition(
                        "MEM-HEAP-007",
                        "Committed heap is far above post-GC live data",
                        MemoryCategory.HEAP_PRESSURE,
                        "INFO",
                        "Notes when the committed heap is at least twice the post-GC live set and the slack is at least 1 GiB, after at least 10 minutes of uptime. This suggests the heap is over-provisioned: the JVM has committed memory to the OS that the application consistently does not use. Reducing -Xmx can free host memory for other processes without harming the application.",
                        "Consider lowering -Xmx (or -XX:MaxRAMPercentage) closer to the post-GC live set to free host memory; alternatively, confirm the oversized heap is intentional to absorb allocation bursts.",
                        "https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        if (context.runtime().uptimeMillis() < MIN_UPTIME_MILLIS) {
            return skipped("JVM uptime is too short to assess heap over-provisioning.");
        }
        long committed = context.memory().heapCommitted();
        if (committed <= 0) {
            return skipped("Committed heap size is not available.");
        }
        // Prefer post-GC used as the live-set estimate; fall back to current used.
        long liveSet;
        MemoryContext.PostGcHeapData postGc = context.postGcHeap();
        if (postGc.heapAvailable() && postGc.heapUsed() > 0) {
            liveSet = postGc.heapUsed();
        } else {
            liveSet = context.memory().heapUsed();
        }
        if (liveSet <= 0) {
            return skipped("Heap used is not available.");
        }
        long slack = committed - liveSet;
        if (committed >= COMMITTED_TO_USED_RATIO * liveSet && slack >= MIN_SLACK_BYTES) {
            return violation("Committed heap " + MemoryFormat.bytes(committed)
                    + " is more than " + COMMITTED_TO_USED_RATIO + "x the post-GC live set "
                    + MemoryFormat.bytes(liveSet) + " (slack " + MemoryFormat.bytes(slack)
                    + "); the heap may be over-provisioned relative to the actual working set.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Memory pools (interpreted / capped-JIT mode)
// ---------------------------------------------------------------------------

final class InterpretedJitModeRule extends AbstractMemoryRule {

    InterpretedJitModeRule() {
        super(new MemoryRuleDefinition(
                "MEM-POOL-006",
                "JIT compiler is disabled or capped below full optimisation",
                MemoryCategory.MEMORY_POOLS,
                "LOW",
                "Detects -Xint (fully interpreted), -XX:-UseCompiler (JIT disabled), or -XX:TieredStopAtLevel<4 (JIT capped below C2 optimisation) in the JVM input arguments. These flags are used for debugging and profiling but left in production significantly reduce throughput and increase CPU usage, which can manifest as elevated heap pressure due to longer-living objects.",
                "Remove -Xint, -XX:-UseCompiler, or -XX:TieredStopAtLevel<4 from production JVM arguments unless specifically required for a diagnostic session.",
                "https://docs.oracle.com/en/java/javase/21/vm/java-virtual-machine-technology-overview.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.hasJvmArgument("-Xint")) {
            return violation("JVM is running in fully interpreted mode (-Xint); JIT compilation is disabled.");
        }
        if (memory.hasJvmArgument("-XX:-UseCompiler")) {
            return violation("JIT compiler is explicitly disabled (-XX:-UseCompiler).");
        }
        for (String arg : memory.inputArguments()) {
            if (arg != null && arg.startsWith("-XX:TieredStopAtLevel=")) {
                try {
                    int level = Integer.parseInt(
                            arg.substring("-XX:TieredStopAtLevel=".length()).trim());
                    if (level < 4) {
                        return violation(arg + " caps JIT at tier " + level
                                + " (below full C2 optimisation at level 4); throughput will be reduced.");
                    }
                } catch (NumberFormatException ignored) {
                    // unparseable value; skip
                }
            }
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Native memory (swap utilization)
// ---------------------------------------------------------------------------

final class HighSwapUtilizationRule extends AbstractMemoryRule {

    private static final int SWAP_USED_PERCENT_THRESHOLD = 50;

    HighSwapUtilizationRule() {
        super(new MemoryRuleDefinition(
                "MEM-FOOTPRINT-004",
                "High swap utilization while JVM footprint exceeds free physical memory",
                MemoryCategory.NATIVE_MEMORY,
                "MEDIUM",
                "Reads swap space statistics from com.sun.management.OperatingSystemMXBean and flags when used swap is a large fraction of total swap AND the JVM's estimated committed footprint (heap + non-heap + direct buffers + thread-stack reservation) exceeds free physical memory. This combination strongly suggests the JVM is partially swapped out, causing latency spikes on heap access.",
                "Reduce the JVM's committed footprint (lower -Xmx, reduce thread count, tune direct-buffer use) or add physical memory; avoid large heaps on hosts with active swap.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        long totalSwap = context.runtime().totalSwapSpaceBytes();
        long freeSwap = context.runtime().freeSwapSpaceBytes();
        if (totalSwap <= 0 || freeSwap < 0) {
            return skipped("Swap space statistics are not available on this platform.");
        }
        if (totalSwap == 0) {
            return skipped("No swap space is configured on this host.");
        }
        long usedSwap = totalSwap - freeSwap;
        int swapPercent = MemoryFormat.percentOf(usedSwap, totalSwap);
        if (swapPercent < SWAP_USED_PERCENT_THRESHOLD) {
            return pass();
        }
        // Estimate whether the JVM footprint competes with physical memory.
        long stacks = (long) context.threads().total() * context.runtime().threadStackBytes();
        long footprint = Math.max(0, context.memory().heapCommitted())
                + Math.max(0, context.memory().nonHeapCommitted())
                + Math.max(0, context.memory().directBufferCapacity())
                + Math.max(0, stacks);
        long freePhysical = readFreePhysical(context);
        if (freePhysical >= 0 && footprint <= freePhysical) {
            // JVM likely fits in RAM; high swap may be from other processes.
            return pass();
        }
        return violation(swapPercent + "% of swap is in use (" + MemoryFormat.bytes(usedSwap) + " of "
                + MemoryFormat.bytes(totalSwap) + ") and the estimated JVM committed footprint "
                + MemoryFormat.bytes(footprint)
                + " exceeds free physical memory; the JVM may be partially swapped out.");
    }

    private static long readFreePhysical(MemoryContext context) {
        // com.sun.management.OperatingSystemMXBean.getFreePhysicalMemorySize() is collected via the
        // same JMX path as the swap stats; approximate it from the OS bean via JMX attribute.
        try {
            javax.management.MBeanServer server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName name = new javax.management.ObjectName("java.lang:type=OperatingSystem");
            Object value = server.getAttribute(name, "FreePhysicalMemorySize");
            if (value instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception | Error ignored) {
            // attribute may not exist on non-HotSpot JVMs
        }
        return -1;
    }
}
