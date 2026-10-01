package io.github.jdubois.bootui.engine.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The union of half-open intervals {@code [start, end)} ({@code docs/PLAN-v2.md} §5.4), so overlapping calls in a
 * request's timeline never add up to more than the time they cover.
 */
public final class IntervalUnion {

    private IntervalUnion() {}

    /** The disjoint, sorted intervals covering exactly what {@code intervals} cover; empty intervals are dropped. */
    public static List<long[]> of(List<long[]> intervals) {
        List<long[]> sorted = new ArrayList<>();
        for (long[] interval : intervals) {
            if (interval[1] > interval[0]) {
                sorted.add(new long[] {interval[0], interval[1]});
            }
        }
        sorted.sort(Comparator.comparingLong(interval -> interval[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] interval : sorted) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && interval[0] <= last[1]) {
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(interval);
            }
        }
        return merged;
    }

    /** The total length {@code intervals} cover, each instant counted once. */
    public static long length(List<long[]> intervals) {
        long total = 0;
        for (long[] interval : of(intervals)) {
            total += interval[1] - interval[0];
        }
        return total;
    }
}
