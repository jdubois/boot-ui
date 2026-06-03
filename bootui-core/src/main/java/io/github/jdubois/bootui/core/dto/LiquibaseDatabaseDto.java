package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Recorded change sets of one Liquibase bean (one managed database / change-log table).
 */
public record LiquibaseDatabaseDto(String name, int total, List<LiquibaseChangeSetDto> changeSets) {}
