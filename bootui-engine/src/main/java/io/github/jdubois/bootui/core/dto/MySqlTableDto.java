package io.github.jdubois.bootui.core.dto;

/** Catalog row/storage estimates retain ordinary MySQL statistics freshness. No application rows are read. */
public record MySqlTableDto(
        String schemaName,
        String tableName,
        String engine,
        String estimatedRows,
        String dataBytes,
        String indexBytes,
        String readOperations,
        String writeOperations,
        Double totalTimeMs) {}
