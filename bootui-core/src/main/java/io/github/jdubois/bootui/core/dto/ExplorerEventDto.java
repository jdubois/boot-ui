package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** Selected canonical evidence, including standalone/history entries with no retained trace detail. */
public record ExplorerEventDto(
        boolean found,
        ActivityEntryDto event,
        List<ActivityEntryDto> related,
        List<ExplorerInvocationDto> invocations,
        List<ExplorerLinkDto> links,
        List<ExplorerSqlDto> sqlReferences,
        List<ExplorerCacheDto> cacheOperations,
        List<String> warnings,
        boolean partial,
        int omittedInvocations) {
    public ExplorerEventDto {
        related = DtoCollections.immutableCopy(related);
        invocations = DtoCollections.immutableCopy(invocations);
        links = DtoCollections.immutableCopy(links);
        sqlReferences = DtoCollections.immutableCopy(sqlReferences);
        cacheOperations = DtoCollections.immutableCopy(cacheOperations);
        warnings = DtoCollections.immutableCopy(warnings);
    }
}
