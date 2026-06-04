package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Summary of one Liquibase change set as recorded in the change-log history table.
 */
public record LiquibaseChangeSetDto(
        String id,
        String author,
        String changeLog,
        String description,
        String comments,
        String execType,
        String dateExecuted,
        Integer orderExecuted,
        String checksum,
        String tag,
        String deploymentId,
        List<String> contexts,
        List<String> labels) {}
