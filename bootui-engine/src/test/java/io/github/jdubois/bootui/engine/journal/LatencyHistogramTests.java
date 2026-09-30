package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.jdubois.bootui.engine.support.Percentiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LatencyHistogramTests {

    @Test
    void bucketsAreExactBelowSixteenMicrosecondsAndSixteenPerPowerOfTwoAbove() {
        for (long micros = 0; micros < 16; micros++) {
            assertThat(LatencyHistogram.bucketOf(micros)).isEqualTo((int) micros);
        }
        assertThat(LatencyHistogram.bucketOf(16)).isEqualTo(16);
        assertThat(LatencyHistogram.bucketOf(31)).isEqualTo(31);
        assertThat(LatencyHistogram.bucketOf(32)).isEqualTo(32);
        assertThat(LatencyHistogram.bucketOf(33)).isEqualTo(32);
        assertThat(LatencyHistogram.bucketOf(Long.MAX_VALUE)).isEqualTo(LatencyHistogram.BUCKETS - 1);
        for (int bucket = 0; bucket < LatencyHistogram.BUCKETS; bucket++) {
            long lower = LatencyHistogram.lowerBoundOf(bucket);
            assertThat(LatencyHistogram.bucketOf(lower)).as("bucket %d", bucket).isEqualTo(bucket);
            if (bucket > 0) {
                assertThat(LatencyHistogram.bucketOf(lower - 1)).isEqualTo(bucket - 1);
            }
        }
    }

    @Test
    void percentilesAreWithinSixAndAQuarterPercentOfTheExactNearestRank() {
        Random random = new Random(42);
        LatencyHistogram histogram = new LatencyHistogram();
        List<Long> micros = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            // Log-uniform from 1 µs to about 30 s, as request and statement latencies spread.
            long value = (long) Math.exp(random.nextDouble() * Math.log(30_000_000));
            micros.add(value);
            histogram.recordNanos(value * 1_000 + random.nextInt(1_000));
        }

        for (int percentile : new int[] {1, 50, 90, 95, 99, 100}) {
            long exact = Percentiles.of(micros, percentile);
            assertThat(histogram.percentileMicros(percentile).doubleValue())
                    .as("p%d of %d µs", percentile, exact)
                    .isCloseTo(exact, within(Math.max(1, exact * 0.0625)));
        }
        assertThat(histogram.count()).isEqualTo(20_000);
        assertThat(histogram.maxMicros())
                .isEqualTo(micros.stream().mapToLong(Long::longValue).max().orElseThrow());
    }

    @Test
    void emptyHistogramsHaveNoPercentileAndUnmeasuredDurationsAreIgnored() {
        LatencyHistogram histogram = new LatencyHistogram();

        histogram.recordNanos(-1);

        assertThat(histogram.count()).isZero();
        assertThat(histogram.percentileMicros(95)).isNull();
    }

    @Test
    void mergingAddsEveryBucketAndCopiesAreIndependent() {
        LatencyHistogram first = new LatencyHistogram();
        LatencyHistogram second = new LatencyHistogram();
        first.recordNanos(5_000);
        second.recordNanos(2_000_000);
        second.recordNanos(3_000);

        LatencyHistogram copy = first.copy();
        first.merge(second);

        assertThat(first.count()).isEqualTo(3);
        assertThat(first.totalMicros()).isEqualTo(2_008);
        assertThat(first.maxMicros()).isEqualTo(2_000);
        assertThat(first.percentileMicros(50)).isEqualTo(5);
        assertThat(copy.count()).isEqualTo(1);
    }
}
