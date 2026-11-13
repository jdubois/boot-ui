package io.github.jdubois.bootui.engine.memory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * Tracks garbage collections triggered by BootUI's own explicit diagnostic and memory-reclamation
 * actions (<b>Free BootUI memory</b> via {@link MemoryOffloadService}, <b>Low-footprint mode</b>
 * via {@link LowFootprintService}, and <b>Heap Dump</b> capture/analysis via
 * {@code HeapDumpService}) so the Memory Advisor can exclude self-inflicted pauses and Full GCs
 * from {@code MEM-GC-002}, {@code MEM-GC-003}, {@code MEM-GC-005}, {@code MEM-GC-006}, and
 * {@code MEM-GC-009}.
 */
public final class BootUiDiagnosticGcTracker {

    private static final int MAX_RETAINED_WINDOWS = 128;

    /** Process-wide tracker reading the live JVM's collector MXBeans. */
    public static final BootUiDiagnosticGcTracker SHARED = createShared();

    @FunctionalInterface
    public interface ThrowingRunnable<E extends Exception> {
        void run() throws E;
    }

    private final Supplier<MemoryContext.GcSample> sampleSupplier;
    private final Supplier<MemoryContext.GcEvent> eventSupplier;

    private int activeDepth;
    private MemoryContext.GcSample activeBeforeSample;
    private MemoryContext.GcEvent activeBeforeEvent = MemoryContext.GcEvent.unavailable();

    private long epoch;
    private int recordedWindows;
    private boolean notificationsObserved;
    private long cumulativeGcTimeMillis;
    private long cumulativeGcCount;
    private final Map<String, Long> cumulativePerCollectorCounts = new HashMap<>();
    private MemoryContext.GcEvent latestEvent = MemoryContext.GcEvent.unavailable();
    private long maxEndTimeMillis = -1;
    private final Deque<DiagnosticWindow> diagnosticWindows = new ArrayDeque<>();
    private final Deque<ExternalExplicitGcEvent> externalExplicitGcs = new ArrayDeque<>();
    private long evictedExplicitGcCount;
    private long evictedExplicitGcMaxDurationMillis = -1;
    private long evictedExplicitGcTotalDurationMillis;

    public BootUiDiagnosticGcTracker() {
        this(() -> null, MemoryContext.GcEvent::unavailable, true);
    }

    BootUiDiagnosticGcTracker(
            Supplier<MemoryContext.GcSample> sampleSupplier, Supplier<MemoryContext.GcEvent> eventSupplier) {
        this(sampleSupplier, eventSupplier, true);
    }

    BootUiDiagnosticGcTracker(
            Supplier<MemoryContext.GcSample> sampleSupplier,
            Supplier<MemoryContext.GcEvent> eventSupplier,
            boolean notificationsObserved) {
        this.sampleSupplier = Objects.requireNonNull(sampleSupplier, "sampleSupplier");
        this.eventSupplier = Objects.requireNonNull(eventSupplier, "eventSupplier");
        this.notificationsObserved = notificationsObserved;
    }

    private static BootUiDiagnosticGcTracker createShared() {
        BootUiDiagnosticGcTracker tracker = new BootUiDiagnosticGcTracker(
                MemoryCollector::currentGcSample,
                () -> MemoryCollector.readLastGcEvent().asGcEvent(),
                false);
        tracker.attachJvmNotificationListeners();
        return tracker;
    }

    private void attachJvmNotificationListeners() {
        try {
            int attached = 0;
            for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (collector instanceof NotificationEmitter emitter) {
                    try {
                        emitter.addNotificationListener(this::onJmxNotification, null, null);
                        attached++;
                    } catch (RuntimeException ignored) {
                        // Ignore individual collector listener failures.
                    }
                }
            }
            if (attached > 0) {
                setNotificationsObserved(true);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Non-HotSpot runtime without notification emitters.
        }
    }

    private void onJmxNotification(Notification notification, Object handback) {
        try {
            if (notification == null
                    || !com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(
                            notification.getType())
                    || !(notification.getUserData() instanceof CompositeData data)) {
                return;
            }
            com.sun.management.GarbageCollectionNotificationInfo info =
                    com.sun.management.GarbageCollectionNotificationInfo.from(data);
            com.sun.management.GcInfo gc = info.getGcInfo();
            if (gc != null) {
                recordGcNotification(
                        info.getGcName(),
                        gc.getId(),
                        info.getGcAction(),
                        info.getGcCause(),
                        gc.getEndTime(),
                        gc.getDuration());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Never fail on the JVM notification thread.
        }
    }

    public synchronized void setNotificationsObserved(boolean notificationsObserved) {
        this.notificationsObserved = notificationsObserved;
    }

    /** Runs a BootUI action that may trigger a garbage collection and records its GC counter delta. */
    public void run(Runnable action) {
        begin();
        try {
            action.run();
        } finally {
            end();
        }
    }

    /** Runs a checked BootUI action that may trigger a garbage collection and records its GC counter delta. */
    public <E extends Exception> void runThrowing(ThrowingRunnable<E> action) throws E {
        begin();
        try {
            action.run();
        } finally {
            end();
        }
    }

    /** Records an explicit before/after diagnostic GC window. */
    synchronized void recordWindow(
            MemoryContext.GcSample before,
            MemoryContext.GcSample after,
            MemoryContext.GcEvent beforeEvent,
            MemoryContext.GcEvent afterEvent) {
        recordedWindows++;
        if (before != null && after != null) {
            if (hasCounterDiscontinuity(before, after)) {
                resetInternal();
                return;
            }
            long timeDelta = before.gcTimeMillis() >= 0 && after.gcTimeMillis() >= before.gcTimeMillis()
                    ? after.gcTimeMillis() - before.gcTimeMillis()
                    : 0L;
            long countDelta = before.gcCount() >= 0 && after.gcCount() >= before.gcCount()
                    ? after.gcCount() - before.gcCount()
                    : 0L;
            cumulativeGcTimeMillis = saturateAdd(cumulativeGcTimeMillis, timeDelta);
            cumulativeGcCount = saturateAdd(cumulativeGcCount, countDelta);
            boolean anyCollectorDelta = false;
            Map<String, LongRange> collectorRanges = new HashMap<>();
            for (Map.Entry<String, Long> entry : after.perCollectorCounts().entrySet()) {
                Long prev = before.perCollectorCounts().get(entry.getKey());
                Long curr = entry.getValue();
                if (prev != null && prev >= 0 && curr != null && curr >= prev) {
                    long delta = curr - prev;
                    if (delta > 0) {
                        anyCollectorDelta = true;
                        collectorRanges.put(entry.getKey(), new LongRange(prev, curr));
                        cumulativePerCollectorCounts.merge(
                                entry.getKey(), delta, BootUiDiagnosticGcTracker::saturateAdd);
                    }
                }
            }
            MemoryContext.GcEvent recordedEvent = MemoryContext.GcEvent.unavailable();
            if (afterEvent != null
                    && afterEvent.available()
                    && isNewDiagnosticEvent(
                            beforeEvent, afterEvent, timeDelta > 0 || countDelta > 0 || anyCollectorDelta)) {
                latestEvent = afterEvent;
                recordedEvent = afterEvent;
                if (afterEvent.endTimeMillis() > maxEndTimeMillis) {
                    maxEndTimeMillis = afterEvent.endTimeMillis();
                }
            }
            DiagnosticWindow window = new DiagnosticWindow(
                    Map.copyOf(collectorRanges), before.uptimeMillis(), after.uptimeMillis(), recordedEvent);
            appendDiagnosticWindow(window);
        } else if (afterEvent != null && afterEvent.available() && !afterEvent.sameEvent(beforeEvent)) {
            latestEvent = afterEvent;
            if (afterEvent.endTimeMillis() > maxEndTimeMillis) {
                maxEndTimeMillis = afterEvent.endTimeMillis();
            }
            appendDiagnosticWindow(new DiagnosticWindow(Map.of(), -1, -1, afterEvent));
        }
    }

    /**
     * Records a GC notification from {@code GcEventSource}, keeping only external stop-the-world
     * {@code System.gc()} pauses that were not triggered by BootUI's own diagnostic/offload actions.
     */
    public synchronized void recordGcNotification(
            String collector, long gcId, String action, String cause, long endTimeMillis, long durationMillis) {
        notificationsObserved = true;
        if (!GcCollectorKinds.isExternalExplicitFullGc(collector, action, cause)) {
            return;
        }
        if (activeDepth > 0 && isInActiveDiagnosticWindow(collector, gcId, endTimeMillis)) {
            return;
        }
        ExternalExplicitGcEvent candidate =
                new ExternalExplicitGcEvent(collector, gcId, endTimeMillis, Math.max(0L, durationMillis));
        for (DiagnosticWindow window : diagnosticWindows) {
            if (window.matches(candidate)) {
                return;
            }
        }
        for (ExternalExplicitGcEvent existing : externalExplicitGcs) {
            if (existing.sameNotification(candidate)) {
                return;
            }
        }
        while (externalExplicitGcs.size() >= MAX_RETAINED_WINDOWS) {
            ExternalExplicitGcEvent evicted = externalExplicitGcs.removeFirst();
            evictedExplicitGcCount = saturateAdd(evictedExplicitGcCount, 1L);
            evictedExplicitGcMaxDurationMillis = Math.max(evictedExplicitGcMaxDurationMillis, evicted.durationMillis());
            evictedExplicitGcTotalDurationMillis =
                    saturateAdd(evictedExplicitGcTotalDurationMillis, evicted.durationMillis());
        }
        externalExplicitGcs.addLast(candidate);
    }

    private boolean isInActiveDiagnosticWindow(String collector, long gcId, long endTimeMillis) {
        if (activeBeforeSample == null) {
            return true;
        }
        Long prevCount = activeBeforeSample.perCollectorCounts().get(collector);
        if (prevCount != null && prevCount >= 0 && gcId >= 0) {
            // A delayed JMX notification for a collection that completed at or before activeBeforeSample
            // belongs to pre-action application activity and must not be discarded.
            return gcId > prevCount;
        }
        if (activeBeforeSample.uptimeMillis() >= 0 && endTimeMillis >= 0) {
            return endTimeMillis > activeBeforeSample.uptimeMillis();
        }
        return true;
    }

    public synchronized int recordedWindows() {
        return recordedWindows;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                epoch,
                cumulativeGcTimeMillis,
                cumulativeGcCount,
                Map.copyOf(cumulativePerCollectorCounts),
                latestEvent,
                maxEndTimeMillis,
                explicitGcSnapshot());
    }

    synchronized void reset() {
        resetInternal();
    }

    private void appendDiagnosticWindow(DiagnosticWindow window) {
        while (diagnosticWindows.size() >= MAX_RETAINED_WINDOWS) {
            diagnosticWindows.removeFirst();
        }
        diagnosticWindows.addLast(window);
        externalExplicitGcs.removeIf(window::matches);
    }

    private MemoryContext.ExplicitGcData explicitGcSnapshot() {
        long count = saturateAdd(evictedExplicitGcCount, externalExplicitGcs.size());
        if (count <= 0) {
            return notificationsObserved
                    ? MemoryContext.ExplicitGcData.none()
                    : MemoryContext.ExplicitGcData.unobserved();
        }
        long maxDuration = evictedExplicitGcMaxDurationMillis;
        long totalDuration = evictedExplicitGcTotalDurationMillis;
        String lastCollector = null;
        for (ExternalExplicitGcEvent event : externalExplicitGcs) {
            maxDuration = Math.max(maxDuration, event.durationMillis());
            totalDuration = saturateAdd(totalDuration, event.durationMillis());
            lastCollector = event.collector();
        }
        return new MemoryContext.ExplicitGcData(count, maxDuration, totalDuration, lastCollector, true);
    }

    private synchronized void begin() {
        if (activeDepth++ == 0) {
            activeBeforeSample = safeSample();
            activeBeforeEvent = safeEvent();
        }
    }

    private synchronized void end() {
        if (--activeDepth == 0) {
            MemoryContext.GcSample before = activeBeforeSample;
            MemoryContext.GcEvent beforeEvent = activeBeforeEvent;
            activeBeforeSample = null;
            activeBeforeEvent = MemoryContext.GcEvent.unavailable();
            MemoryContext.GcSample after = safeSample();
            MemoryContext.GcEvent afterEvent = safeEvent();
            recordWindow(before, after, beforeEvent, afterEvent);
        }
    }

    private void resetInternal() {
        epoch++;
        cumulativeGcTimeMillis = 0L;
        cumulativeGcCount = 0L;
        cumulativePerCollectorCounts.clear();
        latestEvent = MemoryContext.GcEvent.unavailable();
        maxEndTimeMillis = -1L;
        diagnosticWindows.clear();
        externalExplicitGcs.clear();
        evictedExplicitGcCount = 0L;
        evictedExplicitGcMaxDurationMillis = -1L;
        evictedExplicitGcTotalDurationMillis = 0L;
    }

    private static boolean isNewDiagnosticEvent(
            MemoryContext.GcEvent beforeEvent, MemoryContext.GcEvent afterEvent, boolean countersAdvanced) {
        if (beforeEvent == null || !beforeEvent.available()) {
            return true;
        }
        if (afterEvent.id() >= 0 && beforeEvent.id() >= 0) {
            return !afterEvent.sameEvent(beforeEvent);
        }
        if (afterEvent.endTimeMillis() >= 0 && beforeEvent.endTimeMillis() >= 0) {
            return afterEvent.endTimeMillis() > beforeEvent.endTimeMillis()
                    || !Objects.equals(afterEvent.collectorName(), beforeEvent.collectorName());
        }
        return countersAdvanced;
    }

    static boolean hasCounterDiscontinuity(MemoryContext.GcSample previous, MemoryContext.GcSample current) {
        if (previous.uptimeMillis() >= 0
                && current.uptimeMillis() >= 0
                && current.uptimeMillis() < previous.uptimeMillis()) {
            return true;
        }
        if (previous.gcTimeMillis() >= 0
                && current.gcTimeMillis() >= 0
                && current.gcTimeMillis() < previous.gcTimeMillis()) {
            return true;
        }
        if (previous.gcCount() >= 0 && current.gcCount() >= 0 && current.gcCount() < previous.gcCount()) {
            return true;
        }
        if (!previous.perCollectorCounts().isEmpty()
                && !current.perCollectorCounts().isEmpty()
                && !previous.perCollectorCounts()
                        .keySet()
                        .equals(current.perCollectorCounts().keySet())) {
            return true;
        }
        for (Map.Entry<String, Long> entry : current.perCollectorCounts().entrySet()) {
            Long prev = previous.perCollectorCounts().get(entry.getKey());
            if (prev != null
                    && prev >= 0
                    && entry.getValue() != null
                    && entry.getValue() >= 0
                    && entry.getValue() < prev) {
                return true;
            }
        }
        return false;
    }

    static long saturateAdd(long left, long right) {
        if (right <= 0) {
            return left;
        }
        if (left <= 0) {
            return right;
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private MemoryContext.GcSample safeSample() {
        try {
            return sampleSupplier.get();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private MemoryContext.GcEvent safeEvent() {
        try {
            MemoryContext.GcEvent event = eventSupplier.get();
            return event != null ? event : MemoryContext.GcEvent.unavailable();
        } catch (RuntimeException | LinkageError ex) {
            return MemoryContext.GcEvent.unavailable();
        }
    }

    private record LongRange(long fromExclusive, long toInclusive) {
        boolean contains(long value) {
            return value > fromExclusive && value <= toInclusive;
        }
    }

    private record DiagnosticWindow(
            Map<String, LongRange> collectorRanges,
            long beforeUptimeMillis,
            long afterUptimeMillis,
            MemoryContext.GcEvent afterEvent) {

        boolean matches(ExternalExplicitGcEvent candidate) {
            LongRange range = collectorRanges.get(candidate.collector());
            if (range != null && candidate.gcId() >= 0) {
                return range.contains(candidate.gcId());
            }
            if (afterEvent != null
                    && afterEvent.available()
                    && Objects.equals(afterEvent.collectorName(), candidate.collector())) {
                if (afterEvent.id() >= 0 && candidate.gcId() >= 0) {
                    return afterEvent.id() == candidate.gcId();
                }
                if (afterEvent.endTimeMillis() >= 0 && candidate.endTimeMillis() >= 0) {
                    return afterEvent.endTimeMillis() == candidate.endTimeMillis();
                }
            }
            return collectorRanges.isEmpty()
                    && candidate.gcId() < 0
                    && beforeUptimeMillis >= 0
                    && afterUptimeMillis >= beforeUptimeMillis
                    && candidate.endTimeMillis() >= beforeUptimeMillis
                    && candidate.endTimeMillis() <= afterUptimeMillis;
        }
    }

    private record ExternalExplicitGcEvent(String collector, long gcId, long endTimeMillis, long durationMillis) {

        boolean sameNotification(ExternalExplicitGcEvent other) {
            if (!Objects.equals(collector, other.collector())) {
                return false;
            }
            if (gcId >= 0 && other.gcId() >= 0) {
                return gcId == other.gcId();
            }
            return endTimeMillis >= 0 && endTimeMillis == other.endTimeMillis();
        }
    }

    record Snapshot(
            long epoch,
            long gcTimeMillis,
            long gcCount,
            Map<String, Long> perCollectorCounts,
            MemoryContext.GcEvent latestEvent,
            long maxEndTimeMillis,
            MemoryContext.ExplicitGcData explicitGc) {

        Snapshot(
                long epoch,
                long gcTimeMillis,
                long gcCount,
                Map<String, Long> perCollectorCounts,
                MemoryContext.GcEvent latestEvent,
                long maxEndTimeMillis) {
            this(
                    epoch,
                    gcTimeMillis,
                    gcCount,
                    perCollectorCounts,
                    latestEvent,
                    maxEndTimeMillis,
                    MemoryContext.ExplicitGcData.none());
        }

        Snapshot {
            perCollectorCounts = perCollectorCounts == null ? Map.of() : Map.copyOf(perCollectorCounts);
            latestEvent = latestEvent == null ? MemoryContext.GcEvent.unavailable() : latestEvent;
            explicitGc = explicitGc == null ? MemoryContext.ExplicitGcData.none() : explicitGc;
        }

        static Snapshot empty() {
            return new Snapshot(
                    0, 0, 0, Map.of(), MemoryContext.GcEvent.unavailable(), -1, MemoryContext.ExplicitGcData.none());
        }
    }
}
