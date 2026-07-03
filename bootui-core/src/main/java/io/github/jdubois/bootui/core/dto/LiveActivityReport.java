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
 * @param pageInfo pagination metadata when served from the durable activity store, or {@code null} for
 *     the default live in-memory re-merge (existing behavior, unchanged)
 * @param persistenceOption the persistence option's current state (see {@link ActivityPersistenceOptionDto}),
 *     or {@code null} for callers that have not been updated to populate it (existing behavior, unchanged)
 */
public record LiveActivityReport(
        boolean available,
        List<ActivityEntryDto> entries,
        Map<String, Integer> typeCounts,
        ActivityKpiDto kpis,
        List<String> sources,
        List<String> warnings,
        ActivityPageInfo pageInfo,
        ActivityPersistenceOptionDto persistenceOption) {

    public LiveActivityReport {
        entries = entries == null ? List.of() : List.copyOf(entries);
        typeCounts = typeCounts == null ? Map.of() : Map.copyOf(typeCounts);
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Convenience constructor for callers that have a page info but no persistence option yet. */
    public LiveActivityReport(
            boolean available,
            List<ActivityEntryDto> entries,
            Map<String, Integer> typeCounts,
            ActivityKpiDto kpis,
            List<String> sources,
            List<String> warnings,
            ActivityPageInfo pageInfo) {
        this(available, entries, typeCounts, kpis, sources, warnings, pageInfo, null);
    }

    /** Convenience constructor for the default live in-memory re-merge path, which has no page info. */
    public LiveActivityReport(
            boolean available,
            List<ActivityEntryDto> entries,
            Map<String, Integer> typeCounts,
            ActivityKpiDto kpis,
            List<String> sources,
            List<String> warnings) {
        this(available, entries, typeCounts, kpis, sources, warnings, null, null);
    }
}
