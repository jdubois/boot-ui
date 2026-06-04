package io.github.jdubois.bootui.core.dto;

import java.time.Instant;
import java.util.List;

/**
 * Stable browser-facing representation of one live HTTP session.
 */
public record HttpSessionDto(
        String sessionKey,
        String id,
        boolean idMasked,
        boolean current,
        Instant creationTime,
        Instant lastAccessedTime,
        Long idleSeconds,
        int maxInactiveIntervalSeconds,
        int attributeCount,
        List<HttpSessionAttributeDto> attributes) {}
