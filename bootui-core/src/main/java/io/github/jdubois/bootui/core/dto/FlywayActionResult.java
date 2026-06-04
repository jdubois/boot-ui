package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Result of a Flyway action.
 */
public record FlywayActionResult(
        String status,
        String message,
        String beanName,
        Integer migrationsExecuted,
        List<String> schemasCleaned,
        List<String> schemasDropped,
        String migrationPath,
        List<String> warnings) {}
