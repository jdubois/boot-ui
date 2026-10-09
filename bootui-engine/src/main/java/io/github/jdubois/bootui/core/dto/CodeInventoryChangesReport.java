package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The methods changed or added since the previous run, a page of them.
 *
 * @param available whether the inventory sensor records this run
 * @param unavailableReason why not, or {@code null}
 * @param counts the change counts, or {@code null} when unavailable
 * @param changes the changed and added methods
 * @param page paging metadata
 */
public record CodeInventoryChangesReport(
        boolean available,
        String unavailableReason,
        CodeInventoryChangeCountsDto counts,
        List<CodeInventoryMethodDto> changes,
        PageMetadata page) {

    public CodeInventoryChangesReport {
        changes = DtoCollections.immutableCopy(changes);
    }
}
