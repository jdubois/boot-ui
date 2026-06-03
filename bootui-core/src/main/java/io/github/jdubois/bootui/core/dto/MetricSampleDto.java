package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One concrete tagged Micrometer meter sample.
 */
public record MetricSampleDto(List<MetricTagDto> tags, List<MetricMeasurementDto> measurements) {}
