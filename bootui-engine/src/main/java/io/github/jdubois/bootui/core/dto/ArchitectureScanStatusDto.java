package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one local ArchUnit architecture analysis run.
 */
public record ArchitectureScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int rulesEvaluated,
        int classesAnalyzed,
        int violationsFound) {}
