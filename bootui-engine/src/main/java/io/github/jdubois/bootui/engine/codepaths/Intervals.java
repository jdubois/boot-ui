package io.github.jdubois.bootui.engine.codepaths;

import java.util.Arrays;

/**
 * A bounded set of {@code [start, end)} intervals on the JVM's nanosecond clock, whose union length overlapping
 * children of a request share once ({@code docs/PLAN-v2.md} §5.4, §5.14): two handoffs running at the same time count
 * their overlap once. Past {@value #MAX} intervals, the newest is merged into the last kept one, which can only
 * overstate the union by the gap between them. Not thread-safe.
 */
final class Intervals {

    static final int MAX = 256;

    private long[] starts = new long[4];
    private long[] ends = new long[4];
    private int size;

    /** Adds {@code [start, end)}; an empty or inverted interval adds nothing. */
    void add(long start, long end) {
        if (end <= start) {
            return;
        }
        if (size == MAX) {
            starts[size - 1] = Math.min(starts[size - 1], start);
            ends[size - 1] = Math.max(ends[size - 1], end);
            return;
        }
        if (size == starts.length) {
            starts = Arrays.copyOf(starts, size * 2);
            ends = Arrays.copyOf(ends, size * 2);
        }
        starts[size] = start;
        ends[size] = end;
        size++;
    }

    /** The intervals added. */
    int size() {
        return size;
    }

    /** The earliest start, or 0 when empty. */
    long first() {
        long first = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            first = Math.min(first, starts[i]);
        }
        return size == 0 ? 0L : first;
    }

    /**
     * The union as disjoint {@code [start, end)} pairs in ascending order, {@code start0, end0, start1, ...}: what
     * {@link #addAll} restores without changing the union, in the least memory.
     */
    long[] compact() {
        if (size == 0) {
            return new long[0];
        }
        Integer[] order = order();
        long[] pairs = new long[size * 2];
        int count = 0;
        long currentStart = starts[order[0]];
        long currentEnd = ends[order[0]];
        for (int k = 1; k < size; k++) {
            int i = order[k];
            if (starts[i] > currentEnd) {
                pairs[count++] = currentStart;
                pairs[count++] = currentEnd;
                currentStart = starts[i];
                currentEnd = ends[i];
            } else if (ends[i] > currentEnd) {
                currentEnd = ends[i];
            }
        }
        pairs[count++] = currentStart;
        pairs[count++] = currentEnd;
        return Arrays.copyOf(pairs, count);
    }

    /** Adds the intervals of {@code pairs}, as {@link #compact} returned them. */
    void addAll(long[] pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            add(pairs[i], pairs[i + 1]);
        }
    }

    private Integer[] order() {
        Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(starts[a], starts[b]));
        return order;
    }

    /** The length of the union of the intervals, in nanoseconds. */
    long union() {
        if (size == 0) {
            return 0L;
        }
        Integer[] order = order();
        long total = 0L;
        long currentStart = starts[order[0]];
        long currentEnd = ends[order[0]];
        for (int k = 1; k < size; k++) {
            int i = order[k];
            if (starts[i] > currentEnd) {
                total += currentEnd - currentStart;
                currentStart = starts[i];
                currentEnd = ends[i];
            } else if (ends[i] > currentEnd) {
                currentEnd = ends[i];
            }
        }
        return total + (currentEnd - currentStart);
    }
}
