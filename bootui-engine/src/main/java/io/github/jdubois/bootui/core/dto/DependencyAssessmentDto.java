package io.github.jdubois.bootui.core.dto;

/** Completion of the package query and interpretation of every returned advisory. */
public record DependencyAssessmentDto(boolean queryComplete, boolean detailAssessmentComplete) {
    public DependencyAssessmentDto {
        if (detailAssessmentComplete && !queryComplete) {
            throw new IllegalArgumentException("Detail assessment requires a complete query");
        }
    }

    public static DependencyAssessmentDto unknown() {
        return new DependencyAssessmentDto(false, false);
    }
}
