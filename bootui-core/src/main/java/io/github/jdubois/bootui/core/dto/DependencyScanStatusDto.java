package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about the dependency vulnerability scan.
 */
public record DependencyScanStatusDto(
        String scanner, String status, String message, Long scannedAt, int packagesScanned, int vulnerabilitiesFound) {}
