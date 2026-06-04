package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Result of a Liquibase action.
 */
public record LiquibaseActionResult(
        String status,
        String message,
        String beanName,
        Integer pendingBefore,
        Integer pendingAfter,
        Integer changeSetsApplied,
        List<String> warnings) {}
