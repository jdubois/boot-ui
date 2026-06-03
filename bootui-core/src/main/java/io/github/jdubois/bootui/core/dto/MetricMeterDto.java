package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Micrometer meter exposed by the application's meter registry.
 */
public record MetricMeterDto(
        String name, String description, String baseUnit, String type, List<MetricAvailableTagDto> availableTags) {}
