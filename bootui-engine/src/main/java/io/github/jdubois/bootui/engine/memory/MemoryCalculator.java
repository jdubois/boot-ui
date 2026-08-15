package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import java.math.BigDecimal;

/**
 * Paketo {@code libjvm}-style JVM memory calculator.
 *
 * <p>Partitions a target container-memory budget into JVM regions using:
 * <pre>
 *   heap = totalMemory − headRoom − directMemory − metaspace
 *          − reservedCodeCache − (stack × threadCount)
 * </pre>
 *
 * <p>The formula and the default region sizes mirror
 * {@code paketo-buildpacks/libjvm/calc/calculator.go}. We adapt the
 * buildpack model where BootUI has live JVM observations:
 *
 * <ul>
 *   <li><b>Loaded class count</b> comes from
 *       {@link java.lang.management.ClassLoadingMXBean#getLoadedClassCount()}
 *       instead of counting JAR entries at build time, so the buildpack's
 *       {@code ClassLoadFactor = 0.35} (a build-time → runtime estimator) is
 *       not applied. We do apply a {@link #META_SAFETY_FACTOR} on top of the
 *       observed count to account for lazy class loading after the user
 *       opens the panel.</li>
 *   <li><b>Direct memory</b> keeps libjvm's 10 MiB fallback, but is raised to
 *       at least the currently observed direct-buffer usage. The generated
 *       options deliberately do not hard-cap direct memory because a live
 *       snapshot cannot predict future Netty/NIO demand.</li>
 *   <li><b>Default thread count</b> is {@code max(liveThreads, 250)}. Virtual
 *       threads do not reduce this platform-thread reserve or {@code -Xss}:
 *       their stacks are heap-backed, while carrier and helper platform
 *       threads still use native stacks.</li>
 * </ul>
 *
 * <p>This class is pure (no Spring, no JMX); the controller resolves all
 * inputs and passes them in. That makes the formula trivial to unit-test.
 */
final class MemoryCalculator {

    private static final long MEBIBYTE = 1024L * 1024L;

    /**
     * libjvm: {@code DefaultDirectMemory = 10 * Mebi}. This is a modeling
     * fallback, not a universally safe hard cap.
     */
    static final long DIRECT_MEMORY_BYTES = 10L * MEBIBYTE;

    /**
     * libjvm: {@code DefaultReservedCodeCache = 240 * Mebi}.
     */
    static final long CODE_CACHE_BYTES = 240L * MEBIBYTE;

    /**
     * libjvm: {@code DefaultStack = 1 * Mebi}.
     */
    static final long STACK_BYTES_PER_THREAD = MEBIBYTE;

    /**
     * libjvm: {@code ClassOverhead = 14_000_000} (decimal MB, not MiB).
     */
    static final long META_BASE_BYTES = 14_000_000L;

    /**
     * libjvm: {@code ClassSize = 5_800} bytes per loaded class.
     */
    static final long META_PER_CLASS_BYTES = 5_800L;

    /**
     * Safety factor on observed loaded-class count, used to size metaspace.
     * The live count is a snapshot and applications can load more classes
     * after the panel opens. This is an explicit BootUI heuristic, not a JVM
     * ergonomic guarantee.
     */
    static final double META_SAFETY_FACTOR = 1.25;

    /**
     * Floor for the default thread count, matching libjvm's value.
     */
    static final int DEFAULT_THREAD_COUNT_FLOOR = 250;

    static final int MIN_THREAD_COUNT = 1;
    static final int MAX_THREAD_COUNT = 10_000;
    static final int MIN_HEAD_ROOM_PERCENT = 0;
    static final int MAX_HEAD_ROOM_PERCENT = 30;
    static final long MIN_TOTAL_MEMORY_BYTES = 128L * MEBIBYTE;
    static final long MAX_TOTAL_MEMORY_BYTES = 64L * 1024 * MEBIBYTE;

    static int defaultThreadCount(int liveThreadCount) {
        return Math.max(liveThreadCount, DEFAULT_THREAD_COUNT_FLOOR);
    }

    private static long bytesToMiBFloor(long bytes) {
        return Math.max(0, bytes / MEBIBYTE);
    }

    private static long bytesToMiBCeil(long bytes) {
        return Math.max(0, (bytes + MEBIBYTE - 1) / MEBIBYTE);
    }

    private static long roundUpTo(long value, long multiple) {
        if (value <= 0) return multiple;
        return ((value + multiple - 1) / multiple) * multiple;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Compute a memory plan for the given inputs.
     *
     * @param totalMemoryBytes     target container memory budget
     * @param threadCount          thread count to reserve stack memory for
     * @param loadedClasses        live class count from {@code ClassLoadingMXBean}
     * @param headRoomPercent      percentage of total memory to leave unallocated
     * @param liveThreadCount      current live thread count (reported for UI context)
     * @param liveLoadedClassCount currently loaded classes (reported for UI context)
     * @return calculation DTO; if inputs leave no room for any heap, the
     * returned DTO has {@code valid = false} and a non-null
     * {@code error} — no exception is thrown so the panel can keep
     * polling without an HTTP error
     */
    MemoryCalculationDto calculate(
            long totalMemoryBytes,
            int threadCount,
            int loadedClasses,
            int headRoomPercent,
            int liveThreadCount,
            int liveLoadedClassCount) {
        return calculate(
                totalMemoryBytes,
                threadCount,
                loadedClasses,
                headRoomPercent,
                liveThreadCount,
                liveLoadedClassCount,
                false);
    }

    MemoryCalculationDto calculate(
            long totalMemoryBytes,
            int threadCount,
            int loadedClasses,
            int headRoomPercent,
            int liveThreadCount,
            int liveLoadedClassCount,
            boolean virtualThreadsEnabled) {
        return calculate(
                totalMemoryBytes,
                threadCount,
                loadedClasses,
                headRoomPercent,
                liveThreadCount,
                liveLoadedClassCount,
                virtualThreadsEnabled,
                "spring.threads.virtual.enabled");
    }

    MemoryCalculationDto calculate(
            long totalMemoryBytes,
            int threadCount,
            int loadedClasses,
            int headRoomPercent,
            int liveThreadCount,
            int liveLoadedClassCount,
            boolean virtualThreadsEnabled,
            String virtualThreadsProperty) {
        return calculate(
                totalMemoryBytes,
                threadCount,
                loadedClasses,
                headRoomPercent,
                liveThreadCount,
                liveLoadedClassCount,
                virtualThreadsEnabled,
                virtualThreadsProperty,
                0);
    }

    MemoryCalculationDto calculate(
            long totalMemoryBytes,
            int threadCount,
            int loadedClasses,
            int headRoomPercent,
            int liveThreadCount,
            int liveLoadedClassCount,
            boolean virtualThreadsEnabled,
            String virtualThreadsProperty,
            long observedDirectMemoryBytes) {

        long clampedTotal = clamp(totalMemoryBytes, MIN_TOTAL_MEMORY_BYTES, MAX_TOTAL_MEMORY_BYTES);
        int clampedThreads = (int) clamp(threadCount, MIN_THREAD_COUNT, MAX_THREAD_COUNT);
        int clampedHeadRoom = (int) clamp(headRoomPercent, MIN_HEAD_ROOM_PERCENT, MAX_HEAD_ROOM_PERCENT);
        int clampedClasses = Math.max(loadedClasses, 0);

        long metaspaceBytes = computeMetaspaceBytes(clampedClasses);
        long directMemoryBytes = computeDirectMemoryBytes(observedDirectMemoryBytes);
        long stackBytesPerThread = STACK_BYTES_PER_THREAD;
        long stackBytesTotal = stackBytesPerThread * (long) clampedThreads;
        long fixedRegionsBytes = directMemoryBytes + metaspaceBytes + CODE_CACHE_BYTES + stackBytesTotal;
        long headRoomBytes = (long) ((clampedHeadRoom / 100.0) * clampedTotal);
        long heapBytes = clampedTotal - headRoomBytes - fixedRegionsBytes;

        if (heapBytes < MEBIBYTE) {
            String message = String.format(
                    "No room for a renderable heap: fixed regions (%d MiB) + headroom (%d MiB) "
                            + "leave less than 1 MiB from the %d MiB total. "
                            + "Try a larger total memory, fewer threads, or lower headroom.",
                    bytesToMiBCeil(fixedRegionsBytes), bytesToMiBCeil(headRoomBytes), bytesToMiBCeil(clampedTotal));
            return new MemoryCalculationDto(
                    clampedTotal,
                    0,
                    metaspaceBytes,
                    CODE_CACHE_BYTES,
                    directMemoryBytes,
                    stackBytesPerThread,
                    stackBytesTotal,
                    headRoomBytes,
                    fixedRegionsBytes,
                    clampedThreads,
                    clampedClasses,
                    liveThreadCount,
                    liveLoadedClassCount,
                    clampedHeadRoom,
                    virtualThreadsEnabled,
                    virtualThreadsProperty,
                    "",
                    false,
                    message);
        }

        String jvmOptions = buildJvmOptions(heapBytes, metaspaceBytes, CODE_CACHE_BYTES, stackBytesPerThread);

        return new MemoryCalculationDto(
                clampedTotal,
                heapBytes,
                metaspaceBytes,
                CODE_CACHE_BYTES,
                directMemoryBytes,
                stackBytesPerThread,
                stackBytesTotal,
                headRoomBytes,
                fixedRegionsBytes,
                clampedThreads,
                clampedClasses,
                liveThreadCount,
                liveLoadedClassCount,
                clampedHeadRoom,
                virtualThreadsEnabled,
                virtualThreadsProperty,
                jvmOptions,
                true,
                null);
    }

    /**
     * Pick a sensible default {@code totalMemoryBytes} that is independent of
     * the host machine's RAM. We size to roughly 1.5× the app's observed
     * footprint, with a floor sized to fit all fixed regions plus 128 MiB of
     * heap, and a hard upper clamp of 2 GiB so a 32 GB Mac doesn't poison
     * the recommendation. The user can move the value freely afterwards.
     */
    long defaultTotalMemoryBytes(
            long heapCommittedBytes, long nonHeapCommittedBytes, int threadCount, int loadedClasses) {
        int safeThreads = Math.max(threadCount, DEFAULT_THREAD_COUNT_FLOOR);
        long fixed = DIRECT_MEMORY_BYTES
                + computeMetaspaceBytes(loadedClasses)
                + CODE_CACHE_BYTES
                + STACK_BYTES_PER_THREAD * (long) safeThreads;
        long floor = fixed + 128L * MEBIBYTE;

        long useful = heapCommittedBytes + Math.max(0, nonHeapCommittedBytes);
        useful = useful + (useful / 2); // ×1.5 footprint

        long picked = Math.max(floor, useful);
        picked = roundUpTo(picked, 64L * MEBIBYTE);

        long min = 384L * MEBIBYTE;
        long max = 2048L * MEBIBYTE;
        return clamp(picked, min, max);
    }

    private long computeMetaspaceBytes(int loadedClasses) {
        double withFactor = (META_BASE_BYTES + META_PER_CLASS_BYTES * (double) loadedClasses) * META_SAFETY_FACTOR;
        return roundUpTo((long) Math.ceil(withFactor), MEBIBYTE);
    }

    private static long computeDirectMemoryBytes(long observedDirectMemoryBytes) {
        long boundedObserved = clamp(observedDirectMemoryBytes, 0, MAX_TOTAL_MEMORY_BYTES);
        return Math.max(DIRECT_MEMORY_BYTES, roundUpTo(boundedObserved, MEBIBYTE));
    }

    String buildKubernetesJvmOptions(
            MemoryCalculationDto calculation, double maxRamPercentage, double initialRamPercentage) {
        if (!calculation.valid()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("-XX:MaxRAMPercentage=").append(formatPercentage(maxRamPercentage));
        sb.append(" -XX:MinRAMPercentage=").append(formatPercentage(maxRamPercentage));
        sb.append(" -XX:InitialRAMPercentage=").append(formatPercentage(initialRamPercentage));
        sb.append(" -XX:MaxMetaspaceSize=")
                .append(bytesToMiBCeil(calculation.metaspaceBytes()))
                .append("m");
        sb.append(" -XX:ReservedCodeCacheSize=")
                .append(bytesToMiBCeil(calculation.codeCacheBytes()))
                .append("m");
        sb.append(" -Xss").append(calculation.stackBytesPerThread() / 1024).append("k");
        return sb.toString();
    }

    private static String formatPercentage(double percentage) {
        return BigDecimal.valueOf(percentage).stripTrailingZeros().toPlainString();
    }

    private String buildJvmOptions(long heapBytes, long metaspaceBytes, long codeCacheBytes, long stackBytesPerThread) {

        long heapMb = bytesToMiBFloor(heapBytes);
        long metaMb = bytesToMiBCeil(metaspaceBytes);
        long ccMb = bytesToMiBCeil(codeCacheBytes);
        long stackKb = stackBytesPerThread / 1024;

        StringBuilder sb = new StringBuilder(256);
        sb.append("-Xms").append(heapMb).append("m");
        sb.append(" -Xmx").append(heapMb).append("m");
        sb.append(" -XX:MaxMetaspaceSize=").append(metaMb).append("m");
        sb.append(" -XX:ReservedCodeCacheSize=").append(ccMb).append("m");
        sb.append(" -Xss").append(stackKb).append("k");
        return sb.toString();
    }
}
