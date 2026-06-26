package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import java.util.Locale;

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
 * {@code paketo-buildpacks/libjvm/calc/calculator.go}. We deviate from the
 * buildpack on two points because BootUI has access to a live JVM:
 *
 * <ul>
 *   <li><b>Loaded class count</b> comes from
 *       {@link java.lang.management.ClassLoadingMXBean#getLoadedClassCount()}
 *       instead of counting JAR entries at build time, so the buildpack's
 *       {@code ClassLoadFactor = 0.35} (a build-time → runtime estimator) is
 *       not applied. We do apply a {@link #META_SAFETY_FACTOR} on top of the
 *       observed count to account for lazy class loading after the user
 *       opens the panel.</li>
 *   <li><b>Default thread count</b> is {@code max(liveThreads, 250)} for
 *       platform threads and {@code max(liveThreads, 80)} when Spring
 *       virtual threads are enabled, so a freshly-started small app doesn't
 *       under-reserve stack memory.</li>
 * </ul>
 *
 * <p>This class is pure (no Spring, no JMX); the controller resolves all
 * inputs and passes them in. That makes the formula trivial to unit-test.
 */
final class MemoryCalculator {

    /**
     * libjvm: {@code DefaultDirectMemory = 10 * Mebi}.
     */
    static final long DIRECT_MEMORY_BYTES = 10L * 1024 * 1024;

    /**
     * libjvm: {@code DefaultReservedCodeCache = 240 * Mebi}.
     */
    static final long CODE_CACHE_BYTES = 240L * 1024 * 1024;

    /**
     * libjvm: {@code DefaultStack = 1 * Mebi}.
     */
    static final long STACK_BYTES_PER_THREAD = 1L * 1024 * 1024;

    /**
     * Smaller platform-thread stacks for virtual-thread deployments. The JVM
     * still needs carrier/platform thread stacks, but the request-concurrency
     * model no longer reserves one native stack per request.
     */
    static final long VIRTUAL_THREAD_STACK_BYTES_PER_THREAD = 512L * 1024;

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
     * The live count is a snapshot; Spring lazily loads many classes
     * (devtools, JDBC drivers, error paths, JSON serializers …) after the
     * panel is first opened. 1.25× gives a forgiving but not wasteful cap.
     */
    static final double META_SAFETY_FACTOR = 1.25;

    /**
     * Floor for the default thread count, matching libjvm's value.
     */
    static final int DEFAULT_THREAD_COUNT_FLOOR = 250;

    /**
     * Conservative carrier/platform-thread floor when Spring virtual threads are
     * enabled. It leaves room for Tomcat acceptors, GC/JIT/helper threads,
     * scheduler/database workers, and carrier threads without assuming a
     * 200-thread servlet request pool.
     */
    static final int VIRTUAL_THREAD_COUNT_FLOOR = 80;

    static final int MIN_THREAD_COUNT = 1;
    static final int MAX_THREAD_COUNT = 10_000;
    static final int MIN_HEAD_ROOM_PERCENT = 0;
    static final int MAX_HEAD_ROOM_PERCENT = 90;
    static final long MIN_TOTAL_MEMORY_BYTES = 128L * 1024 * 1024;
    static final long MAX_TOTAL_MEMORY_BYTES = 64L * 1024 * 1024 * 1024;

    private static final long GC_FLIP_HEAP_BYTES = 4L * 1024 * 1024 * 1024;

    private final JdkVersion jdkVersion;

    MemoryCalculator() {
        this(JdkVersion.current());
    }

    MemoryCalculator(JdkVersion jdkVersion) {
        this.jdkVersion = jdkVersion;
    }

    static int defaultThreadCount(int liveThreadCount) {
        return defaultThreadCount(liveThreadCount, false);
    }

    static int defaultThreadCount(int liveThreadCount, boolean virtualThreadsEnabled) {
        int floor = virtualThreadsEnabled ? VIRTUAL_THREAD_COUNT_FLOOR : DEFAULT_THREAD_COUNT_FLOOR;
        return Math.max(liveThreadCount, floor);
    }

    private static long bytesToMb(long bytes) {
        return Math.max(0, Math.round(bytes / (1024.0 * 1024.0)));
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

        long clampedTotal = clamp(totalMemoryBytes, MIN_TOTAL_MEMORY_BYTES, MAX_TOTAL_MEMORY_BYTES);
        int clampedThreads = (int) clamp(threadCount, MIN_THREAD_COUNT, MAX_THREAD_COUNT);
        int clampedHeadRoom = (int) clamp(headRoomPercent, MIN_HEAD_ROOM_PERCENT, MAX_HEAD_ROOM_PERCENT);
        int clampedClasses = Math.max(loadedClasses, 0);

        long metaspaceBytes = computeMetaspaceBytes(clampedClasses);
        long stackBytesPerThread = stackBytesPerThread(virtualThreadsEnabled);
        long stackBytesTotal = stackBytesPerThread * (long) clampedThreads;
        long fixedRegionsBytes = DIRECT_MEMORY_BYTES + metaspaceBytes + CODE_CACHE_BYTES + stackBytesTotal;
        long headRoomBytes = (long) ((clampedHeadRoom / 100.0) * clampedTotal);
        long heapBytes = clampedTotal - headRoomBytes - fixedRegionsBytes;

        if (heapBytes <= 0) {
            String message = String.format(
                    "No room for heap: fixed regions (%d MB) + headroom (%d MB) >= total (%d MB). "
                            + "Try a larger total memory, fewer threads, or lower headroom.",
                    bytesToMb(fixedRegionsBytes), bytesToMb(headRoomBytes), bytesToMb(clampedTotal));
            return new MemoryCalculationDto(
                    clampedTotal,
                    0,
                    metaspaceBytes,
                    CODE_CACHE_BYTES,
                    DIRECT_MEMORY_BYTES,
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
                    "",
                    false,
                    message);
        }

        String jvmOptions =
                buildJvmOptions(heapBytes, metaspaceBytes, CODE_CACHE_BYTES, DIRECT_MEMORY_BYTES, stackBytesPerThread);

        return new MemoryCalculationDto(
                clampedTotal,
                heapBytes,
                metaspaceBytes,
                CODE_CACHE_BYTES,
                DIRECT_MEMORY_BYTES,
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
                jvmOptions,
                true,
                null);
    }

    /**
     * Pick a sensible default {@code totalMemoryBytes} that is independent of
     * the host machine's RAM. We size to roughly 1.5× the app's observed
     * footprint, with a floor sized to fit all fixed regions plus 128 MB of
     * heap, and a hard upper clamp of 2 GiB so a 32 GB Mac doesn't poison
     * the recommendation. The user can move the value freely afterwards.
     */
    long defaultTotalMemoryBytes(
            long heapCommittedBytes, long nonHeapCommittedBytes, int threadCount, int loadedClasses) {
        return defaultTotalMemoryBytes(heapCommittedBytes, nonHeapCommittedBytes, threadCount, loadedClasses, false);
    }

    long defaultTotalMemoryBytes(
            long heapCommittedBytes,
            long nonHeapCommittedBytes,
            int threadCount,
            int loadedClasses,
            boolean virtualThreadsEnabled) {

        int safeThreads =
                Math.max(threadCount, virtualThreadsEnabled ? VIRTUAL_THREAD_COUNT_FLOOR : DEFAULT_THREAD_COUNT_FLOOR);
        long fixed = DIRECT_MEMORY_BYTES
                + computeMetaspaceBytes(loadedClasses)
                + CODE_CACHE_BYTES
                + stackBytesPerThread(virtualThreadsEnabled) * (long) safeThreads;
        long floor = fixed + 128L * 1024 * 1024;

        long useful = heapCommittedBytes + Math.max(0, nonHeapCommittedBytes);
        useful = useful + (useful / 2); // ×1.5 footprint

        long picked = Math.max(floor, useful);
        picked = roundUpTo(picked, 64L * 1024 * 1024);

        long min = 384L * 1024 * 1024;
        long max = 2048L * 1024 * 1024;
        return clamp(picked, min, max);
    }

    private long computeMetaspaceBytes(int loadedClasses) {
        double withFactor = (META_BASE_BYTES + META_PER_CLASS_BYTES * (double) loadedClasses) * META_SAFETY_FACTOR;
        return (long) Math.ceil(withFactor);
    }

    String buildKubernetesJvmOptions(
            MemoryCalculationDto calculation, double maxRamPercentage, double initialRamPercentage) {
        if (!calculation.valid()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("-XX:+UseContainerSupport");
        sb.append(" -XX:MaxRAMPercentage=").append(formatPercentage(maxRamPercentage));
        sb.append(" -XX:InitialRAMPercentage=").append(formatPercentage(initialRamPercentage));
        sb.append(" -XX:MaxMetaspaceSize=")
                .append(bytesToMb(calculation.metaspaceBytes()))
                .append("m");
        sb.append(" -XX:ReservedCodeCacheSize=")
                .append(bytesToMb(calculation.codeCacheBytes()))
                .append("m");
        sb.append(" -XX:MaxDirectMemorySize=")
                .append(bytesToMb(calculation.directMemoryBytes()))
                .append("m");
        sb.append(" -Xss").append(calculation.stackBytesPerThread() / 1024).append("k");
        appendCommonOptions(sb, calculation.heapBytes());
        return sb.toString();
    }

    private static long stackBytesPerThread(boolean virtualThreadsEnabled) {
        return virtualThreadsEnabled ? VIRTUAL_THREAD_STACK_BYTES_PER_THREAD : STACK_BYTES_PER_THREAD;
    }

    private static String formatPercentage(double percentage) {
        return String.format(Locale.ROOT, "%.1f", percentage);
    }

    private String buildJvmOptions(
            long heapBytes,
            long metaspaceBytes,
            long codeCacheBytes,
            long directMemoryBytes,
            long stackBytesPerThread) {

        long heapMb = bytesToMb(heapBytes);
        long metaMb = bytesToMb(metaspaceBytes);
        long ccMb = bytesToMb(codeCacheBytes);
        long dmMb = bytesToMb(directMemoryBytes);
        long stackKb = stackBytesPerThread / 1024;

        StringBuilder sb = new StringBuilder(256);
        sb.append("-Xms").append(heapMb).append("m");
        sb.append(" -Xmx").append(heapMb).append("m");
        sb.append(" -XX:MaxMetaspaceSize=").append(metaMb).append("m");
        sb.append(" -XX:ReservedCodeCacheSize=").append(ccMb).append("m");
        sb.append(" -XX:MaxDirectMemorySize=").append(dmMb).append("m");
        sb.append(" -Xss").append(stackKb).append("k");
        sb.append(" -XX:+AlwaysPreTouch");
        appendCommonOptions(sb, heapBytes);
        return sb.toString();
    }

    private void appendCommonOptions(StringBuilder sb, long heapBytes) {
        if (heapBytes >= GC_FLIP_HEAP_BYTES) {
            sb.append(" -XX:+UseZGC");
            if (jdkVersion.feature() >= 21 && jdkVersion.feature() < 24) {
                sb.append(" -XX:+ZGenerational");
            }
        } else {
            sb.append(" -XX:+UseG1GC");
        }
        sb.append(" -XX:+UseStringDeduplication");
        if (jdkVersion.feature() >= 25) {
            sb.append(" -XX:+UseCompactObjectHeaders");
        }
        sb.append(" -XX:+ExitOnOutOfMemoryError");
        sb.append(" -XX:+HeapDumpOnOutOfMemoryError");
        sb.append(" -XX:HeapDumpPath=/tmp");
    }

    /**
     * Indirection over {@link Runtime#version()} so tests can pin the feature
     * version and verify JDK-gated options (e.g. {@code UseCompactObjectHeaders}
     * which became a product flag in JDK 25 / JEP 519 and is rejected on
     * earlier JDKs).
     */
    @FunctionalInterface
    interface JdkVersion {
        static JdkVersion current() {
            return () -> Runtime.version().feature();
        }

        int feature();
    }
}
