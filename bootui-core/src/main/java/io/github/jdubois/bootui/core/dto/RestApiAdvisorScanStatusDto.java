package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one local REST API Advisor analysis run.
 */
public record RestApiAdvisorScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int rulesEvaluated,
        int controllersAnalyzed,
        int handlersAnalyzed,
        int violationsFound) {}
