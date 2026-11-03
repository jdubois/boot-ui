package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadBlockerDto(
        String observationId,
        long threadId,
        String name,
        int directBlocked,
        int transitiveBlocked,
        int maxDepth,
        String representativeFrame,
        boolean partial,
        List<String> blockedObservationIds,
        boolean blockedListTruncated) {
    public ThreadBlockerDto {
        blockedObservationIds = DtoCollections.immutableCopy(blockedObservationIds);
    }
}
