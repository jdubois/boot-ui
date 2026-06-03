package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level Flyway migration report.
 */
public record FlywayReport(boolean flywayPresent, int total, List<FlywayDatabaseDto> databases) {}
