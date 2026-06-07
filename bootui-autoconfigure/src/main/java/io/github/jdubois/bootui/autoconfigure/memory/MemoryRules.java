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
                        "Flags when live heap usage is very close to the maximum heap. This is a single-snapshot reading that includes not-yet-collected garbage, so confirm it persists after a GC, but a sustained reading this high risks long GC pauses or OutOfMemoryError.",
                        "Increase -Xmx (or MaxRAMPercentage), reduce retained objects, or profile the heap to find the growth source.",
                        "https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.heapMax() <= 0) {
            return skipped("Maximum heap size is not reported by this JVM.");
        }
        int percent = MemoryFormat.percentOf(memory.heapUsed(), memory.heapMax());
        if (percent >= THRESHOLD_PERCENT) {
            return violation("Heap is " + percent + "% full (" + MemoryFormat.bytes(memory.heapUsed()) + " of "
                    + MemoryFormat.bytes(memory.heapMax()) + ").");
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
                "Flags when the tenured/old generation pool is nearly full, a common precursor to full GCs and promotion failures.",
                "Investigate long-lived object retention; consider raising the heap size or tuning the young/old ratio.",
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
            return violation("The JVM does not report a bounded maximum heap; set -Xmx or -XX:MaxRAMPercentage.");
        }
        Long containerLimit = memory.containerMemoryLimitBytes();
        if (containerLimit == null || containerLimit < MIN_CONTAINER_LIMIT) {
            return pass();
        }
        boolean smallHeap = memory.heapMax() < containerLimit * SMALL_HEAP_PERCENT / 100;
        if (smallHeap && context.heapUsedPercent() >= PRESSURE_PERCENT) {
            int percent = MemoryFormat.percentOf(memory.heapMax(), containerLimit);
            return violation("Max heap " + MemoryFormat.bytes(memory.heapMax()) + " is only " + percent
                    + "% of the container memory limit " + MemoryFormat.bytes(containerLimit) + " and is already "
                    + context.heapUsedPercent() + "% full; raising the heap could use the available container memory.");
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
                "Flags java.nio direct (off-heap) buffer capacity that is near an explicit -XX:MaxDirectMemorySize, or large without any cap. Direct memory is not bounded by -Xmx and can leak native memory.",
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
        long containerThreshold = memory.containerMemoryLimitBytes() == null
                ? Long.MAX_VALUE
                : memory.containerMemoryLimitBytes() * UNCAPPED_CONTAINER_PERCENT / 100;
        if (capacity >= Math.min(UNCAPPED_WARN_BYTES, containerThreshold)) {
            return violation("Direct buffer pool reserves " + MemoryFormat.bytes(capacity)
                    + " of off-heap memory across " + memory.directBufferCount()
                    + " buffers with no -XX:MaxDirectMemorySize cap; monitor for native-memory growth.");
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
                "MEM-GC-002",
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
        super(new MemoryRuleDefinition(
                "MEM-THREAD-001",
                "Thread deadlock detected",
                MemoryCategory.THREADS,
                "CRITICAL",
                "Detects threads blocked in a cycle of lock acquisition; deadlocked threads make no progress and can hang request processing.",
                "Inspect the deadlocked threads in the Threads panel, then establish a consistent global lock-ordering or use tryLock with timeouts.",
                "https://docs.oracle.com/javase/tutorial/essential/concurrency/deadlock.html"));
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

    HighBlockedThreadRatioRule() {
        super(new MemoryRuleDefinition(
                "MEM-THREAD-002",
                "High proportion of BLOCKED threads",
                MemoryCategory.THREADS,
                "MEDIUM",
                "Flags when a large share of live threads are BLOCKED waiting for monitors, indicating lock contention that limits throughput.",
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
        boolean highRatio = blocked >= MIN_BLOCKED && (double) blocked / threads.total() >= RATIO_THRESHOLD;
        if (highRatio || blocked >= ABSOLUTE_BLOCKED) {
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
                        "Peak thread count is far above the live count",
                        MemoryCategory.THREADS,
                        "LOW",
                        "Flags a large gap between the peak and current live thread counts, which can indicate pool exhaustion bursts, thread churn, or a thread leak.",
                        "Review thread-pool sizing and lifecycle; bound pool sizes and ensure short-lived threads are not created per request.",
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
            return violation("Peak threads " + threads.peak() + " is well above the current " + threads.total()
                    + " live threads (gap " + gap + "); check for thread leaks or pool churn.");
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
                "Flags JDK collection or map classes that occupy a large amount of heap (shallow histogram bytes), a frequent signature of an unbounded cache or accumulating list.",
                "Bound the offending collection with an eviction policy or a size limit (e.g., a real cache), and verify entries are removed.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        if (!context.heapContent().available()) {
            return skipped("No class histogram is available; run Heap Dump analysis or re-scan to collect one.");
        }
        long totalBytes = context.heapContent().totalBytes();
        List<HeapClassHistogramEntryDto> candidates = new ArrayList<>();
        for (HeapClassHistogramEntryDto entry : context.heapContent().histogram()) {
            if (!isCollectionClass(entry.className())) {
                continue;
            }
            int sharePercent = MemoryFormat.percentOf(entry.bytes(), totalBytes);
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
            int sharePercent = MemoryFormat.percentOf(entry.bytes(), totalBytes);
            details.add(entry.className() + " occupies " + MemoryFormat.bytes(entry.bytes()) + " (" + sharePercent
                    + "% of histogram bytes, shallow) across " + entry.instances()
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

final class DominantClassRule extends AbstractMemoryRule {

    private static final int SHARE_PERCENT_THRESHOLD = 25;

    private static final List<String> PRIMITIVE_ARRAY_TYPES =
            List.of("byte[]", "char[]", "int[]", "long[]", "short[]", "boolean[]", "float[]", "double[]");

    DominantClassRule() {
        super(new MemoryRuleDefinition(
                "MEM-CONTENT-003",
                "A single class dominates the sampled heap",
                MemoryCategory.HEAP_CONTENT,
                "LOW",
                "Flags when one class (excluding primitive arrays, which are routinely dominant) occupies a large fraction of the sampled heap by shallow bytes; a strongly dominant top class is worth understanding even if expected.",
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
                .filter(entry -> !PRIMITIVE_ARRAY_TYPES.contains(entry.className()))
                .findFirst()
                .filter(entry -> MemoryFormat.percentOf(entry.bytes(), totalBytes) >= SHARE_PERCENT_THRESHOLD)
                .map(entry -> violation(entry.className() + " occupies "
                        + MemoryFormat.percentOf(entry.bytes(), totalBytes) + "% of the sampled heap, shallow ("
                        + MemoryFormat.bytes(entry.bytes()) + ")."))
                .orElseGet(this::pass);
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
                        "MEM-GC-003",
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
                "For low-latency collectors (ZGC, Shenandoah), a smaller -Xms than -Xmx makes the JVM grow and re-commit the heap on demand, which can add latency and commit/uncommit churn. Equal -Xms and -Xmx keep the heap fully committed for steady-state, latency-sensitive services.",
                "For latency-sensitive services using ZGC or Shenandoah, set -Xms equal to -Xmx so the heap is fully committed up front.",
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

    private static final long LOWER_BOUND = 32L * MemoryFormat.GIGABYTE;
    private static final long UPPER_BOUND = 48L * MemoryFormat.GIGABYTE;

    CompressedOopsCliffRule() {
        super(new MemoryRuleDefinition(
                "MEM-HEAP-004",
                "Max heap is just above the compressed-oops threshold",
                MemoryCategory.HEAP_PRESSURE,
                "INFO",
                "Notes a max heap larger than ~32 GiB but not dramatically so. Above this threshold the JVM disables compressed ordinary object pointers, so 64-bit references take more space and a heap just over 32 GiB can hold fewer live objects than one capped near 31 GiB. -XX:ObjectAlignmentInBytes can move the boundary.",
                "Either cap the heap near 31 GiB to keep compressed oops, or grow it well past this range (and scale out) when a larger heap is genuinely required.",
                "https://wiki.openjdk.org/display/HotSpot/CompressedOops"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        long heapMax = context.memory().heapMax();
        if (heapMax > LOWER_BOUND && heapMax <= UPPER_BOUND) {
            return violation("Max heap " + MemoryFormat.bytes(heapMax)
                    + " is just above the ~32 GiB compressed-oops threshold; a heap at or below ~31 GiB may hold"
                    + " more objects for the same memory.");
        }
        return pass();
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
                "https://docs.oracle.com/en/java/javase/21/vm/class-data-sharing.html"));
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
