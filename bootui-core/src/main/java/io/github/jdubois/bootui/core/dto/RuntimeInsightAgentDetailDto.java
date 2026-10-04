package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Runtime Insights observation for an agent, with its evidence ({@code docs/PLAN-v2.md} §5.6). Drill down with
 * {@code get_request_profile} on its exemplar request.
 *
 * @param available whether the observation is in this run's retained events
 * @param unavailableReason why it is not, or {@code null}
 * @param observation the compact observation, or {@code null}
 * @param whatToCheck every conditional check, the first of which is {@code verify}
 * @param limitations what it cannot see
 * @param columns the evidence columns
 * @param rows at most 20 evidence rows
 * @param truncated the rows left out
 * @param next the calls that follow this observation up, or that find a current id when it is unavailable, at most
 *     three
 */
public record RuntimeInsightAgentDetailDto(
        boolean available,
        String unavailableReason,
        RuntimeInsightAgentDto observation,
        List<String> whatToCheck,
        List<String> limitations,
        List<String> columns,
        List<RuntimeObservationRowDto> rows,
        long truncated,
        List<RuntimeNextStepDto> next) {

    public RuntimeInsightAgentDetailDto {
        whatToCheck = DtoCollections.immutableCopy(whatToCheck);
        limitations = DtoCollections.immutableCopy(limitations);
        columns = DtoCollections.immutableCopy(columns);
        rows = DtoCollections.immutableCopy(rows);
        next = DtoCollections.immutableCopy(next);
    }

    public RuntimeInsightAgentDetailDto(
            boolean available,
            String unavailableReason,
            RuntimeInsightAgentDto observation,
            List<String> whatToCheck,
            List<String> limitations,
            List<String> columns,
            List<RuntimeObservationRowDto> rows,
            long truncated) {
        this(available, unavailableReason, observation, whatToCheck, limitations, columns, rows, truncated, List.of());
    }
}
