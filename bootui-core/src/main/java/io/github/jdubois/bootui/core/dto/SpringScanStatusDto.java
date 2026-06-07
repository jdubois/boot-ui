package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one Spring Advisor analysis run.
 */
public record SpringScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int rulesEvaluated,
        int componentsAnalyzed,
        int violationsFound) {}
