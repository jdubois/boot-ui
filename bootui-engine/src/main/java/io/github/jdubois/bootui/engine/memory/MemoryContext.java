package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationCollector;
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
        ExplicitGcData explicitGc,
        AdvisorViolationCollector violationCollector) {

    MemoryContext(
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
            AdvisorViolationCollector violationCollector) {
        this(
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
                null,
                violationCollector);
    }

    MemoryContext(
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
            GcTrend gcTrend) {
        this(
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
                null,
                null);
    }

    MemoryContext withViolationCollector(AdvisorViolationCollector collector) {
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
                explicitGc,
                collector);
    }

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
        explicitGc = explicitGc == null ? ExplicitGcData.from(latestGcEvent) : explicitGc;
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
                gcTrend);
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
                explicitGc,
                violationCollector);
    }

    /** Returns a copy with the scanner-filtered latest GC event attached. */
    MemoryContext withLatestGcEvent(GcEvent event) {
        ExplicitGcData updatedExplicitGc =
                event == null || !event.available() ? ExplicitGcData.none() : ExplicitGcData.from(event);
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
                explicitGc.count() > 0 && !explicitGc.equals(ExplicitGcData.from(latestGcEvent))
                        ? explicitGc
                        : updatedExplicitGc,
                violationCollector);
    }

    /** Returns a copy with explicit-GC pause observations attached. */
    MemoryContext withExplicitGc(ExplicitGcData updatedExplicitGc) {
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
                updatedExplicitGc,
                violationCollector);
    }

    /** Returns a copy with the scanner-adjusted runtime data attached. */
    MemoryContext withRuntime(RuntimeData updatedRuntime) {
        return new MemoryContext(
                memory,
                threads,
                heapContent,
                postGcHeap,
                classLoading,
                updatedRuntime,
                preHistogramGc,
                postHistogramGc,
                latestGcEvent,
                postHistogramGcEvent,
                gcTrend,
                explicitGc,
                violationCollector);
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
    record MemoryPoolSnapshot(String name, long used, long committed, long max, long collectionUsed) {

        MemoryPoolSnapshot(String name, long used, long committed, long max) {
            this(name, used, committed, max, -1);
        }

        int usedPercent() {
            return MemoryFormat.percentOf(used, max);
        }

        int collectionUsedPercent() {
            return MemoryFormat.percentOf(collectionUsed, max);
        }
    }

    /**
     * A single {@code java.nio} buffer pool reading ({@code BufferPoolMXBean}), typically "direct"
     * or "mapped".
     */
    record BufferPoolSnapshot(String name, long used, long capacity, long count) {}

    /**
     * Linux cgroup v2 {@code memory.events} counters for kernel memory throttling and OOM activity.
     */
    record CgroupMemoryEvents(long high, long max, long oom, long oomKill) {

        boolean hasAnyEvent() {
            return high > 0 || max > 0 || oom > 0 || oomKill > 0;
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
            Long containerMemoryCurrentBytes,
            Long containerMemoryWorkingSetBytes,
            List<BufferPoolSnapshot> bufferPools,
            Long containerMemoryHighBytes,
            CgroupMemoryEvents containerMemoryEvents,
            Long containerMemoryHighCurrentBytes,
            Long containerMemoryHighWorkingSetBytes) {

        MemoryData {
            pools = pools == null ? List.of() : List.copyOf(pools);
            inputArguments = inputArguments == null ? List.of() : List.copyOf(inputArguments);
            gcCollectorNames = gcCollectorNames == null ? List.of() : List.copyOf(gcCollectorNames);
            bufferPools = bufferPools == null ? List.of() : List.copyOf(bufferPools);
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
                Long containerMemoryCurrentBytes,
                Long containerMemoryWorkingSetBytes,
                List<BufferPoolSnapshot> bufferPools,
                Long containerMemoryHighBytes,
                CgroupMemoryEvents containerMemoryEvents) {
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
                    containerMemoryWorkingSetBytes,
                    bufferPools,
                    containerMemoryHighBytes,
                    containerMemoryEvents,
                    containerMemoryCurrentBytes,
                    containerMemoryWorkingSetBytes);
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
                Long containerMemoryCurrentBytes,
                Long containerMemoryWorkingSetBytes,
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
                    containerMemoryWorkingSetBytes,
                    bufferPools,
                    null,
                    null);
        }

        /**
         * Backward-compatible constructor for callers that omit {@code containerMemoryWorkingSetBytes}.
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

        static boolean isNonHeapPoolName(String name) {
            String lower = lower(name);
            return lower.equals("metaspace")
                    || lower.equals("compressed class space")
                    || lower.contains("code cache")
                    || lower.contains("codecache")
                    || lower.contains("codeheap");
        }

        /**
         * Sums {@link MemoryPoolSnapshot#collectionUsed()} across heap pools when a post-collection
         * reading has been recorded. If an old-generation pool is present with positive current usage
         * but no recorded {@code collectionUsage} yet (or an unknown {@code -1} reading), returns
         * {@code -1} so callers do not mistake young-only {@code collectionUsage} for full-heap
         * post-collection occupancy.
         */
        long heapCollectionUsed() {
            long sum = 0;
            boolean anyPositive = false;
            for (MemoryPoolSnapshot pool : pools) {
                if (isNonHeapPoolName(pool.name())) {
                    continue;
                }
                if (pool.collectionUsed() < 0) {
                    if (isOldGenerationPoolName(pool.name()) && pool.used() > 0) {
                        return -1;
                    }
                    continue;
                }
                if (isOldGenerationPoolName(pool.name()) && pool.collectionUsed() == 0 && pool.used() > 0) {
                    return -1;
                }
                if (pool.collectionUsed() > 0) {
                    anyPositive = true;
                }
                sum = MemoryFormat.sum(sum, pool.collectionUsed());
                if (sum < 0) {
                    return -1;
                }
            }
            return anyPositive ? sum : -1;
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
            boolean populationComplete,
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
                boolean detailsTruncated,
                String collectionError) {
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
                    true,
                    collectionError);
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
            String collectionError,
            List<HeapClassHistogramEntryDto> classLoaderEntries,
            int dynamicClassLoaderClassCount,
            long dynamicClassLoaderInstances,
            int generatedProxyClassCount,
            long generatedProxyInstances,
            long generatedProxyBytes) {

        HeapContentData(
                boolean available,
                List<HeapClassHistogramEntryDto> histogram,
                long totalInstances,
                long totalBytes,
                String collectionError,
                List<HeapClassHistogramEntryDto> classLoaderEntries,
                long dynamicClassLoaderInstances,
                int generatedProxyClassCount,
                long generatedProxyInstances,
                long generatedProxyBytes) {
            this(
                    available,
                    histogram,
                    totalInstances,
                    totalBytes,
                    collectionError,
                    classLoaderEntries,
                    classLoaderEntries == null ? 0 : classLoaderEntries.size(),
                    dynamicClassLoaderInstances,
                    generatedProxyClassCount,
                    generatedProxyInstances,
                    generatedProxyBytes);
        }

        HeapContentData(
                boolean available,
                List<HeapClassHistogramEntryDto> histogram,
                long totalInstances,
                long totalBytes,
                String collectionError) {
            this(
                    available,
                    histogram,
                    totalInstances,
                    totalBytes,
                    collectionError,
                    HistogramClassSummary.from(histogram));
        }

        private HeapContentData(
                boolean available,
                List<HeapClassHistogramEntryDto> histogram,
                long totalInstances,
                long totalBytes,
                String collectionError,
                HistogramClassSummary summary) {
            this(
                    available,
                    histogram,
                    totalInstances,
                    totalBytes,
                    collectionError,
                    summary.classLoaderEntries(),
                    summary.dynamicClassLoaderClassCount(),
                    summary.dynamicClassLoaderInstances(),
                    summary.generatedProxyClassCount(),
                    summary.generatedProxyInstances(),
                    summary.generatedProxyBytes());
        }

        HeapContentData(
                boolean available, List<HeapClassHistogramEntryDto> histogram, long totalInstances, long totalBytes) {
            this(available, histogram, totalInstances, totalBytes, (String) null);
        }

        HeapContentData {
            histogram = histogram == null ? List.of() : List.copyOf(histogram);
            classLoaderEntries = classLoaderEntries == null ? List.of() : List.copyOf(classLoaderEntries);
        }

        static HeapContentData unavailable() {
            return new HeapContentData(false, List.of(), 0, 0, null, List.of(), 0, 0, 0, 0, 0);
        }

        static HeapContentData failed() {
            return new HeapContentData(
                    false, List.of(), 0, 0, "Class histogram collection failed.", List.of(), 0, 0, 0, 0, 0);
        }
    }

    /**
     * Pre-truncation ClassLoader and generated-proxy aggregates computed over the complete
     * {@code GC.class_histogram} before top-200 display truncation.
     */
    record HistogramClassSummary(
            List<HeapClassHistogramEntryDto> classLoaderEntries,
            int dynamicClassLoaderClassCount,
            long dynamicClassLoaderInstances,
            int generatedProxyClassCount,
            long generatedProxyInstances,
            long generatedProxyBytes) {

        private static final int MAX_CLASSLOADER_ENTRIES = 10;

        static HistogramClassSummary from(List<HeapClassHistogramEntryDto> entries) {
            if (entries == null || entries.isEmpty()) {
                return new HistogramClassSummary(List.of(), 0, 0, 0, 0, 0);
            }
            List<HeapClassHistogramEntryDto> loaders = new java.util.ArrayList<>();
            long loaderInstances = 0;
            int proxyClasses = 0;
            long proxyInstances = 0;
            long proxyBytes = 0;
            for (HeapClassHistogramEntryDto entry : entries) {
                if (entry == null || entry.instances() <= 0 || entry.bytes() < 0) {
                    continue;
                }
                String className = entry.className();
                if (isDynamicClassLoaderClass(className, entry.instances())) {
                    loaders.add(entry);
                    loaderInstances = BootUiDiagnosticGcTracker.saturateAdd(loaderInstances, entry.instances());
                }
                if (isGeneratedProxyClass(className)) {
                    proxyClasses++;
                    proxyInstances = BootUiDiagnosticGcTracker.saturateAdd(proxyInstances, entry.instances());
                    proxyBytes = BootUiDiagnosticGcTracker.saturateAdd(proxyBytes, entry.bytes());
                }
            }
            loaders.sort(java.util.Comparator.comparingLong(HeapClassHistogramEntryDto::instances)
                    .reversed()
                    .thenComparing(java.util.Comparator.comparingLong(HeapClassHistogramEntryDto::bytes)
                            .reversed())
                    .thenComparing(HeapClassHistogramEntryDto::className));
            int totalLoaderClasses = loaders.size();
            List<HeapClassHistogramEntryDto> topLoaders = totalLoaderClasses <= MAX_CLASSLOADER_ENTRIES
                    ? List.copyOf(loaders)
                    : List.copyOf(loaders.subList(0, MAX_CLASSLOADER_ENTRIES));
            return new HistogramClassSummary(
                    topLoaders, totalLoaderClasses, loaderInstances, proxyClasses, proxyInstances, proxyBytes);
        }

        static boolean isDynamicClassLoaderClass(String className, long instances) {
            if (className == null || instances <= 0 || DominantClassRule.isArrayClass(className)) {
                return false;
            }
            if (isJdkReflectionDelegatingClassLoader(className)) {
                return false;
            }
            if (isStandardSingletonClassLoader(className) && instances <= 5) {
                return false;
            }
            if (className.endsWith("ClassLoader")
                    || className.equals("javax.management.loading.MLet")
                    || className.equals("javax.management.loading.PrivateMLet")) {
                return true;
            }
            int dollar = className.lastIndexOf('$');
            if (dollar > 0 && className.substring(0, dollar).endsWith("ClassLoader")) {
                String inner = className.substring(dollar + 1);
                return inner.endsWith("Loader") || inner.equals("ChildFirst") || inner.equals("ParentLast");
            }
            return false;
        }

        private static boolean isJdkReflectionDelegatingClassLoader(String className) {
            return className.equals("jdk.internal.reflect.DelegatingClassLoader")
                    || className.equals("sun.reflect.DelegatingClassLoader");
        }

        private static boolean isStandardSingletonClassLoader(String className) {
            return className.startsWith("jdk.internal.loader.ClassLoaders$")
                    || className.equals("org.springframework.boot.loader.launch.LaunchedClassLoader")
                    || className.equals("org.springframework.boot.loader.LaunchedURLClassLoader")
                    || className.equals("io.quarkus.bootstrap.runner.RunnerClassLoader");
        }

        static boolean isGeneratedProxyClass(String className) {
            if (className == null || DominantClassRule.isArrayClass(className)) {
                return false;
            }
            return className.contains("$$SpringCGLIB$$")
                    || className.contains("$$EnhancerBy")
                    || className.contains("$ByteBuddy$")
                    || className.contains("$Proxy")
                    || className.startsWith("jdk.proxy");
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
     * and the UseCompressedOops VM option), the single most recently completed GC event's
     * duration/collector (used by MEM-GC-006), and the live HotSpot {@code MaxHeapSize},
     * {@code ObjectAlignmentInBytes} and {@code ZGenerational} options (-1/{@code null} when the
     * option is not readable) collected once per scan for GC and footprint heuristics.
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
            long freePhysicalMemoryBytes,
            long maxHeapSizeOptionBytes,
            long objectAlignmentBytes,
            Boolean zGenerational,
            Boolean useCompactObjectHeaders) {

        static final long DEFAULT_THREAD_STACK_BYTES = 1024L * 1024;

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
                String lastGcCollectorName,
                long freePhysicalMemoryBytes,
                long maxHeapSizeOptionBytes,
                long objectAlignmentBytes,
                Boolean zGenerational) {
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
                    freePhysicalMemoryBytes,
                    maxHeapSizeOptionBytes,
                    objectAlignmentBytes,
                    zGenerational,
                    null);
        }

        RuntimeData withGcCounters(long updatedUptimeMillis, long updatedGcTimeMillis, long updatedGcCount) {
            return new RuntimeData(
                    updatedUptimeMillis,
                    updatedGcTimeMillis,
                    updatedGcCount,
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
                    freePhysicalMemoryBytes,
                    maxHeapSizeOptionBytes,
                    objectAlignmentBytes,
                    zGenerational,
                    useCompactObjectHeaders);
        }

        /**
         * Backward-compatible constructor for callers that predate the live HotSpot {@code MaxHeapSize},
         * {@code ObjectAlignmentInBytes} and {@code ZGenerational} readings (MEM-HEAP-004, MEM-GC-008);
         * they default to unavailable.
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
                String lastGcCollectorName,
                long freePhysicalMemoryBytes) {
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
                    freePhysicalMemoryBytes,
                    -1,
                    -1,
                    null);
        }

        /**
         * Backward-compatible constructor for callers that predate the total-physical-memory
         * reading (MEM-GC-004's server-class-machine ergonomics skip), the latest-GC-event reading
         * (MEM-GC-006), and free physical memory. They default to unavailable so
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
    record GcEvent(long id, long endTimeMillis, long durationMillis, String collectorName, String gcCause) {

        GcEvent(long id, long endTimeMillis, long durationMillis, String collectorName) {
            this(id, endTimeMillis, durationMillis, collectorName, null);
        }

        static GcEvent unavailable() {
            return new GcEvent(-1, -1, -1, null, null);
        }

        static GcEvent from(RuntimeData runtime) {
            return runtime.lastGcDurationMillis() < 0
                    ? unavailable()
                    : new GcEvent(-1, -1, runtime.lastGcDurationMillis(), runtime.lastGcCollectorName(), null);
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
     * Aggregated external stop-the-world {@code System.gc()} pauses observed via
     * {@code GarbageCollectionNotificationInfo.getGcCause()} (excluding {@code Heap Inspection Initiated GC},
     * {@code Heap Dump Initiated GC}, and BootUI's own memory offload/low-footprint actions).
     */
    record ExplicitGcData(
            long count,
            long maxDurationMillis,
            long totalDurationMillis,
            String lastCollectorName,
            boolean notificationsObserved) {

        ExplicitGcData(long count, long maxDurationMillis, long totalDurationMillis, String lastCollectorName) {
            this(count, maxDurationMillis, totalDurationMillis, lastCollectorName, true);
        }

        static ExplicitGcData none() {
            return new ExplicitGcData(0, -1, 0, null, true);
        }

        static ExplicitGcData unobserved() {
            return new ExplicitGcData(0, -1, 0, null, false);
        }

        static ExplicitGcData from(GcEvent event) {
            if (event == null
                    || !event.available()
                    || !GcCollectorKinds.isExternalExplicitFullGc(event.collectorName(), null, event.gcCause())) {
                return none();
            }
            return new ExplicitGcData(1, event.durationMillis(), event.durationMillis(), event.collectorName(), true);
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
}
