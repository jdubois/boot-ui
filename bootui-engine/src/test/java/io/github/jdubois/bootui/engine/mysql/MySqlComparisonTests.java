package io.github.jdubois.bootui.engine.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MySqlMetricDto;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MySqlComparisonTests {
    @Test
    void aSlowStatusQueryDoesNotMistakeSamplingUncertaintyForARestart() {
        MySqlComparisons comparisons = new MySqlComparisons();
        comparisons.observe("pool", "server", sample(100_000, 100_100, "100", "10"));
        assertThat(comparisons.observe("pool", "server", sample(200_000, 206_000, "200", "20")))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.delta()).isEqualTo("10");
                    assertThat(change.previousReadAt()).isEqualTo(100_100);
                    assertThat(change.readAt()).isEqualTo(206_000);
                });
    }

    @Test
    void detectsAChangedBootWindowEvenWhenUptimeAndCountersIncreased() {
        MySqlComparisons comparisons = new MySqlComparisons();
        comparisons.observe("pool", "server", report(100_000, "100", "10"));
        assertThat(comparisons.observe("pool", "server", report(300_000, "150", "20")))
                .isEmpty();
    }

    @Test
    void exactDeltasUseEachMetricsActualIntervalAcrossPartialReads() {
        MySqlComparisons comparisons = new MySqlComparisons();
        assertThat(comparisons.observe("pool", "server", report(100_000, "100", "18446744073709551000")))
                .isEmpty();
        assertThat(comparisons.observe("pool", "server", report(101_000, "101", null)))
                .isEmpty();
        assertThat(comparisons.observe("pool", "server", report(103_000, "103", "18446744073709551615")))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.delta()).isEqualTo("615");
                    assertThat(change.previousReadAt()).isEqualTo(100_000);
                    assertThat(change.readAt()).isEqualTo(103_000);
                });
    }

    @Test
    void serverRestartCounterResetAndRemovedSourcesDoNotProduceBogusComparisons() {
        MySqlComparisons comparisons = new MySqlComparisons();
        comparisons.observe("pool", "server", report(100_000, "100", "100"));
        assertThat(comparisons.observe("pool", "server", report(101_000, "101", "1")))
                .isEmpty();
        assertThat(comparisons.observe("pool", "other", report(102_000, "102", "200")))
                .isEmpty();
        assertThat(comparisons.observe("pool", "other", report(103_000, "1", "300")))
                .isEmpty();
        comparisons.retain(Set.of());
        assertThat(comparisons.observe("pool", "other", report(104_000, "2", "400")))
                .isEmpty();
        comparisons.clear();
        assertThat(comparisons.observe("pool", "other", report(105_000, "3", "500")))
                .isEmpty();
    }

    @Test
    void absentUptimeNeverCertifiesComparableCounters() {
        MySqlComparisons comparisons = new MySqlComparisons();
        comparisons.observe("pool", "server", report(100_000, "100", "10"));
        assertThat(comparisons.observe("pool", "server", report(110_000, null, "500")))
                .isEmpty();
    }

    private static MySqlCounterSample report(long at, String uptime, String count) {
        return sample(at, at, uptime, count);
    }

    private static MySqlCounterSample sample(long startedAt, long observedAt, String uptime, String count) {
        List<MySqlMetricDto> metrics = List.of(
                new MySqlMetricDto("Uptime", "Uptime", uptime, "seconds", "SERVER", "global_status"),
                new MySqlMetricDto("Connections", "Connections", count, "count", "SERVER", "global_status"));
        return new MySqlCounterSample("fixture", startedAt, observedAt, metrics);
    }
}
