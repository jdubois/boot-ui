package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The overhead benchmark's distribution-free confidence interval of the median, against binomial tables. */
class AgentOverheadMedianIntervalTests {

    @Test
    void fifteenPairsUseTheFourthLowestAndFourthHighestAt965PercentCoverage() {
        double[] values = {15, 3, 14, 1, 13, 2, 12, 4, 11, 5, 10, 6, 9, 7, 8};

        double[] interval = AgentOverheadBenchmarkIT.medianInterval(values);

        assertThat(interval[0]).isEqualTo(4);
        assertThat(interval[1]).isEqualTo(12);
        assertThat(interval[2]).isCloseTo(0.9648, within(0.0001));
    }

    @Test
    void ninePairsUseTheSecondLowestAndSecondHighest() {
        double[] interval = AgentOverheadBenchmarkIT.medianInterval(new double[] {9, 8, 7, 6, 5, 4, 3, 2, 1});

        assertThat(interval[0]).isEqualTo(2);
        assertThat(interval[1]).isEqualTo(8);
        assertThat(interval[2]).isCloseTo(0.9609, within(0.0001));
    }

    @Test
    void fewPairsFallBackToTheirRangeWithItsLowerCoverage() {
        double[] interval = AgentOverheadBenchmarkIT.medianInterval(new double[] {3, 1, 2, 5, 4});

        assertThat(interval[0]).isEqualTo(1);
        assertThat(interval[1]).isEqualTo(5);
        assertThat(interval[2]).isCloseTo(0.9375, within(0.0001));
    }

    @Test
    void theIntervalGateReadsTheLowerBoundAndTheMedianGateTheMedian() {
        assertThat(AgentOverheadBenchmarkIT.Gate.of("")).isEqualTo(AgentOverheadBenchmarkIT.Gate.MEDIAN);
        assertThat(AgentOverheadBenchmarkIT.Gate.of("median").enforced(11.2, 7.4))
                .isEqualTo(11.2);
        assertThat(AgentOverheadBenchmarkIT.Gate.of(" Interval ").enforced(11.2, 7.4))
                .isEqualTo(7.4);
        assertThatThrownBy(() -> AgentOverheadBenchmarkIT.Gate.of("mean"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("median or interval");
    }
}
