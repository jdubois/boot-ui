package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationEvaluationTests {

    @Test
    void noRequestsOrOtherWorkDoesNotEstablishEligibility() {
        Observation.Evaluation evaluation =
                new Observation.Evaluation(0, List.of(), "Recorded work could not be placed.");

        assertThat(evaluation.hasEligibleWork()).isFalse();
        assertThat(evaluation.uncounted()).isEqualTo("Recorded work could not be placed.");
    }

    @Test
    void eligibleNonRequestWorkDoesNotInventARequestCount() {
        Observation.Evaluation evaluation = new Observation.Evaluation(0, List.of(), null, true);

        assertThat(evaluation.hasEligibleWork()).isTrue();
        assertThat(evaluation.eligibleRequests()).isZero();
    }

    @Test
    void positiveRequestsAndSufficientFindingsCannotBeReportedAsNothingEligible() {
        assertThat(new Observation.Evaluation(3, List.of(), null, false).hasEligibleWork())
                .isTrue();
        Finding finding = new Finding(
                "heap",
                "Heap",
                true,
                "Occupancy rose after collections.",
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThat(new Observation.Evaluation(0, List.of(finding), null, false).hasEligibleWork())
                .isTrue();
        assertThat(new Observation.Evaluation(0, List.of(finding)).hasEligibleWork())
                .isTrue();
    }
}
