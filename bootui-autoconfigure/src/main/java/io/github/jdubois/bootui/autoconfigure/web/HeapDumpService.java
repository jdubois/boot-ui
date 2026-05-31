package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.HeapClassHistogramEntryDto;
import io.github.jdubois.bootui.core.BootUiDtos.HeapDumpCaptureStatusDto;
import io.github.jdubois.bootui.core.BootUiDtos.HeapDumpFileDto;
import io.github.jdubois.bootui.core.BootUiDtos.HeapDumpReport;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Captures and inspects local JVM heap snapshots for the Heap Dump panel.
 *
 * <p>Capture uses {@code com.sun.management.HotSpotDiagnosticMXBean#dumpHeap} and writes a
 * {@code .hprof} file to a bounded local directory. Analysis uses the HotSpot diagnostic
 * command {@code GC.class_histogram} to surface a value-free class histogram (class names and
 * aggregate sizes only); object field values are never read, so secrets held in live objects
 * are not disclosed. Both actions trigger a full GC and are therefore exposed only through
 * explicit POST endpoints, never on passive reads.</p>
 */
public class HeapDumpService {

    /** Filenames accepted for delete/download, preventing path traversal. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.hprof");

    /** Matches a histogram data row: {@code   1:   12345   9876544   classname [(module)]}. */
    private static final Pattern HISTOGRAM_ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+).*$");

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final String STATUS_NOT_CAPTURED = "NOT_CAPTURED";
    private static final String STATUS_CAPTURED = "CAPTURED";
    private static final String STATUS_ANALYZED = "ANALYZED";
    private static final String STATUS_ERROR = "ERROR";

    /** Smart filter: re-sort classes by bytes-per-instance descending. */
    static final String SMART_FILTER_BIG_OBJECTS = "big-objects";

    /** Smart filter: keep only JDK collection and map classes. */
    static final String SMART_FILTER_COLLECTION_BLOAT = "collection-bloat";

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

    @FunctionalInterface
    interface HeapDumper {
        void dump(Path file, boolean live) throws Exception;
    }

    @FunctionalInterface
    interface HistogramSource {
        String classHistogram() throws Exception;
    }

    private final BootUiProperties.HeapDump config;
    private final Path baseDir;
    private final HeapDumper dumper;
    private final HistogramSource histogramSource;
    private final Clock clock;
    private final boolean hotspotAvailable;

    private volatile HeapDumpCaptureStatusDto status = new HeapDumpCaptureStatusDto(STATUS_NOT_CAPTURED, null, null);
    private volatile List<HeapClassHistogramEntryDto> allClasses = List.of();
    private volatile long histogramTotalInstances;
    private volatile long histogramTotalBytes;

    public HeapDumpService(BootUiProperties.HeapDump config) {
        this(
                config,
                Paths.get(config.getOutputDir()).toAbsolutePath().normalize(),
                HeapDumpService::dumpWithHotSpot,
                HeapDumpService::histogramWithDiagnosticCommand,
                Clock.systemUTC(),
                hotspotAvailable());
    }

    HeapDumpService(
            BootUiProperties.HeapDump config,
            Path baseDir,
            HeapDumper dumper,
            HistogramSource histogramSource,
            Clock clock,
            boolean hotspotAvailable) {
        this.config = config;
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.dumper = dumper;
        this.histogramSource = histogramSource;
        this.clock = clock;
        this.hotspotAvailable = hotspotAvailable;
    }

    /** Whether the HotSpot diagnostic MXBean used for heap dumps is available on this JVM. */
    public static boolean hotspotAvailable() {
        try {
            Class<?> type = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            return !ManagementFactory.getPlatformMXBeans(
                            type.asSubclass(java.lang.management.PlatformManagedObject.class))
                    .isEmpty();
        } catch (Throwable ex) {
            return false;
        }
    }

    public HeapDumpReport report() {
        return buildReport(null, null);
    }

    public HeapDumpReport report(String filter) {
        return buildReport(filter, null);
    }

    public HeapDumpReport report(String filter, String smartFilter) {
        return buildReport(filter, smartFilter);
    }

    public synchronized HeapDumpReport capture(boolean live) {
        if (!hotspotAvailable) {
            return errorReport("Heap dumps are not supported on this JVM");
        }
        if (!config.isCaptureEnabled()) {
            return errorReport("Heap dump capture is disabled via bootui.heap-dump.capture-enabled=false");
        }
        try {
            Files.createDirectories(baseDir);
            long heapUsed = liveHeapUsedBytes();
            long usable = freeDiskBytes();
            if (usable > 0 && usable < heapUsed + (heapUsed / 2)) {
                return errorReport("Not enough free disk space to capture a heap dump safely");
            }
            Path file = nextDumpFile(live);
            dumper.dump(file, live);
            evictOldDumps();
            refreshHistogram();
            this.status = new HeapDumpCaptureStatusDto(
                    STATUS_CAPTURED, file.getFileName().toString(), now());
            return buildReport(null, null);
        } catch (Exception ex) {
            return errorReport("Heap dump capture failed: " + rootMessage(ex));
        }
    }

    public synchronized HeapDumpReport analyze() {
        if (!hotspotAvailable) {
            return errorReport("Heap analysis is not supported on this JVM");
        }
        try {
            refreshHistogram();
            this.status =
                    new HeapDumpCaptureStatusDto(STATUS_ANALYZED, "Live heap analyzed without writing a dump", now());
            return buildReport(null, null);
        } catch (Exception ex) {
            return errorReport("Heap analysis failed: " + rootMessage(ex));
        }
    }

    public synchronized HeapDumpReport delete(String name) {
        Path file = resolveExisting(name);
        if (file == null) {
            return errorReport("Unknown heap dump");
        }
        try {
            Files.deleteIfExists(file);
            return buildReport(null, null);
        } catch (IOException ex) {
            return errorReport("Failed to delete heap dump: " + rootMessage(ex));
        }
    }

    /** Resolves an existing dump file for download, or {@code null} when the name is unsafe or missing. */
    public Path resolveExisting(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            return null;
        }
        if (!Files.isDirectory(baseDir)) {
            return null;
        }
        // Derive the returned path from the trusted directory listing rather than resolving the
        // user-supplied string, so the value handed to file operations never carries request taint.
        try (var stream = Files.list(baseDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> baseDir.equals(path.getParent()))
                    .filter(path -> path.getFileName().toString().equals(name))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private HeapDumpReport buildReport(String filter, String smartFilter) {
        List<HeapDumpFileDto> dumps = listDumps();
        List<HeapClassHistogramEntryDto> displayed = filteredTopClasses(allClasses, filter, smartFilter);
        return new HeapDumpReport(
                hotspotAvailable,
                config.isCaptureEnabled(),
                config.isAllowRawDownload(),
                baseDir.toString(),
                config.getMaxDumps(),
                dumps.size(),
                liveHeapUsedBytes(),
                freeDiskBytes(),
                status,
                dumps,
                histogramTotalInstances,
                histogramTotalBytes,
                displayed);
    }

    private List<HeapClassHistogramEntryDto> filteredTopClasses(
            List<HeapClassHistogramEntryDto> all, String filter, String smartFilter) {
        int limit = Math.max(1, config.getTopClasses());
        List<HeapClassHistogramEntryDto> base = applySmartFilter(all, smartFilter);
        if (filter == null || filter.isBlank()) {
            return base.subList(0, Math.min(limit, base.size()));
        }
        String prefix = filter.trim();
        List<HeapClassHistogramEntryDto> matched = new ArrayList<>();
        for (HeapClassHistogramEntryDto entry : base) {
            if (entry.className().startsWith(prefix)) {
                matched.add(entry);
                if (matched.size() == limit) {
                    break;
                }
            }
        }
        return List.copyOf(matched);
    }

    private List<HeapClassHistogramEntryDto> applySmartFilter(
            List<HeapClassHistogramEntryDto> all, String smartFilter) {
        if (SMART_FILTER_BIG_OBJECTS.equals(smartFilter)) {
            return all.stream()
                    .filter(e -> e.instances() > 0)
                    .sorted(Comparator.comparingDouble(
                                    (HeapClassHistogramEntryDto e) -> (double) e.bytes() / e.instances())
                            .reversed())
                    .toList();
        }
        if (SMART_FILTER_COLLECTION_BLOAT.equals(smartFilter)) {
            return all.stream().filter(e -> isCollectionClass(e.className())).toList();
        }
        return all;
    }

    private static boolean isCollectionClass(String className) {
        for (String prefix : COLLECTION_CLASS_PREFIXES) {
            if (className.equals(prefix) || className.startsWith(prefix + "$")) {
                return true;
            }
        }
        return false;
    }

    private HeapDumpReport errorReport(String message) {
        this.status = new HeapDumpCaptureStatusDto(STATUS_ERROR, message, now());
        return buildReport(null, null);
    }

    private List<HeapDumpFileDto> listDumps() {
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<HeapDumpFileDto> dumps = new ArrayList<>();
        try (var stream = Files.list(baseDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".hprof"))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        long size = sizeOf(path);
                        long createdAt = lastModified(path);
                        boolean live = name.contains("-live");
                        dumps.add(new HeapDumpFileDto(name, size, createdAt, live));
                    });
        } catch (IOException ex) {
            return List.of();
        }
        dumps.sort(Comparator.comparingLong(HeapDumpFileDto::createdAtEpochMs).reversed());
        return List.copyOf(dumps);
    }

    private void evictOldDumps() {
        int maxDumps = Math.max(0, config.getMaxDumps());
        List<HeapDumpFileDto> dumps = listDumps();
        if (dumps.size() <= maxDumps) {
            return;
        }
        for (HeapDumpFileDto dump : dumps.subList(maxDumps, dumps.size())) {
            Path file = baseDir.resolve(dump.name()).normalize();
            if (baseDir.equals(file.getParent())) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // best-effort eviction
                }
            }
        }
    }

    private Path nextDumpFile(boolean live) {
        String timestamp = TIMESTAMP.format(java.time.LocalDateTime.now(clock));
        String suffix = live ? "-live" : "";
        Path candidate = baseDir.resolve("app-heap-" + timestamp + suffix + ".hprof");
        int counter = 1;
        while (Files.exists(candidate)) {
            candidate = baseDir.resolve("app-heap-" + timestamp + suffix + "-" + counter + ".hprof");
            counter++;
        }
        return candidate;
    }

    private void refreshHistogram() throws Exception {
        String raw = histogramSource.classHistogram();
        List<HeapClassHistogramEntryDto> all = parseHistogram(raw);
        int limit = Math.max(1, config.getMaxClasses());
        if (all.size() > limit) {
            all = all.subList(0, limit);
        }
        long totalInstances = 0;
        long totalBytes = 0;
        for (HeapClassHistogramEntryDto entry : all) {
            totalInstances += entry.instances();
            totalBytes += entry.bytes();
        }
        this.allClasses = List.copyOf(all);
        this.histogramTotalInstances = totalInstances;
        this.histogramTotalBytes = totalBytes;
    }

    private static List<HeapClassHistogramEntryDto> parseHistogram(String raw) {
        List<HeapClassHistogramEntryDto> entries = new ArrayList<>();
        if (raw == null) {
            return entries;
        }
        for (String line : raw.split("\\R")) {
            Matcher matcher = HISTOGRAM_ROW.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            long instances = Long.parseLong(matcher.group(1));
            long bytes = Long.parseLong(matcher.group(2));
            String className = matcher.group(3);
            entries.add(new HeapClassHistogramEntryDto(0, className, instances, bytes));
        }
        entries.sort(Comparator.comparingLong(HeapClassHistogramEntryDto::bytes).reversed());
        List<HeapClassHistogramEntryDto> ranked = new ArrayList<>(entries.size());
        int rank = 1;
        for (HeapClassHistogramEntryDto entry : entries) {
            ranked.add(new HeapClassHistogramEntryDto(rank++, entry.className(), entry.instances(), entry.bytes()));
        }
        return ranked;
    }

    private static void dumpWithHotSpot(Path file, boolean live) throws Exception {
        Class<?> type = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
        Object bean =
                ManagementFactory.getPlatformMXBean(type.asSubclass(java.lang.management.PlatformManagedObject.class));
        type.getMethod("dumpHeap", String.class, boolean.class).invoke(bean, file.toString(), live);
    }

    private static String histogramWithDiagnosticCommand() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        Object result = server.invoke(
                name, "gcClassHistogram", new Object[] {new String[] {}}, new String[] {String[].class.getName()});
        return result == null ? "" : result.toString();
    }

    private long liveHeapUsedBytes() {
        try {
            return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        } catch (Exception ex) {
            return 0L;
        }
    }

    private long freeDiskBytes() {
        Path probe = baseDir;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return -1L;
        }
        try {
            return Files.getFileStore(probe).getUsableSpace();
        } catch (IOException ex) {
            return -1L;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static long lastModified(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private long now() {
        return clock.millis();
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null ? message : current.getClass().getSimpleName();
    }
}
