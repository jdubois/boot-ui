package io.github.jdubois.bootui.engine.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityEvaluationTests {
    @Test
    void confirmedEmptyAndInapplicablePassDoNotCreateUsableEvidence() {
        SecurityEvaluation evaluation = new SecurityEvaluation();
        assertThat(evaluation.evidence(List.of())).isEqualTo(new AdvisorEvidenceDto(false, true, List.of()));
        evaluation.begin();
        assertThat(evaluation.applies(false)).isFalse();
        assertThat(evaluation.hasApplicableTargets()).isFalse();
        evaluation.finish(result("PASS", 0));
        assertThat(evaluation.evidence(List.of())).isEqualTo(new AdvisorEvidenceDto(false, true, List.of()));
    }

    @Test
    void applicablePassRequiresAllConsumedObservations() {
        SecurityEvaluation evaluation = new SecurityEvaluation();
        evaluation.begin();
        assertThat(evaluation.applies(true)).isTrue();
        evaluation.applies(false);
        assertThat(evaluation.hasApplicableTargets()).isTrue();
        assertThat(evaluation.required(false)).isFalse();
        assertThat(evaluation.required(true)).isTrue();
        evaluation.finish(result("PASS", 0));
        assertThat(evaluation.evidence(List.of()).usable()).isFalse();
        assertThat(evaluation.evidence(List.of()).coverageComplete()).isFalse();

        evaluation.begin();
        evaluation.applies(true);
        evaluation.required(true);
        evaluation.finish(result("PASS", 0));
        assertThat(evaluation.evidence(List.of()).usable()).isTrue();
        assertThat(evaluation.evidence(List.of()).coverageComplete()).isFalse();
        assertThat(evaluation.evidence(List.of()).limitations()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SKIPPED", "ERROR"})
    void incompleteResultsAreNotFindingsEvenWithPositiveCounts(String status) {
        SecurityEvaluation evaluation = new SecurityEvaluation();
        evaluation.begin();
        evaluation.applies(true);
        evaluation.finish(result(status, 1));
        assertThat(evaluation.evidence(List.of()).usable()).isFalse();
        assertThat(evaluation.evidence(List.of()).coverageComplete()).isFalse();
        assertThat(evaluation.evidence(List.of()).limitations()).containsExactly("SEC-TEST: Observed detail");
    }

    @Test
    void genuineInfoFindingSurvivesMissingEvidenceAndDismissal() {
        SecurityEvaluation evaluation = new SecurityEvaluation();
        evaluation.begin();
        evaluation.required(false);
        evaluation.finish(result("VIOLATION", 1).withDismissed(true));
        assertThat(evaluation.evidence(List.of()).usable()).isTrue();
        assertThat(evaluation.evidence(List.of()).coverageComplete()).isFalse();
        assertThat(evaluation.evidence(List.of()).limitations()).isNotEmpty();
    }

    @Test
    void emptyViolationCannotEstablishUsability() {
        SecurityEvaluation evaluation = new SecurityEvaluation();
        evaluation.begin();
        evaluation.applies(true);
        evaluation.finish(result("VIOLATION", 0));
        assertThat(evaluation.evidence(List.of()).usable()).isFalse();
    }

    @Test
    void collectionLimitationsDoNotEraseCompletedChecksAndResetStartsANewScan() {
        SecurityEvaluation evaluation = new SecurityEvaluation();
        evaluation.begin();
        evaluation.applies(true);
        evaluation.finish(result("PASS", 0));
        assertThat(evaluation.evidence(List.of())).isEqualTo(new AdvisorEvidenceDto(true, true, List.of()));

        AdvisorEvidenceDto previous = evaluation.evidence(List.of("Collection incomplete"));
        assertThat(previous).isEqualTo(new AdvisorEvidenceDto(true, false, List.of("Collection incomplete")));
        evaluation.begin();
        evaluation.required(false);
        evaluation.finish(result("SKIPPED", 0));
        evaluation.reset();
        assertThat(evaluation.hasApplicableTargets()).isFalse();
        assertThat(evaluation.evidence(List.of())).isEqualTo(new AdvisorEvidenceDto(false, true, List.of()));
        evaluation.finish(result("PASS", 0));
        assertThat(evaluation.evidence(List.of())).isEqualTo(new AdvisorEvidenceDto(false, true, List.of()));
        assertThat(previous).isEqualTo(new AdvisorEvidenceDto(true, false, List.of("Collection incomplete")));
    }

    private static SecurityRuleResultDto result(String status, int count) {
        return new SecurityRuleResultDto(
                "SEC-TEST",
                "Test check",
                "TEST",
                "INFO",
                "Test",
                status,
                count,
                List.of("Observed detail"),
                "Review",
                null);
    }
}
