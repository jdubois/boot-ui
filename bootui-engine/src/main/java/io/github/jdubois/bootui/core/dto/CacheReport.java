package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level cache report.
 *
 * @param cacheAvailable whether at least one cache manager was discovered
 * @param clearEnabled whether the clear action is permitted by configuration
 * @param managerCount the number of reported cache managers
 * @param cacheCount the number of reported caches
 * @param operationCount the number of discovered declarative cache operations
 * @param tierCount the number of reported cache tiers across every reported cache
 * @param truncated whether managers, caches or tiers were dropped to keep the report bounded
 * @param managers the reported cache managers
 * @param operations the discovered declarative cache operations
 * @param warnings scan and truncation warnings
 */
public record CacheReport(
        boolean cacheAvailable,
        boolean clearEnabled,
        int managerCount,
        int cacheCount,
        int operationCount,
        int tierCount,
        boolean truncated,
        List<CacheManagerDto> managers,
        List<CacheOperationDto> operations,
        List<String> warnings) {

    public CacheReport {
        managers = DtoCollections.immutableCopy(managers);
        operations = DtoCollections.immutableCopy(operations);
        warnings = DtoCollections.immutableCopy(warnings);
    }
}
