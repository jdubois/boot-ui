package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AdvisorAssessmentEvidenceTests {

    @Test
    void completedOutcomesSurviveUnrelatedMissingEvidence() {
        for (String outcome : List.of("PASS", "VIOLATION")) {
            var evidence = AdvisorAssessmentEvidence.fromResults(
                    List.of(outcome, "SKIPPED", "ERROR"), Function.identity(), false);
            assertThat(evidence.usable()).isTrue();
            assertThat(evidence.incomplete()).isTrue();
        }
    }

    @Test
    void emptySkippedAndFailedEvaluationsDoNotEstablishUsableEvidence() {
        for (List<String> outcomes : List.of(List.<String>of(), List.of("SKIPPED"), List.of("ERROR"))) {
            var evidence = AdvisorAssessmentEvidence.fromResults(outcomes, Function.identity(), false);
            assertThat(evidence.usable()).isFalse();
            assertThat(evidence.incomplete()).isEqualTo(outcomes.contains("ERROR"));
        }
    }

    @Test
    void intentionalNonApplicabilityDoesNotMakeCompletedChecksIncomplete() {
        var evidence = AdvisorAssessmentEvidence.fromResults(List.of("PASS", "SKIPPED"), Function.identity(), false);
        assertThat(evidence.usable()).isTrue();
        assertThat(evidence.incomplete()).isFalse();
        assertThat(AdvisorAssessmentEvidence.fromResults(List.of("PASS"), Function.identity(), true)
                        .incomplete())
                .isTrue();
    }
}
