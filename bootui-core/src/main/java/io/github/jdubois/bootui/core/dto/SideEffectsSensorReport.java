package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Side Effects sensor's rows for this run ({@code docs/PLAN-v2.md} §5.16, M5-5a), most frequent first, paged.
 *
 * @param available whether the agent records side effects for this run
 * @param unavailableReason why not, or {@code null}
 * @param sensor the sensor and its coverage, or {@code null} for an unknown sensor
 * @param rows this page of its rows
 * @param page the paging metadata
 * @param limitations what the rows cannot see
 */
public record SideEffectsSensorReport(
        boolean available,
        String unavailableReason,
        SideEffectsSensorDto sensor,
        List<SideEffectsRowDto> rows,
        PageMetadata page,
        List<String> limitations) {

    public SideEffectsSensorReport {
        rows = DtoCollections.immutableCopy(rows);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
