package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One evidence row of an observation ({@code docs/PLAN-v2.md} §5.5), already masked.
 *
 * @param cells one value per evidence column
 */
public record RuntimeObservationRowDto(List<String> cells) {

    public RuntimeObservationRowDto {
        cells = DtoCollections.immutableCopy(cells);
    }
}
