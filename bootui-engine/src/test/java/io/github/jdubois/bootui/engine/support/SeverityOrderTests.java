package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared severity-ranking and severity-tally helpers every advisor scanner (Architecture,
 * Hibernate, Memory, REST API, Pentesting, CRaC, GraalVM, the Quarkus app advisor, and the Quarkus
 * Security advisor) relies on to sort and group findings by severity.
 */
class SeverityOrderTests {

    @Test
    void ranksKnownSeveritiesMostSevereFirst() {
        assertThat(SeverityOrder.rank("CRITICAL")).isZero();
        assertThat(SeverityOrder.rank("HIGH")).isEqualTo(1);
        assertThat(SeverityOrder.rank("MEDIUM")).isEqualTo(2);
        assertThat(SeverityOrder.rank("LOW")).isEqualTo(3);
        assertThat(SeverityOrder.rank("INFO")).isEqualTo(4);
    }

    @Test
    void unknownSeveritySortsAfterEveryKnownSeverity() {
        assertThat(SeverityOrder.rank("NOT_A_SEVERITY")).isEqualTo(SeverityOrder.DEFAULT.size());
    }

    @Test
    void ranksWithinACustomOrder() {
        List<String> order = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN");

        assertThat(SeverityOrder.rank(order, "UNKNOWN")).isEqualTo(4);
        assertThat(SeverityOrder.rank(order, "NONE")).isEqualTo(order.size());
    }

    @Test
    void countsZeroInitializesEverySeverityAndTalliesCountableResults() {
        List<String> severities = List.of("HIGH", "HIGH", "LOW", "INFO");

        Map<String, Integer> counts = SeverityOrder.counts(severities, ignored -> true, s -> s);

        assertThat(counts)
                .containsExactly(
                        Map.entry("CRITICAL", 0),
                        Map.entry("HIGH", 2),
                        Map.entry("MEDIUM", 0),
                        Map.entry("LOW", 1),
                        Map.entry("INFO", 1));
    }

    @Test
    void countsSkipsResultsThatAreNotCountable() {
        List<String> severities = List.of("HIGH", "HIGH", "LOW");

        Map<String, Integer> counts = SeverityOrder.counts(severities, "HIGH"::equals, s -> s);

        assertThat(counts.get("HIGH")).isEqualTo(2);
        assertThat(counts.get("LOW")).isZero();
    }

    @Test
    void countsSkipsSeveritiesNotInOrder() {
        List<String> severities = List.of("HIGH", "UNMAPPED");

        Map<String, Integer> counts = SeverityOrder.counts(severities, ignored -> true, s -> s);

        assertThat(counts).doesNotContainKey("UNMAPPED");
        assertThat(counts.get("HIGH")).isEqualTo(1);
    }

    @Test
    void countsWithoutPredicateCountsEveryResult() {
        List<String> severities = List.of("CRITICAL", "CRITICAL", "INFO");

        Map<String, Integer> counts = SeverityOrder.counts(severities, s -> s);

        assertThat(counts.get("CRITICAL")).isEqualTo(2);
        assertThat(counts.get("INFO")).isEqualTo(1);
        assertThat(counts.get("HIGH")).isZero();
    }

    @Test
    void countsWithCustomOrderUsesThatOrdersVocabulary() {
        List<String> order = List.of("CRITICAL", "UNKNOWN");
        List<String> severities = List.of("CRITICAL", "UNKNOWN", "UNKNOWN");

        Map<String, Integer> counts = SeverityOrder.counts(order, severities, s -> s);

        assertThat(counts).containsExactly(Map.entry("CRITICAL", 1), Map.entry("UNKNOWN", 2));
    }
}
