package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** A cached, sanitized observation, never an atomic snapshot or a database health verdict. */
public record MySqlInsightReport(
        boolean localOnly,
        String disclaimer,
        String status,
        String message,
        Long readStartedAt,
        Long readAt,
        int dataSourcesRead,
        List<MySqlDataSourceDto> dataSources,
        List<MySqlDiagnosticDto> diagnostics,
        List<String> limitations,
        boolean truncated) {

    public MySqlInsightReport {
        dataSources = DtoCollections.immutableCopy(dataSources);
        diagnostics = DtoCollections.immutableCopy(diagnostics);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
