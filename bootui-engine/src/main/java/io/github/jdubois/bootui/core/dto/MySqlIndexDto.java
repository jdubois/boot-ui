package io.github.jdubois.bootui.core.dto;

/** Handler operations, not query counts or disk reads. Null indexName is MySQL's no-index/insert bucket. */
public record MySqlIndexDto(
        String schemaName,
        String tableName,
        String indexName,
        String readOperations,
        String writeOperations,
        String fetchOperations,
        String insertOperations,
        String updateOperations,
        String deleteOperations,
        Double totalTimeMs) {}
