package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** Policy-safe thread evidence belonging to a single immutable capture. */
public record CapturedThreadDto(
        String observationId,
        ThreadInfoDto thread,
        String family,
        String activity,
        boolean stackTruncated,
        boolean ownershipComplete,
        List<ThreadLockDto> ownedLocks) {

    public CapturedThreadDto {
        ownedLocks = DtoCollections.immutableCopy(ownedLocks);
    }
}
