package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

import io.github.jdubois.bootui.autoconfigure.memoryadvisor.MemoryAdvisorContext.MemoryData;
import io.github.jdubois.bootui.autoconfigure.memoryadvisor.MemoryAdvisorContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.autoconfigure.memoryadvisor.MemoryAdvisorContext.ThreadData;
import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Static Memory Advisor rules. Each rule reads only from {@link MemoryAdvisorContext} and returns a
 * health finding with a severity; rules never collect data or mutate runtime state.
 */
abstract class AbstractMemoryAdvisorRule implements MemoryAdvisorRule {

    private final MemoryAdvisorRuleDefinition definition;

    AbstractMemoryAdvisorRule(MemoryAdvisorRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final MemoryAdvisorRuleDefinition definition() {
        return definition;
    }

    abstract io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context);

    @Override
    public final io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluate(MemoryAdvisorContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return MemoryAdvisorRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto pass() {
        return MemoryAdvisorRuleSupport.pass(definition);
    }

    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto skipped(String reason) {
        return MemoryAdvisorRuleSupport.skipped(definition, reason);
    }

    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : MemoryAdvisorRuleSupport.violation(definition, details);
    }

    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto violation(String detail) {
        return violation(List.of(detail));
    }
}

/**
 * Shared formatting and threshold helpers for Memory Advisor rules.
 */
final class MemoryAdvisorFormat {

    static final long KILOBYTE = 1024L;
    static final long MEGABYTE = 1024L * 1024;
    static final long GIGABYTE = 1024L * 1024 * 1024;

    private MemoryAdvisorFormat() {}

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

final class HighHeapUtilizationRule extends AbstractMemoryAdvisorRule {

    private static final int THRESHOLD_PERCENT = 90;

    HighHeapUtilizationRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-HEAP-001",
                "Heap utilization is critically high",
                MemoryAdvisorCategory.HEAP_PRESSURE,
                "HIGH",
                "Flags when live heap usage is close to the maximum heap, which risks long GC pauses or OutOfMemoryError.",
                "Increase -Xmx (or MaxRAMPercentage), reduce retained objects, or profile the heap to find the growth source.",
                "https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        MemoryData memory = context.memory();
        if (memory.heapMax() <= 0) {
            return skipped("Maximum heap size is not reported by this JVM.");
        }
        int percent = MemoryAdvisorFormat.percentOf(memory.heapUsed(), memory.heapMax());
        if (percent >= THRESHOLD_PERCENT) {
            return violation("Heap is " + percent + "% full (" + MemoryAdvisorFormat.bytes(memory.heapUsed()) + " of "
                    + MemoryAdvisorFormat.bytes(memory.heapMax()) + ").");
        }
        return pass();
    }
}

final class OldGenerationNearMaxRule extends AbstractMemoryAdvisorRule {

    private static final int THRESHOLD_PERCENT = 85;

    OldGenerationNearMaxRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-HEAP-002",
                "Old generation is near its maximum",
                MemoryAdvisorCategory.HEAP_PRESSURE,
                "MEDIUM",
                "Flags when the tenured/old generation pool is nearly full, a common precursor to full GCs and promotion failures.",
                "Investigate long-lived object retention; consider raising the heap size or tuning the young/old ratio.",
                "https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        Optional<MemoryPoolSnapshot> oldGen = context.memory().oldGenerationPool();
        if (oldGen.isEmpty()) {
            return skipped("No old-generation memory pool is exposed by the active garbage collector.");
        }
        MemoryPoolSnapshot pool = oldGen.get();
        if (pool.max() <= 0) {
            return skipped("Old-generation pool '" + pool.name() + "' does not report a maximum size.");
        }
        if (pool.usedPercent() >= THRESHOLD_PERCENT) {
            return violation("Old-generation pool '" + pool.name() + "' is " + pool.usedPercent() + "% full ("
                    + MemoryAdvisorFormat.bytes(pool.used()) + " of " + MemoryAdvisorFormat.bytes(pool.max()) + ").");
        }
        return pass();
    }
}

final class UnsetOrSmallMaxHeapRule extends AbstractMemoryAdvisorRule {

    UnsetOrSmallMaxHeapRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-HEAP-003",
                "Maximum heap is unset or small relative to the container limit",
                MemoryAdvisorCategory.HEAP_PRESSURE,
                "MEDIUM",
                "Flags when -Xmx is effectively unbounded, or when the configured max heap uses only a small fraction of the detected container memory limit.",
                "Set an explicit -Xmx or -XX:MaxRAMPercentage so the heap is sized predictably against the container memory limit.",
                "https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        MemoryData memory = context.memory();
        if (memory.heapMax() <= 0) {
            return violation("The JVM does not report a bounded maximum heap; set -Xmx or -XX:MaxRAMPercentage.");
        }
        Long containerLimit = memory.containerMemoryLimitBytes();
        if (containerLimit == null) {
            return pass();
        }
        if (memory.heapMax() < containerLimit / 4) {
            int percent = MemoryAdvisorFormat.percentOf(memory.heapMax(), containerLimit);
            return violation("Max heap " + MemoryAdvisorFormat.bytes(memory.heapMax()) + " is only " + percent
                    + "% of the container memory limit " + MemoryAdvisorFormat.bytes(containerLimit)
                    + "; memory may be under-utilized.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Memory pools
// ---------------------------------------------------------------------------

final class MetaspaceSaturationRule extends AbstractMemoryAdvisorRule {

    private static final int THRESHOLD_PERCENT = 85;

    MetaspaceSaturationRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-POOL-001",
                "Metaspace is close to its maximum",
                MemoryAdvisorCategory.MEMORY_POOLS,
                "MEDIUM",
                "Flags when the Metaspace pool is nearly full, which can cause OutOfMemoryError: Metaspace, often from classloader leaks or excessive dynamic class generation.",
                "Raise -XX:MaxMetaspaceSize, or investigate classloader leaks and runtime class generation (proxies, scripting).",
                "https://docs.oracle.com/en/java/javase/21/vm/class-metadata.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        Optional<MemoryPoolSnapshot> metaspace = context.memory().metaspacePool();
        if (metaspace.isEmpty() || metaspace.get().max() <= 0) {
            return skipped("Metaspace has no configured maximum (effectively unbounded).");
        }
        MemoryPoolSnapshot pool = metaspace.get();
        if (pool.usedPercent() >= THRESHOLD_PERCENT) {
            return violation("Metaspace is " + pool.usedPercent() + "% full ("
                    + MemoryAdvisorFormat.bytes(pool.used()) + " of " + MemoryAdvisorFormat.bytes(pool.max()) + ").");
        }
        return pass();
    }
}

final class CodeCacheSaturationRule extends AbstractMemoryAdvisorRule {

    private static final int THRESHOLD_PERCENT = 85;

    CodeCacheSaturationRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-POOL-002",
                "Code cache is close to its maximum",
                MemoryAdvisorCategory.MEMORY_POOLS,
                "MEDIUM",
                "Flags when the JIT code cache is nearly full; once exhausted the JIT stops compiling and the application falls back to slower interpreted execution.",
                "Increase -XX:ReservedCodeCacheSize, or reduce the amount of compiled code (fewer megamorphic call sites, less code).",
                "https://docs.oracle.com/en/java/javase/21/vm/codecache.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        Optional<MemoryPoolSnapshot> codeCache = context.memory().codeCachePool();
        if (codeCache.isEmpty() || codeCache.get().max() <= 0) {
            return skipped("No code-cache pool with a configured maximum is exposed by this JVM.");
        }
        MemoryPoolSnapshot pool = codeCache.get();
        if (pool.usedPercent() >= THRESHOLD_PERCENT) {
            return violation("Code cache is " + pool.usedPercent() + "% full ("
                    + MemoryAdvisorFormat.bytes(pool.used()) + " of " + MemoryAdvisorFormat.bytes(pool.max()) + ").");
        }
        return pass();
    }
}

final class DirectBufferGrowthRule extends AbstractMemoryAdvisorRule {

    private static final long ABSOLUTE_WARN_BYTES = 256L * MemoryAdvisorFormat.MEGABYTE;

    DirectBufferGrowthRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-POOL-003",
                "Direct buffer usage is high",
                MemoryAdvisorCategory.MEMORY_POOLS,
                "LOW",
                "Flags large or near-limit java.nio direct (off-heap) buffer usage, which is not bounded by -Xmx and can leak native memory.",
                "Audit direct ByteBuffer allocations and pooling; set or raise -XX:MaxDirectMemorySize and ensure buffers are released.",
                "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        MemoryData memory = context.memory();
        long used = memory.directBufferUsed();
        long max = memory.maxDirectMemoryBytes();
        if (max > 0 && used >= (long) (max * 0.8)) {
            int percent = MemoryAdvisorFormat.percentOf(used, max);
            return violation("Direct buffers use " + MemoryAdvisorFormat.bytes(used) + " (" + percent
                    + "% of -XX:MaxDirectMemorySize " + MemoryAdvisorFormat.bytes(max) + ") across "
                    + memory.directBufferCount() + " buffers.");
        }
        if (used >= ABSOLUTE_WARN_BYTES) {
            return violation("Direct buffer pool holds " + MemoryAdvisorFormat.bytes(used) + " of off-heap memory across "
                    + memory.directBufferCount() + " buffers; monitor for native-memory growth.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// GC configuration
// ---------------------------------------------------------------------------

final class GcChoiceVsHeapSizeRule extends AbstractMemoryAdvisorRule {

    private static final long LARGE_HEAP_BYTES = 4L * MemoryAdvisorFormat.GIGABYTE;
    private static final long SMALL_HEAP_BYTES = 2L * MemoryAdvisorFormat.GIGABYTE;

    GcChoiceVsHeapSizeRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-GC-001",
                "Garbage collector choice does not match the heap size",
                MemoryAdvisorCategory.GC_CONFIGURATION,
                "INFO",
                "Suggests a low-latency collector (ZGC) for large heaps, and notes when a low-latency collector is used for a small heap where G1 is usually sufficient.",
                "For heaps at or above ~4 GiB consider -XX:+UseZGC for shorter pauses; for small heaps G1 (the default) is usually a good fit.",
                "https://docs.oracle.com/en/java/javase/21/gctuning/available-collectors.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        MemoryData memory = context.memory();
        if (memory.heapMax() <= 0) {
            return skipped("Maximum heap size is not reported by this JVM.");
        }
        boolean lowLatency = memory.usesGarbageCollector("zgc")
                || memory.usesGarbageCollector("z generational")
                || memory.usesGarbageCollector("shenandoah");
        boolean g1 = memory.usesGarbageCollector("g1");
        if (memory.heapMax() >= LARGE_HEAP_BYTES && g1 && !lowLatency) {
            return violation("Heap max is " + MemoryAdvisorFormat.bytes(memory.heapMax())
                    + " using G1; a low-latency collector such as ZGC can reduce pause times at this size.");
        }
        if (memory.heapMax() < SMALL_HEAP_BYTES && lowLatency) {
            return violation("A low-latency collector is configured for a small heap ("
                    + MemoryAdvisorFormat.bytes(memory.heapMax())
                    + "); G1 is usually sufficient and uses fewer resources at this size.");
        }
        return pass();
    }
}

final class MissingHeapSizingInContainerRule extends AbstractMemoryAdvisorRule {

    MissingHeapSizingInContainerRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-GC-002",
                "Heap sizing is not configured for a container",
                MemoryAdvisorCategory.GC_CONFIGURATION,
                "MEDIUM",
                "Flags a detected container memory limit with neither -Xmx nor -XX:MaxRAMPercentage set, leaving the heap to the JVM default which may not match the container budget.",
                "Set -XX:MaxRAMPercentage (or an explicit -Xmx) so the heap is sized deterministically against the container memory limit.",
                "https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        MemoryData memory = context.memory();
        if (memory.containerMemoryLimitBytes() == null) {
            return skipped("No container memory limit was detected.");
        }
        boolean explicitMaxHeap = memory.hasJvmArgumentPrefix("-Xmx");
        boolean ramPercentage = memory.hasJvmArgumentPrefix("-XX:MaxRAMPercentage")
                || memory.hasJvmArgumentPrefix("-XX:InitialRAMPercentage");
        if (!explicitMaxHeap && !ramPercentage) {
            return violation("Container memory limit " + MemoryAdvisorFormat.bytes(memory.containerMemoryLimitBytes())
                    + " detected but neither -Xmx nor -XX:MaxRAMPercentage is set.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// Threads
// ---------------------------------------------------------------------------

final class DeadlockDetectedRule extends AbstractMemoryAdvisorRule {

    DeadlockDetectedRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-THREAD-001",
                "Thread deadlock detected",
                MemoryAdvisorCategory.THREADS,
                "CRITICAL",
                "Detects threads blocked in a cycle of lock acquisition; deadlocked threads make no progress and can hang request processing.",
                "Inspect the deadlocked threads in the Threads panel, then establish a consistent global lock-ordering or use tryLock with timeouts.",
                "https://docs.oracle.com/javase/tutorial/essential/concurrency/deadlock.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        ThreadData threads = context.threads();
        if (!threads.deadlockDetected()) {
            return pass();
        }
        return violation("Deadlock detected involving thread id(s): " + threads.deadlockedThreadIds() + ".");
    }
}

final class HighBlockedThreadRatioRule extends AbstractMemoryAdvisorRule {

    private static final int MIN_BLOCKED = 3;
    private static final double RATIO_THRESHOLD = 0.2;

    HighBlockedThreadRatioRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-THREAD-002",
                "High proportion of BLOCKED threads",
                MemoryAdvisorCategory.THREADS,
                "MEDIUM",
                "Flags when a large share of live threads are BLOCKED waiting for monitors, indicating lock contention that limits throughput.",
                "Identify the contended lock in the Threads panel and reduce the critical section, shard the lock, or use lock-free structures.",
                "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.State.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        ThreadData threads = context.threads();
        if (threads.total() <= 0) {
            return skipped("No thread snapshot is available.");
        }
        int blocked = context.blockedThreadCount();
        if (blocked >= MIN_BLOCKED && (double) blocked / threads.total() >= RATIO_THRESHOLD) {
            int percent = MemoryAdvisorFormat.percentOf(blocked, threads.total());
            return violation(blocked + " of " + threads.total() + " threads (" + percent
                    + "%) are BLOCKED waiting for a monitor, indicating lock contention.");
        }
        return pass();
    }
}

final class ThreadPoolExhaustionGapRule extends AbstractMemoryAdvisorRule {

    private static final int MIN_GAP = 50;

    ThreadPoolExhaustionGapRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-THREAD-003",
                "Peak thread count is far above the live count",
                MemoryAdvisorCategory.THREADS,
                "LOW",
                "Flags a large gap between the peak and current live thread counts, which can indicate pool exhaustion bursts, thread churn, or a thread leak.",
                "Review thread-pool sizing and lifecycle; bound pool sizes and ensure short-lived threads are not created per request.",
                "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        ThreadData threads = context.threads();
        if (threads.total() <= 0) {
            return skipped("No thread snapshot is available.");
        }
        int gap = threads.peak() - threads.total();
        if (threads.peak() >= 2 * threads.total() && gap >= MIN_GAP) {
            return violation("Peak threads " + threads.peak() + " is well above the current " + threads.total()
                    + " live threads (gap " + gap + "); check for thread leaks or pool churn.");
        }
        return pass();
    }
}

final class RunawayCpuThreadRule extends AbstractMemoryAdvisorRule {

    private static final long CPU_THRESHOLD_MILLIS = 60_000L;
    private static final int MAX_REPORTED = 5;

    RunawayCpuThreadRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-THREAD-004",
                "Runnable threads with very high accumulated CPU time",
                MemoryAdvisorCategory.THREADS,
                "INFO",
                "Highlights RUNNABLE threads that have consumed a large amount of CPU time; in a single snapshot these are candidates for hot loops or runaway work, not a confirmed problem.",
                "Correlate with two consecutive thread snapshots; if CPU keeps climbing for the same thread, profile its stack for a hot or spinning loop.",
                "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        ThreadData threads = context.threads();
        if (!threads.cpuTimeSupported()) {
            return skipped("Per-thread CPU timing is not supported or not enabled on this JVM.");
        }
        List<ThreadInfoDto> hot = new ArrayList<>();
        for (ThreadInfoDto thread : threads.threads()) {
            if ("RUNNABLE".equalsIgnoreCase(thread.state())
                    && thread.cpuTimeMillis() != null
                    && thread.cpuTimeMillis() >= CPU_THRESHOLD_MILLIS) {
                hot.add(thread);
            }
        }
        if (hot.isEmpty()) {
            return pass();
        }
        hot.sort((left, right) -> Long.compare(right.cpuTimeMillis(), left.cpuTimeMillis()));
        List<String> details = new ArrayList<>();
        for (ThreadInfoDto thread : hot.subList(0, Math.min(MAX_REPORTED, hot.size()))) {
            details.add("Thread '" + thread.name() + "' (id " + thread.id() + ") has used "
                    + (thread.cpuTimeMillis() / 1000) + "s of CPU while RUNNABLE.");
        }
        return violation(details);
    }
}

// ---------------------------------------------------------------------------
// Heap content
// ---------------------------------------------------------------------------

final class BigObjectsRule extends AbstractMemoryAdvisorRule {

    private static final long BYTES_PER_INSTANCE_THRESHOLD = 512L * MemoryAdvisorFormat.KILOBYTE;
    private static final long MIN_TOTAL_BYTES = 10L * MemoryAdvisorFormat.MEGABYTE;
    private static final int MAX_REPORTED = 5;

    BigObjectsRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-CONTENT-001",
                "Classes with very large average instance size",
                MemoryAdvisorCategory.HEAP_CONTENT,
                "INFO",
                "Surfaces classes whose average retained size per instance is large; these big objects dominate allocation and can fragment the heap.",
                "Review whether these objects can be streamed, paged, or pooled instead of held whole in memory.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
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
            details.add(entry.className() + " averages " + MemoryAdvisorFormat.bytes(perInstance) + "/instance across "
                    + entry.instances() + " instances (" + MemoryAdvisorFormat.bytes(entry.bytes()) + " total).");
        }
        return violation(details);
    }
}

final class CollectionBloatRule extends AbstractMemoryAdvisorRule {

    private static final long ABSOLUTE_THRESHOLD = 50L * MemoryAdvisorFormat.MEGABYTE;
    private static final int SHARE_PERCENT_THRESHOLD = 10;
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
        super(new MemoryAdvisorRuleDefinition(
                "MEM-CONTENT-002",
                "Collections retain a large share of the heap",
                MemoryAdvisorCategory.HEAP_CONTENT,
                "MEDIUM",
                "Flags JDK collection or map classes that retain a large amount of heap, a frequent signature of an unbounded cache or accumulating list.",
                "Bound the offending collection with an eviction policy or a size limit (e.g., a real cache), and verify entries are removed.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        if (!context.heapContent().available()) {
            return skipped("No class histogram is available; run Heap Dump analysis or re-scan to collect one.");
        }
        long totalBytes = context.heapContent().totalBytes();
        List<HeapClassHistogramEntryDto> candidates = new ArrayList<>();
        for (HeapClassHistogramEntryDto entry : context.heapContent().histogram()) {
            if (!isCollectionClass(entry.className())) {
                continue;
            }
            int sharePercent = MemoryAdvisorFormat.percentOf(entry.bytes(), totalBytes);
            if (entry.bytes() >= ABSOLUTE_THRESHOLD || sharePercent >= SHARE_PERCENT_THRESHOLD) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return pass();
        }
        candidates.sort((left, right) -> Long.compare(right.bytes(), left.bytes()));
        List<String> details = new ArrayList<>();
        for (HeapClassHistogramEntryDto entry : candidates.subList(0, Math.min(MAX_REPORTED, candidates.size()))) {
            int sharePercent = MemoryAdvisorFormat.percentOf(entry.bytes(), totalBytes);
            details.add(entry.className() + " retains " + MemoryAdvisorFormat.bytes(entry.bytes()) + " (" + sharePercent
                    + "% of sampled heap) across " + entry.instances()
                    + " instances — likely an unbounded cache or accumulating collection.");
        }
        return violation(details);
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

final class DominantClassRule extends AbstractMemoryAdvisorRule {

    private static final int SHARE_PERCENT_THRESHOLD = 25;

    DominantClassRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-CONTENT-003",
                "A single class dominates the sampled heap",
                MemoryAdvisorCategory.HEAP_CONTENT,
                "LOW",
                "Flags when one class retains a large fraction of the sampled heap; a strongly dominant top class is worth understanding even if expected.",
                "Confirm the dominant class is expected; if not, trace its references to find what keeps the instances alive.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        if (!context.heapContent().available()) {
            return skipped("No class histogram is available; run Heap Dump analysis or re-scan to collect one.");
        }
        long totalBytes = context.heapContent().totalBytes();
        if (totalBytes <= 0) {
            return skipped("The sampled heap histogram is empty.");
        }
        return context.heapContent().histogram().stream()
                .findFirst()
                .filter(entry -> MemoryAdvisorFormat.percentOf(entry.bytes(), totalBytes) >= SHARE_PERCENT_THRESHOLD)
                .map(entry -> violation(entry.className() + " retains "
                        + MemoryAdvisorFormat.percentOf(entry.bytes(), totalBytes) + "% of the sampled heap ("
                        + MemoryAdvisorFormat.bytes(entry.bytes()) + ")."))
                .orElseGet(this::pass);
    }
}

// ---------------------------------------------------------------------------
// Class loading
// ---------------------------------------------------------------------------

final class ExcessiveLoadedClassesRule extends AbstractMemoryAdvisorRule {

    private static final int LOADED_THRESHOLD = 50_000;

    ExcessiveLoadedClassesRule() {
        super(new MemoryAdvisorRuleDefinition(
                "MEM-CLASS-001",
                "Very large number of loaded classes",
                MemoryAdvisorCategory.CLASS_LOADING,
                "INFO",
                "Flags a high loaded-class count with little or no class unloading, which can indicate a classloader leak or runaway dynamic class generation and pressures Metaspace.",
                "If the application does not legitimately use this many classes, look for classloader leaks (redeploys, scripting, proxy generation).",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryAdvisorRuleResultDto evaluateRule(MemoryAdvisorContext context) {
        MemoryAdvisorContext.ClassLoadingData classLoading = context.classLoading();
        if (classLoading.loadedClasses() >= LOADED_THRESHOLD) {
            return violation(classLoading.loadedClasses() + " classes are currently loaded ("
                    + classLoading.totalLoadedClasses() + " loaded and " + classLoading.unloadedClasses()
                    + " unloaded since start); watch for classloader leaks and Metaspace pressure.");
        }
        return pass();
    }
}
