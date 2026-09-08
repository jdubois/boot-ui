package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorAssessmentEvidenceDto;
import java.util.Collection;
import java.util.function.Function;

/** Captures actual rule outcomes before report builders discard passing and skipped results. */
public final class AdvisorAssessmentEvidence {

    private AdvisorAssessmentEvidence() {}

    public static <T> AdvisorAssessmentEvidenceDto fromResults(
            Collection<T> results, Function<T, String> status, boolean incomplete) {
        boolean usable =
                results.stream().map(status).anyMatch(value -> "PASS".equals(value) || "VIOLATION".equals(value));
        boolean failed = results.stream().map(status).anyMatch("ERROR"::equals);
        return new AdvisorAssessmentEvidenceDto(usable, incomplete || failed);
    }
}
