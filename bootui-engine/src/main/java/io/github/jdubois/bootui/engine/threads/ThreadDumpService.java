package io.github.jdubois.bootui.engine.threads;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.core.dto.ThreadCaptureQuality;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.core.dto.ThreadLockDto;
import io.github.jdubois.bootui.core.dto.ThreadStateCountDto;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.journal.ThreadFamilies;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Builds a point-in-time, read-only snapshot of the JVM's live threads using {@link ThreadMXBean}.
 *
 * <p>The snapshot is taken in-process; it never requires the host application to expose the
 * Actuator {@code threaddump} endpoint over HTTP. The service never suspends, interrupts, or kills
 * a thread. Detailed stack frames and lock metadata are routed through the BootUI value-exposure
 * model (via the framework-neutral {@link ExposurePolicy}) so the panel stays consistent with the
 * rest of BootUI's masking guarantees on every adapter.</p>
 */
public class ThreadDumpService {

    private final ThreadMXBean threadMxBean;
    private final ExposurePolicy exposure;
    private final SecretMasker masker = new SecretMasker();
    private final ThreadCaptureSettings settings;
    private final SingleFlightAction acquisition = new SingleFlightAction();
    private final LongSupplier wallClock;
    private final LongSupplier nanoClock;
    private static final System.Logger LOG = System.getLogger(ThreadDumpService.class.getName());

    public ThreadDumpService(ExposurePolicy exposure) {
        this(ManagementFactory.getThreadMXBean(), exposure);
    }

    public ThreadDumpService(ThreadMXBean threadMxBean, ExposurePolicy exposure) {
        this(threadMxBean, exposure, ThreadCaptureSettings.defaults(), System::currentTimeMillis, System::nanoTime);
    }

    public ThreadDumpService(ExposurePolicy exposure, ThreadCaptureSettings settings) {
        this(ManagementFactory.getThreadMXBean(), exposure, settings, System::currentTimeMillis, System::nanoTime);
    }

    ThreadDumpService(
            ThreadMXBean threadMxBean,
            ExposurePolicy exposure,
            ThreadCaptureSettings settings,
            LongSupplier wallClock,
            LongSupplier nanoClock) {
        this.threadMxBean = threadMxBean;
        this.exposure = exposure;
        this.settings = settings;
        this.wallClock = wallClock;
        this.nanoClock = nanoClock;
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
        return report(query, state, offset, limit, true);
    }

    public ThreadDumpReport report(String query, String state, Integer offset, Integer limit, boolean includeBootUi) {
        if (threadMxBean == null) {
            return ThreadDumpReport.unavailable("ThreadMXBean is not available on this JVM");
        }
        ThreadSnapshot snapshot;
        try {
            snapshot = acquire();
        } catch (ActionBusyException ex) {
            throw ex;
        } catch (ThreadAnalysisException ex) {
            return ThreadDumpReport.unavailable(ex.getMessage());
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.DEBUG, "Thread information could not be read", ex);
            return ThreadDumpReport.unavailable("Thread information could not be read.");
        }

        ThreadDumpReport full = snapshot.report();
        List<ThreadInfoDto> rows = full.threads();
        String normalizedQuery = PagedList.normalize(query);
        String normalizedState = PagedList.normalize(state);
        PagedList.Result<ThreadInfoDto> page = PagedList.from(
                rows,
                row -> (includeBootUi || !snapshot.bootUiThreadIds().contains(row.id()))
                        && matchesState(row, normalizedState)
                        && matchesQuery(row, normalizedQuery),
                offset,
                limit);

        return new ThreadDumpReport(
                true,
                null,
                full.capturedAt(),
                rows.size(),
                full.daemonThreads(),
                full.peakThreads(),
                full.startedThreadCount(),
                full.virtualThreadsSupported(),
                full.cpuTimeSupported(),
                full.deadlockDetected(),
                full.deadlockedThreadIds(),
                full.stateCounts(),
                page.items(),
                page.page(),
                full.quality(),
                includeBootUi ? 0 : snapshot.bootUiThreadIds().size());
    }

    /**
     * Produces the legacy BootUI text format, not a native JVM thread dump.
     *
     * <p>Returns {@code null} when thread information cannot be read.</p>
     */
    public String rawDump() {
        if (threadMxBean == null) {
            return null;
        }
        ThreadDumpReport snapshot;
        try {
            snapshot = acquire().report();
        } catch (ActionBusyException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.DEBUG, "Thread dump could not be read", ex);
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("BootUI thread dump\n");
        builder.append("Captured-at: ").append(snapshot.capturedAt()).append('\n');
        builder.append("Total threads: ").append(snapshot.threads().size()).append('\n');
        builder.append("Coverage: platform threads only; ")
                .append(
                        snapshot.quality().populationComplete()
                                ? "complete enumerated population"
                                : "partial population")
                .append('\n');
        for (String limitation : snapshot.quality().limitations()) {
            builder.append("Limitation: ").append(limitation).append('\n');
        }
        if (!snapshot.deadlockedThreadIds().isEmpty()) {
            builder.append("Deadlocked thread ids: ")
                    .append(snapshot.deadlockedThreadIds())
                    .append('\n');
        }
        builder.append('\n');
        for (ThreadInfoDto row : snapshot.threads()) {
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

    ThreadSnapshot acquire() {
        return acquire(() -> {});
    }

    ThreadSnapshot acquire(Runnable admission) {
        if (!available()) {
            throw new IllegalStateException("ThreadMXBean is not available on this JVM.");
        }
        return acquisition.run(ActionOperations.THREAD_CAPTURE, () -> {
            admission.run();
            return capture();
        });
    }

    private ThreadSnapshot capture() {
        long capturedAt = wallClock.getAsLong();
        long startedNanos = nanoClock.getAsLong();
        boolean cpuTimeSupported = threadMxBean.isThreadCpuTimeSupported() && threadMxBean.isThreadCpuTimeEnabled();
        Set<Long> deadlocked = findDeadlockedThreadIds(startedNanos);
        checkDeadline(startedNanos);
        long[] allIds = threadMxBean.getAllThreadIds();
        checkDeadline(startedNanos);
        Arrays.sort(allIds);
        long[] ids = Arrays.copyOf(allIds, Math.min(allIds.length, settings.maxThreads()));
        boolean monitors = threadMxBean.isObjectMonitorUsageSupported();
        boolean synchronizers = settings.includeSynchronizers() && threadMxBean.isSynchronizerUsageSupported();
        List<String> limitations = new ArrayList<>();
        limitations.add("Platform threads only; virtual threads are not enumerated.");
        ThreadInfo[] infos;
        try {
            checkDeadline(startedNanos);
            infos = threadMxBean.getThreadInfo(ids, monitors, synchronizers, settings.maxStackDepth() + 1);
        } catch (UnsupportedOperationException ex) {
            checkDeadline(startedNanos);
            infos = threadMxBean.getThreadInfo(ids, settings.maxStackDepth() + 1);
            monitors = false;
            synchronizers = false;
            limitations.add("Bounded ownership capture is unsupported; metadata and stacks only.");
        }
        if (!synchronizers) {
            limitations.add("Complete owned-synchronizer inventory was not read.");
        }
        List<ThreadInfoDto> rows = new ArrayList<>(infos.length);
        List<CapturedThreadDto> captured = new ArrayList<>(infos.length);
        Set<Long> bootUiThreadIds = new java.util.HashSet<>();
        Map<Long, ThreadSnapshot.CpuReading> cpuReadings = new LinkedHashMap<>();
        Map<String, String> lockKeys = new LinkedHashMap<>();
        Map<Long, String> waitingLocks = new LinkedHashMap<>();
        Map<String, Integer> stateTally = new LinkedHashMap<>();
        int daemonThreads = 0;
        int vanished = 0;
        boolean stacksComplete = true;
        boolean maskedFrames = false;
        long bytes = 4096;

        ValueExposure exposeValues = exposure.valueExposure();
        boolean hideDetail = exposeValues == ValueExposure.METADATA_ONLY;
        boolean maskSecrets = exposeValues != ValueExposure.FULL && exposure.maskSecrets();

        for (ThreadInfo info : infos) {
            if (info == null) {
                vanished++;
                continue;
            }
            if (nanoClock.getAsLong() - startedNanos > settings.captureDeadlineMillis() * 1_000_000L) {
                limitations.add("Capture soft deadline reached; no further thread details were retained.");
                break;
            }
            boolean daemon = info.isDaemon();
            String state = info.getThreadState() == null
                    ? "UNKNOWN"
                    : info.getThreadState().name();

            Long cpuTime = null;
            Long userTime = null;
            ThreadSnapshot.CpuReading reading = null;
            if (cpuTimeSupported) {
                long cpu = threadMxBean.getThreadCpuTime(info.getThreadId());
                long user = threadMxBean.getThreadUserTime(info.getThreadId());
                cpuTime = cpu < 0 ? null : cpu / 1_000_000L;
                userTime = user < 0 ? null : user / 1_000_000L;
                reading = new ThreadSnapshot.CpuReading(cpu, user, nanoClock.getAsLong());
            }

            String name = maskName(info.getThreadName(), maskSecrets);
            boolean truncated = info.getStackTrace().length > settings.maxStackDepth();
            List<String> stackTrace = hideDetail ? List.of() : renderStack(info.getStackTrace(), maskSecrets);
            boolean maskedStack = stackTrace.contains(SecretMasker.MASKED_VALUE);
            String lockName = hideDetail ? null : maskName(info.getLockName(), maskSecrets);
            String lockOwnerName = hideDetail ? null : maskName(info.getLockOwnerName(), maskSecrets);
            Long lockOwnerId = hideDetail || info.getLockOwnerId() < 0 ? null : info.getLockOwnerId();

            ThreadInfoDto row = new ThreadInfoDto(
                    info.getThreadId(),
                    name,
                    state,
                    info.getPriority(),
                    daemon,
                    false,
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
                    stackTrace);
            List<ThreadLockDto> owned = new ArrayList<>();
            boolean ownershipComplete = monitors && synchronizers && !truncated && !hideDetail;
            if (!hideDetail) {
                for (var monitor : info.getLockedMonitors()) {
                    if (owned.size() >= settings.maxOwnedLocks()) {
                        ownershipComplete = false;
                        break;
                    }
                    owned.add(new ThreadLockDto(
                            "MONITOR",
                            maskName(monitor.toString(), maskSecrets),
                            monitor.getLockedStackDepth() < 0 ? null : monitor.getLockedStackDepth()));
                }
                for (var synchronizer : info.getLockedSynchronizers()) {
                    if (owned.size() >= settings.maxOwnedLocks()) {
                        ownershipComplete = false;
                        break;
                    }
                    owned.add(new ThreadLockDto(
                            "OWNABLE_SYNCHRONIZER", maskName(synchronizer.toString(), maskSecrets), null));
                }
            }
            boolean bootUi = info.getThreadName().startsWith("bootui-");
            long rowBytes = estimate(row, owned) + (lockName == null ? 0 : 64) + (bootUi ? 64 : 0);
            if (bytes + rowBytes > settings.maxCaptureBytes()) {
                limitations.add("Retained payload limit reached; remaining thread details were omitted.");
                break;
            }
            bytes += rowBytes;
            rows.add(row);
            if (bootUi) {
                bootUiThreadIds.add(row.id());
            }
            captured.add(new CapturedThreadDto(
                    null, row, ThreadFamilies.of(name), activity(row), truncated, ownershipComplete, owned));
            if (lockName != null) {
                String key = lockKeys.computeIfAbsent(info.getLockName(), ignored -> "lock-" + lockKeys.size());
                waitingLocks.put(row.id(), key);
            }
            if (reading != null) {
                cpuReadings.put(row.id(), reading);
            }
            if (daemon) {
                daemonThreads++;
            }
            stateTally.merge(state, 1, Integer::sum);
            stacksComplete &= !truncated && !hideDetail && !maskedStack;
            maskedFrames |= maskedStack;
        }

        rows.sort(Comparator.comparing(ThreadInfoDto::id));

        List<ThreadStateCountDto> stateCounts = stateTally.entrySet().stream()
                .map(entry -> new ThreadStateCountDto(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ThreadStateCountDto::state))
                .toList();

        List<Long> deadlockedIds =
                deadlocked.stream().sorted().limit(settings.maxThreads()).toList();

        // The peak is a separate counter read after the dump: never report it below the live threads just counted.
        int peakThreads = Math.max(threadMxBean.getPeakThreadCount(), rows.size());
        int omitted = Math.max(0, allIds.length - rows.size() - vanished);
        if (omitted > 0) {
            limitations.add("Thread population is incomplete; filtering and counts cover captured threads only.");
        }
        if (hideDetail) {
            limitations.add("Stack and lock details are hidden by the exposure policy.");
        } else if (!stacksComplete) {
            limitations.add("Some stacks or their owned-monitor inventories are incomplete.");
        }
        if (maskedFrames) {
            limitations.add(
                    "Some frame text was masked; those stacks are excluded from equality-based grouping and comparison.");
        }
        ThreadCaptureQuality quality = new ThreadCaptureQuality(
                "ThreadMXBean",
                List.of("PLATFORM"),
                allIds.length,
                rows.size(),
                vanished,
                omitted,
                settings.maxStackDepth(),
                omitted == 0 && vanished == 0,
                stacksComplete,
                monitors && !hideDetail,
                synchronizers && !hideDetail,
                limitations);
        ThreadDumpReport report = new ThreadDumpReport(
                true,
                null,
                capturedAt,
                rows.size(),
                daemonThreads,
                peakThreads,
                threadMxBean.getTotalStartedThreadCount(),
                virtualThreadsSupported(),
                cpuTimeSupported,
                !deadlocked.isEmpty(),
                deadlockedIds,
                stateCounts,
                rows,
                PagedList.from(rows, 0, rows.size()).page(),
                quality);
        return new ThreadSnapshot(
                report,
                captured,
                cpuReadings,
                waitingLocks,
                wallClock.getAsLong(),
                nanoClock.getAsLong() - startedNanos,
                bytes,
                bootUiThreadIds);
    }

    private void checkDeadline(long startedNanos) {
        if (nanoClock.getAsLong() - startedNanos > settings.captureDeadlineMillis() * 1_000_000L) {
            throw new ThreadAnalysisException(
                    503, "Thread capture soft deadline reached before the next JVM acquisition.");
        }
    }

    private Set<Long> findDeadlockedThreadIds(long startedNanos) {
        long[] ids;
        try {
            checkDeadline(startedNanos);
            ids = threadMxBean.isSynchronizerUsageSupported()
                    ? threadMxBean.findDeadlockedThreads()
                    : threadMxBean.findMonitorDeadlockedThreads();
        } catch (UnsupportedOperationException ex) {
            checkDeadline(startedNanos);
            ids = threadMxBean.findMonitorDeadlockedThreads();
        }
        if (ids == null || ids.length == 0) {
            return Set.of();
        }
        return Arrays.stream(ids).boxed().collect(Collectors.toUnmodifiableSet());
    }

    private static boolean virtualThreadsSupported() {
        // Detect the stable Thread.isVirtual() API (Java 21+) rather than assuming a version,
        // This is runtime support, not virtual-thread capture coverage.
        try {
            Thread.class.getMethod("isVirtual");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private List<String> renderStack(StackTraceElement[] elements, boolean maskSecrets) {
        if (elements == null || elements.length == 0) {
            return List.of();
        }
        List<String> frames = new ArrayList<>(elements.length);
        for (int i = 0; i < Math.min(elements.length, settings.maxStackDepth()); i++) {
            frames.add(maskName(elements[i].toString(), maskSecrets));
        }
        return frames;
    }

    private String maskName(String name, boolean maskSecrets) {
        if (name == null) {
            return null;
        }
        if (maskSecrets && masker.isSecret(name)) {
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

    private static long estimate(ThreadInfoDto row, List<ThreadLockDto> owned) {
        long bytes = 1536 + 2 * textBytes(row.name()) + textBytes(row.lockName()) + textBytes(row.lockOwnerName());
        for (String frame : row.stackTrace()) {
            bytes += 128 + textBytes(frame);
        }
        for (ThreadLockDto lock : owned) {
            bytes += 256 + textBytes(lock.identity());
        }
        return bytes;
    }

    private static long textBytes(String value) {
        return value == null ? 0 : 64 + value.length() * 2L;
    }

    static String activity(ThreadInfoDto row) {
        if (row.state().equals("BLOCKED")) {
            return "MONITOR_ACQUISITION";
        }
        if (row.state().equals("WAITING") || row.state().equals("TIMED_WAITING")) {
            if (row.stackTrace().stream().anyMatch(ThreadDumpService::conditionWaitFrame)) {
                return "CONDITION_OR_EVENT_WAIT";
            }
            if (row.lockOwnerId() != null
                    && row.lockName() != null
                    && (row.lockName().contains("ReentrantLock$")
                            || (row.lockName().contains("ReentrantReadWriteLock$")
                                    && row.stackTrace().stream()
                                            .anyMatch(ThreadDumpService::writeLockAcquisitionFrame)))) {
                return "EXCLUSIVE_SYNCHRONIZER_ACQUISITION";
            }
            return "WAITING_UNCLASSIFIED";
        }
        return row.inNative() ? "NATIVE_EXECUTION" : "UNCLASSIFIED";
    }

    private static boolean conditionWaitFrame(String frame) {
        String method = frame.substring(frame.lastIndexOf('/') + 1);
        return method.startsWith("java.lang.Object.wait(")
                || method.startsWith("java.lang.Object.wait0(")
                || method.startsWith("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.");
    }

    private static boolean writeLockAcquisitionFrame(String frame) {
        String method = frame.substring(frame.lastIndexOf('/') + 1);
        return method.startsWith("java.util.concurrent.locks.ReentrantReadWriteLock$WriteLock.lock(")
                || method.startsWith("java.util.concurrent.locks.ReentrantReadWriteLock$WriteLock.lockInterruptibly(")
                || method.startsWith("java.util.concurrent.locks.ReentrantReadWriteLock$WriteLock.tryLock(");
    }
}
