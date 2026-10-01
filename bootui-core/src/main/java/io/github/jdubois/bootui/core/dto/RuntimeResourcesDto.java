package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The run's resource track and CPU ledger ({@code docs/PLAN-v2.md} §5.11), a detail read of Live Activity: one point
 * per sweep of the JVM, oldest first, and the run's totals.
 *
 * @param available whether the sampler runs: the journal is enabled and records the {@code resources} source
 * @param unavailableReason why it does not, or {@code null}
 * @param families the thread family names, in the order of each point's {@code familyCpuNanos}
 * @param points the most recent sweeps, oldest first
 * @param totals the run's totals, including the sweeps the track no longer keeps
 */
public record RuntimeResourcesDto(
        boolean available,
        String unavailableReason,
        List<String> families,
        List<RuntimeResourcePointDto> points,
        RuntimeResourceTotalsDto totals) {

    public RuntimeResourcesDto {
        families = DtoCollections.immutableCopy(families);
        points = DtoCollections.immutableCopy(points);
    }
}
