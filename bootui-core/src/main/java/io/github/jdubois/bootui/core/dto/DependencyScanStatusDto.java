package io.github.jdubois.bootui.core.dto;

/**
 * Metadata about the dependency vulnerability scan.
 *
 * @param packagesScanned how many distinct packages were actually submitted to the scanner and completed
 * @param packagesSkipped how many inventory packages were dropped before the scan because the configured
 *     {@code bootui.vulnerabilities.max-packages} bound was reached; a non-zero value means
 *     {@code packagesScanned} is not full coverage of the inventory
 */
public record DependencyScanStatusDto(
        String scanner,
        String status,
        String message,
        Long scannedAt,
        int packagesScanned,
        int packagesSkipped,
        int vulnerabilitiesFound) {}
