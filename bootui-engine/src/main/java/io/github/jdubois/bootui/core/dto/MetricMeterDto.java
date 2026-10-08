package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Micrometer meter exposed by the application's meter registry.
 *
 * <p>{@code description} and {@code baseUnit} are the registry's own metadata; {@code provenance} adds
 * BootUI's evidence-backed group, contributor and explanation for the meter family.</p>
 */
public record MetricMeterDto(
        String name,
        String description,
        String baseUnit,
        String type,
        List<MetricAvailableTagDto> availableTags,
        MetricProvenanceDto provenance) {

    public MetricMeterDto {
        availableTags = DtoCollections.immutableCopy(availableTags);
    }

    public MetricMeterDto(
            String name, String description, String baseUnit, String type, List<MetricAvailableTagDto> availableTags) {
        this(name, description, baseUnit, type, availableTags, null);
    }
}
