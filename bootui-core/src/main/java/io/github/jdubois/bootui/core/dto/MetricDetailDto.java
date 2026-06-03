package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Detail view for one Micrometer meter name, including current values.
 */
public record MetricDetailDto(
        boolean metricsAvailable,
        String name,
        String description,
        String baseUnit,
        String type,
        List<MetricMeasurementDto> measurements,
        List<MetricAvailableTagDto> availableTags,
        List<MetricSampleDto> samples) {}
