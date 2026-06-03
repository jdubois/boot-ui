package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a point-in-time, read-only snapshot of the JVM's live threads using {@link ThreadMXBean}.
 *
 * <p>The snapshot is taken in-process; it never requires the host application to expose the
 * Actuator {@code threaddump} endpoint over HTTP. The service never suspends, interrupts, or kills
 * a thread. Detailed stack frames and lock metadata are routed through the BootUI value-exposure
 * model so the panel stays consistent with the rest of BootUI's masking guarantees.</p>
 */
public class ThreadDumpService {

    private final ThreadMXBean threadMxBean;
    private final BootUiProperties properties;
    private final SecretMasker masker = new SecretMasker();

    public ThreadDumpService(BootUiProperties properties) {
        this(ManagementFactory.getThreadMXBean(), properties);
    }

    ThreadDumpService(ThreadMXBean threadMxBean, BootUiProperties properties) {
        this.threadMxBean = threadMxBean;
        this.properties = properties;
    }

    /**
     * Returns {@code true} when a usable {@link ThreadMXBean} is available on this JVM.
     */
    public boolean available() {
        return threadMxBean != null;
    }

    /**
     * Captures a snapshot and returns a filtered, paged report.
     */
    public ThreadDumpReport report(String query, String state, Integer offset, Integer limit) {
        if (threadMxBean == null) {
            return ThreadDumpReport.unavailable("ThreadMXBean is not available on this JVM");
        }
        Snapshot snapshot;
        try {
            snapshot = capture();
        } catch (RuntimeException ex) {
            return ThreadDumpReport.unavailable("Thread information could not be read: " + ex.getMessage());
        }

        List<ThreadInfoDto> rows = snapshot.rows();
        String normalizedQuery = PagedList.normalize(query);
        String normalizedState = PagedList.normalize(state);
        PagedList.Result<ThreadInfoDto> page = PagedList.from(
                rows,
                row -> matchesState(row, normalizedState) && matchesQuery(row, normalizedQuery),
                offset,
                limit);

        return new ThreadDumpReport(
                true,
                null,
                snapshot.capturedAt(),
                rows.size(),
                snapshot.daemonThreads(),
                snapshot.peakThreads(),
                snapshot.startedThreadCount(),
                snapshot.virtualThreadsSupported(),
                snapshot.cpuTimeSupported(),
                !snapshot.deadlockedThreadIds().isEmpty(),
                snapshot.deadlockedThreadIds(),
                snapshot.stateCounts(),
                page.items(),
                page.page());
    }

    /**
     * Produces a plain-text, {@code jstack}-style dump of the current snapshot for offline analysis.
     *
     * <p>Returns {@code null} when thread information cannot be read.</p>
     */
    public String rawDump() {
        if (threadMxBean == null) {
            return null;
        }
        Snapshot snapshot;
        try {
            snapshot = capture();
        } catch (RuntimeException ex) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("BootUI thread dump\n");
        builder.append("Captured-at: ").append(snapshot.capturedAt()).append('\n');
        builder.append("Total threads: ").append(snapshot.rows().size()).append('\n');
        if (!snapshot.deadlockedThreadIds().isEmpty()) {
            builder.append("Deadlocked thread ids: ")
                    .append(snapshot.deadlockedThreadIds())
                    .append('\n');
        }
        builder.append('\n');
        for (ThreadInfoDto row : snapshot.rows()) {
            builder.append('"').append(row.name()).append('"');
            if (row.virtual()) {
                builder.append(" virtual");
            }
            if (row.daemon()) {
                builder.append(" daemon");
            }
            builder.append(" id=").append(row.id());
            builder.append(" priority=").append(row.priority());
            builder.append("\n   java.lang.Thread.State: ").append(row.state());
            if (row.lockName() != null) {
                builder.append("\n   waiting on ").append(row.lockName());
                if (row.lockOwnerName() != null) {
                    builder.append(" owned by \"")
                            .append(row.lockOwnerName())
                            .append("\" id=")
                            .append(row.lockOwnerId());
                }
            }
            builder.append('\n');
            for (String frame : row.stackTrace()) {
                builder.append("\tat ").append(frame).append('\n');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private Snapshot capture() {
        long capturedAt = System.currentTimeMillis();
        boolean cpuTimeSupported = threadMxBean.isThreadCpuTimeSupported() && threadMxBean.isThreadCpuTimeEnabled();
        Set<Long> deadlocked = findDeadlockedThreadIds();
        Map<Long, Thread> liveThreads = liveThreadsById();

        ThreadInfo[] infos = threadMxBean.dumpAllThreads(true, true);
        List<ThreadInfoDto> rows = new ArrayList<>(infos.length);
        Map<String, Integer> stateTally = new LinkedHashMap<>();
        int daemonThreads = 0;

        ValueExposure exposeValues = properties.getExposeValues();
        boolean hideDetail = exposeValues == ValueExposure.METADATA_ONLY;

        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            Thread thread = liveThreads.get(info.getThreadId());
            boolean daemon = thread != null && thread.isDaemon();
            boolean virtual = thread != null && isVirtual(thread);
            int priority = thread != null ? thread.getPriority() : Thread.NORM_PRIORITY;
            if (daemon) {
                daemonThreads++;
            }
            String state = info.getThreadState() == null
                    ? "UNKNOWN"
                    : info.getThreadState().name();
            stateTally.merge(state, 1, Integer::sum);

            Long cpuTime = null;
            Long userTime = null;
            if (cpuTimeSupported) {
                long cpu = threadMxBean.getThreadCpuTime(info.getThreadId());
                long user = threadMxBean.getThreadUserTime(info.getThreadId());
                cpuTime = cpu < 0 ? null : cpu / 1_000_000L;
                userTime = user < 0 ? null : user / 1_000_000L;
            }

            String name = maskName(info.getThreadName(), exposeValues);
            List<String> stackTrace = hideDetail ? List.of() : renderStack(info.getStackTrace());
            String lockName = hideDetail ? null : info.getLockName();
            String lockOwnerName = hideDetail ? null : maskName(info.getLockOwnerName(), exposeValues);
            Long lockOwnerId = info.getLockOwnerId() < 0 ? null : info.getLockOwnerId();

            rows.add(new ThreadInfoDto(
                    info.getThreadId(),
                    name,
                    state,
                    priority,
                    daemon,
                    virtual,
                    cpuTime,
                    userTime,
                    info.getBlockedCount(),
                    info.getWaitedCount(),
                    info.isInNative(),
                    info.isSuspended(),
                    deadlocked.contains(info.getThreadId()),
                    lockName,
                    lockOwnerId,
                    lockOwnerName,
                    stackTrace));
        }

        rows.sort(Comparator.comparing(ThreadInfoDto::id));

        List<ThreadStateCountDto> stateCounts = stateTally.entrySet().stream()
                .map(entry -> new ThreadStateCountDto(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ThreadStateCountDto::state))
                .collect(Collectors.toList());

        List<Long> deadlockedIds = deadlocked.stream().sorted().toList();

        return new Snapshot(
                capturedAt,
                daemonThreads,
                threadMxBean.getPeakThreadCount(),
                threadMxBean.getTotalStartedThreadCount(),
                virtualThreadsSupported(),
                cpuTimeSupported,
                deadlockedIds,
                stateCounts,
                rows);
    }

    private Set<Long> findDeadlockedThreadIds() {
        long[] ids = null;
        try {
            if (threadMxBean.isSynchronizerUsageSupported()) {
                ids = threadMxBean.findDeadlockedThreads();
            }
        } catch (UnsupportedOperationException ignored) {
            ids = null;
        }
        if (ids == null) {
            ids = threadMxBean.findMonitorDeadlockedThreads();
        }
        if (ids == null || ids.length == 0) {
            return Set.of();
        }
        return Arrays.stream(ids).boxed().collect(Collectors.toUnmodifiableSet());
    }

    private Map<Long, Thread> liveThreadsById() {
        Map<Long, Thread> byId = new HashMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            byId.put(thread.getId(), thread);
        }
        return byId;
    }

    private static boolean isVirtual(Thread thread) {
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean virtualThreadsSupported() {
        // Detect the stable Thread.isVirtual() API (Java 21+) rather than assuming a version,
        // matching the reflective detection used per-thread in isVirtual(Thread).
        try {
            Thread.class.getMethod("isVirtual");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private List<String> renderStack(StackTraceElement[] elements) {
        if (elements == null || elements.length == 0) {
            return List.of();
        }
        List<String> frames = new ArrayList<>(elements.length);
        for (StackTraceElement element : elements) {
            frames.add(element.toString());
        }
        return frames;
    }

    private String maskName(String name, ValueExposure exposeValues) {
        if (name == null) {
            return null;
        }
        if (exposeValues != ValueExposure.FULL && properties.isMaskSecrets() && masker.isSecret(name)) {
            return SecretMasker.MASKED_VALUE;
        }
        return name;
    }

    private boolean matchesState(ThreadInfoDto row, String state) {
        return state.isEmpty() || row.state().toLowerCase(Locale.ROOT).equals(state);
    }

    private boolean matchesQuery(ThreadInfoDto row, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (PagedList.contains(row.name(), query) || PagedList.contains(row.state(), query)) {
            return true;
        }
        for (String frame : row.stackTrace()) {
            if (PagedList.contains(frame, query)) {
                return true;
            }
        }
        return false;
    }

    private record Snapshot(
            long capturedAt,
            int daemonThreads,
            int peakThreads,
            long startedThreadCount,
            boolean virtualThreadsSupported,
            boolean cpuTimeSupported,
            List<Long> deadlockedThreadIds,
            List<ThreadStateCountDto> stateCounts,
            List<ThreadInfoDto> rows) {}
}
