package io.github.jdubois.bootui.core.dto;

/**
 * Paging metadata for list-style reports.
 */
public record PageMetadata(int total, int matched, int offset, int limit, int returned, boolean hasMore) {}
