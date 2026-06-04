package io.github.jdubois.bootui.core.dto;

/**
 * Summary of one Flyway migration as reported by {@code Flyway.info()}.
 */
public record FlywayMigrationDto(
        String type,
        String version,
        String description,
        String script,
        String state,
        String installedBy,
        String installedOn,
        Integer installedRank,
        Integer executionTime,
        Integer checksum) {}
