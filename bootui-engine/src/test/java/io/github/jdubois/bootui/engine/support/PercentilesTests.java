package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class PercentilesTests {

    @Test
    void emptyWindowHasNoPercentile() {
        assertThat(Percentiles.of(List.of(), 95)).isNull();
        assertThat(Percentiles.of(null, 95)).isNull();
        assertThat(Percentiles.ofSorted(List.of(), 50)).isNull();
    }

    @Test
    void singleValueIsEveryPercentile() {
        assertThat(Percentiles.of(List.of(7L), 1)).isEqualTo(7L);
        assertThat(Percentiles.of(List.of(7L), 50)).isEqualTo(7L);
        assertThat(Percentiles.of(List.of(7L), 100)).isEqualTo(7L);
    }

    @Test
    void nearestRankIsAnObservedValueNeverAnInterpolation() {
        List<Long> values = LongStream.rangeClosed(1, 100).boxed().toList();

        assertThat(Percentiles.of(values, 50)).isEqualTo(50L);
        assertThat(Percentiles.of(values, 95)).isEqualTo(95L);
        assertThat(Percentiles.of(values, 99)).isEqualTo(99L);
        assertThat(Percentiles.of(List.of(10L, 20L), 50)).isEqualTo(10L);
        assertThat(Percentiles.of(List.of(10L, 20L), 51)).isEqualTo(20L);
    }

    @Test
    void sortsUnorderedInputAndIgnoresUntimedValues() {
        List<Long> values = new ArrayList<>(Arrays.asList(40L, null, 10L, 30L, 20L));

        assertThat(Percentiles.of(values, 50)).isEqualTo(20L);
        assertThat(Percentiles.of(values, 95)).isEqualTo(40L);
        assertThat(Percentiles.sortedAscending(values)).containsExactly(10L, 20L, 30L, 40L);
    }

    /**
     * The three copies this helper replaced — SQL Trace's statement aggregate, the engine Live Activity
     * assembler and Spring MVC's Live Activity service — all computed the rank in floating point. Pin the
     * formula over every window size a default buffer can hold, so no previously reported figure moves.
     */
    @Test
    void matchesTheFormulaEveryReplacedCopyUsed() {
        for (int size = 1; size <= 1_000; size++) {
            for (int percentile : new int[] {1, 50, 90, 95, 99, 100}) {
                int legacy = Math.min(size - 1, Math.max(0, (int) Math.ceil(percentile / 100.0 * size) - 1));
                assertThat(Percentiles.nearestRankIndex(size, percentile))
                        .as("p%d of %d", percentile, size)
                        .isEqualTo(legacy);
            }
        }
    }

    @Test
    void rejectsAnEmptyRank() {
        assertThatThrownBy(() -> Percentiles.nearestRankIndex(0, 50)).isInstanceOf(IllegalArgumentException.class);
    }
}
