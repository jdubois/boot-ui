package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Browseable list of Micrometer meters.
 */
public record MetricsReport(boolean metricsAvailable, int total, List<MetricMeterDto> meters) {}
