package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The advisor findings dismissed one by one, as stored in {@code .bootui/boot-ui.yml}.
 *
 * @param ignored stored entries that could not be read (malformed, oversized, or from a newer format); they are kept
 *     in the file untouched and never counted as dismissed findings
 */
public record DismissedFindingsDto(List<DismissedFindingDto> dismissed, int ignored) {

    public DismissedFindingsDto {
        dismissed = DtoCollections.immutableCopy(dismissed);
    }
}
