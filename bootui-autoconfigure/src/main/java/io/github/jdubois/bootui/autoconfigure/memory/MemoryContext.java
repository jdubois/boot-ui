package io.github.jdubois.bootui.autoconfigure.memory;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, point-in-time aggregation of the JVM runtime data already produced by the Memory,
 * Threads, and Heap Dump panels. Memory Advisor rules read only from this context; they never
 * collect data themselves, which keeps each rule deterministic and unit-testable.
 */
record MemoryContext(
        MemoryData memory,
        ThreadData threads,
        HeapContentData heapContent,
        PostGcHeapData postGcHeap,
        ClassLoadingData classLoading,
        RuntimeData runtime,
        GcSample preHistogramGc,
        GcTrend gcTrend) {

    MemoryContext {
        memory = memory == null ? MemoryData.empty() : memory;
        threads = threads == null ? ThreadData.empty() : threads;
        heapContent = heapContent == null ? HeapContentData.unavailable() : heapContent;
        postGcHeap = postGcHeap == null ? PostGcHeapData.unavailable() : postGcHeap;
        classLoading = classLoading == null ? ClassLoadingData.empty() : classLoading;
        runtime = runtime == null ? RuntimeData.empty() : runtime;
        preHistogramGc = preHistogramGc == null ? GcSample.from(runtime) : preHistogramGc;
        gcTrend = gcTrend == null ? GcTrend.unavailable() : gcTrend;
    }

    /**
     * Convenience constructor used by tests and any caller that does not provide post-GC or
     * cross-scan data. The post-GC heap reading defaults to unavailable, the pre-histogram GC
     * sample mirrors the (single) runtime reading, and the GC trend is unavailable.
     */
    MemoryContext(
            MemoryData memory,
            ThreadData threads,
            HeapContentData heapContent,
            ClassLoadingData classLoading,
            RuntimeData runtime) {
        this(memory, threads, heapContent, PostGcHeapData.unavailable(), classLoading, runtime, null, null);
    }

    /** Returns a copy of this context with the scanner-computed GC trend attached. */
    MemoryContext withGcTrend(GcTrend trend) {
        return new MemoryContext(
                memory, threads, heapContent, postGcHeap, classLoading, runtime, preHistogramGc, trend);
    }

    int heapUsedPercent() {
        long max = memory.heapMax();
        if (max > 0) {
            return (int) Math.min(100, memory.heapUsed() * 100L / max);
        }
        long committed = memory.heapCommitted();
        return committed > 0 ? (int) Math.min(100, memory.heapUsed() * 100L / committed) : 0;
    }

    int blockedThreadCount() {
        return threads.stateCounts().stream()
                .filter(count -> "BLOCKED".equalsIgnoreCase(count.state()))
                .mapToInt(ThreadStateCountDto::count)
                .sum();
    }

    /**
     * A single JVM memory pool reading (heap region, metaspace, or code cache).
     */
    record MemoryPoolSnapshot(String name, long used, long committed, long max) {

        int usedPercent() {
            if (max > 0) {
                return (int) Math.min(100, used * 100L / max);
            }
            return committed > 0 ? (int) Math.min(100, used * 100L / committed) : 0;
        }
    }

    record MemoryData(
            long heapUsed,
            long heapCommitted,
            long heapMax,
            long nonHeapUsed,
            long nonHeapCommitted,
            long nonHeapMax,
            List<MemoryPoolSnapshot> pools,
            long directBufferUsed,
            long directBufferCapacity,
            long directBufferCount,
            long maxDirectMemoryBytes,
            List<String> inputArguments,
            List<String> gcCollectorNames,
            Long containerMemoryLimitBytes,
            Long containerMemoryCurrentBytes) {

        MemoryData {
            pools = pools == null ? List.of() : List.copyOf(pools);
            inputArguments = inputArguments == null ? List.of() : List.copyOf(inputArguments);
            gcCollectorNames = gcCollectorNames == null ? List.of() : List.copyOf(gcCollectorNames);
        }

        static MemoryData empty() {
            return new MemoryData(0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, -1, List.of(), List.of(), null, null);
        }

        Optional<MemoryPoolSnapshot> oldGenerationPool() {
            return pools.stream()
                    .filter(pool -> isOldGenerationPoolName(pool.name()))
                    .findFirst();
        }

        /** Matches the tenured/old-generation pool name across the HotSpot collectors. */
        static boolean isOldGenerationPoolName(String name) {
            String lower = lower(name);
            return lower.contains("old gen") || lower.contains("tenured");
        }

        Optional<MemoryPoolSnapshot> metaspacePool() {
            return findPool(name -> name.equals("metaspace"));
        }

        Optional<MemoryPoolSnapshot> compressedClassSpacePool() {
            return findPool(name -> name.equals("compressed class space"));
        }

        List<MemoryPoolSnapshot> codeCachePools() {
            return pools.stream()
                    .filter(pool -> {
                        String name = lower(pool.name());
                        return name.contains("code cache") || name.contains("codeheap");
                    })
                    .toList();
        }

        private Optional<MemoryPoolSnapshot> findPool(java.util.function.Predicate<String> nameMatches) {
            return pools.stream()
                    .filter(pool -> nameMatches.test(lower(pool.name())))
                    .findFirst();
        }

        boolean usesGarbageCollector(String token) {
            String needle = token.toLowerCase(Locale.ROOT);
            for (String name : gcCollectorNames) {
                if (lower(name).contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasJvmArgumentPrefix(String prefix) {
            String needle = prefix.toLowerCase(Locale.ROOT);
            for (String arg : inputArguments) {
                if (arg != null && lower(arg).startsWith(needle)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasJvmArgument(String flag) {
            for (String arg : inputArguments) {
                if (flag.equalsIgnoreCase(arg)) {
                    return true;
                }
            }
            return false;
        }

        private static String lower(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT);
        }
    }

    record ThreadData(
            int total,
            int peak,
            int daemon,
            boolean cpuTimeSupported,
            boolean deadlockDetected,
            List<Long> deadlockedThreadIds,
            List<ThreadStateCountDto> stateCounts,
            List<ThreadInfoDto> threads) {

        ThreadData {
            deadlockedThreadIds = deadlockedThreadIds == null ? List.of() : List.copyOf(deadlockedThreadIds);
            stateCounts = stateCounts == null ? List.of() : List.copyOf(stateCounts);
            threads = threads == null ? List.of() : List.copyOf(threads);
        }

        static ThreadData empty() {
            return new ThreadData(0, 0, 0, false, false, List.of(), List.of(), List.of());
        }
    }

    record HeapContentData(
            boolean available, List<HeapClassHistogramEntryDto> histogram, long totalInstances, long totalBytes) {

        HeapContentData {
            histogram = histogram == null ? List.of() : List.copyOf(histogram);
        }

        static HeapContentData unavailable() {
            return new HeapContentData(false, List.of(), 0, 0);
        }
    }

    record ClassLoadingData(int loadedClasses, long totalLoadedClasses, long unloadedClasses) {

        static ClassLoadingData empty() {
            return new ClassLoadingData(0, 0, 0);
        }
    }

    /**
     * Process-level scalars that are cheap single readings from the JVM but are not part of the
     * memory, thread, or heap-content snapshots: JVM uptime, cumulative GC time/count, the pending
     * finalization backlog, the parsed {@code -Xms}/{@code -Xss} sizes used by the native-memory
     * and GC-overhead rules, plus OS-level metrics (available processors, swap space, and the
     * UseCompressedOops VM option) collected once per scan for GC and footprint heuristics.
     */
    record RuntimeData(
            long uptimeMillis,
            long gcCollectionTimeMillis,
            long gcCollectionCount,
            int objectPendingFinalizationCount,
            long initialHeapBytes,
            long threadStackBytes,
            int availableProcessors,
            long freeSwapSpaceBytes,
            long totalSwapSpaceBytes,
            Boolean useCompressedOops) {

        static final long DEFAULT_THREAD_STACK_BYTES = 1024L * 1024;

        static RuntimeData empty() {
            return new RuntimeData(0, -1, 0, 0, -1, DEFAULT_THREAD_STACK_BYTES, 1, -1, -1, null);
        }
    }

    /**
     * Heap occupancy re-read immediately after the {@code GC.class_histogram} diagnostic command,
     * which forces a full GC. Comparing this post-GC reading with the pre-GC {@link MemoryData}
     * lets the heap-pressure rules distinguish sustained retained pressure (still high after a GC)
     * from transient garbage that the collection reclaims. Heap and old-generation availability are
     * tracked separately because a collector may expose live heap usage without an old-gen pool.
     */
    record PostGcHeapData(boolean heapAvailable, long heapUsed, boolean oldGenAvailable, long oldGenUsed) {

        static PostGcHeapData unavailable() {
            return new PostGcHeapData(false, -1, false, -1);
        }
    }

    /** A point-in-time GC counter reading used to derive scan-to-scan {@link GcTrend} deltas. */
    record GcSample(long uptimeMillis, long gcTimeMillis, long gcCount, Map<String, Long> perCollectorCounts) {

        GcSample {
            perCollectorCounts = perCollectorCounts == null ? Map.of() : Map.copyOf(perCollectorCounts);
        }

        /** Backward-compatible constructor without per-collector breakdown. */
        GcSample(long uptimeMillis, long gcTimeMillis, long gcCount) {
            this(uptimeMillis, gcTimeMillis, gcCount, null);
        }

        static GcSample from(RuntimeData runtime) {
            return new GcSample(runtime.uptimeMillis(), runtime.gcCollectionTimeMillis(), runtime.gcCollectionCount());
        }
    }

    /**
     * GC activity between the previous scan and this one. The window deliberately spans the previous
     * scan's post-histogram sample to this scan's pre-histogram sample so that neither scan's own
     * forced full GC is counted as application GC overhead. Per-collector deltas allow rules to
     * track specific collectors (e.g. G1 Full GC frequency).
     */
    record GcTrend(
            boolean available,
            long deltaGcTimeMillis,
            long deltaUptimeMillis,
            long deltaGcCount,
            Map<String, Long> perCollectorDeltas) {

        GcTrend {
            perCollectorDeltas = perCollectorDeltas == null ? Map.of() : Map.copyOf(perCollectorDeltas);
        }

        static GcTrend unavailable() {
            return new GcTrend(false, 0, 0, 0, null);
        }

        static GcTrend between(GcSample previous, GcSample current) {
            long deltaUptime = current.uptimeMillis() - previous.uptimeMillis();
            if (deltaUptime <= 0 || previous.gcTimeMillis() < 0 || current.gcTimeMillis() < 0) {
                return unavailable();
            }
            long deltaGcTime = Math.max(0, current.gcTimeMillis() - previous.gcTimeMillis());
            long deltaGcCount = Math.max(0, current.gcCount() - previous.gcCount());
            Map<String, Long> deltas = new HashMap<>();
            for (Map.Entry<String, Long> entry : current.perCollectorCounts().entrySet()) {
                long prev = previous.perCollectorCounts().getOrDefault(entry.getKey(), 0L);
                long delta = Math.max(0L, entry.getValue() - prev);
                if (delta > 0) {
                    deltas.put(entry.getKey(), delta);
                }
            }
            return new GcTrend(true, deltaGcTime, deltaUptime, deltaGcCount, deltas);
        }
    }
}
