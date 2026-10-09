package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one local Database Advisor analysis run.
 */
public record DatabaseAdvisorScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int rulesEvaluated,
        int tablesAnalyzed,
        int violationsFound) {}
