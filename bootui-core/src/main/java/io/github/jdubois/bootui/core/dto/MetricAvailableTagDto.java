package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Available values for one Micrometer meter tag key.
 */
public record MetricAvailableTagDto(String key, List<String> values, boolean truncated) {}
