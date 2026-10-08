package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One provenance group of meters, aggregated over the meters matching the current filters.
 *
 * <p>{@code meterCount} counts distinct meter names, {@code describedMeterCount} how many of them carry a
 * native registry description (the group's documentation coverage), and {@code commonTagKeys}/{@code baseUnits}
 * are bounded, deterministic summaries of the group's meters. Tag <em>values</em> never appear here.</p>
 */
public record MetricGroupDto(
        String id,
        String label,
        String contributor,
        String summary,
        String interpretation,
        int meterCount,
        int describedMeterCount,
        List<String> families,
        List<String> commonTagKeys,
        List<String> baseUnits) {

    public MetricGroupDto {
        families = DtoCollections.immutableCopy(families);
        commonTagKeys = DtoCollections.immutableCopy(commonTagKeys);
        baseUnits = DtoCollections.immutableCopy(baseUnits);
    }
}
