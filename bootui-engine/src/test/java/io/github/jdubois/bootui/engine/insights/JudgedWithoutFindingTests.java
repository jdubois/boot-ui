package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class JudgedWithoutFindingTests {

    private static final String GAP =
            "2 requests ran SQL after the handler that could not be placed against their" + " transactions.";

    @Test
    void everySentenceBuiltHereIsJudgedNotAGap() {
        for (String judged : List.of(
                JudgedWithoutFinding.heapUnderThreshold(4),
                JudgedWithoutFinding.fastTransactions(1, 50_000_000),
                JudgedWithoutFinding.fastTransactions(3, 1_500_000),
                JudgedWithoutFinding.leftToLazySql(1),
                JudgedWithoutFinding.leftToLazySql(2),
                JudgedWithoutFinding.fastTransactions(2, 50_000_000) + " " + JudgedWithoutFinding.leftToLazySql(2))) {
            assertThat(JudgedWithoutFinding.gaps(judged)).as(judged).isNull();
        }
        assertThat(JudgedWithoutFinding.gaps(null)).isNull();
    }

    @Test
    void aGapBesideAJudgedSentenceIsKept() {
        assertThat(JudgedWithoutFinding.gaps(GAP + " " + JudgedWithoutFinding.leftToLazySql(3)))
                .isEqualTo(GAP);
        assertThat(JudgedWithoutFinding.gaps(JudgedWithoutFinding.heapUnderThreshold(5) + " " + GAP))
                .isEqualTo(GAP);
        assertThat(JudgedWithoutFinding.gaps("Any other sentence.")).isEqualTo("Any other sentence.");
    }

    @Test
    void theAgentsChecksNotRunKeepEvaluatedGapsAndDropJudgedSentences() {
        List<RuntimeInsightCheckDto> checks = List.of(
                new RuntimeInsightCheckDto("ok", "Ok", "EVALUATED", 3, 0, null),
                new RuntimeInsightCheckDto(
                        "fast", "Fast", "EVALUATED", 3, 0, JudgedWithoutFinding.fastTransactions(2, 50_000_000)),
                new RuntimeInsightCheckDto(
                        "lazy", "Lazy", "EVALUATED", 3, 1, GAP + " " + JudgedWithoutFinding.leftToLazySql(1)),
                new RuntimeInsightCheckDto("none", "None", "INSUFFICIENT", 0, 0, "No eligible work."));

        assertThat(RuntimeInsightsAgentView.checksNotRun(checks))
                .containsExactly("lazy: EVALUATED, partly: " + GAP, "none: INSUFFICIENT, No eligible work.");
    }
}
