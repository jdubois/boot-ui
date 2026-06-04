package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Migration state of one Flyway bean (one managed database / history table).
 */
public record FlywayDatabaseDto(
        String name,
        String currentVersion,
        int applied,
        int pending,
        int total,
        List<FlywayMigrationDto> migrations,
        boolean migrateEnabled,
        String migrateDisabledReason,
        boolean cleanEnabled,
        String cleanDisabledReason) {}
