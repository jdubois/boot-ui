package io.github.jdubois.bootui.engine.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The one nearest-rank percentile BootUI computes over its retained windows.
 *
 * <p>SQL Trace statement rankings, HTTP route summaries and the Live Activity request, slowest-request and
 * outbound-call KPIs all describe a bounded buffer BootUI already holds, so they compute an exact percentile
 * over that window instead of estimating one. They share this helper so a p95 means the same thing in every
 * panel and on every adapter: the smallest retained value at or above which {@code percentile} percent of the
 * window lies, which is always one of the observed values rather than an interpolation between two.</p>
 *
 * <p>The rank is {@code ceil(percentile / 100 × size)}, clamped to the window. It is computed in floating
 * point exactly as the three copies this helper replaces did, so no previously reported figure changes.</p>
 */
public final class Percentiles {

    private Percentiles() {}

    /**
     * The zero-based index of the nearest-rank {@code percentile} in a sorted window of {@code size} values.
     *
     * @throws IllegalArgumentException when {@code size} is not positive
     */
    public static int nearestRankIndex(int size, int percentile) {
        if (size <= 0) {
            throw new IllegalArgumentException("A percentile needs at least one value");
        }
        int rank = (int) Math.ceil(percentile / 100.0 * size);
        return Math.min(size - 1, Math.max(0, rank - 1));
    }

    /**
     * The nearest-rank {@code percentile} of values already sorted in ascending order, or {@code null} when
     * the window is empty. The caller owns the sort, so a group asked for several percentiles sorts once.
     */
    public static Long ofSorted(List<Long> sortedAscending, int percentile) {
        if (sortedAscending == null || sortedAscending.isEmpty()) {
            return null;
        }
        return sortedAscending.get(nearestRankIndex(sortedAscending.size(), percentile));
    }

    /**
     * The nearest-rank {@code percentile} of {@code values} in any order, or {@code null} when there are none.
     * {@code null} elements are ignored, because an exchange or call BootUI could not time has no duration to
     * rank rather than a duration of zero.
     */
    public static Long of(Collection<Long> values, int percentile) {
        return ofSorted(sortedAscending(values), percentile);
    }

    /** A sorted, {@code null}-free copy of {@code values}, for callers that need several percentiles. */
    public static List<Long> sortedAscending(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Long> sorted = new ArrayList<>(values.size());
        for (Long value : values) {
            if (value != null) {
                sorted.add(value);
            }
        }
        sorted.sort(null);
        return sorted;
    }
}
