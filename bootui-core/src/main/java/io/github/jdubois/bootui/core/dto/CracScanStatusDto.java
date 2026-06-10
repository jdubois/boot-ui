package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one local CRaC checkpoint/restore readiness analysis run.
 */
public record CracScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int checksRun,
        int classesAnalyzed,
        int findingsFound) {}
