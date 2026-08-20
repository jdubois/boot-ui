package io.github.jdubois.bootui.core.dto;

/**
 * Aggregate call count for one gRPC status code, joined from existing native metrics.
 *
 * @param status the gRPC status code name (for example {@code OK}, {@code NOT_FOUND}, {@code UNKNOWN})
 * @param count how many calls completed with that status
 */
public record GrpcStatusCountDto(String status, long count) {}
