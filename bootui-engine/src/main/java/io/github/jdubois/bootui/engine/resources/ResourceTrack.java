package io.github.jdubois.bootui.engine.resources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resource track and CPU ledger of the current run ({@code docs/PLAN-v2.md} §5.11): one point per sweep, in a fixed
 * ring of the {@value #CAPACITY} most recent, plus the run's totals. It lives with the journal's aggregates, outside the
 * evidence budget, and costs a bounded few hundred kilobytes.
 *
 * <p>Each coherent point's CPU parts sum to measured process CPU: the requests' share, each thread family's share,
 * and the unclassified remainder (GC, JIT, VM threads, and work without consecutive thread readings). Missing or
 * inconsistent process readings leave the remainder unknown, including in run totals until cleared. Each point
 * stores the journal's sequence number, which places it among the events by order, not by clock.</p>
 *
 * <p>Thread-safe: the sampler adds points, and readers take copies.</p>
 */
public final class ResourceTrack {

    /** The most recent points kept: 15 minutes at the default interval. */
    public static final int CAPACITY = 900;

    /** The most thread families the track names; the others share {@link #OTHER_FAMILY}. */
    public static final int MAX_FAMILIES = 32;

    /** The family of the threads beyond {@link #MAX_FAMILIES}. */
    public static final String OTHER_FAMILY = "Other";

    /** The family of BootUI's own threads, whose names start with {@code bootui-}. */
    public static final String BOOTUI_FAMILY = "BootUI";

    private final Point[] ring = new Point[CAPACITY];
    private final List<String> families = new ArrayList<>();
    private int next;
    private int size;
    private long sweeps;
    private long processCpuNanos;
    private long requestCpuNanos;
    private long internalCpuNanos;
    private boolean internalCpuKnown = true;
    private long[] familyCpuNanos = new long[0];

    /** The index of {@code family}, registering it while there is room, else the index of {@link #OTHER_FAMILY}. */
    synchronized int family(String family) {
        int index = families.indexOf(family);
        if (index >= 0) {
            return index;
        }
        if (families.size() < MAX_FAMILIES || OTHER_FAMILY.equals(family)) {
            families.add(family);
            return families.size() - 1;
        }
        return family(OTHER_FAMILY);
    }

    synchronized void add(Point point) {
        ring[next] = point;
        next = (next + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
        sweeps++;
        if (point.processCpuNanos() >= 0) {
            processCpuNanos += point.processCpuNanos();
        }
        if (point.processCpuNanos() < 0 || point.internalCpuNanos() < 0) {
            internalCpuKnown = false;
        } else {
            internalCpuNanos += point.internalCpuNanos();
        }
        requestCpuNanos += point.requestCpuNanos();
        long[] parts = point.familyCpuNanos();
        if (familyCpuNanos.length < parts.length) {
            long[] grown = new long[parts.length];
            System.arraycopy(familyCpuNanos, 0, grown, 0, familyCpuNanos.length);
            familyCpuNanos = grown;
        }
        for (int i = 0; i < parts.length; i++) {
            familyCpuNanos[i] += parts[i];
        }
    }

    /** The points kept, oldest first. */
    public synchronized List<Point> points() {
        List<Point> points = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            points.add(ring[(next - size + i + CAPACITY) % CAPACITY]);
        }
        return Collections.unmodifiableList(points);
    }

    /** The family names, in the order of each point's {@link Point#familyCpuNanos()}. */
    public synchronized List<String> families() {
        return List.copyOf(families);
    }

    /** The run's totals since it started or was cleared, including the points the ring evicted. */
    public synchronized Totals totals() {
        Map<String, Long> byFamily = new LinkedHashMap<>();
        for (int i = 0; i < familyCpuNanos.length && i < families.size(); i++) {
            if (familyCpuNanos[i] != 0) {
                byFamily.put(families.get(i), familyCpuNanos[i]);
            }
        }
        return new Totals(sweeps, processCpuNanos, requestCpuNanos, internalCpuKnown ? internalCpuNanos : -1, byFamily);
    }

    /** Drops every point and total, for <b>Clear recording</b>; the family names are kept. */
    public synchronized void clear() {
        Arrays.fill(ring, null);
        next = 0;
        size = 0;
        sweeps = 0;
        processCpuNanos = 0;
        requestCpuNanos = 0;
        internalCpuNanos = 0;
        internalCpuKnown = true;
        familyCpuNanos = new long[0];
    }

    /**
     * One sweep. CPU values are the interval's, in nanoseconds; {@code processCpuNanos} and {@code internalCpuNanos} are
     * {@code -1} when consecutive process readings are unavailable or decrease. The remainder is also {@code -1}
     * when measured thread CPU exceeds measured process CPU.
     *
     * @param epochMillis when the sweep ran, for display only
     * @param sequence the journal's last sequence number when the sweep ran
     * @param intervalNanos the interval's length
     * @param processCpuNanos the process's CPU time in the interval
     * @param requestCpuNanos the share the scope readings credited to requests
     * @param internalCpuNanos unclassified process CPU after measured thread deltas, or {@code -1} when unknown
     * @param familyCpuNanos each family's share, indexed as {@link ResourceTrack#families()}
     * @param unreadThreads platform threads beyond {@code bootui.resources.max-threads}, whose CPU counts as internal
     * @param heapUsedBytes heap used
     * @param heapCommittedBytes heap committed
     * @param heapAfterGcBytes heap used after each heap pool's last collection, or {@code -1} when unknown
     * @param allocatedBytes bytes the threads read allocated in the interval, or {@code -1} when unknown
     * @param liveThreads live platform threads
     * @param daemonThreads live daemon threads
     */
    public record Point(
            long epochMillis,
            long sequence,
            long intervalNanos,
            long processCpuNanos,
            long requestCpuNanos,
            long internalCpuNanos,
            long[] familyCpuNanos,
            int unreadThreads,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapAfterGcBytes,
            long allocatedBytes,
            int liveThreads,
            int daemonThreads) {

        public Point {
            familyCpuNanos = familyCpuNanos == null ? new long[0] : familyCpuNanos.clone();
        }

        /** Each family's share, indexed as {@link ResourceTrack#families()}; a copy. */
        @Override
        public long[] familyCpuNanos() {
            return familyCpuNanos.clone();
        }

        /** The family shares summed: CPU work outside requests on the threads read. */
        public long familiesCpuNanos() {
            long sum = 0;
            for (long part : familyCpuNanos) {
                sum += part;
            }
            return sum;
        }
    }

    /**
     * The run's totals.
     *
     * @param sweeps the sweeps recorded, including those the ring evicted
     * @param processCpuNanos the process's CPU time over the sweeps with known process deltas
     * @param requestCpuNanos the requests' share
     * @param internalCpuNanos unclassified process CPU, or {@code -1} if any interval's remainder was unknown
     * @param familyCpuNanos each family's share, the non-zero ones only
     */
    public record Totals(
            long sweeps,
            long processCpuNanos,
            long requestCpuNanos,
            long internalCpuNanos,
            Map<String, Long> familyCpuNanos) {

        public Totals {
            familyCpuNanos = Collections.unmodifiableMap(new LinkedHashMap<>(familyCpuNanos));
        }
    }
}
