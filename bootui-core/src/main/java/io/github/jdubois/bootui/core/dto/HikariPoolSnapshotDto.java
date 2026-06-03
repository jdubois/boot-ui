package io.github.jdubois.bootui.core.dto;

/**
 * Live connection counts for one database connection pool at a point in time.
 */
public record HikariPoolSnapshotDto(long timestamp, int active, int idle, int total, int pending) {}
