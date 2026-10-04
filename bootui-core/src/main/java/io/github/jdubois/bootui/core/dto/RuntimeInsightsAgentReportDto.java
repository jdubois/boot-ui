package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Runtime Insights report compacted for an agent ({@code docs/PLAN-v2.md} §5.6): coverage first, then the checks
 * that could not fully run, then at most {@code limit} observations. An empty list never means healthy: read {@code
 * checksNotRun} and {@code limitations} before concluding anything.
 *
 * @param available whether the runtime journal records, so the report could be projected
 * @param unavailableReason why it could not, or {@code null}
 * @param query the filter applied: empty, {@code new}, {@code security}, {@code diff}, {@code latency}, an observation
 *     kind, or a route, table, bean, or class
 * @param requests the completed HTTP exchanges the report read. Zero is not proof nothing ran: scheduled runs,
 *     consumed messages, and evicted traffic are not counted. An empty observation list means not exercised only when
 *     {@code limitations} say so
 * @param coverage how each source's events are linked to their request or execution
 * @param checksNotRun the checks that did not apply or ran partially, each with its reason
 * @param observations the observations that matched, every kind's most affected first, then the rest
 * @param omitted the matching observations beyond {@code limit}
 * @param notExercised declared routes no request of this run reached, at most {@value #MAX_NOT_EXERCISED}
 * @param notExercisedOmitted the declared routes not reached beyond those listed
 * @param limitations what the report cannot see
 * @param next the calls that answer the obvious follow-up questions, at most three
 */
public record RuntimeInsightsAgentReportDto(
        boolean available,
        String unavailableReason,
        String query,
        long requests,
        List<RuntimeInsightCoverageDto> coverage,
        List<String> checksNotRun,
        List<RuntimeInsightAgentDto> observations,
        int omitted,
        List<String> notExercised,
        int notExercisedOmitted,
        List<String> limitations,
        List<RuntimeNextStepDto> next) {

    /** The observations listed when no limit is asked for. */
    public static final int DEFAULT_LIMIT = 8;

    /** The routes not exercised that one answer lists at most. */
    public static final int MAX_NOT_EXERCISED = 8;

    public RuntimeInsightsAgentReportDto {
        coverage = DtoCollections.immutableCopy(coverage);
        checksNotRun = DtoCollections.immutableCopy(checksNotRun);
        observations = DtoCollections.immutableCopy(observations);
        notExercised = DtoCollections.immutableCopy(notExercised);
        limitations = DtoCollections.immutableCopy(limitations);
        next = DtoCollections.immutableCopy(next);
    }

    public RuntimeInsightsAgentReportDto(
            boolean available,
            String unavailableReason,
            String query,
            long requests,
            List<RuntimeInsightCoverageDto> coverage,
            List<String> checksNotRun,
            List<RuntimeInsightAgentDto> observations,
            int omitted,
            List<String> notExercised,
            int notExercisedOmitted,
            List<String> limitations) {
        this(
                available,
                unavailableReason,
                query,
                requests,
                coverage,
                checksNotRun,
                observations,
                omitted,
                notExercised,
                notExercisedOmitted,
                limitations,
                List.of());
    }
}
