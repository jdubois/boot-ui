package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.support.Percentiles;
import java.util.Arrays;

/**
 * A log-linear latency histogram in microseconds ({@code docs/PLAN-v2.md} §5.2): values below 16 µs each have a bucket
 * of their own, and every power of two above has 16 linear sub-buckets, so a percentile read from it is within 6.25 %
 * of the true value. It holds a dense {@code long[512]}, reaching about 4.7 hours; longer values count in the last
 * bucket.
 *
 * <p>It is deterministic and mergeable, so run summaries and run comparison add histograms bucket by bucket. A
 * percentile uses the same nearest rank as {@link Percentiles}, and reads as the middle of the bucket holding that
 * rank. Not thread-safe: the journal's dispatcher is its only writer, and readers take a {@link #copy()}.</p>
 */
public final class LatencyHistogram {

    /** The number of buckets. */
    public static final int BUCKETS = 512;

    private static final int SUB_BUCKET_BITS = 4;

    private static final int SUB_BUCKETS = 1 << SUB_BUCKET_BITS;

    private final long[] counts;
    private long count;
    private long totalMicros;
    private long maxMicros;

    public LatencyHistogram() {
        this(new long[BUCKETS], 0, 0, 0);
    }

    private LatencyHistogram(long[] counts, long count, long totalMicros, long maxMicros) {
        this.counts = counts;
        this.count = count;
        this.totalMicros = totalMicros;
        this.maxMicros = maxMicros;
    }

    /** Records one duration in nanoseconds; a negative duration, which means none was measured, is ignored. */
    public void recordNanos(long nanos) {
        if (nanos < 0) {
            return;
        }
        recordValue(nanos / 1_000);
    }

    /** Records a non-negative raw value, such as allocated bytes, without converting its units. */
    public void recordValue(long micros) {
        if (micros < 0) {
            return;
        }
        counts[bucketOf(micros)]++;
        count++;
        totalMicros += micros;
        maxMicros = Math.max(maxMicros, micros);
    }

    /** Adds every value {@code other} recorded. */
    public void merge(LatencyHistogram other) {
        for (int i = 0; i < BUCKETS; i++) {
            counts[i] += other.counts[i];
        }
        count += other.count;
        totalMicros += other.totalMicros;
        maxMicros = Math.max(maxMicros, other.maxMicros);
    }

    public long count() {
        return count;
    }

    public long totalMicros() {
        return totalMicros;
    }

    public long maxMicros() {
        return maxMicros;
    }

    /**
     * The nearest-rank {@code percentile} in microseconds, as the middle of the bucket holding that rank, or
     * {@code null} when nothing was recorded.
     */
    public Long percentileMicros(int percentile) {
        return percentileValue(percentile);
    }

    /** The nearest-rank percentile in the raw units supplied to {@link #recordValue(long)}. */
    public Long percentileValue(int percentile) {
        if (count == 0) {
            return null;
        }
        long rank = (long) Percentiles.nearestRankIndex((int) Math.min(Integer.MAX_VALUE, count), percentile) + 1;
        if (count > Integer.MAX_VALUE) {
            rank = (long) Math.ceil(percentile / 100.0 * count);
        }
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return Math.min(maxMicros, middleOf(i));
            }
        }
        return maxMicros;
    }

    /** The values recorded in {@code bucket}; for run summaries, which keep only non-empty buckets. */
    long bucketCount(int bucket) {
        return counts[bucket];
    }

    /** A histogram rebuilt from a run summary's buckets and totals. */
    static LatencyHistogram restore(long[] counts, long count, long totalMicros, long maxMicros) {
        if (counts.length != BUCKETS) {
            throw new IllegalArgumentException("A latency histogram has " + BUCKETS + " buckets");
        }
        return new LatencyHistogram(Arrays.copyOf(counts, BUCKETS), count, totalMicros, maxMicros);
    }

    /** A copy readers can use while the dispatcher keeps recording. */
    public LatencyHistogram copy() {
        return new LatencyHistogram(Arrays.copyOf(counts, BUCKETS), count, totalMicros, maxMicros);
    }

    /** The bucket of a value in microseconds. */
    static int bucketOf(long micros) {
        if (micros < SUB_BUCKETS) {
            return (int) Math.max(0, micros);
        }
        int power = 63 - Long.numberOfLeadingZeros(micros);
        int subBucket = (int) (micros >>> (power - SUB_BUCKET_BITS)) & (SUB_BUCKETS - 1);
        int bucket = (power - SUB_BUCKET_BITS + 1) * SUB_BUCKETS + subBucket;
        return Math.min(BUCKETS - 1, bucket);
    }

    /** The smallest value in microseconds that {@code bucket} holds. */
    static long lowerBoundOf(int bucket) {
        if (bucket < SUB_BUCKETS) {
            return bucket;
        }
        int power = bucket / SUB_BUCKETS + SUB_BUCKET_BITS - 1;
        long subBucket = bucket % SUB_BUCKETS;
        return (1L << power) + (subBucket << (power - SUB_BUCKET_BITS));
    }

    private static long middleOf(int bucket) {
        if (bucket < SUB_BUCKETS) {
            return bucket;
        }
        long width = 1L << (bucket / SUB_BUCKETS - 1);
        return lowerBoundOf(bucket) + width / 2;
    }
}
