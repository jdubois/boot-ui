package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Lexical references from captured SQL, never physical-table health or schema inventory.
 * dataSource may be null (unknown scope); status is COMPLETE, PARTIAL, or UNAVAILABLE.
 */
public record ExplorerSqlDto(String eventId, String dataSource, List<String> identifiers, String status) {
    public ExplorerSqlDto {
        identifiers = DtoCollections.immutableCopy(identifiers);
    }
}
