package io.github.jdubois.bootui.engine.memory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
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
    private final LongSupplier uptimeSupplier;
    private long observationStartUptimeMillis;

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
    private boolean diagnosticHistoryTruncated;
    private final Deque<ExternalExplicitGcEvent> externalExplicitGcs = new ArrayDeque<>();
    private long evictedExplicitGcCount;
    private long evictedExplicitGcMaxDurationMillis = -1;
    private long evictedExplicitGcTotalDurationMillis;
    private long notificationSequence;
    private long evictedExplicitGcMinEndTimeMillis = Long.MAX_VALUE;
    private long evictedExplicitGcMaxEndTimeMillis = -1;
    private long evictedTimedExplicitGcSequence;
    private long untimedExplicitGcSequence;

    public BootUiDiagnosticGcTracker() {
        this(
                () -> null,
                MemoryContext.GcEvent::unavailable,
                true,
                () -> ManagementFactory.getRuntimeMXBean().getUptime());
    }

    BootUiDiagnosticGcTracker(
            Supplier<MemoryContext.GcSample> sampleSupplier, Supplier<MemoryContext.GcEvent> eventSupplier) {
        this(sampleSupplier, eventSupplier, true);
    }

    BootUiDiagnosticGcTracker(
            Supplier<MemoryContext.GcSample> sampleSupplier,
            Supplier<MemoryContext.GcEvent> eventSupplier,
            boolean notificationsObserved) {
        this(sampleSupplier, eventSupplier, notificationsObserved, () -> 0L);
    }

    BootUiDiagnosticGcTracker(
            Supplier<MemoryContext.GcSample> sampleSupplier,
            Supplier<MemoryContext.GcEvent> eventSupplier,
            boolean notificationsObserved,
            LongSupplier uptimeSupplier) {
        this.sampleSupplier = Objects.requireNonNull(sampleSupplier, "sampleSupplier");
        this.eventSupplier = Objects.requireNonNull(eventSupplier, "eventSupplier");
        this.uptimeSupplier = Objects.requireNonNull(uptimeSupplier, "uptimeSupplier");
        this.observationStartUptimeMillis = safeUptime();
        this.notificationsObserved = notificationsObserved;
    }

    private static BootUiDiagnosticGcTracker createShared() {
        BootUiDiagnosticGcTracker tracker = new BootUiDiagnosticGcTracker(
                MemoryCollector::currentGcSample,
                () -> MemoryCollector.readLastGcEvent().asGcEvent(),
                false,
                () -> ManagementFactory.getRuntimeMXBean().getUptime());
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
                    Map.copyOf(collectorRanges),
                    before.uptimeMillis(),
                    after.uptimeMillis(),
                    recordedEvent,
                    before.gcTimeMillis() >= 0 && after.gcTimeMillis() >= before.gcTimeMillis() ? timeDelta : -1L,
                    before.gcCount() >= 0 && after.gcCount() >= before.gcCount() ? countDelta : -1L);
            appendDiagnosticWindow(window);
        } else if (afterEvent != null && afterEvent.available() && !afterEvent.sameEvent(beforeEvent)) {
            latestEvent = afterEvent;
            if (afterEvent.endTimeMillis() > maxEndTimeMillis) {
                maxEndTimeMillis = afterEvent.endTimeMillis();
            }
            appendDiagnosticWindow(new DiagnosticWindow(Map.of(), -1, -1, afterEvent, -1, -1));
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
        ExternalExplicitGcEvent candidate = new ExternalExplicitGcEvent(
                collector, gcId, endTimeMillis, Math.max(0L, durationMillis), saturateAdd(notificationSequence, 1));
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
        notificationSequence = candidate.sequence();
        if (endTimeMillis < 0) {
            untimedExplicitGcSequence = notificationSequence;
        }
        while (externalExplicitGcs.size() >= MAX_RETAINED_WINDOWS) {
            ExternalExplicitGcEvent evicted = externalExplicitGcs.removeFirst();
            evictedExplicitGcCount = saturateAdd(evictedExplicitGcCount, 1L);
            evictedExplicitGcMaxDurationMillis = Math.max(evictedExplicitGcMaxDurationMillis, evicted.durationMillis());
            evictedExplicitGcTotalDurationMillis =
                    saturateAdd(evictedExplicitGcTotalDurationMillis, evicted.durationMillis());
            if (evicted.endTimeMillis() >= 0) {
                evictedTimedExplicitGcSequence = Math.max(evictedTimedExplicitGcSequence, evicted.sequence());
                evictedExplicitGcMinEndTimeMillis =
                        Math.min(evictedExplicitGcMinEndTimeMillis, evicted.endTimeMillis());
                evictedExplicitGcMaxEndTimeMillis =
                        Math.max(evictedExplicitGcMaxEndTimeMillis, evicted.endTimeMillis());
            }
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

    synchronized Snapshot resetIfEpoch(long expectedEpoch) {
        if (epoch == expectedEpoch) {
            resetInternal();
        }
        return snapshot();
    }

    synchronized DiagnosticAccounting diagnosticAccounting(
            Snapshot expected, MemoryContext.GcEvent event, long ownGcTimeMillis) {
        if (activeDepth > 0
                || diagnosticHistoryTruncated
                || expected.epoch() != epoch
                || expected.gcTimeMillis() != cumulativeGcTimeMillis
                || expected.gcCount() != cumulativeGcCount
                || !expected.perCollectorCounts().equals(cumulativePerCollectorCounts)
                || !expected.latestEvent().equals(latestEvent)
                || expected.maxEndTimeMillis() != maxEndTimeMillis
                || event.collectorName() == null) {
            return new DiagnosticAccounting(Accounting.UNKNOWN, Accounting.UNKNOWN);
        }
        Accounting time = ownGcTimeMillis < 0 ? Accounting.UNKNOWN : Accounting.NOT_INCLUDED;
        Accounting count = Accounting.NOT_INCLUDED;
        for (DiagnosticWindow window : diagnosticWindows) {
            LongRange ids = window.collectorRanges().get(event.collectorName());
            if (ids != null && !ids.contains(event.id())) {
                continue;
            }
            if (window.beforeUptimeMillis() >= 0
                    && window.afterUptimeMillis() >= window.beforeUptimeMillis()
                    && (event.endTimeMillis() < window.beforeUptimeMillis()
                            || event.endTimeMillis() > window.afterUptimeMillis())) {
                continue;
            }
            boolean included = ids != null && ids.contains(event.id());
            time = accounting(time, included, window.gcTimeMillis(), ownGcTimeMillis);
            count = accounting(count, included, window.gcCount(), 1L);
        }
        return new DiagnosticAccounting(time, count);
    }

    private static Accounting accounting(Accounting current, boolean included, long observed, long required) {
        if (current == Accounting.INCLUDED || observed == 0) {
            return current;
        }
        return included && required >= 0 && observed >= required ? Accounting.INCLUDED : Accounting.UNKNOWN;
    }

    /**
     * Reads (previous boundary, current boundary] without consuming notification history or changing
     * the cumulative diagnostic accounting. The cursor belongs to one scanner, not to the tracker.
     */
    synchronized ExplicitGcObservation observeExplicitGc(
            ExplicitGcCursor previous, long expectedEpoch, long throughUptimeMillis) {
        return observeExplicitGc(previous, expectedEpoch, throughUptimeMillis, false);
    }

    synchronized ExplicitGcObservation observeExplicitGc(
            ExplicitGcCursor previous, long expectedEpoch, long throughUptimeMillis, boolean resetObservation) {
        long since = previous == null ? observationStartUptimeMillis : previous.throughUptimeMillis();
        boolean epochChanged = expectedEpoch != epoch || (previous != null && previous.epoch() != epoch);
        long nextBoundary = throughUptimeMillis;
        if (resetObservation || epochChanged) {
            // Reset may happen after the context's last uptime sample. Its cleared interval is
            // unknown, and the next comparable interval must start no earlier than the actual reset.
            nextBoundary = observationStartUptimeMillis < 0
                    ? -1L
                    : Math.max(observationStartUptimeMillis, throughUptimeMillis);
        }
        ExplicitGcCursor cursor = new ExplicitGcCursor(epoch, nextBoundary, notificationSequence);
        if (resetObservation
                || epochChanged
                || since < 0
                || since < observationStartUptimeMillis
                || throughUptimeMillis < since) {
            return new ExplicitGcObservation(
                    cursor,
                    new MemoryContext.ExplicitGcData(
                            0,
                            -1,
                            0,
                            null,
                            notificationsObserved,
                            "Explicit-GC observation boundaries are unavailable or incomparable; a new baseline is required."));
        }

        long previousSequence = previous == null ? 0 : previous.notificationSequence();
        // A bounded time envelope can conservatively overstate loss after out-of-order delivery,
        // but must never turn evicted in-window events into complete clean coverage.
        boolean incomplete = untimedExplicitGcSequence > previousSequence
                || (evictedExplicitGcMaxEndTimeMillis > since
                        && evictedExplicitGcMinEndTimeMillis <= throughUptimeMillis)
                || (previous != null
                        && evictedTimedExplicitGcSequence > previousSequence
                        && evictedExplicitGcMinEndTimeMillis <= since
                        && evictedExplicitGcMaxEndTimeMillis > observationStartUptimeMillis);
        long count = 0;
        long maxDuration = -1;
        long totalDuration = 0;
        long latestEnd = -1;
        String lastCollector = null;
        for (ExternalExplicitGcEvent event : externalExplicitGcs) {
            if (previous != null
                    && event.sequence() > previousSequence
                    && event.endTimeMillis() > observationStartUptimeMillis
                    && event.endTimeMillis() <= since) {
                // Late delivery cannot retroactively repair a report already published for that interval.
                incomplete = true;
            }
            if (event.endTimeMillis() > since && event.endTimeMillis() <= throughUptimeMillis) {
                count++;
                maxDuration = Math.max(maxDuration, event.durationMillis());
                totalDuration = saturateAdd(totalDuration, event.durationMillis());
                if (event.endTimeMillis() >= latestEnd) {
                    latestEnd = event.endTimeMillis();
                    lastCollector = event.collector();
                }
            }
        }
        String limitation = null;
        if (!notificationsObserved) {
            limitation = "GC cause notifications are not active on this runtime; external System.gc() pauses could not"
                    + " be fully observed.";
        } else if (incomplete) {
            limitation = "Explicit-GC notification history is incomplete: events were evicted, untimed, or delivered"
                    + " after their scan boundary.";
        }
        return new ExplicitGcObservation(
                cursor,
                new MemoryContext.ExplicitGcData(
                        count, maxDuration, totalDuration, lastCollector, notificationsObserved, limitation));
    }

    private void appendDiagnosticWindow(DiagnosticWindow window) {
        while (diagnosticWindows.size() >= MAX_RETAINED_WINDOWS) {
            diagnosticWindows.removeFirst();
            diagnosticHistoryTruncated = true;
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
        diagnosticHistoryTruncated = false;
        externalExplicitGcs.clear();
        evictedExplicitGcCount = 0L;
        evictedExplicitGcMaxDurationMillis = -1L;
        evictedExplicitGcTotalDurationMillis = 0L;
        notificationSequence = 0L;
        evictedExplicitGcMinEndTimeMillis = Long.MAX_VALUE;
        evictedExplicitGcMaxEndTimeMillis = -1L;
        evictedTimedExplicitGcSequence = 0L;
        untimedExplicitGcSequence = 0L;
        observationStartUptimeMillis = safeUptime();
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

    private long safeUptime() {
        try {
            return uptimeSupplier.getAsLong();
        } catch (RuntimeException | LinkageError ex) {
            return -1L;
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
            MemoryContext.GcEvent afterEvent,
            long gcTimeMillis,
            long gcCount) {

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
                    && candidate.endTimeMillis() > beforeUptimeMillis
                    && candidate.endTimeMillis() <= afterUptimeMillis;
        }
    }

    private record ExternalExplicitGcEvent(
            String collector, long gcId, long endTimeMillis, long durationMillis, long sequence) {

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

    record ExplicitGcCursor(long epoch, long throughUptimeMillis, long notificationSequence) {}

    record ExplicitGcObservation(ExplicitGcCursor cursor, MemoryContext.ExplicitGcData data) {}

    enum Accounting {
        NOT_INCLUDED,
        INCLUDED,
        UNKNOWN
    }

    record DiagnosticAccounting(Accounting time, Accounting count) {}

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
