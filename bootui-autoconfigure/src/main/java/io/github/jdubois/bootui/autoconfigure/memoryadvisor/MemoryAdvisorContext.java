package io.github.jdubois.bootui.autoconfigure.memoryadvisor;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Immutable, point-in-time aggregation of the JVM runtime data already produced by the Memory,
 * Threads, and Heap Dump panels. Memory Advisor rules read only from this context; they never
 * collect data themselves, which keeps each rule deterministic and unit-testable.
 */
record MemoryAdvisorContext(
        MemoryData memory, ThreadData threads, HeapContentData heapContent, ClassLoadingData classLoading) {

    MemoryAdvisorContext {
        memory = memory == null ? MemoryData.empty() : memory;
        threads = threads == null ? ThreadData.empty() : threads;
        heapContent = heapContent == null ? HeapContentData.unavailable() : heapContent;
        classLoading = classLoading == null ? ClassLoadingData.empty() : classLoading;
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
            Long containerMemoryLimitBytes) {

        MemoryData {
            pools = pools == null ? List.of() : List.copyOf(pools);
            inputArguments = inputArguments == null ? List.of() : List.copyOf(inputArguments);
            gcCollectorNames = gcCollectorNames == null ? List.of() : List.copyOf(gcCollectorNames);
        }

        static MemoryData empty() {
            return new MemoryData(0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, -1, List.of(), List.of(), null);
        }

        Optional<MemoryPoolSnapshot> oldGenerationPool() {
            return findPool(name -> name.contains("old gen") || name.contains("tenured"));
        }

        Optional<MemoryPoolSnapshot> metaspacePool() {
            return findPool(name -> name.equals("metaspace"));
        }

        Optional<MemoryPoolSnapshot> codeCachePool() {
            return pools.stream()
                    .filter(pool -> {
                        String name = lower(pool.name());
                        return name.contains("code cache") || name.contains("codeheap");
                    })
                    .reduce((first, second) -> new MemoryPoolSnapshot(
                            "Code cache",
                            first.used() + second.used(),
                            first.committed() + second.committed(),
                            sumMax(first.max(), second.max())));
        }

        private Optional<MemoryPoolSnapshot> findPool(java.util.function.Predicate<String> nameMatches) {
            return pools.stream()
                    .filter(pool -> nameMatches.test(lower(pool.name())))
                    .findFirst();
        }

        private static long sumMax(long first, long second) {
            if (first < 0 || second < 0) {
                return -1;
            }
            return first + second;
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
}
