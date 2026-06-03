package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level Liquibase migration report.
 */
public record LiquibaseReport(boolean liquibasePresent, int total, List<LiquibaseDatabaseDto> databases) {}
