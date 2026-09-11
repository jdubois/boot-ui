package io.github.jdubois.bootui.core.dto;

/** Metadata only: no keys, key hashes, values, fabricated durations, or implicit fills. */
public record ExplorerCacheDto(String eventId, String managerName, String cacheName, String operation) {}
