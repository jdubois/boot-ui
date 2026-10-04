package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Code Inventory's dependency use, a page of it.
 *
 * @param available whether the inventory sensor records this run
 * @param unavailableReason why not, or {@code null}
 * @param counts the dependency counts, or {@code null} when unavailable
 * @param dependencies a page of the jars, declared dependencies not loaded first
 * @param page paging metadata
 */
public record CodeInventoryDependenciesReport(
        boolean available,
        String unavailableReason,
        CodeInventoryDependencyCountsDto counts,
        List<CodeInventoryDependencyDto> dependencies,
        PageMetadata page) {

    public CodeInventoryDependenciesReport {
        dependencies = DtoCollections.immutableCopy(dependencies);
    }
}
