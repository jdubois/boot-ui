package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadCycleDto(
        String source,
        int participantCount,
        List<String> participantIds,
        boolean participantListTruncated,
        int affectedCount,
        List<String> affectedObservationIds,
        boolean affectedListTruncated) {
    public ThreadCycleDto {
        participantIds = DtoCollections.immutableCopy(participantIds);
        affectedObservationIds = DtoCollections.immutableCopy(affectedObservationIds);
    }
}
