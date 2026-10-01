package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Runtime Insights report ({@code docs/PLAN-v2.md} §5.5): what this run did that no single panel shows, projected on
 * read from the runtime journal. Coverage comes first, because the reliability of every observation depends on it, and
 * every check says whether it ran, so an empty list of observations never reads as healthy.
 *
 * @param available whether the journal records, so the report could be projected
 * @param unavailableReason why it could not, with the property to set, or {@code null}
 * @param window the run and events the report covers
 * @param coverage how each source's events are linked to their request or execution
 * @param checks every observation kind, with whether it could run and over how many requests
 * @param observations what the checks found, most affected requests first
 * @param limitations what the report as a whole cannot see
 */
public record RuntimeInsightsReportDto(
        boolean available,
        String unavailableReason,
        RuntimeInsightsWindowDto window,
        List<RuntimeInsightCoverageDto> coverage,
        List<RuntimeInsightCheckDto> checks,
        List<RuntimeObservationDto> observations,
        List<String> limitations) {

    public RuntimeInsightsReportDto {
        coverage = DtoCollections.immutableCopy(coverage);
        checks = DtoCollections.immutableCopy(checks);
        observations = DtoCollections.immutableCopy(observations);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
