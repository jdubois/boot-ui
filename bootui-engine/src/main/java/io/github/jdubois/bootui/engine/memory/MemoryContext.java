package io.github.jdubois.bootui.engine.memory;

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
        GcSample postHistogramGc,
        GcEvent latestGcEvent,
        GcEvent postHistogramGcEvent,
        GcTrend gcTrend,
        BufferPoolTrend bufferPoolTrend,
        OldGenTrend oldGenTrend) {

    MemoryContext {
        memory = memory == null ? MemoryData.empty() : memory;
        threads = threads == null ? ThreadData.empty() : threads;
        heapContent = heapContent == null ? HeapContentData.unavailable() : heapContent;
        postGcHeap = postGcHeap == null ? PostGcHeapData.unavailable() : postGcHeap;
        classLoading = classLoading == null ? ClassLoadingData.empty() : classLoading;
        runtime = runtime == null ? RuntimeData.empty() : runtime;
        preHistogramGc = preHistogramGc == null ? GcSample.from(runtime) : preHistogramGc;
        postHistogramGc = postHistogramGc == null ? GcSample.from(runtime) : postHistogramGc;
        latestGcEvent = latestGcEvent == null ? GcEvent.from(runtime) : latestGcEvent;
        postHistogramGcEvent = postHistogramGcEvent == null ? GcEvent.unavailable() : postHistogramGcEvent;
        gcTrend = gcTrend == null ? GcTrend.unavailable() : gcTrend;
        bufferPoolTrend = bufferPoolTrend == null ? BufferPoolTrend.unavailable() : bufferPoolTrend;
        oldGenTrend = oldGenTrend == null ? OldGenTrend.unavailable() : oldGenTrend;
    }

    /**
     * Backward-compatible constructor for callers that predate the post-histogram GC baseline. The
     * fallback retains aggregate GC counters but cannot preserve a per-collector breakdown.
     */
    MemoryContext(
            MemoryData memory,
            ThreadData threads,
            HeapContentData heapContent,
            PostGcHeapData postGcHeap,
            ClassLoadingData classLoading,
            RuntimeData runtime,
            GcSample preHistogramGc,
            GcTrend gcTrend,
            BufferPoolTrend bufferPoolTrend,
            OldGenTrend oldGenTrend) {
        this(
                memory,
                threads,
                heapContent,
                postGcHeap,
                classLoading,
                runtime,
                preHistogramGc,
                null,
                null,
                null,
                gcTrend,
                bufferPoolTrend,
                oldGenTrend);
    }

    /**
     * Backward-compatible constructor for callers that predate the cross-scan buffer-pool-growth
     * and old-generation-trend rules (MEM-POOL-007 / MEM-HEAP-008); both trends default to
     * unavailable.
     */
    MemoryContext(
            MemoryData memory,
            ThreadData threads,
            HeapContentData heapContent,
            PostGcHeapData postGcHeap,
            ClassLoadingData classLoading,
            RuntimeData runtime,
            GcSample preHistogramGc,
            GcTrend gcTrend) {
        this(
                memory,
                threads,
                heapContent,
                postGcHeap,
                classLoading,
                runtime,
                preHistogramGc,
                null,
                null,
                null,
                gcTrend,
                null,
                null);
    }

    /**
     * Convenience constructor used by tests and any caller that does not provide post-histogram or
     * cross-scan data. The post-histogram heap reading defaults to unavailable, the pre-histogram GC
     * sample mirrors the (single) runtime reading, and every cross-scan trend is unavailable.
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
                memory,
                threads,
                heapContent,
                postGcHeap,
                classLoading,
                runtime,
                preHistogramGc,
                postHistogramGc,
                latestGcEvent,
                postHistogramGcEvent,
                trend,
                bufferPoolTrend,
                oldGenTrend);
    }

    /** Returns a copy of this context with the scanner-computed buffer-pool growth trend attached. */
    MemoryContext withBufferPoolTrend(BufferPoolTrend trend) {
        return new MemoryContext(
                memory,
                threads,
                heapContent,
                postGcHeap,
                classLoading,
                runtime,
                preHistogramGc,
                postHistogramGc,
                latestGcEvent,
                postHistogramGcEvent,
                gcTrend,
                trend,
                oldGenTrend);
    }

    /** Returns a copy of this context with the scanner-computed old-generation usage trend attached. */
    MemoryContext withOldGenTrend(OldGenTrend trend) {
        return new MemoryContext(
                memory,
                threads,
                heapContent,
                postGcHeap,
                classLoading,
                runtime,
                preHistogramGc,
                postHistogramGc,
                latestGcEvent,
                postHistogramGcEvent,
                gcTrend,
                bufferPoolTrend,
                trend);
    }

    /** Returns a copy with the scanner-filtered latest GC event attached. */
    MemoryContext withLatestGcEvent(GcEvent event) {
        return new MemoryContext(
                memory,
                threads,
                heapContent,
                postGcHeap,
                classLoading,
                runtime,
                preHistogramGc,
                postHistogramGc,
                event,
                postHistogramGcEvent,
                gcTrend,
                bufferPoolTrend,
                oldGenTrend);
    }

    int heapUsedPercent() {
        long max = memory.heapMax();
        return MemoryFormat.percentOf(memory.heapUsed(), max);
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
            return MemoryFormat.percentOf(used, max);
        }
    }

    /**
     * A single {@code java.nio} buffer pool reading ({@code BufferPoolMXBean}), typically "direct"
     * or "mapped". Captured per-pool (unlike the aggregated direct-buffer totals below) so
     * MEM-POOL-007 can track the direct pool across scans.
     */
    record BufferPoolSnapshot(String name, long used, long capacity, long count) {}

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
            Long containerMemoryCurrentBytes,
            Long containerMemoryWorkingSetBytes,
            List<BufferPoolSnapshot> bufferPools) {

        MemoryData {
            pools = pools == null ? List.of() : List.copyOf(pools);
            inputArguments = inputArguments == null ? List.of() : List.copyOf(inputArguments);
            gcCollectorNames = gcCollectorNames == null ? List.of() : List.copyOf(gcCollectorNames);
            bufferPools = bufferPools == null ? List.of() : List.copyOf(bufferPools);
        }

        /**
         * Backward-compatible constructor for callers that predate per-pool buffer-pool tracking
         * (MEM-POOL-007's cross-scan growth-without-release rule); defaults to no per-pool readings.
         */
        MemoryData(
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
                Long containerMemoryCurrentBytes,
                List<BufferPoolSnapshot> bufferPools) {
            this(
                    heapUsed,
                    heapCommitted,
                    heapMax,
                    nonHeapUsed,
                    nonHeapCommitted,
                    nonHeapMax,
                    pools,
                    directBufferUsed,
                    directBufferCapacity,
                    directBufferCount,
                    maxDirectMemoryBytes,
                    inputArguments,
                    gcCollectorNames,
                    containerMemoryLimitBytes,
                    containerMemoryCurrentBytes,
                    null,
                    bufferPools);
        }

        MemoryData(
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
            this(
                    heapUsed,
                    heapCommitted,
                    heapMax,
                    nonHeapUsed,
                    nonHeapCommitted,
                    nonHeapMax,
                    pools,
                    directBufferUsed,
                    directBufferCapacity,
                    directBufferCount,
                    maxDirectMemoryBytes,
                    inputArguments,
                    gcCollectorNames,
                    containerMemoryLimitBytes,
                    containerMemoryCurrentBytes,
                    null,
                    List.of());
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
                        return name.contains("code cache") || name.contains("codecache") || name.contains("codeheap");
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

        Boolean booleanJvmArgument(String option) {
            Boolean value = null;
            for (String arg : inputArguments) {
                if (("-XX:+" + option).equals(arg)) value = true;
                if (("-XX:-" + option).equals(arg)) value = false;
            }
            return value;
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
            List<ThreadInfoDto> threads,
            boolean detailsTruncated,
            String collectionError) {

        ThreadData {
            deadlockedThreadIds = deadlockedThreadIds == null ? List.of() : List.copyOf(deadlockedThreadIds);
            stateCounts = stateCounts == null ? List.of() : List.copyOf(stateCounts);
            threads = threads == null ? List.of() : List.copyOf(threads);
        }

        ThreadData(
                int total,
                int peak,
                int daemon,
                boolean cpuTimeSupported,
                boolean deadlockDetected,
                List<Long> deadlockedThreadIds,
                List<ThreadStateCountDto> stateCounts,
                List<ThreadInfoDto> threads,
                boolean detailsTruncated) {
            this(
                    total,
                    peak,
                    daemon,
                    cpuTimeSupported,
                    deadlockDetected,
                    deadlockedThreadIds,
                    stateCounts,
                    threads,
                    detailsTruncated,
                    null);
        }

        ThreadData(
                int total,
                int peak,
                int daemon,
                boolean cpuTimeSupported,
                boolean deadlockDetected,
                List<Long> deadlockedThreadIds,
                List<ThreadStateCountDto> stateCounts,
                List<ThreadInfoDto> threads) {
            this(
                    total,
                    peak,
                    daemon,
                    cpuTimeSupported,
                    deadlockDetected,
                    deadlockedThreadIds,
                    stateCounts,
                    threads,
                    false);
        }

        static ThreadData failed(String reason) {
            return new ThreadData(0, 0, 0, false, false, List.of(), List.of(), List.of(), false, reason);
        }

        static ThreadData empty() {
            return new ThreadData(0, 0, 0, false, false, List.of(), List.of(), List.of());
        }
    }

    record HeapContentData(
            boolean available,
            List<HeapClassHistogramEntryDto> histogram,
            long totalInstances,
            long totalBytes,
            String collectionError) {

        HeapContentData(
                boolean available, List<HeapClassHistogramEntryDto> histogram, long totalInstances, long totalBytes) {
            this(available, histogram, totalInstances, totalBytes, null);
        }

        HeapContentData {
            histogram = histogram == null ? List.of() : List.copyOf(histogram);
        }

        static HeapContentData unavailable() {
            return new HeapContentData(false, List.of(), 0, 0);
        }

        static HeapContentData failed() {
            return new HeapContentData(false, List.of(), 0, 0, "Class histogram collection failed.");
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
     * and GC-overhead rules, OS-level metrics (available processors, physical memory, swap space,
     * and the UseCompressedOops VM option), and the single most recently completed GC event's
     * duration/collector (used by MEM-GC-006) collected once per scan for GC and footprint
     * heuristics.
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
            Boolean useCompressedOops,
            long totalPhysicalMemoryBytes,
            long lastGcDurationMillis,
            String lastGcCollectorName,
            long freePhysicalMemoryBytes) {

        static final long DEFAULT_THREAD_STACK_BYTES = 1024L * 1024;

        /**
         * Backward-compatible constructor for callers that predate the total-physical-memory
         * reading (MEM-GC-004's server-class-machine ergonomics skip), the latest-GC-event reading
         * (MEM-GC-006), and free physical memory (MEM-FOOTPRINT-004). They default to unavailable so
         * existing behavior is preserved when these newer fields are not supplied.
         */
        RuntimeData(
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
            this(
                    uptimeMillis,
                    gcCollectionTimeMillis,
                    gcCollectionCount,
                    objectPendingFinalizationCount,
                    initialHeapBytes,
                    threadStackBytes,
                    availableProcessors,
                    freeSwapSpaceBytes,
                    totalSwapSpaceBytes,
                    useCompressedOops,
                    -1,
                    -1,
                    null,
                    -1);
        }

        /**
         * Backward-compatible constructor for callers that predate collection of free physical
         * memory in the runtime snapshot.
         */
        RuntimeData(
                long uptimeMillis,
                long gcCollectionTimeMillis,
                long gcCollectionCount,
                int objectPendingFinalizationCount,
                long initialHeapBytes,
                long threadStackBytes,
                int availableProcessors,
                long freeSwapSpaceBytes,
                long totalSwapSpaceBytes,
                Boolean useCompressedOops,
                long totalPhysicalMemoryBytes,
                long lastGcDurationMillis,
                String lastGcCollectorName) {
            this(
                    uptimeMillis,
                    gcCollectionTimeMillis,
                    gcCollectionCount,
                    objectPendingFinalizationCount,
                    initialHeapBytes,
                    threadStackBytes,
                    availableProcessors,
                    freeSwapSpaceBytes,
                    totalSwapSpaceBytes,
                    useCompressedOops,
                    totalPhysicalMemoryBytes,
                    lastGcDurationMillis,
                    lastGcCollectorName,
                    -1);
        }

        static RuntimeData empty() {
            return new RuntimeData(0, -1, 0, 0, -1, DEFAULT_THREAD_STACK_BYTES, 1, -1, -1, null);
        }
    }

    /**
     * Heap occupancy re-read after the histogram request. The historical type name does not imply
     * a verified full GC: the JVM can skip the requested collection, and allocations can resume
     * before these readings. Neither heap nor old-generation occupancy is a retained-size measurement.
     */
    record PostGcHeapData(
            boolean heapAvailable, long heapUsed, boolean oldGenAvailable, long oldGenUsed, long heapCommitted) {

        PostGcHeapData(boolean heapAvailable, long heapUsed, boolean oldGenAvailable, long oldGenUsed) {
            this(heapAvailable, heapUsed, oldGenAvailable, oldGenUsed, -1);
        }

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
     * Identity and duration of the latest completed collector event. GcInfo event ids and end times
     * share the JVM uptime time base, allowing the scanner to suppress a prior scan's histogram GC.
     */
    record GcEvent(long id, long endTimeMillis, long durationMillis, String collectorName) {

        static GcEvent unavailable() {
            return new GcEvent(-1, -1, -1, null);
        }

        static GcEvent from(RuntimeData runtime) {
            return runtime.lastGcDurationMillis() < 0
                    ? unavailable()
                    : new GcEvent(-1, -1, runtime.lastGcDurationMillis(), runtime.lastGcCollectorName());
        }

        boolean available() {
            return durationMillis >= 0;
        }

        boolean sameEvent(GcEvent other) {
            return other != null
                    && available()
                    && other.available()
                    && id >= 0
                    && id == other.id
                    && endTimeMillis == other.endTimeMillis
                    && java.util.Objects.equals(collectorName, other.collectorName);
        }
    }

    /**
     * GC activity between the previous scan and this one. The window deliberately spans the previous
     * scan's post-histogram sample to this scan's pre-histogram sample so that neither scan's own
     * diagnostic request interval contributes to the delta. Per-collector deltas allow rules to
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
            if (previous.uptimeMillis() < 0
                    || current.uptimeMillis() <= previous.uptimeMillis()
                    || (previous.gcTimeMillis() >= 0
                            && current.gcTimeMillis() >= 0
                            && current.gcTimeMillis() < previous.gcTimeMillis())
                    || (previous.gcCount() >= 0 && current.gcCount() >= 0 && current.gcCount() < previous.gcCount())
                    || !previous.perCollectorCounts()
                            .keySet()
                            .equals(current.perCollectorCounts().keySet())) {
                return unavailable();
            }
            long deltaGcTime = previous.gcTimeMillis() < 0 || current.gcTimeMillis() < 0
                    ? -1
                    : current.gcTimeMillis() - previous.gcTimeMillis();
            long deltaGcCount =
                    previous.gcCount() < 0 || current.gcCount() < 0 ? -1 : current.gcCount() - previous.gcCount();
            Map<String, Long> deltas = new HashMap<>();
            for (Map.Entry<String, Long> entry : current.perCollectorCounts().entrySet()) {
                long prev = previous.perCollectorCounts().get(entry.getKey());
                if (prev >= 0 && entry.getValue() >= 0 && entry.getValue() < prev) {
                    return unavailable();
                }
                if (prev < 0 || entry.getValue() < 0) {
                    continue;
                }
                long delta = entry.getValue() - prev;
                deltas.put(entry.getKey(), delta);
            }
            return new GcTrend(true, deltaGcTime, deltaUptime, deltaGcCount, deltas);
        }
    }

    /**
     * Sampled net-growth tracking for buffer pools. Missing/unknown observations break consecutive
     * evidence. Increasing endpoints do not prove no releases occurred between observations.
     */
    record BufferPoolTrend(boolean available, Map<String, Integer> consecutiveIncreaseStreaks) {

        BufferPoolTrend {
            consecutiveIncreaseStreaks =
                    consecutiveIncreaseStreaks == null ? Map.of() : Map.copyOf(consecutiveIncreaseStreaks);
        }

        static BufferPoolTrend unavailable() {
            return new BufferPoolTrend(false, Map.of());
        }

        int streakFor(String poolName) {
            return consecutiveIncreaseStreaks.getOrDefault(poolName, 0);
        }
    }

    /**
     * Consecutive increases in post-histogram old-generation occupancy, not retained-size or
     * verified post-full-GC growth. Missing/invalid readings require a new baseline.
     */
    record OldGenTrend(boolean available, int consecutiveIncreaseStreak, long lastUsedBytes) {

        static OldGenTrend unavailable() {
            return new OldGenTrend(false, 0, -1);
        }
    }
}
