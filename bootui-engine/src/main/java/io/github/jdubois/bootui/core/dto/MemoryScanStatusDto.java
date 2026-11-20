package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one local Memory Advisor analysis run.
 */
public record MemoryScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int rulesEvaluated,
        int violationsFound,
        boolean includeHistogram) {

    public MemoryScanStatusDto(
            String analyzer, String status, String message, Long scannedAt, int rulesEvaluated, int violationsFound) {
        this(analyzer, status, message, scannedAt, rulesEvaluated, violationsFound, true);
    }
}
