package io.github.jdubois.bootui.core.dto;

/**
 * A concrete tag attached to a Micrometer meter sample.
 */
public record MetricTagDto(String key, String value) {}
