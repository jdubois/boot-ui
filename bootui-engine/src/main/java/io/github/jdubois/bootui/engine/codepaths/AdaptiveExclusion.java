package io.github.jdubois.bootui.engine.codepaths;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The code-paths sensor's adaptive exclusion, decided in the engine ({@code docs/PLAN-v2.md} §5.13, §5.14, M5-4a): a
 * method called more than {@value #MAX_CALLS_PER_SECOND} times a second with a mean under {@value #MIN_MEAN_NANOS} ns,
 * such as a getter in a loop, is excluded for the rest of the run, so its time stays in its caller. The advice itself
 * keeps no counter.
 *
 * <p>The rate is measured against the time the fragments themselves recorded, not the wall time between drains, so
 * sparse heavy requests are judged on what they did: for each method, the calls and time of its nodes are summed with the
 * duration of every fragment it ran in, counted once per fragment. A method is evaluated as soon as its fragments add up
 * to {@value #EVIDENCE_NANOS} ns, which one long fragment can do alone, and otherwise when a window of
 * {@value #WINDOW_NANOS} ns of wall time ends, if it was called at least {@value #MIN_CALLS} times; a method called
 * fewer times is never excluded, since a handful of calls in a short fragment is no rate, and goes on adding up. An
 * evaluated method starts over unless excluded, and a method once excluded is never evaluated again; a new run starts a
 * new instance. Not thread-safe.
 */
public final class AdaptiveExclusion {

    /** Calls a second of recorded time above which a cheap method is excluded. */
    public static final long MAX_CALLS_PER_SECOND = 50_000;

    /** The mean duration, in nanoseconds, under which a frequent method is excluded. */
    public static final long MIN_MEAN_NANOS = 2_000;

    /** The recorded time, summed over a method's fragments, at which it is evaluated at once. */
    public static final long EVIDENCE_NANOS = 100_000_000L;

    /** The fewest calls a method is excluded with: the rate's calls over {@link #EVIDENCE_NANOS}. */
    public static final long MIN_CALLS = MAX_CALLS_PER_SECOND * EVIDENCE_NANOS / 1_000_000_000L;

    /** The wall time after which every method with at least {@link #MIN_CALLS} calls is evaluated. */
    public static final long WINDOW_NANOS = 1_000_000_000L;

    /** The most methods excluded in a run. */
    public static final int MAX_EXCLUDED = 10_000;

    private static final int CALLS = 0;
    private static final int NANOS = 1;
    private static final int FRAGMENT_NANOS = 2;
    private static final int LAST_FRAGMENT = 3;

    private final Map<Integer, long[]> sums = new HashMap<>();
    private final Set<Integer> due = new LinkedHashSet<>();
    private final Set<Integer> excluded = new LinkedHashSet<>();
    private long windowStart = Long.MIN_VALUE;
    private long fragments;

    /** Adds a fragment's calls, time, and recorded duration per method, Other nodes aside. */
    public void record(CodePathFragment fragment, long nowNanos) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = nowNanos;
        }
        long sequence = ++fragments;
        long duration = Math.max(0L, fragment.endNanos() - fragment.startNanos());
        for (int node = 0; node < fragment.nodeCount(); node++) {
            int id = fragment.method()[node];
            if (id < 0 || excluded.contains(id)) {
                continue;
            }
            long[] method = sums.computeIfAbsent(id, ignored -> new long[4]);
            method[CALLS] += fragment.calls()[node];
            method[NANOS] += fragment.total()[node];
            if (method[LAST_FRAGMENT] != sequence) {
                method[LAST_FRAGMENT] = sequence;
                method[FRAGMENT_NANOS] += duration;
                if (method[FRAGMENT_NANOS] >= EVIDENCE_NANOS) {
                    due.add(id);
                }
            }
        }
    }

    /**
     * Evaluates the methods whose fragments recorded {@link #EVIDENCE_NANOS}, and, once the window lasted
     * {@link #WINDOW_NANOS}, every method called at least {@link #MIN_CALLS} times: the methods it newly excludes, in
     * ascending id order.
     */
    public List<Integer> evaluate(long nowNanos) {
        boolean windowEnded = windowStart != Long.MIN_VALUE && nowNanos - windowStart >= WINDOW_NANOS;
        if (due.isEmpty() && !windowEnded) {
            return List.of();
        }
        List<Integer> newly = new ArrayList<>();
        Iterator<Map.Entry<Integer, long[]>> entries = sums.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, long[]> entry = entries.next();
            long[] method = entry.getValue();
            if (!due.contains(entry.getKey()) && !(windowEnded && method[CALLS] >= MIN_CALLS)) {
                continue;
            }
            entries.remove();
            if (excluded(method[CALLS], method[NANOS], method[FRAGMENT_NANOS])
                    && excluded.size() + newly.size() < MAX_EXCLUDED) {
                newly.add(entry.getKey());
            }
        }
        due.clear();
        if (windowEnded) {
            windowStart = nowNanos;
        }
        newly.sort(Integer::compare);
        excluded.addAll(newly);
        return newly;
    }

    /**
     * Whether {@code calls} calls taking {@code nanos} in total, in fragments that recorded {@code fragmentNanos}, are
     * at least {@link #MIN_CALLS} and exceed the rate with a mean under the threshold.
     */
    static boolean excluded(long calls, long nanos, long fragmentNanos) {
        if (calls < MIN_CALLS) {
            return false;
        }
        // calls / seconds > MAX_CALLS_PER_SECOND, and nanos / calls < MIN_MEAN_NANOS, without dividing.
        boolean frequent =
                (double) calls * 1_000_000_000d > (double) MAX_CALLS_PER_SECOND * Math.max(0L, fragmentNanos);
        boolean cheap = nanos < MIN_MEAN_NANOS * calls;
        return frequent && cheap;
    }

    /** Every method excluded in this run, in the order they were. */
    public List<Integer> excluded() {
        return List.copyOf(excluded);
    }

    /** Whether {@code id} is excluded. */
    public boolean isExcluded(int id) {
        return excluded.contains(id);
    }
}
