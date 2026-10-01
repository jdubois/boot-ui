package io.github.jdubois.bootui.engine.resources;

import io.github.jdubois.bootui.engine.resources.ResourceUsage.Unmeasured;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Measures the CPU time, allocated bytes, and completed GC pauses of each request by identity, not by timestamp
 * ({@code docs/PLAN-v2.md} §5.11).
 *
 * <p>A request's work runs on threads in <em>segments</em>. A segment opens when a thread switches to the request's
 * work and closes when it switches away, which {@code BootUiCorrelation} reports through {@link #switchTo} every time a
 * correlation scope opens or closes, on all three stacks. Each segment reads its thread's CPU time, allocated bytes, and
 * the collection count of every pause collector when it opens and closes, and adds the differences to its request. A
 * request that hops threads sums its segments.</p>
 *
 * <p>Only requests an adapter {@linkplain #begin begins} are measured, until it {@linkplain #take takes} their usage
 * when it publishes the request. Taking closes every segment of the request still open, on whichever thread, so an
 * adapter that cannot see where its framework's worker finishes, such as Quarkus REST, may {@linkplain #switchTo enter}
 * a segment without closing it.</p>
 *
 * <p>Thread-safe. A segment is closed exactly once, by its thread or by {@link #take}.</p>
 */
public final class SegmentMeter {

    /** The most GC ranges a request keeps; beyond them, {@link ResourceUsage#gcPauseRangesTruncated()} is set. */
    public static final int MAX_GC_RANGES = 16;

    /** The most requests measured at once, so requests never taken cannot grow the meter without bound. */
    static final int MAX_OPEN_REQUESTS = 10_000;

    /** How long an untaken request is kept before the meter may drop it to make room. */
    static final long STALE_NANOS = TimeUnit.MINUTES.toNanos(10);

    private static final SegmentMeter SHARED = new SegmentMeter(new JvmReadings());

    private final Readings readings;
    private final ConcurrentHashMap<String, Meter> meters = new ConcurrentHashMap<>();
    private final ThreadLocal<Segment> segments = new ThreadLocal<>();

    SegmentMeter(Readings readings) {
        this.readings = readings;
    }

    /** The meter {@code BootUiCorrelation} reports to. */
    public static SegmentMeter shared() {
        return SHARED;
    }

    /**
     * Starts measuring {@code requestId}, and opens its first segment on the calling thread, which the adapter calls
     * where the request's work starts. Does nothing for {@code null}.
     *
     * @return whether the request is measured; {@code false} when the meter is full of requests never taken
     */
    public boolean begin(String requestId) {
        if (requestId == null) {
            return false;
        }
        try {
            if (meters.size() >= MAX_OPEN_REQUESTS && !purgeStale()) {
                return false;
            }
            meters.putIfAbsent(requestId, new Meter(System.nanoTime()));
            switchTo(requestId);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Reports that the calling thread now works for {@code requestId}, or for no request when it is {@code null}: closes
     * the thread's segment of another request, and opens one for {@code requestId} when it is measured. Never throws.
     */
    public void switchTo(String requestId) {
        try {
            Segment segment = segments.get();
            if (segment != null) {
                Meter current = segment.meter;
                if (current != null) {
                    if (requestId != null && requestId.equals(segment.requestId)) {
                        return;
                    }
                    segment.closeOnOwnThread(current, readings);
                }
            }
            if (requestId == null || meters.isEmpty()) {
                return;
            }
            Meter meter = meters.get(requestId);
            if (meter == null) {
                return;
            }
            if (segment == null) {
                segment = new Segment(Thread.currentThread(), readings.collectors());
                segments.set(segment);
            }
            segment.open(meter, requestId, readings);
        } catch (RuntimeException | LinkageError ex) {
            // Measuring never disturbs the application's work.
        }
    }

    /**
     * Stops measuring {@code requestId} and returns its usage, closing its segments still open on any thread. Work the
     * request does afterwards is not measured.
     *
     * @return the request's usage, or {@code null} when it was not {@linkplain #begin begun}
     */
    public ResourceUsage take(String requestId) {
        if (requestId == null) {
            return null;
        }
        try {
            Meter meter = meters.remove(requestId);
            return meter == null ? null : meter.take(readings);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /** How many requests are measured now; for tests and diagnostics. */
    int openRequests() {
        return meters.size();
    }

    private boolean purgeStale() {
        long now = System.nanoTime();
        for (Iterator<Meter> iterator = meters.values().iterator(); iterator.hasNext(); ) {
            Meter meter = iterator.next();
            if (now - meter.begunNanos > STALE_NANOS) {
                iterator.remove();
                meter.discard();
            }
        }
        return meters.size() < MAX_OPEN_REQUESTS;
    }

    /** Where readings come from, so tests can drive the meter deterministically. */
    interface Readings {

        /** Whether this JVM measures per-thread CPU time and allocated bytes. */
        boolean supported();

        long currentCpuNanos();

        long currentAllocatedBytes();

        long cpuNanos(long threadId);

        long allocatedBytes(long threadId);

        int collectors();

        String collector(int index);

        void readCollections(long[] counts);
    }

    /** The JVM's own readings. */
    private static final class JvmReadings implements Readings {

        @Override
        public boolean supported() {
            return ThreadReadings.get().supported();
        }

        @Override
        public long currentCpuNanos() {
            return ThreadReadings.get().currentCpuNanos();
        }

        @Override
        public long currentAllocatedBytes() {
            return ThreadReadings.get().currentAllocatedBytes();
        }

        @Override
        public long cpuNanos(long threadId) {
            return ThreadReadings.get().cpuNanos(threadId);
        }

        @Override
        public long allocatedBytes(long threadId) {
            return ThreadReadings.get().allocatedBytes(threadId);
        }

        @Override
        public int collectors() {
            return PauseCollectors.get().size();
        }

        @Override
        public String collector(int index) {
            return PauseCollectors.get().name(index);
        }

        @Override
        public void readCollections(long[] counts) {
            PauseCollectors.get().read(counts);
        }
    }

    /** The open work of one thread for one request. Its fields are written by its thread, then published under the lock. */
    private static final class Segment {

        private final Thread thread;
        private final long threadId;
        private final boolean virtual;
        private final long[] startCollections;
        private final long[] endCollections;
        private volatile Meter meter;
        private String requestId;
        private long startCpuNanos;
        private long startAllocatedBytes;

        @SuppressWarnings("deprecation") // Thread.threadId() is JDK 19+; the baseline is 17.
        Segment(Thread thread, int collectors) {
            this.thread = thread;
            this.threadId = thread.getId();
            this.virtual = "java.lang.VirtualThread".equals(thread.getClass().getName());
            this.startCollections = new long[collectors];
            this.endCollections = new long[collectors];
        }

        void open(Meter meter, String requestId, Readings readings) {
            readings.readCollections(startCollections);
            startAllocatedBytes = readings.currentAllocatedBytes();
            startCpuNanos = readings.currentCpuNanos();
            this.requestId = requestId;
            if (meter.opened(this)) {
                this.meter = meter;
            }
        }

        void closeOnOwnThread(Meter meter, Readings readings) {
            long cpu = readings.currentCpuNanos();
            long allocated = readings.currentAllocatedBytes();
            readings.readCollections(endCollections);
            meter.closed(this, cpu, allocated, endCollections, readings);
        }
    }

    /** One measured request: its totals so far and its segments still open. */
    private static final class Meter {

        private final long begunNanos;
        private final List<Segment> open = new ArrayList<>(2);
        private final List<GcPauseRange> ranges = new ArrayList<>();
        private boolean done;
        private long cpuNanos;
        private long allocatedBytes;
        private int segments;
        private int unmeasured;
        private Unmeasured reason;
        private boolean truncated;

        Meter(long begunNanos) {
            this.begunNanos = begunNanos;
        }

        synchronized boolean opened(Segment segment) {
            if (done) {
                return false;
            }
            open.add(segment);
            return true;
        }

        synchronized void closed(Segment segment, long cpu, long allocated, long[] collections, Readings readings) {
            if (!open.remove(segment)) {
                return;
            }
            credit(segment, cpu, allocated, collections, readings);
        }

        synchronized ResourceUsage take(Readings readings) {
            done = true;
            if (!open.isEmpty()) {
                long[] collections = new long[readings.collectors()];
                readings.readCollections(collections);
                Thread caller = Thread.currentThread();
                for (Segment segment : open) {
                    boolean own = segment.thread == caller;
                    long cpu = own ? readings.currentCpuNanos() : readings.cpuNanos(segment.threadId);
                    long allocated = own ? readings.currentAllocatedBytes() : readings.allocatedBytes(segment.threadId);
                    credit(segment, cpu, allocated, collections, readings);
                }
                open.clear();
            }
            long pauses = 0;
            for (GcPauseRange range : ranges) {
                pauses += range.count();
            }
            return new ResourceUsage(cpuNanos, allocatedBytes, segments, unmeasured, reason, pauses, ranges, truncated);
        }

        synchronized void discard() {
            done = true;
            for (Segment segment : open) {
                segment.meter = null;
            }
            open.clear();
        }

        /**
         * Adds a closed segment. It releases the segment last, because its thread reuses the segment's start readings
         * as soon as it sees it released.
         */
        private void credit(Segment segment, long cpu, long allocated, long[] collections, Readings readings) {
            segments++;
            if (segment.startCpuNanos >= 0 && cpu >= 0 && segment.startAllocatedBytes >= 0 && allocated >= 0) {
                cpuNanos += Math.max(0, cpu - segment.startCpuNanos);
                allocatedBytes += Math.max(0, allocated - segment.startAllocatedBytes);
            } else {
                unmeasured++;
                if (reason == null) {
                    reason = segment.virtual
                            ? Unmeasured.VIRTUAL_THREAD
                            : readings.supported() ? Unmeasured.THREAD_ENDED : Unmeasured.UNSUPPORTED;
                }
            }
            for (int i = 0; i < collections.length; i++) {
                long after = segment.startCollections[i];
                long last = collections[i];
                if (after >= 0 && last > after) {
                    addRange(readings.collector(i), after, last);
                }
            }
            segment.meter = null;
        }

        /** Adds the collections {@code (after, last]}, merged with the overlapping or adjacent ranges kept. */
        private void addRange(String collector, long after, long last) {
            for (Iterator<GcPauseRange> iterator = ranges.iterator(); iterator.hasNext(); ) {
                GcPauseRange range = iterator.next();
                if (range.collector().equals(collector) && range.afterId() <= last && after <= range.lastId()) {
                    after = Math.min(after, range.afterId());
                    last = Math.max(last, range.lastId());
                    iterator.remove();
                }
            }
            if (ranges.size() < MAX_GC_RANGES) {
                ranges.add(new GcPauseRange(collector, after, last));
            } else {
                truncated = true;
            }
        }
    }
}
