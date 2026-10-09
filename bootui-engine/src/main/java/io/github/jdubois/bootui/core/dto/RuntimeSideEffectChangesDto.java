package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * What changed outside the JVM between two runs, in a run comparison ({@code docs/PLAN-v2.md} §5.8, §5.16, M5-7b): the
 * hosts, file patterns, processes, and variable names the BootUI agent's Side Effects sensors saw a route, an
 * execution, or startup use in one run and not the other. A sensor is compared only when it recorded the whole of both
 * runs; names and masked patterns only, never a value.
 *
 * @param available whether side effects can be compared for this comparison
 * @param unavailableReason why not, or {@code null}
 * @param partial whether a sensor kept only part of its keys, so some rows are withheld
 * @param sensors each compared sensor, with whether it was compared and its counts
 * @param changes the keys new, gone, or whose owner was not exercised in this run, new first, at most {@link
 *     RuntimeRunComparisonDto#MAX_ROWS}
 * @param changesTotal how many there are
 * @param limitations what the comparison cannot see
 */
public record RuntimeSideEffectChangesDto(
        boolean available,
        String unavailableReason,
        boolean partial,
        List<RuntimeSideEffectSensorDto> sensors,
        List<RuntimeSideEffectChangeDto> changes,
        int changesTotal,
        List<String> limitations) {

    public RuntimeSideEffectChangesDto {
        sensors = DtoCollections.immutableCopy(sensors);
        changes = DtoCollections.immutableCopy(changes);
        limitations = DtoCollections.immutableCopy(limitations);
    }

    /** Side effects are not compared, for {@code reason}. */
    public static RuntimeSideEffectChangesDto unavailable(String reason) {
        return new RuntimeSideEffectChangesDto(false, reason, false, List.of(), List.of(), 0, List.of());
    }
}
