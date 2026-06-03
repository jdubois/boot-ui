package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about one local GraalVM native-image readiness analysis run.
 */
public record GraalVmScanStatusDto(
        String analyzer,
        String status,
        String message,
        Long scannedAt,
        int checksRun,
        int classesAnalyzed,
        int findingsFound) {}
