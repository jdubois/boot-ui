package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Runtime Insights report compacted for an agent ({@code docs/PLAN-v2.md} §5.6): coverage first, then the checks
 * that could not fully run, then at most {@code limit} observations. An empty list never means healthy: read {@code
 * checksNotRun} and {@code limitations} before concluding anything.
 *
 * @param available whether the runtime journal records, so the report could be projected
 * @param unavailableReason why it could not, or {@code null}
 * @param query the filter applied: empty, {@code new}, {@code security}, {@code diff}, {@code latency}, or a route,
 *     table, bean, or class
 * @param coverage how each source's events are linked to their request or execution
 * @param checksNotRun the checks that did not apply or ran partially, each with its reason
 * @param observations the observations that matched, most affected first
 * @param omitted the matching observations beyond {@code limit}
 * @param limitations what the report cannot see
 */
public record RuntimeInsightsAgentReportDto(
        boolean available,
        String unavailableReason,
        String query,
        List<RuntimeInsightCoverageDto> coverage,
        List<String> checksNotRun,
        List<RuntimeInsightAgentDto> observations,
        int omitted,
        List<String> limitations) {

    /** The observations listed when no limit is asked for. */
    public static final int DEFAULT_LIMIT = 8;

    public RuntimeInsightsAgentReportDto {
        coverage = DtoCollections.immutableCopy(coverage);
        checksNotRun = DtoCollections.immutableCopy(checksNotRun);
        observations = DtoCollections.immutableCopy(observations);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
