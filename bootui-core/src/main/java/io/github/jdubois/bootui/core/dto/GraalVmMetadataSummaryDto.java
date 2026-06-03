package io.github.jdubois.bootui.core.dto;

/**
 * Summary of the {@code reachability-metadata.json} scaffold that the panel can generate from the
 * last scan.
 */
public record GraalVmMetadataSummaryDto(int reflectionEntries, int serializationEntries, int resourceEntries) {}
