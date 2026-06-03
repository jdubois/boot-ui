package io.github.jdubois.bootui.core.dto;

/**
 * One cache known to a Spring {@code CacheManager}.
 */
public record CacheDto(String managerName, String name, String nativeType, Long size, CacheMetricsDto metrics) {}
