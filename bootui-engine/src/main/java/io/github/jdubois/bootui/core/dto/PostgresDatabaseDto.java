package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One PostgreSQL datasource the panel read, and everything it managed to read from it.
 *
 * @param name the adapter-reported datasource name
 * @param status {@code READ}, {@code PARTIAL} or {@code ERROR}
 * @param message the failure or partial-read reason, already redacted and truncated; {@code null} when clean
 * @param role the role the read connected as, and whether it can read the statistics of other backends
 */
public record PostgresDatabaseDto(
        String name,
        String databaseName,
        String serverVersion,
        int serverMajorVersion,
        String role,
        boolean monitoringRole,
        String status,
        String message,
        PostgresVitalSignsDto vitalSigns,
        List<PostgresSectionDto> sections,
        List<PostgresSessionDto> sessions,
        List<PostgresStatementDto> statements,
        List<PostgresIndexDto> indexes,
        List<PostgresTableDto> tables,
        List<PostgresVacuumDto> vacuum,
        PostgresReplicationDto replication,
        List<PostgresSettingDto> settings,
        List<PostgresChangeDto> changes,
        boolean truncated) {

    public PostgresDatabaseDto {
        sections = DtoCollections.immutableCopy(sections);
        sessions = DtoCollections.immutableCopy(sessions);
        statements = DtoCollections.immutableCopy(statements);
        indexes = DtoCollections.immutableCopy(indexes);
        tables = DtoCollections.immutableCopy(tables);
        vacuum = DtoCollections.immutableCopy(vacuum);
        settings = DtoCollections.immutableCopy(settings);
        changes = DtoCollections.immutableCopy(changes);
    }
}
