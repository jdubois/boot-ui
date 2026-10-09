package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Browseable list of Micrometer meters.
 *
 * <p>{@code groups} summarizes the provenance groups of the meters matching the search, type, provenance
 * and explanation filters, before the group filter narrows the list, so the group list stays navigable.
 * {@code catalogueVersion} identifies the curated meter-family catalogue that produced the explanations.</p>
 */
public record MetricsReport(
        boolean metricsAvailable,
        int total,
        List<MetricMeterDto> meters,
        List<String> availableTypes,
        PageMetadata page,
        List<MetricGroupDto> groups,
        String catalogueVersion) {

    public MetricsReport {
        meters = DtoCollections.immutableCopy(meters);
        availableTypes = DtoCollections.immutableCopy(availableTypes);
        groups = DtoCollections.immutableCopy(groups);
    }

    public MetricsReport(
            boolean metricsAvailable,
            int total,
            List<MetricMeterDto> meters,
            List<String> availableTypes,
            PageMetadata page) {
        this(metricsAvailable, total, meters, availableTypes, page, List.of(), null);
    }

    public MetricsReport(boolean metricsAvailable, int total, List<MetricMeterDto> meters) {
        this(metricsAvailable, total, meters, types(meters), singlePage(total, meters), List.of(), null);
    }

    private static List<String> types(List<MetricMeterDto> meters) {
        return meters.stream()
                .map(MetricMeterDto::type)
                .filter(type -> type != null && !type.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static PageMetadata singlePage(int total, List<MetricMeterDto> meters) {
        return new PageMetadata(total, total, 0, meters.size(), meters.size(), meters.size() < total);
    }
}
