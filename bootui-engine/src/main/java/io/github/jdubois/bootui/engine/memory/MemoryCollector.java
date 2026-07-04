package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.engine.memory.MemoryContext.BufferPoolSnapshot;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ClassLoadingData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.GcSample;
import io.github.jdubois.bootui.engine.memory.MemoryContext.GcTrend;
import io.github.jdubois.bootui.engine.memory.MemoryContext.HeapContentData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.engine.memory.MemoryContext.PostGcHeapData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.RuntimeData;
import io.github.jdubois.bootui.engine.memory.MemoryContext.ThreadData;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Builds a {@link MemoryContext} from the live JVM, reusing the same management beans that
 * back the Memory and Heap Dump panels and the {@code ThreadDumpReport} produced by the Threads
 * panel. The heap-content histogram is read lazily through a {@link HistogramSource}; the default
 * implementation invokes the HotSpot {@code GC.class_histogram} diagnostic command (which triggers
 * a full GC), exactly like the Heap Dump panel's analyze action.
 */
final class MemoryCollector {

    /** Matches a histogram data row: {@code   1:   12345   9876544   classname [(module)]}. */
    private static final Pattern HISTOGRAM_ROW =
            Pattern.compile("^\\s*+\\d++:\\s++(\\d++)\\s++(\\d++)\\s++(\\S++).*+$");

    private static final int MAX_HISTOGRAM_CLASSES = 200;

    private static final List<Path> CGROUP_LIMIT_FILES =
            List.of(Path.of("/sys/fs/cgroup/memory.max"), Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));

    private static final List<Path> CGROUP_CURRENT_FILES =
            List.of(Path.of("/sys/fs/cgroup/memory.current"), Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"));

    @FunctionalInterface
    interface HistogramSource {
        /** Returns the raw {@code GC.class_histogram} output, or {@code null} when unavailable. */
        String classHistogram() throws Exception;
    }

    private final ThreadDumpReportSupplier threadReportSupplier;
    private final HistogramSource histogramSource;

    @FunctionalInterface
    interface ThreadDumpReportSupplier {
        ThreadDumpReport report();
    }

    MemoryCollector(ThreadDumpReportSupplier threadReportSupplier, HistogramSource histogramSource) {
        this.threadReportSupplier = threadReportSupplier;
        this.histogramSource = histogramSource;
    }

    MemoryContext collect() {
        MemoryData memory = collectMemory();
        ThreadData threads = collectThreads();
        // Sample GC counters BEFORE the histogram so this scan's own forced full GC is excluded
        // from the recent-overhead window; the post-histogram runtime reading becomes the baseline
        // for the next scan.
        GcSample preHistogramGc = currentGcSample();
        HeapContentData heapContent = collectHeapContent();
        PostGcHeapData postGcHeap = collectPostGcHeap(heapContent.available());
        ClassLoadingData classLoading = collectClassLoading();
        RuntimeData runtime = collectRuntime();
        return new MemoryContext(
                memory, threads, heapContent, postGcHeap, classLoading, runtime, preHistogramGc, GcTrend.unavailable());
    }

    /**
     * Re-reads heap and old-generation occupancy after the histogram's forced full GC so the
     * heap-pressure rules can tell sustained retained pressure from transient garbage. Returns
     * {@link PostGcHeapData#unavailable()} when no histogram (and therefore no full GC) ran.
     */
    private PostGcHeapData collectPostGcHeap(boolean histogramRan) {
        if (!histogramRan) {
            return PostGcHeapData.unavailable();
        }
        long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        boolean oldGenAvailable = false;
        long oldGenUsed = -1;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (MemoryData.isOldGenerationPoolName(pool.getName())) {
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    oldGenUsed = usage.getUsed();
                    oldGenAvailable = true;
                    break;
                }
            }
        }
        return new PostGcHeapData(true, heapUsed, oldGenAvailable, oldGenUsed);
    }

    private GcSample currentGcSample() {
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        long gcTimeMillis = 0;
        long gcCount = 0;
        boolean gcTimeKnown = false;
        Map<String, Long> perCollectorCounts = new HashMap<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            if (count > 0) {
                perCollectorCounts.put(gc.getName(), count);
            }
            // Exclude concurrent-cycle beans (ZGC Cycles, Shenandoah Cycles, G1 Concurrent GC,
            // ConcurrentMarkSweep) from the STW-only time/count totals. Including their concurrent
            // phase time would inflate overhead for apps that deliberately chose a concurrent collector.
            if (!isConcurrentCycleBean(gc.getName())) {
                long time = gc.getCollectionTime();
                if (time >= 0) {
                    gcTimeMillis += time;
                    gcTimeKnown = true;
                }
                if (count > 0) {
                    gcCount += count;
                }
            }
        }
        return new GcSample(uptimeMillis, gcTimeKnown ? gcTimeMillis : -1, gcCount, perCollectorCounts);
    }

    /**
     * Returns {@code true} for GarbageCollectorMXBeans that report concurrent (non-STW) cycle time.
     * Their collection time runs while the application is still executing, so including it in an
     * "overhead" percentage produces inflated, misleading results for concurrent collectors such as
     * ZGC, Shenandoah, and G1 concurrent marking.
     */
    static boolean isConcurrentCycleBean(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        // "cycles" covers: ZGC Cycles, ZGC Major Cycles, ZGC Minor Cycles, Shenandoah Cycles
        // "concurrent" covers: G1 Concurrent GC, ConcurrentMarkSweep (legacy CMS)
        return lower.contains("cycles") || lower.contains("concurrent");
    }

    private MemoryData collectMemory() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        List<MemoryPoolSnapshot> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                pools.add(
                        new MemoryPoolSnapshot(pool.getName(), usage.getUsed(), usage.getCommitted(), usage.getMax()));
            }
        }

        long directUsed = 0;
        long directCapacity = 0;
        long directCount = 0;
        List<BufferPoolSnapshot> bufferPools = new ArrayList<>();
        for (BufferPoolMXBean bufferPool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            bufferPools.add(new BufferPoolSnapshot(
                    bufferPool.getName(),
                    Math.max(0, bufferPool.getMemoryUsed()),
                    Math.max(0, bufferPool.getTotalCapacity()),
                    Math.max(0, bufferPool.getCount())));
            if ("direct".equalsIgnoreCase(bufferPool.getName())) {
                directUsed += Math.max(0, bufferPool.getMemoryUsed());
                directCapacity += Math.max(0, bufferPool.getTotalCapacity());
                directCount += Math.max(0, bufferPool.getCount());
            }
        }

        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> gcNames = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcNames.add(gc.getName());
        }

        OptionalLong containerLimitValue = detectContainerLimit();
        Long containerLimit = containerLimitValue.isPresent() ? containerLimitValue.getAsLong() : null;
        OptionalLong containerCurrentValue = detectContainerCurrentUsage();
        Long containerCurrent = containerCurrentValue.isPresent() ? containerCurrentValue.getAsLong() : null;

        return new MemoryData(
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                nonHeap.getCommitted(),
                nonHeap.getMax(),
                pools,
                directUsed,
                directCapacity,
                directCount,
                parseMaxDirectMemory(inputArgs),
                inputArgs,
                gcNames,
                containerLimit,
                containerCurrent,
                bufferPools);
    }

    private ThreadData collectThreads() {
        ThreadDumpReport report;
        try {
            report = threadReportSupplier.report();
        } catch (RuntimeException ex) {
            return ThreadData.empty();
        }
        if (report == null || !report.available()) {
            return ThreadData.empty();
        }
        return new ThreadData(
                report.totalThreads(),
                report.peakThreads(),
                report.daemonThreads(),
                report.cpuTimeSupported(),
                report.deadlockDetected(),
                report.deadlockedThreadIds(),
                report.stateCounts(),
                report.threads());
    }

    private HeapContentData collectHeapContent() {
        String raw;
        try {
            raw = histogramSource.classHistogram();
        } catch (Exception ex) {
            return HeapContentData.unavailable();
        }
        if (raw == null || raw.isBlank()) {
            return HeapContentData.unavailable();
        }
        List<HeapClassHistogramEntryDto> entries = parseHistogram(raw);
        if (entries.isEmpty()) {
            return HeapContentData.unavailable();
        }
        // Totals cover every histogram row so percentage-of-heap rules are not skewed by the
        // top-N truncation applied to the entries surfaced for display.
        long totalInstances = 0;
        long totalBytes = 0;
        for (HeapClassHistogramEntryDto entry : entries) {
            totalInstances += entry.instances();
            totalBytes += entry.bytes();
        }
        return new HeapContentData(true, topEntries(entries), totalInstances, totalBytes);
    }

    private ClassLoadingData collectClassLoading() {
        ClassLoadingMXBean bean = ManagementFactory.getClassLoadingMXBean();
        return new ClassLoadingData(
                bean.getLoadedClassCount(), bean.getTotalLoadedClassCount(), bean.getUnloadedClassCount());
    }

    private RuntimeData collectRuntime() {
        GcSample gc = currentGcSample();
        int pendingFinalization = ManagementFactory.getMemoryMXBean().getObjectPendingFinalizationCount();
        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

        int availableProcessors = ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors();
        long freeSwap = readOsBeanLong("FreeSwapSpaceSize");
        long totalSwap = readOsBeanLong("TotalSwapSpaceSize");
        long totalPhysicalMemory = readOsBeanLong("TotalPhysicalMemorySize");
        Boolean useCompressedOops = readVmOptionBoolean("UseCompressedOops");
        LastGcPause lastGcPause = readLastGcPause();

        return new RuntimeData(
                gc.uptimeMillis(),
                gc.gcTimeMillis(),
                gc.gcCount(),
                pendingFinalization,
                parseInitialHeap(inputArgs),
                parseThreadStackBytes(inputArgs),
                availableProcessors,
                freeSwap,
                totalSwap,
                useCompressedOops,
                totalPhysicalMemory,
                lastGcPause.millis(),
                lastGcPause.collectorName());
    }

    /**
     * The duration and originating collector of the single most recent garbage collection (MEM-GC-006's
     * outlier-pause rule). {@link #unavailable()} on a non-HotSpot JVM or before any collection has run.
     */
    private record LastGcPause(long millis, String collectorName) {
        static LastGcPause unavailable() {
            return new LastGcPause(-1, null);
        }
    }

    /**
     * Reads the duration of the single most recent garbage collection across all collectors via the
     * HotSpot-specific {@code com.sun.management.GarbageCollectorMXBean.getLastGcInfo()} extension,
     * keeping the longest pause (and its collector name) when more than one collector has run since
     * the JVM started. Unlike the generic scalar reads elsewhere in this class, this directly uses the
     * {@code com.sun.management} types (there is no generic string-keyed JMX attribute for the
     * composite "last GC info" value); the whole lookup is wrapped so a non-HotSpot JVM degrades to
     * {@link LastGcPause#unavailable()} instead of failing this (or any other) scan.
     */
    private static LastGcPause readLastGcPause() {
        try {
            long longestDurationMillis = -1;
            String longestCollectorName = null;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean instanceof com.sun.management.GarbageCollectorMXBean sunBean) {
                    com.sun.management.GcInfo info = sunBean.getLastGcInfo();
                    if (info != null && info.getDuration() > longestDurationMillis) {
                        longestDurationMillis = info.getDuration();
                        longestCollectorName = bean.getName();
                    }
                }
            }
            return longestDurationMillis >= 0
                    ? new LastGcPause(longestDurationMillis, longestCollectorName)
                    : LastGcPause.unavailable();
        } catch (RuntimeException | LinkageError ex) {
            // com.sun.management is a HotSpot extension; degrade gracefully on other JVMs.
            return LastGcPause.unavailable();
        }
    }

    private static List<HeapClassHistogramEntryDto> parseHistogram(String raw) {
        List<HeapClassHistogramEntryDto> entries = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            Matcher matcher = HISTOGRAM_ROW.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            long instances = Long.parseLong(matcher.group(1));
            long bytes = Long.parseLong(matcher.group(2));
            entries.add(new HeapClassHistogramEntryDto(0, normalizeClassName(matcher.group(3)), instances, bytes));
        }
        entries.sort(Comparator.comparingLong(HeapClassHistogramEntryDto::bytes).reversed());
        List<HeapClassHistogramEntryDto> ranked = new ArrayList<>(entries.size());
        int rank = 1;
        for (HeapClassHistogramEntryDto entry : entries) {
            ranked.add(new HeapClassHistogramEntryDto(rank++, entry.className(), entry.instances(), entry.bytes()));
        }
        return ranked;
    }

    /** Keeps only the largest classes for display while totals are computed over every row. */
    private static List<HeapClassHistogramEntryDto> topEntries(List<HeapClassHistogramEntryDto> ranked) {
        return ranked.size() <= MAX_HISTOGRAM_CLASSES ? ranked : List.copyOf(ranked.subList(0, MAX_HISTOGRAM_CLASSES));
    }

    private static String normalizeClassName(String rawClassName) {
        if (rawClassName == null || !rawClassName.startsWith("[")) {
            return rawClassName;
        }
        int dimensions = 0;
        while (dimensions < rawClassName.length() && rawClassName.charAt(dimensions) == '[') {
            dimensions++;
        }
        if (dimensions == rawClassName.length()) {
            return rawClassName;
        }
        char descriptor = rawClassName.charAt(dimensions);
        String componentName;
        switch (descriptor) {
            case 'B' -> componentName = "byte";
            case 'C' -> componentName = "char";
            case 'D' -> componentName = "double";
            case 'F' -> componentName = "float";
            case 'I' -> componentName = "int";
            case 'J' -> componentName = "long";
            case 'S' -> componentName = "short";
            case 'Z' -> componentName = "boolean";
            case 'L' -> {
                if (!rawClassName.endsWith(";") || rawClassName.length() <= dimensions + 2) {
                    return rawClassName;
                }
                componentName = rawClassName
                        .substring(dimensions + 1, rawClassName.length() - 1)
                        .replace('/', '.');
            }
            default -> {
                return rawClassName;
            }
        }
        if (descriptor != 'L' && rawClassName.length() != dimensions + 1) {
            return rawClassName;
        }
        return componentName + "[]".repeat(dimensions);
    }

    private static long parseMaxDirectMemory(List<String> inputArgs) {
        for (String arg : inputArgs) {
            if (arg != null && arg.startsWith("-XX:MaxDirectMemorySize=")) {
                long parsed = parseMemorySize(arg.substring("-XX:MaxDirectMemorySize=".length()));
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return -1;
    }

    private static long parseInitialHeap(List<String> inputArgs) {
        for (String arg : inputArgs) {
            if (arg != null && arg.startsWith("-Xms")) {
                long parsed = parseMemorySize(arg.substring("-Xms".length()));
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        // -Xms is not in the parsed args; fall back to the JVM's own reported initial heap size.
        // MemoryUsage.getInit() returns the amount of memory in bytes that the JVM initially
        // requested from the OS, which equals -Xms (or the ergonomic default when unset).
        long init = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit();
        return init > 0 ? init : -1;
    }

    private static long parseThreadStackBytes(List<String> inputArgs) {
        for (String arg : inputArgs) {
            if (arg == null) {
                continue;
            }
            if (arg.startsWith("-Xss")) {
                long parsed = parseMemorySize(arg.substring("-Xss".length()));
                if (parsed > 0) {
                    return parsed;
                }
            } else if (arg.startsWith("-XX:ThreadStackSize=")) {
                // -XX:ThreadStackSize is expressed in kilobytes, unlike the suffix-aware -Xss form.
                try {
                    long kb = Long.parseLong(
                            arg.substring("-XX:ThreadStackSize=".length()).trim());
                    if (kb > 0) {
                        return kb * 1024L;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        // Neither -Xss nor -XX:ThreadStackSize was set; try the live VM option so we get the
        // ergonomic default (which varies by OS and JVM version) rather than always guessing 1 MiB.
        String vmValue = readVmOption("ThreadStackSize");
        if (vmValue != null) {
            try {
                long kb = Long.parseLong(vmValue.trim());
                if (kb > 0) {
                    return kb * 1024L;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the compile-time default
            }
        }
        return RuntimeData.DEFAULT_THREAD_STACK_BYTES;
    }

    private static long parseMemorySize(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String trimmed = value.trim();
        long multiplier = 1;
        char unit = Character.toLowerCase(trimmed.charAt(trimmed.length() - 1));
        String number = trimmed;
        switch (unit) {
            case 'k' -> multiplier = 1024L;
            case 'm' -> multiplier = 1024L * 1024;
            case 'g' -> multiplier = 1024L * 1024 * 1024;
            default -> multiplier = 1;
        }
        if (multiplier != 1) {
            number = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            return Long.parseLong(number.trim()) * multiplier;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static OptionalLong detectContainerLimit() {
        for (Path file : CGROUP_LIMIT_FILES) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String value = Files.readString(file).trim();
                if (value.isEmpty() || "max".equals(value)) {
                    continue;
                }
                long parsed = Long.parseLong(value);
                if (parsed > 0 && parsed < Long.MAX_VALUE / 2) {
                    return OptionalLong.of(parsed);
                }
            } catch (RuntimeException | java.io.IOException ex) {
                // best-effort detection; ignore unreadable cgroup files
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong detectContainerCurrentUsage() {
        for (Path file : CGROUP_CURRENT_FILES) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String value = Files.readString(file).trim();
                if (value.isEmpty() || "max".equals(value)) {
                    continue;
                }
                long parsed = Long.parseLong(value);
                if (parsed > 0 && parsed < Long.MAX_VALUE / 2) {
                    return OptionalLong.of(parsed);
                }
            } catch (RuntimeException | java.io.IOException ex) {
                // best-effort detection; ignore unreadable cgroup files
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Reads a single HotSpot VM diagnostic option value via JMX. Returns {@code null} when the JVM
     * is not HotSpot, the option is not recognised, or the lookup fails for any reason.
     */
    static String readVmOption(String optionName) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.sun.management:type=HotSpotDiagnostic");
            Object vmOption = server.invoke(
                    name, "getVMOption", new Object[] {optionName}, new String[] {String.class.getName()});
            if (vmOption instanceof javax.management.openmbean.CompositeData cd) {
                Object val = cd.get("value");
                return val instanceof String s ? s : null;
            }
        } catch (Exception | Error ignored) {
            // non-HotSpot JVM or option not available
        }
        return null;
    }

    private static Boolean readVmOptionBoolean(String optionName) {
        String value = readVmOption(optionName);
        return value != null ? Boolean.parseBoolean(value) : null;
    }

    /** Reads a numeric attribute from the platform OperatingSystem MXBean; returns -1 on failure. */
    private static long readOsBeanLong(String attributeName) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("java.lang:type=OperatingSystem");
            Object value = server.getAttribute(name, attributeName);
            if (value instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception | Error ignored) {
            // attribute may not exist on non-HotSpot JVMs
        }
        return -1;
    }

    /** Reads the live class histogram through the HotSpot diagnostic command, like the Heap Dump panel. */
    static String diagnosticCommandHistogram() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        Object result = server.invoke(
                name, "gcClassHistogram", new Object[] {new String[] {}}, new String[] {String[].class.getName()});
        return result == null ? "" : result.toString();
    }
}
