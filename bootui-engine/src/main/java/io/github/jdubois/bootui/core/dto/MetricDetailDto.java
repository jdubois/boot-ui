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
        List<MetricSampleDto> samples,
        int totalSamples,
        PageMetadata samplePage,
        boolean samplesTruncated,
        MetricProvenanceDto provenance) {

    public MetricDetailDto {
        measurements = DtoCollections.immutableCopy(measurements);
        availableTags = DtoCollections.immutableCopy(availableTags);
        samples = DtoCollections.immutableCopy(samples);
    }

    public MetricDetailDto(
            boolean metricsAvailable,
            String name,
            String description,
            String baseUnit,
            String type,
            List<MetricMeasurementDto> measurements,
            List<MetricAvailableTagDto> availableTags,
            List<MetricSampleDto> samples,
            int totalSamples,
            PageMetadata samplePage,
            boolean samplesTruncated) {
        this(
                metricsAvailable,
                name,
                description,
                baseUnit,
                type,
                measurements,
                availableTags,
                samples,
                totalSamples,
                samplePage,
                samplesTruncated,
                null);
    }

    public MetricDetailDto(
            boolean metricsAvailable,
            String name,
            String description,
            String baseUnit,
            String type,
            List<MetricMeasurementDto> measurements,
            List<MetricAvailableTagDto> availableTags,
            List<MetricSampleDto> samples) {
        this(
                metricsAvailable,
                name,
                description,
                baseUnit,
                type,
                measurements,
                availableTags,
                samples,
                samples.size(),
                new PageMetadata(samples.size(), samples.size(), 0, samples.size(), samples.size(), false),
                false);
    }
}
