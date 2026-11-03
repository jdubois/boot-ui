package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ThreadStackGroupDto(
        String id,
        String representativeId,
        int count,
        boolean completeStacks,
        List<String> memberIds,
        boolean memberListTruncated,
        List<ThreadStateCountDto> states) {
    public ThreadStackGroupDto {
        memberIds = DtoCollections.immutableCopy(memberIds);
        states = DtoCollections.immutableCopy(states);
    }
}
