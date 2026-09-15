package io.github.jdubois.bootui.core.dto;

import java.util.List;

/** One existing JDBC datasource; all counters and byte quantities use exact decimal strings. */
public record MySqlDataSourceDto(
        String name,
        String schemaName,
        String serverVersion,
        String serverFlavor,
        String account,
        String status,
        String message,
        Long readStartedAt,
        Long readAt,
        List<MySqlCapabilityDto> capabilities,
        List<MySqlSectionDto> sections,
        List<MySqlMetricDto> vitalSigns,
        List<MySqlSessionDto> sessions,
        List<MySqlLockWaitDto> lockWaits,
        List<MySqlStatementDto> statements,
        List<MySqlIndexDto> indexes,
        List<MySqlTableDto> tables,
        List<MySqlMetricDto> innodb,
        List<MySqlReplicationChannelDto> replication,
        List<MySqlSettingDto> settings,
        List<MySqlChangeDto> changes,
        boolean truncated) {

    public MySqlDataSourceDto {
        capabilities = DtoCollections.immutableCopy(capabilities);
        sections = DtoCollections.immutableCopy(sections);
        vitalSigns = DtoCollections.immutableCopy(vitalSigns);
        sessions = DtoCollections.immutableCopy(sessions);
        lockWaits = DtoCollections.immutableCopy(lockWaits);
        statements = DtoCollections.immutableCopy(statements);
        indexes = DtoCollections.immutableCopy(indexes);
        tables = DtoCollections.immutableCopy(tables);
        innodb = DtoCollections.immutableCopy(innodb);
        replication = DtoCollections.immutableCopy(replication);
        settings = DtoCollections.immutableCopy(settings);
        changes = DtoCollections.immutableCopy(changes);
    }
}
