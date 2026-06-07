package io.github.jdubois.bootui.autoconfigure.memory;

import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.ClassLoadingData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.HeapContentData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.MemoryData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.MemoryPoolSnapshot;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.RuntimeData;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryContext.ThreadData;
import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
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
import java.util.List;
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
    private static final Pattern HISTOGRAM_ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+).*$");

    private static final int MAX_HISTOGRAM_CLASSES = 200;

    private static final List<Path> CGROUP_LIMIT_FILES =
            List.of(Path.of("/sys/fs/cgroup/memory.max"), Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));

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
        return new MemoryContext(
                collectMemory(), collectThreads(), collectHeapContent(), collectClassLoading(), collectRuntime());
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
        for (BufferPoolMXBean bufferPool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
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
                containerLimit);
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
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();

        long gcTimeMillis = 0;
        long gcCount = 0;
        boolean gcTimeKnown = false;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = gc.getCollectionTime();
            if (time >= 0) {
                gcTimeMillis += time;
                gcTimeKnown = true;
            }
            long count = gc.getCollectionCount();
            if (count > 0) {
                gcCount += count;
            }
        }

        int pendingFinalization = ManagementFactory.getMemoryMXBean().getObjectPendingFinalizationCount();

        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        return new RuntimeData(
                uptimeMillis,
                gcTimeKnown ? gcTimeMillis : -1,
                gcCount,
                pendingFinalization,
                parseInitialHeap(inputArgs),
                parseThreadStackBytes(inputArgs));
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
        return -1;
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
                    // fall through to the default estimate
                }
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

    /** Reads the live class histogram through the HotSpot diagnostic command, like the Heap Dump panel. */
    static String diagnosticCommandHistogram() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        Object result = server.invoke(
                name, "gcClassHistogram", new Object[] {new String[] {}}, new String[] {String[].class.getName()});
        return result == null ? "" : result.toString();
    }
}
