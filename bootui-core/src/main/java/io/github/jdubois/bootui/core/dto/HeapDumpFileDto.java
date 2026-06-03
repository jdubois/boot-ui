package io.github.jdubois.bootui.core.dto;

/**
 * One captured heap dump file on local disk.
 */
public record HeapDumpFileDto(String name, long sizeBytes, long createdAtEpochMs, boolean live) {}
