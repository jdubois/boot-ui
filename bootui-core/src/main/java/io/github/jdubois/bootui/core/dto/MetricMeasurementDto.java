package io.github.jdubois.bootui.core.dto;

/**
 * One measured statistic for a Micrometer meter.
 */
public record MetricMeasurementDto(String statistic, double value) {}
