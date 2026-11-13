package io.github.jdubois.bootui.engine.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Tracks garbage collections triggered by BootUI's own explicit diagnostic and memory-reclamation
 * actions (<b>Free BootUI memory</b> via {@link MemoryOffloadService}, <b>Low-footprint mode</b>
 * via {@link LowFootprintService}, and <b>Heap Dump</b> capture/analysis via
 * {@code HeapDumpService}) so the Memory Advisor can exclude self-inflicted pauses and Full GCs
 * from {@code MEM-GC-002}, {@code MEM-GC-003}, {@code MEM-GC-005}, and {@code MEM-GC-006}.
 */
public final class BootUiDiagnosticGcTracker {

    /** Process-wide tracker reading the live JVM's collector MXBeans. */
    public static final BootUiDiagnosticGcTracker SHARED = new BootUiDiagnosticGcTracker(
            MemoryCollector::currentGcSample,
            () -> MemoryCollector.readLastGcEvent().asGcEvent());

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
    private long cumulativeGcTimeMillis;
    private long cumulativeGcCount;
    private final Map<String, Long> cumulativePerCollectorCounts = new HashMap<>();
    private MemoryContext.GcEvent latestEvent = MemoryContext.GcEvent.unavailable();
    private long maxEndTimeMillis = -1;

    public BootUiDiagnosticGcTracker() {
        this(() -> null, MemoryContext.GcEvent::unavailable);
    }

    BootUiDiagnosticGcTracker(
            Supplier<MemoryContext.GcSample> sampleSupplier, Supplier<MemoryContext.GcEvent> eventSupplier) {
        this.sampleSupplier = Objects.requireNonNull(sampleSupplier, "sampleSupplier");
        this.eventSupplier = Objects.requireNonNull(eventSupplier, "eventSupplier");
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
            for (Map.Entry<String, Long> entry : after.perCollectorCounts().entrySet()) {
                Long prev = before.perCollectorCounts().get(entry.getKey());
                Long curr = entry.getValue();
                if (prev != null && prev >= 0 && curr != null && curr >= prev) {
                    long delta = curr - prev;
                    if (delta > 0) {
                        anyCollectorDelta = true;
                        cumulativePerCollectorCounts.merge(
                                entry.getKey(), delta, BootUiDiagnosticGcTracker::saturateAdd);
                    }
                }
            }
            if (afterEvent != null
                    && afterEvent.available()
                    && isNewDiagnosticEvent(
                            beforeEvent, afterEvent, timeDelta > 0 || countDelta > 0 || anyCollectorDelta)) {
                latestEvent = afterEvent;
                if (afterEvent.endTimeMillis() > maxEndTimeMillis) {
                    maxEndTimeMillis = afterEvent.endTimeMillis();
                }
            }
        } else if (afterEvent != null && afterEvent.available() && !afterEvent.sameEvent(beforeEvent)) {
            latestEvent = afterEvent;
            if (afterEvent.endTimeMillis() > maxEndTimeMillis) {
                maxEndTimeMillis = afterEvent.endTimeMillis();
            }
        }
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
                maxEndTimeMillis);
    }

    synchronized void reset() {
        resetInternal();
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

    record Snapshot(
            long epoch,
            long gcTimeMillis,
            long gcCount,
            Map<String, Long> perCollectorCounts,
            MemoryContext.GcEvent latestEvent,
            long maxEndTimeMillis) {

        Snapshot {
            perCollectorCounts = perCollectorCounts == null ? Map.of() : Map.copyOf(perCollectorCounts);
            latestEvent = latestEvent == null ? MemoryContext.GcEvent.unavailable() : latestEvent;
        }

        static Snapshot empty() {
            return new Snapshot(0, 0, 0, Map.of(), MemoryContext.GcEvent.unavailable(), -1);
        }
    }
}
