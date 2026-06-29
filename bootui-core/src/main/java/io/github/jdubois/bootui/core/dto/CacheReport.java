package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level cache report.
 */
public record CacheReport(
        boolean cacheAvailable,
        boolean clearEnabled,
        int managerCount,
        int cacheCount,
        int operationCount,
        List<CacheManagerDto> managers,
        List<CacheOperationDto> operations,
        List<String> warnings) {}
