package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Completion of the package query and interpretation of every returned advisory.
 *
 * <p>{@code unassessedAdvisoryIds} is a bounded preview of query-returned IDs whose details were not obtained,
 * not interpreted findings. {@code unassessedAdvisoryCount} counts all distinct such IDs for this dependency,
 * including IDs omitted from the preview. Neither supplies severity, a vulnerability count, or usability.
 */
public record DependencyAssessmentDto(
        boolean queryComplete,
        boolean detailAssessmentComplete,
        List<String> unassessedAdvisoryIds,
        Integer unassessedAdvisoryCount) {
    public DependencyAssessmentDto {
        unassessedAdvisoryIds = DtoCollections.immutableCopy(unassessedAdvisoryIds);
        unassessedAdvisoryCount = unassessedAdvisoryCount == null ? 0 : unassessedAdvisoryCount;
        if (detailAssessmentComplete && !queryComplete) {
            throw new IllegalArgumentException("Detail assessment requires a complete query");
        }
        if (unassessedAdvisoryCount < unassessedAdvisoryIds.size()
                || detailAssessmentComplete && (unassessedAdvisoryCount > 0 || !unassessedAdvisoryIds.isEmpty())) {
            throw new IllegalArgumentException("Unassessed advisory candidates require incomplete detail assessment");
        }
    }

    public DependencyAssessmentDto(boolean queryComplete, boolean detailAssessmentComplete) {
        this(queryComplete, detailAssessmentComplete, List.of(), 0);
    }

    public static DependencyAssessmentDto unknown() {
        return new DependencyAssessmentDto(false, false);
    }
}
