package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one local Spring Security Advisor analysis run.
 */
public record SecurityScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int rulesEvaluated,
        int filterChainsAnalyzed,
        int violationsFound) {}
