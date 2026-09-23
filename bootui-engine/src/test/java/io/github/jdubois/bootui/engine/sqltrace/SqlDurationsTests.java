package io.github.jdubois.bootui.engine.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlDurationsTests {

    @Test
    void convertsExactMicrosecondTotalsToFractionalMillis() {
        assertThat(SqlDurations.millis(0L)).isZero();
        assertThat(SqlDurations.millis(400L)).isEqualTo(0.4);
        assertThat(SqlDurations.millis(1_200L)).isEqualTo(1.2);
    }

    @Test
    void keepsASubMicrosecondMeanPositive() {
        assertThat(SqlDurations.millis(1.0 / 3.0)).isGreaterThan(0.0);
        assertThat(SqlDurations.millis(0.0)).isZero();
    }

    @Test
    void roundsRatherThanTruncatesToWholeMillis() {
        assertThat(SqlDurations.roundedMillis(0L)).isZero();
        assertThat(SqlDurations.roundedMillis(499L)).isZero();
        assertThat(SqlDurations.roundedMillis(500L)).isEqualTo(1L);
        assertThat(SqlDurations.roundedMillis(1_499L)).isEqualTo(1L);
    }

    @Test
    void ceilsToTheWholeMillisecondsADurationCovers() {
        assertThat(SqlDurations.ceilMillis(-5L)).isZero();
        assertThat(SqlDurations.ceilMillis(0L)).isZero();
        assertThat(SqlDurations.ceilMillis(1L)).isEqualTo(1L);
        assertThat(SqlDurations.ceilMillis(999L)).isEqualTo(1L);
        assertThat(SqlDurations.ceilMillis(1_000L)).isEqualTo(1L);
        assertThat(SqlDurations.ceilMillis(1_001L)).isEqualTo(2L);
    }

    @Test
    void ceilsWithoutOverflowingNearLongMaxValue() {
        assertThat(SqlDurations.ceilMillis(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE / 1_000L + 1);
        assertThat(SqlDurations.ceilMillis(Long.MAX_VALUE - 998)).isPositive();
    }
}
