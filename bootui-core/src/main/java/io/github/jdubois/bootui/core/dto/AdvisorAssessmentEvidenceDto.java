package io.github.jdubois.bootui.core.dto;

/**
 * Evidence available to an advisor assessment, independently of findings and scan execution status.
 *
 * @param usable whether an applicable evaluation reached an evidence-backed outcome
 * @param incomplete whether required evidence was unavailable, failed, or truncated; intentional
 *     inapplicability alone does not make an assessment incomplete
 */
public record AdvisorAssessmentEvidenceDto(boolean usable, boolean incomplete) {

    /** Legacy reports cannot establish successful evaluation from their attempted-rule counters. */
    public static AdvisorAssessmentEvidenceDto unknown() {
        return new AdvisorAssessmentEvidenceDto(false, true);
    }
}
