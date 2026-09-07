package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ThreadData;
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
            if (definition.category() == MemoryCategory.THREADS
                    && context.threads().collectionError() != null) {
                return MemoryRuleSupport.error(definition, context.threads().collectionError());
            }
            if (definition.category() == MemoryCategory.HEAP_CONTENT
                    && context.heapContent().collectionError() != null) {
                return MemoryRuleSupport.error(definition, context.heapContent().collectionError());
            }
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
            return "unavailable";
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
        if (whole <= 0 || part <= 0) {
            return 0;
        }
        if (part >= whole) {
            return 100;
        }
        return java.math.BigInteger.valueOf(part)
                .multiply(java.math.BigInteger.valueOf(100))
                .divide(java.math.BigInteger.valueOf(whole))
                .intValue();
    }

    /** Unknown or overflowing estimates stay unknown, rather than wrapping into healthy values. */
    static long sum(long... values) {
        long total = 0;
        for (long value : values) {
            if (value < 0 || value > Long.MAX_VALUE - total) {
                return -1;
            }
            total += value;
        }
        return total;
    }

    static long product(long left, long right) {
        if (left < 0 || right < 0 || (right > 0 && left > Long.MAX_VALUE / right)) {
            return -1;
        }
        return left * right;
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
                        "Heap occupancy is near its reported maximum",
                        MemoryCategory.HEAP_PRESSURE,
                        "MEDIUM",
                        "Flags a heap-occupancy snapshot at least 95% of the reported maximum. A post-histogram reading is preferred, but histogram success does not verify a completed full GC and allocations can resume before the reading. Occupancy can include reclaimable garbage and is not a retained live set.",
                        "Confirm pressure under representative load with GC logs or a profiler before reducing retention or raising -Xmx; check total process and container headroom first.",
                        "https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.heapMax() <= 0) {
            return skipped("Maximum heap size is not reported by this JVM.");
        }
        MemoryContext.PostGcHeapData postGc = context.postGcHeap();
        if (postGc.heapAvailable() && postGc.heapUsed() >= 0) {
            int postPercent = MemoryFormat.percentOf(postGc.heapUsed(), memory.heapMax());
            if (postPercent >= THRESHOLD_PERCENT) {
                return violation(
                        MemoryRuleSupport.MEDIUM,
                        "Heap is " + postPercent + "% full (" + MemoryFormat.bytes(postGc.heapUsed()) + " of "
                                + MemoryFormat.bytes(memory.heapMax())
                                + ") in the post-histogram snapshot; confirm sustained pressure with GC evidence.");
            }
            return pass();
        }
        if (memory.heapUsed() < 0) {
            return skipped("Heap usage is unavailable.");
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
                "Flags old-generation occupancy at 85% of a reported pool maximum. The post-histogram snapshot is preferred, but does not prove a completed full GC or long-lived retention. Pool boundaries and reclamation differ between collectors.",
                "Confirm pressure with collector-specific GC logs and representative workload observations before changing heap or generation sizing.",
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
                return violation("Old-generation pool '" + pool.name() + "' is " + postPercent + "% full ("
                        + MemoryFormat.bytes(postGc.oldGenUsed()) + " of " + MemoryFormat.bytes(pool.max())
                        + ") in the post-histogram snapshot; this is occupancy, not a retained-size measurement.");
            }
            return pass();
        }
        if (pool.used() < 0) {
            return skipped("Old-generation usage is unavailable.");
        }
        if (pool.usedPercent() >= THRESHOLD_PERCENT) {
            return violation("Old-generation pool '" + pool.name() + "' is " + pool.usedPercent() + "% full ("
                    + MemoryFormat.bytes(pool.used()) + " of " + MemoryFormat.bytes(pool.max()) + ").");
        }
        return pass();
    }
}

final class SmallMaxHeapUnderPressureRule extends AbstractMemoryRule {

    private static final long MIN_CONTAINER_LIMIT = MemoryFormat.GIGABYTE;
    private static final int SMALL_HEAP_PERCENT = 15;
    private static final int PRESSURE_PERCENT = 80;

    SmallMaxHeapUnderPressureRule() {
        super(new MemoryRuleDefinition(
                "MEM-HEAP-003",
                "Maximum heap is capped well below the container limit",
                MemoryCategory.HEAP_PRESSURE,
                "LOW",
                "Flags high heap occupancy with a maximum below 15% of a container limit of at least 1 GiB. A container limit is not available free memory: other processes and native allocations can consume the remaining capacity.",
                "Confirm sustained heap pressure and actual container/native headroom before raising -Xmx or -XX:MaxRAMPercentage.",
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
        boolean smallHeap = MemoryFormat.percentOf(memory.heapMax(), containerLimit) < SMALL_HEAP_PERCENT;
        // Prefer the more recent snapshot without claiming it proves collection or retention.
        int usedPercent;
        MemoryContext.PostGcHeapData postGc = context.postGcHeap();
        if (postGc.heapAvailable() && postGc.heapUsed() >= 0) {
            usedPercent = MemoryFormat.percentOf(postGc.heapUsed(), memory.heapMax());
        } else if (memory.heapUsed() >= 0) {
            usedPercent = context.heapUsedPercent();
        } else {
            return skipped("Heap occupancy is unavailable.");
        }
        if (smallHeap && usedPercent >= PRESSURE_PERCENT) {
            int percent = MemoryFormat.percentOf(memory.heapMax(), containerLimit);
            return violation("Max heap " + MemoryFormat.bytes(memory.heapMax()) + " is only " + percent
                    + "% of the container memory limit " + MemoryFormat.bytes(containerLimit) + " and is already "
                    + usedPercent + "% full; check total container and native usage before considering a larger heap.");
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
        if (metaspace.isEmpty()) {
            return skipped("No Metaspace pool is exposed by this JVM.");
        }
        if (metaspace.get().max() <= 0) {
            return skipped("Metaspace has no configured maximum (effectively unbounded).");
        }
        MemoryPoolSnapshot pool = metaspace.get();
        if (pool.used() < 0) {
            return skipped("Metaspace usage is unavailable.");
        }
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
                "Flags a JIT code-cache segment at 90% of its reported maximum. A saturated segment can constrain new compilation even if aggregate capacity looks healthy; existing compiled methods do not all revert to interpretation.",
                "Increase -XX:ReservedCodeCacheSize, or reduce the amount of compiled code (fewer megamorphic call sites, less code).",
                "https://docs.oracle.com/en/java/javase/21/vm/codecache.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        List<MemoryPoolSnapshot> segments = context.memory().codeCachePools();
        if (segments.isEmpty()) {
            return skipped("No code-cache pool is exposed by this JVM.");
        }
        if (segments.stream().noneMatch(pool -> pool.max() > 0 && pool.used() >= 0)) {
            return skipped("Code-cache usage or maxima are unavailable.");
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

    private static final int LIMIT_PERCENT = 80;

    DirectBufferGrowthRule() {
        super(new MemoryRuleDefinition(
                "MEM-POOL-003",
                "Direct buffer usage is high",
                MemoryCategory.MEMORY_POOLS,
                "LOW",
                "Flags NIO direct-buffer capacity at 80% of a known effective cap. OpenJDK limits capacity, not the buffer pool's estimated used bytes or all native memory. A live HotSpot MaxDirectMemorySize value of zero resolves to max heap; an unavailable option is not evidence of an unlimited cap.",
                "Review direct-buffer allocation and pooling under representative load; check native/container headroom before changing -XX:MaxDirectMemorySize. Use supported library lifecycle APIs, not manual Cleaner invocation.",
                "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        long capacity = memory.directBufferCapacity();
        long max = memory.maxDirectMemoryBytes();
        if (capacity < 0 || max <= 0) {
            return skipped(
                    "Direct-buffer capacity or its effective maximum is unavailable; a missing cap is not an unlimited cap.");
        }
        int percent = MemoryFormat.percentOf(capacity, max);
        if (percent >= LIMIT_PERCENT) {
            return violation("Direct-buffer capacity is " + MemoryFormat.bytes(capacity) + " (" + percent
                    + "% of the effective NIO direct-memory cap " + MemoryFormat.bytes(max)
                    + "); this does not measure the process's complete native footprint.");
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
                "Notes a detected container memory limit with neither -Xmx/-XX:MaxHeapSize nor an explicit RAM-sizing option set. The JVM is container-aware and normally defaults the max heap to about 25% of the limit, while small-heap ergonomics can use 50%.",
                "Set -XX:MaxRAMPercentage (or an explicit -Xmx/-XX:MaxHeapSize) if you want the heap sized deliberately rather than by default ergonomics.",
                "https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.containerMemoryLimitBytes() == null) {
            return skipped("No container memory limit was detected.");
        }
        boolean explicitMaxHeap =
                memory.hasJvmArgumentPrefix("-Xmx") || memory.hasJvmArgumentPrefix("-XX:MaxHeapSize=");
        boolean ramSizing = memory.hasJvmArgumentPrefix("-XX:MaxRAMPercentage")
                || memory.hasJvmArgumentPrefix("-XX:MaxRAMFraction")
                || memory.hasJvmArgumentPrefix("-XX:MaxRAM=");
        if (!explicitMaxHeap && !ramSizing) {
            return violation("Container memory limit " + MemoryFormat.bytes(memory.containerMemoryLimitBytes())
                    + " detected but neither -Xmx/-XX:MaxHeapSize nor an explicit RAM-sizing option is set; the"
                    + " heap defaults to about 25% of the limit (50% for small limits).");
        }
        return pass();
    }
}

final class ContainerSupportDisabledRule extends AbstractMemoryRule {

    ContainerSupportDisabledRule() {
        super(new MemoryRuleDefinition(
                "MEM-GC-007",
                "JVM container awareness is explicitly disabled",
                MemoryCategory.GC_CONFIGURATION,
                "HIGH",
                "Detects -XX:-UseContainerSupport while a cgroup memory limit is visible. Container support is enabled by default on supported JDKs; disabling it makes JVM ergonomics use host-level memory and CPU information instead of container constraints, which can oversize the heap and JVM worker pools.",
                "Remove -XX:-UseContainerSupport so heap, GC, JIT, and common-pool ergonomics respect the container limits; use explicit -Xmx/-XX:MaxRAMPercentage and -XX:ActiveProcessorCount only when deliberate overrides are required.",
                "https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        if (memory.containerMemoryLimitBytes() == null) {
            return skipped("No container memory limit was detected.");
        }
        if (Boolean.FALSE.equals(memory.booleanJvmArgument("UseContainerSupport"))) {
            return violation("-XX:-UseContainerSupport is set despite a detected cgroup memory limit of "
                    + MemoryFormat.bytes(memory.containerMemoryLimitBytes())
                    + "; JVM ergonomics may size against the host and exceed the container.");
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
        if (threads.total() <= 0) {
            return skipped("No thread snapshot is available to assess platform-thread deadlocks.");
        }
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
                        "Notes a large gap between the peak platform-thread count since JVM start or the last resetPeakThreadCount call and the current count. This is historical context, not evidence of a current leak or exhausted pool.",
                        "Review thread-pool sizing and lifecycle; bound pool sizes and ensure short-lived threads are not created per request if these bursts recur.",
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        ThreadData threads = context.threads();
        if (threads.total() <= 0) {
            return skipped("No thread snapshot is available.");
        }
        long gap = (long) threads.peak() - threads.total();
        if (threads.peak() >= 2L * threads.total() && gap >= MIN_GAP) {
            return violation(
                    "Peak threads " + threads.peak() + " was well above the current " + threads.total()
                            + " live threads (gap " + gap
                            + ") since JVM start or the last peak reset; this is historical context, not necessarily a current leak.");
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
                        "Highlights RUNNABLE platform threads whose accumulated CPU time is a large fraction of the JVM's uptime, i.e. they have kept a core busy for much of the process's life. CPU time is cumulative since the thread started, so this is a hot-loop candidate to investigate, not a confirmed problem. The rule skips when its bounded platform-thread detail page is incomplete.",
                        "Correlate with two consecutive thread snapshots; if CPU keeps climbing for the same thread, profile its stack for a hot or spinning loop.",
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        ThreadData threads = context.threads();
        if (!threads.cpuTimeSupported()) {
            return skipped("Per-thread CPU timing is not supported or not enabled on this JVM.");
        }
        if (threads.detailsTruncated()) {
            return skipped(
                    "Per-thread details are capped at 1,000 platform threads; CPU-hot-thread analysis is incomplete.");
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
                    + (thread.cpuTimeMillis() / 1000) + "s of accumulated CPU (" + percent
                    + "% of JVM uptime) and is currently RUNNABLE; the snapshot does not establish its past states.");
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
                candidateBytes = MemoryFormat.sum(candidateBytes, entry.bytes());
            }
        }
        if (candidates.isEmpty()) {
            return pass();
        }
        if (candidateBytes < 0) {
            return skipped("Collection histogram bytes exceed the numeric range.");
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

    ExcessiveLoadedClassesRule() {
        super(new MemoryRuleDefinition(
                "MEM-CLASS-001",
                "Very large number of currently loaded classes",
                MemoryCategory.CLASS_LOADING,
                "INFO",
                "Reports at least 50,000 currently loaded classes as informational context. Frameworks differ in runtime class generation. Lifetime unloads neither prove this population is healthy nor establish a leak; class unloading is optional when defining loaders become reclaimable.",
                "Compare class counts and Metaspace over representative workloads; investigate classloader retention only if growth is unexpected.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.ClassLoadingData classLoading = context.classLoading();
        boolean manyLoaded = classLoading.loadedClasses() >= LOADED_THRESHOLD;
        if (manyLoaded) {
            return violation(
                    classLoading.loadedClasses() + " classes are currently loaded ("
                            + classLoading.totalLoadedClasses() + " loaded and " + classLoading.unloadedClasses()
                            + " unloaded since start); compare representative workload trends before inferring classloader retention.");
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
                "Configured JVM memory leaves little container headroom",
                MemoryCategory.NATIVE_MEMORY,
                "MEDIUM",
                "Estimates the JVM's configured memory envelope (maximum heap, currently committed non-heap such as Metaspace and code cache, direct-buffer capacity, and approximate thread-stack reservations) against the detected container limit. Using maximum rather than currently committed heap exposes configurations that leave too little native headroom before the heap grows. The estimate is conservative but incomplete: it excludes GC structures, JIT working memory, native libraries, and non-NIO native allocations.",
                "Review configured capacity against measured container usage and native headroom before changing limits. This mixed reservation estimate is neither RSS nor total committed memory.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        Long limit = memory.containerMemoryLimitBytes();
        if (limit == null || limit <= 0) {
            return skipped("No container memory limit was detected.");
        }
        if (memory.heapMax() <= 0) {
            return skipped("Maximum heap is unavailable; the configured envelope cannot be assessed.");
        }
        if (memory.heapMax() >= limit) {
            return violation(
                    MemoryRuleSupport.HIGH,
                    "Maximum heap " + MemoryFormat.bytes(memory.heapMax())
                            + " meets or exceeds the container limit " + MemoryFormat.bytes(limit)
                            + "; the heap configuration alone leaves no native headroom if fully realized. This is not measured residency.");
        }
        if (context.threads().collectionError() != null) {
            return MemoryRuleSupport.error(definition(), context.threads().collectionError());
        }
        if (context.threads().total() <= 0 || context.runtime().threadStackBytes() <= 0) {
            return skipped("Platform-thread stack estimate is unavailable.");
        }
        long stacks = MemoryFormat.product(
                context.threads().total(), context.runtime().threadStackBytes());
        long configuredFootprint =
                MemoryFormat.sum(memory.heapMax(), memory.nonHeapCommitted(), memory.directBufferCapacity(), stacks);
        if (configuredFootprint < 0) {
            return skipped("Configured memory components are unavailable or exceed the numeric range.");
        }
        if (MemoryFormat.percentOf(configuredFootprint, limit) >= THRESHOLD_PERCENT) {
            int percent = MemoryFormat.percentOf(configuredFootprint, limit);
            return violation(
                    "Configured JVM memory envelope " + MemoryFormat.bytes(configuredFootprint) + " is "
                            + percent + "% of the container memory limit " + MemoryFormat.bytes(limit)
                            + " (maximum heap "
                            + MemoryFormat.bytes(memory.heapMax()) + " + committed non-heap "
                            + MemoryFormat.bytes(memory.nonHeapCommitted()) + " + direct buffers "
                            + MemoryFormat.bytes(memory.directBufferCapacity()) + " + ~"
                            + context.threads().total()
                            + " thread stacks " + MemoryFormat.bytes(stacks)
                            + "); review native headroom. This incomplete mixed reservation estimate is not committed or resident memory.");
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
            String count =
                    runtime.gcCollectionCount() >= 0 ? " across " + runtime.gcCollectionCount() + " collections" : "";
            return violation(
                    "Approximate accumulated GC time is " + (runtime.gcCollectionTimeMillis() / 1000) + "s"
                            + count + ", " + percent + "% of the "
                            + (runtime.uptimeMillis() / 1000)
                            + "s uptime. This lifetime ratio includes diagnostic collections and startup; it is not CPU utilization or an exact application-pause percentage.");
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
            String initialLabel = memory.hasJvmArgumentPrefix("-Xms") ? "-Xms" : "Initial heap";
            return violation(initialLabel + " " + MemoryFormat.bytes(initial) + " is smaller than -Xmx "
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
                "Notes a max heap just above the boundary where HotSpot ergonomically disables compressed ordinary object pointers, after which 64-bit references take more space and a heap just over the boundary can hold fewer live objects than one capped just below it. The boundary defaults to ~32 GiB but scales with -XX:ObjectAlignmentInBytes. A false live UseCompressedOops value above that boundary is the expected ergonomic symptom, not a reason to skip; the note is skipped for ZGC and an explicit -XX:-UseCompressedOops.",
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
        // The live option is false ergonomically once an oversized heap has already crossed the
        // boundary, so only an explicit disable suppresses this advisory.
        Boolean useCompressedOops = context.runtime().useCompressedOops();
        if (memory.hasJvmArgument("-XX:-UseCompressedOops")) {
            return skipped("Compressed object pointers are explicitly disabled (-XX:-UseCompressedOops).");
        }
        long alignment = parseObjectAlignmentBytes(memory.inputArguments());
        long boundary = MemoryFormat.product(alignment, COMPRESSED_OOPS_HEAP_PER_ALIGNMENT_BYTE);
        long upperBound = MemoryFormat.sum(boundary, boundary / 4);
        if (boundary <= 0 || upperBound < 0) {
            return skipped("The compressed-oops alignment boundary exceeds the numeric range.");
        }
        if (useCompressedOops != null && !useCompressedOops && heapMax <= boundary) {
            return skipped("Compressed object pointers are disabled (UseCompressedOops=false).");
        }
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
                "Metaspace has no reported maximum inside a memory-limited container",
                MemoryCategory.MEMORY_POOLS,
                "LOW",
                "Notes sizable Metaspace with an undefined MXBean maximum inside a detected memory-limited container. An undefined maximum is not proof of unlimited capacity, a missing effective VM option, or an impending OOM kill.",
                "Review class-metadata growth and total native/container usage. Consider a deliberate MaxMetaspaceSize only with workload evidence; a cap can cause Metaspace OOM and does not guarantee a graceful failure.",
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
            return violation("Metaspace has no reported pool maximum and uses " + MemoryFormat.bytes(pool.used())
                    + " inside a container limited to " + MemoryFormat.bytes(memory.containerMemoryLimitBytes())
                    + "; review native headroom without assuming this undefined maximum means unlimited memory.");
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
                "INFO",
                "Reports a large lifetime class-unload count or lifetime-average rate. These counters can reflect a past burst or redeployment and do not establish sustained recent churn.",
                "Compare recent class-loading observations under representative load before changing dynamic generation, caches, or classloader lifecycles.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-class-loading.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        long unloaded = context.classLoading().unloadedClasses();
        if (unloaded >= UNLOAD_THRESHOLD) {
            return violation(
                    unloaded
                            + " classes have been unloaded since start; this historical total does not establish current churn.");
        }
        long uptimeMillis = context.runtime().uptimeMillis();
        if (uptimeMillis >= MIN_UPTIME_MILLIS) {
            long minutes = uptimeMillis / 60_000L;
            long ratePerMin = minutes > 0 ? unloaded / minutes : 0;
            if (ratePerMin >= UNLOAD_RATE_PER_MIN) {
                return violation("Lifetime-average class unloading is about " + ratePerMin + "/min (" + unloaded
                        + " over " + minutes + " min); this does not establish a sustained recent rate.");
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
                "MEDIUM",
                "Estimates platform-thread stack reservations, not committed or resident pages. A large reservation can be mostly untouched. Virtual threads are excluded because their stacks live on the heap. Do not add the full reservation to cgroup usage: touched stack pages are already counted there.",
                "Reduce the platform thread count (bound pools, prefer virtual threads or async I/O) or lower an oversized -Xss so thread stacks do not dominate native memory.",
                "https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        int platformThreads = context.threads().total();
        if (context.threads().collectionError() != null) {
            return MemoryRuleSupport.error(definition(), context.threads().collectionError());
        }
        if (platformThreads <= 0) {
            return skipped("No thread snapshot is available.");
        }
        long stackBytes = context.runtime().threadStackBytes();
        long reserved = MemoryFormat.product(platformThreads, stackBytes);
        if (stackBytes <= 0 || reserved < 0) {
            return skipped("Platform-thread stack reservation is unavailable or exceeds the numeric range.");
        }
        Long limit = context.memory().containerMemoryLimitBytes();
        boolean relativeBreach =
                limit != null && limit > 0 && MemoryFormat.percentOf(reserved, limit) >= CONTAINER_PERCENT_THRESHOLD;
        boolean absoluteBreach = reserved >= ABSOLUTE_THRESHOLD;
        if (relativeBreach || absoluteBreach) {
            String relativeNote = limit != null && limit > 0
                    ? " (" + MemoryFormat.percentOf(reserved, limit) + "% of the container memory limit "
                            + MemoryFormat.bytes(limit) + ")"
                    : "";
            String residencyNote = " This is an approximate virtual-memory reservation, not confirmed resident usage;"
                    + " touched stack pages are already included in cgroup usage.";
            return violation(
                    MemoryRuleSupport.MEDIUM,
                    platformThreads + " platform threads reserve about " + MemoryFormat.bytes(reserved)
                            + " of stack memory at " + MemoryFormat.bytes(stackBytes) + " each" + relativeNote
                            + "." + residencyNote);
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
                arrayBytes = MemoryFormat.sum(arrayBytes, entry.bytes());
            }
        }
        if (arrayBytes < 0) {
            return skipped("Array histogram bytes exceed the numeric range.");
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
                        "Compares approximate collection elapsed-time deltas with the interval between valid scans. Known concurrent-cycle timers are excluded to avoid cycle/pause double-counting. Invalid/reset counters or changed collector identities require a new baseline. A collection crossing a sample boundary can distort a short window; this is not CPU utilization or an exact pause percentage. Histogram request intervals are excluded.",
                        "Re-run the scan after a representative workload; if recent GC overhead stays high, increase the heap (-Xmx/-XX:MaxRAMPercentage), reduce the allocation rate, or review the collector choice.",
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/GarbageCollectorMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.GcTrend trend = context.gcTrend();
        if (!trend.available() || trend.deltaGcTimeMillis() < 0) {
            return skipped("No comparable GC baseline is available; re-run after a representative workload.");
        }
        if (trend.deltaUptimeMillis() < MIN_WINDOW_MILLIS) {
            return skipped("Too little time has passed since the last scan to measure recent GC overhead.");
        }
        int percent = MemoryFormat.percentOf(trend.deltaGcTimeMillis(), trend.deltaUptimeMillis());
        if (percent < THRESHOLD_PERCENT) {
            return pass();
        }
        String detail = "Approximate GC elapsed-time delta is " + trend.deltaGcTimeMillis() + " ms (" + percent
                + "%) over the last "
                + (trend.deltaUptimeMillis() / 1000) + "s"
                + (trend.deltaGcCount() >= 0 ? " across " + trend.deltaGcCount() + " collections" : "")
                + " since the previous scan; corroborate with GC logs because completed events can cross sampling boundaries.";
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
                "Flags Compressed Class Space at 85% of its reported maximum. Compressed class pointers are distinct from compressed ordinary object pointers; availability and capacity depend on JVM version and configuration. Exhaustion can cause OutOfMemoryError: Compressed class space.",
                "Review class generation and the effective class-space and Metaspace limits before changing -XX:CompressedClassSpaceSize; account for the JVM's object-header mode and native headroom.",
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
        if (ccs.max() <= 0 || ccs.used() < 0) {
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
                "Reads the current cgroup memory usage (memory.current for cgroup v2, memory.usage_in_bytes for v1) and compares its working set against the detected container memory limit. When memory.stat exposes inactive file cache, the rule subtracts that reclaimable portion before evaluating pressure. A working set near the limit leaves little room for the next allocation; if the kernel cannot reclaim enough charged memory, it may OOM-kill a process without invoking JVM OutOfMemoryError handling.",
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
        if (current == null || current < 0) {
            return skipped("Current container memory usage is not available (no cgroup files readable).");
        }
        Long workingSet = memory.containerMemoryWorkingSetBytes();
        if (workingSet != null && (workingSet < 0 || workingSet > current)) {
            return skipped("Container working-set usage is inconsistent with current usage.");
        }
        long measuredUsage = workingSet != null ? workingSet : current;
        int percent = MemoryFormat.percentOf(measuredUsage, limit);
        if (percent >= THRESHOLD_PERCENT) {
            String usageLabel = workingSet != null ? "Container working set" : "Container memory usage";
            return violation(
                    usageLabel + " is " + percent + "% of the cgroup limit ("
                            + MemoryFormat.bytes(measuredUsage) + " of " + MemoryFormat.bytes(limit)
                            + "); if the kernel cannot reclaim enough charged memory for the next allocation, it may OOM-kill a process.");
        }
        return pass();
    }
}

// ---------------------------------------------------------------------------
// GC configuration (Serial GC on multi-core)
// ---------------------------------------------------------------------------

final class SerialGcOnMultiCoreRule extends AbstractMemoryRule {

    /**
     * JDK 17, 21, and 25 select G1 ergonomically on a server-class machine (2+ CPUs and roughly 2 GiB
     * of memory); below that threshold Serial GC remains an expected default.
     */
    private static final long SERVER_CLASS_MEMORY_THRESHOLD_BYTES = 2 * MemoryFormat.GIGABYTE;

    SerialGcOnMultiCoreRule() {
        super(new MemoryRuleDefinition(
                "MEM-GC-004",
                "Serial GC selected on a multi-core system",
                MemoryCategory.GC_CONFIGURATION,
                "LOW",
                "Notes Serial GC on a JVM with at least two available processors and roughly 2 GiB of known memory. This historical HotSpot server-class threshold is context, not proof that Serial is wrong: small heaps and particular workloads may benefit from it.",
                "Compare measured pause, throughput and footprint requirements before choosing a different collector; keep Serial when it suits the workload.",
                "https://openjdk.org/jeps/248"));
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
        long effectiveMemoryBytes = effectiveMemoryBytes(memory, context.runtime());
        if (effectiveMemoryBytes < 0) {
            return skipped("Available physical or container memory is unknown.");
        }
        if (effectiveMemoryBytes >= 0 && effectiveMemoryBytes < SERVER_CLASS_MEMORY_THRESHOLD_BYTES) {
            return skipped("Serial GC is expected below Oracle's historical 'server-class machine' ergonomics"
                    + " threshold of 2 CPUs and ~2 GiB of memory (available: "
                    + MemoryFormat.bytes(effectiveMemoryBytes) + ").");
        }
        return violation("Serial GC is active ('Copy'/'MarkSweepCompact') on a " + cpus
                + "-CPU system; review collector tradeoffs against measured workload goals, not CPU count alone.");
    }

    /**
     * Prefers the container memory limit (what actually bounds this JVM); falls back to total
     * physical memory when no container limit is detected. Returns -1 when neither is known.
     */
    private static long effectiveMemoryBytes(MemoryData memory, MemoryContext.RuntimeData runtime) {
        Long containerLimit = memory.containerMemoryLimitBytes();
        if (containerLimit != null && containerLimit > 0) {
            return containerLimit;
        }
        long totalPhysical = runtime.totalPhysicalMemoryBytes();
        return totalPhysical > 0 ? totalPhysical : -1;
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
                "INFO",
                "Reports a G1 Old Generation collection-count increase between comparable scans, excluding their histogram request intervals. A Full GC can result from allocation pressure, explicit GC or another diagnostic request. The counter alone does not identify the cause or prove G1 failed to keep up.",
                "Inspect unified GC logs for the collection cause and pause duration before changing heap size or G1 tuning. Do not infer allocation failure from the count alone.",
                "https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.GcTrend trend = context.gcTrend();
        if (!trend.available()) {
            return skipped("No previous scan to compare; re-run the scan to measure G1 Full GC frequency.");
        }
        if (!trend.perCollectorDeltas().containsKey("G1 Old Generation")) {
            return skipped("No comparable G1 Old Generation collection count is available.");
        }
        long fullGcDelta = trend.perCollectorDeltas().getOrDefault("G1 Old Generation", 0L);
        if (fullGcDelta <= 0) {
            return pass();
        }
        return violation(
                "G1 Full GC occurred " + fullGcDelta + " time(s) since the last scan (G1 Old Generation"
                        + " collection count increased); the cause is unknown and may include explicit GC or other diagnostics. Check GC logs before tuning.");
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
                        "Committed heap is far above observed heap usage",
                        MemoryCategory.HEAP_PRESSURE,
                        "INFO",
                        "Notes at least 1 GiB of slack and committed heap at least twice used heap in one snapshot after 10 minutes of uptime. This does not establish a live set, consistently unused capacity, resident memory, or production sizing needs.",
                        "Observe representative steady-state and burst workloads, allocation rate and GC behavior before changing heap sizing. Spare committed capacity may be intentional; this snapshot is not a safe downsizing recommendation.",
                        "https://docs.oracle.com/en/java/javase/21/gctuning/factors-affecting-garbage-collection-performance.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        if (context.runtime().uptimeMillis() < MIN_UPTIME_MILLIS) {
            return skipped("JVM uptime is too short to assess heap over-provisioning.");
        }
        MemoryContext.PostGcHeapData postGc = context.postGcHeap();
        boolean postAvailable = postGc.heapAvailable() && postGc.heapCommitted() >= 0 && postGc.heapUsed() >= 0;
        long committed =
                postAvailable ? postGc.heapCommitted() : context.memory().heapCommitted();
        if (committed <= 0) {
            return skipped("Committed heap size is not available.");
        }
        long used = postAvailable ? postGc.heapUsed() : context.memory().heapUsed();
        if (used < 0 || used > committed) {
            return skipped("Comparable heap usage and committed capacity are unavailable.");
        }
        long slack = committed - used;
        if (used <= committed / COMMITTED_TO_USED_RATIO && slack >= MIN_SLACK_BYTES) {
            return violation("Committed heap " + MemoryFormat.bytes(committed)
                    + " is at least " + COMMITTED_TO_USED_RATIO + "x observed heap usage "
                    + MemoryFormat.bytes(used) + " (slack " + MemoryFormat.bytes(slack)
                    + ") in one snapshot; this does not establish spare production capacity or a safe heap reduction.");
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
                "INFO",
                "Notes input arguments selecting interpreted or reduced-tier compilation. These can be deliberate startup, development or diagnostic choices; the arguments alone do not prove a throughput or memory fault.",
                "Review effective compiler settings and workload goals before changing deliberate development or startup configuration.",
                "https://docs.oracle.com/en/java/javase/21/vm/java-virtual-machine-technology-overview.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryData memory = context.memory();
        String executionMode = "";
        String tieredLevel = null;
        for (String arg : memory.inputArguments()) {
            if (arg.equals("-Xint") || arg.equals("-Xmixed") || arg.equals("-Xcomp")) executionMode = arg;
            if (arg.startsWith("-XX:TieredStopAtLevel=")) tieredLevel = arg;
        }
        if (executionMode.equals("-Xint")) {
            return violation("JVM is running in fully interpreted mode (-Xint); JIT compilation is disabled.");
        }
        if (Boolean.FALSE.equals(memory.booleanJvmArgument("UseCompiler"))) {
            return violation("JIT compiler is explicitly disabled (-XX:-UseCompiler).");
        }
        if (tieredLevel != null && !Boolean.FALSE.equals(memory.booleanJvmArgument("TieredCompilation"))) {
            String arg = tieredLevel;
            try {
                int level = Integer.parseInt(
                        arg.substring("-XX:TieredStopAtLevel=".length()).trim());
                if (level < 4) {
                    return violation(arg + " caps JIT at tier " + level
                            + " (below tier 4); assess whether this is intentional for startup or diagnostics.");
                }
            } catch (NumberFormatException ex) {
                return skipped("The compilation tier argument could not be interpreted.");
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
                "High system or environment swap utilization",
                MemoryCategory.NATIVE_MEMORY,
                "INFO",
                "Reports at least 50% swap usage from the operating-system MXBean. These system/environment statistics do not identify this JVM's swapped pages, active paging, or latency. Comparing JVM reservations with currently free RAM cannot establish residency.",
                "Inspect OS process residency and paging activity before attributing swap or latency to this JVM. Do not reduce heap based on system swap usage alone.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        long totalSwap = context.runtime().totalSwapSpaceBytes();
        long freeSwap = context.runtime().freeSwapSpaceBytes();
        if (totalSwap < 0 || freeSwap < 0 || freeSwap > totalSwap) {
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
        return violation(
                swapPercent + "% of swap is in use (" + MemoryFormat.bytes(usedSwap) + " of "
                        + MemoryFormat.bytes(totalSwap)
                        + ") in the operating-system MXBean's environment; this does not establish that this JVM is swapped out or that paging is active.");
    }
}

// ---------------------------------------------------------------------------
// GC configuration (latest-event duration)
// ---------------------------------------------------------------------------

final class GcEventDurationOutlierRule extends AbstractMemoryRule {

    private static final long PAUSE_THRESHOLD_MILLIS = 1_000L;

    GcEventDurationOutlierRule() {
        super(new MemoryRuleDefinition(
                "MEM-GC-006",
                "Most recently completed GC event was long",
                MemoryCategory.GC_CONFIGURATION,
                "MEDIUM",
                "Compares GcInfo.endTime across collector beans to identify the garbage-collection event that"
                        + " actually completed most recently before this scan's histogram, then flags when its duration exceeds "
                        + PAUSE_THRESHOLD_MILLIS + " ms. GcInfo duration is elapsed collection time and is not"
                        + " necessarily a stop-the-world pause for concurrent collectors. This complements the"
                        + " lifetime and recent GC-overhead-ratio checks (MEM-GC-002/MEM-GC-003): a JVM can stay"
                        + " under those ratio thresholds while still reporting a long collection event. This is"
                        + " a single-event reading; an unchanged event from the prior scan's histogram is skipped.",
                "Capture unified GC logs (-Xlog:gc*:file=gc.log:time,level,tags) and inspect the event's phases"
                        + " and cause before tuning heap size, allocation rate, or collector pause goals.",
                "https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/GcInfo.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.GcEvent latestGcEvent = context.latestGcEvent();
        long durationMillis = latestGcEvent.durationMillis();
        if (durationMillis < 0) {
            return skipped("No new application GC event is available (requires a HotSpot JVM and a collection since"
                    + " the previous scan).");
        }
        if (durationMillis < PAUSE_THRESHOLD_MILLIS) {
            return pass();
        }
        String collectorName = latestGcEvent.collectorName();
        String collectorNote = collectorName != null && !collectorName.isBlank() ? " (" + collectorName + ")" : "";
        return violation("The most recently completed GC event took " + durationMillis + " ms" + collectorNote
                + ", at or above the " + PAUSE_THRESHOLD_MILLIS + " ms threshold.");
    }
}

// ---------------------------------------------------------------------------
// Memory pools (buffer pool growth trend)
// ---------------------------------------------------------------------------

final class BufferPoolGrowthWithoutReleaseRule extends AbstractMemoryRule {

    private static final int GROWTH_STREAK_THRESHOLD = 3;

    BufferPoolGrowthWithoutReleaseRule() {
        super(
                new MemoryRuleDefinition(
                        "MEM-POOL-007",
                        "Direct buffer usage has increased across comparable scans",
                        MemoryCategory.MEMORY_POOLS,
                        "LOW",
                        "Reports three increases in estimated direct-buffer used bytes across four comparable snapshots."
                                + " Missing or unknown readings break the streak. Net growth does not establish that no"
                                + " releases occurred between samples or that a leak exists; cache warmup and changing load"
                                + " can produce the same observations. Mapped buffers are excluded. Severity is MEDIUM"
                                + " only when capacity is also near a known effective direct-memory cap.",
                        "Compare representative steady-state load and supported buffer-pool lifecycle metrics before"
                                + " investigating retention. Use library release/close APIs where required, not manual Cleaner calls.",
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/BufferPoolMXBean.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.BufferPoolTrend trend = context.bufferPoolTrend();
        Optional<MemoryContext.BufferPoolSnapshot> direct = context.memory().bufferPools().stream()
                .filter(pool -> "direct".equalsIgnoreCase(pool.name()))
                .findFirst();
        if (direct.isEmpty()
                || direct.get().used() < 0
                || !trend.available()
                || !trend.consecutiveIncreaseStreaks().containsKey(direct.get().name())) {
            return skipped("No comparable direct-buffer usage baseline is available.");
        }
        List<MemoryContext.BufferPoolSnapshot> pools = context.memory().bufferPools();
        List<String> details = new ArrayList<>();
        boolean escalateToHigh = false;
        boolean directPoolAtStaticThreshold = MemoryRuleSupport.VIOLATION.equals(
                new DirectBufferGrowthRule().evaluate(context).status());
        for (MemoryContext.BufferPoolSnapshot pool : pools) {
            if (!"direct".equalsIgnoreCase(pool.name())) {
                continue;
            }
            int streak = trend.streakFor(pool.name());
            if (streak < GROWTH_STREAK_THRESHOLD) {
                continue;
            }
            if (directPoolAtStaticThreshold) {
                escalateToHigh = true;
            }
            details.add(
                    "'" + pool.name() + "' buffer pool usage increased on " + streak
                            + " consecutive scan intervals (now " + MemoryFormat.bytes(pool.used())
                            + "); net growth does not prove missing releases or a leak. Confirm comparable workload conditions.");
        }
        if (details.isEmpty()) {
            return pass();
        }
        return violation(escalateToHigh ? MemoryRuleSupport.MEDIUM : MemoryRuleSupport.LOW, details);
    }
}

// ---------------------------------------------------------------------------
// Heap pressure (old-generation usage trend)
// ---------------------------------------------------------------------------

final class OldGenerationTrendingUpwardRule extends AbstractMemoryRule {

    private static final int GROWTH_STREAK_THRESHOLD = 3;

    OldGenerationTrendingUpwardRule() {
        super(new MemoryRuleDefinition(
                "MEM-HEAP-008",
                "Old-generation occupancy is increasing across comparable scans",
                MemoryCategory.HEAP_PRESSURE,
                "LOW",
                "Reports three increases in old-generation occupancy across four comparable post-histogram snapshots."
                        + " Missing observations break the streak. A histogram request does not verify full collection;"
                        + " occupancy is not retained size, and normal warmup or changing load can produce the same trend.",
                "Compare observations under stable load and confirmed collector-specific reclamation before investigating"
                        + " a leak. Use an explicitly requested heap/JFR investigation only when evidence warrants its cost.",
                "https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html"));
    }

    @Override
    io.github.jdubois.bootui.core.dto.MemoryRuleResultDto evaluateRule(MemoryContext context) {
        MemoryContext.OldGenTrend trend = context.oldGenTrend();
        if (!trend.available()) {
            return skipped("No previous scan to compare; re-run the scan several times to measure the"
                    + " old-generation trend.");
        }
        if (trend.consecutiveIncreaseStreak() < GROWTH_STREAK_THRESHOLD) {
            return pass();
        }
        return violation(
                "Post-histogram old-generation occupancy has increased on " + trend.consecutiveIncreaseStreak()
                        + " consecutive scan intervals (now " + MemoryFormat.bytes(trend.lastUsedBytes())
                        + "); this does not establish retained-size growth or a leak. Confirm stable workload and collection evidence.");
    }
}
