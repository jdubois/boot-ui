package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Side Effects panel's summary ({@code docs/PLAN-v2.md} §5.16, M5-5a): every sensor with its tab, coverage, and
 * counts for this run. Needs the BootUI agent; without it the report is unavailable with the Java Agent panel's reason.
 *
 * @param available whether the agent records side effects for this run
 * @param unavailableReason why not, or {@code null}
 * @param sensors every Side Effects sensor, in tab order, those this version does not ship included
 * @param limitations what the rows cannot see
 */
public record SideEffectsReport(
        boolean available, String unavailableReason, List<SideEffectsSensorDto> sensors, List<String> limitations) {

    public SideEffectsReport {
        sensors = DtoCollections.immutableCopy(sensors);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
