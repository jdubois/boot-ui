package io.github.jdubois.bootui.core.dto;

public record ThreadWaitDto(
        String waiterId, String ownerId, String lockId, String kind, String identity, String explanation) {}
