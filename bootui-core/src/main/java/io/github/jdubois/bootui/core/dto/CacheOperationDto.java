package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One cache annotation operation discovered on an application bean method.
 */
public record CacheOperationDto(
        String beanName,
        String targetType,
        String method,
        String operation,
        List<String> caches,
        String key,
        String condition,
        String unless,
        boolean allEntries,
        boolean beforeInvocation) {}
