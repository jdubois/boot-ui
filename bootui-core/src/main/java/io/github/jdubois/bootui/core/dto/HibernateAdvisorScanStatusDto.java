package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one local Hibernate Advisor analysis run.
 */
public record HibernateAdvisorScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int rulesEvaluated,
        int entitiesAnalyzed,
        int violationsFound) {}
