package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Map;

/**
 * Top-level Live Activity payload: the merged stream plus a KPI summary.
 *
 * @param available whether at least one backing signal source is present
 * @param entries the merged, reverse-chronological activity entries (already capped)
 * @param typeCounts per-type entry counts over the returned window
 * @param kpis at-a-glance key indicators
 * @param sources labels of the activity sources that contributed data
 * @param warnings non-fatal notes (for example, sources that were unavailable)
 */
public record LiveActivityReport(
        boolean available,
        List<ActivityEntryDto> entries,
        Map<String, Integer> typeCounts,
        ActivityKpiDto kpis,
        List<String> sources,
        List<String> warnings) {

    public LiveActivityReport {
        entries = entries == null ? List.of() : List.copyOf(entries);
        typeCounts = typeCounts == null ? Map.of() : Map.copyOf(typeCounts);
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
