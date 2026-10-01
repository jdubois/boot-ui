package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One observation with its evidence ({@code docs/PLAN-v2.md} §5.5): at most 20 rows, already masked, and how many were
 * left out.
 *
 * @param available whether the observation exists in the current report
 * @param unavailableReason why it does not, or {@code null}
 * @param observation the observation, or {@code null}
 * @param columns the evidence columns
 * @param rows the evidence rows, each with one cell per column
 * @param truncated the rows left out
 */
public record RuntimeObservationDetailDto(
        boolean available,
        String unavailableReason,
        RuntimeObservationDto observation,
        List<String> columns,
        List<RuntimeObservationRowDto> rows,
        long truncated) {

    public RuntimeObservationDetailDto {
        columns = DtoCollections.immutableCopy(columns);
        rows = DtoCollections.immutableCopy(rows);
    }
}
