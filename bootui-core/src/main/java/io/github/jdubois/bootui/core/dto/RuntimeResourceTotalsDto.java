package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * The run's CPU ledger totals ({@code docs/PLAN-v2.md} §5.11), in nanoseconds, since the run started or its recording
 * was cleared.
 *
 * @param sweeps the sweeps recorded, including those the track no longer keeps
 * @param processCpuNanos the process's CPU time over the sweeps
 * @param requestCpuNanos the share credited to requests
 * @param internalCpuNanos the JVM's own work
 * @param familyCpuNanos each thread family's share outside requests, the non-zero ones only
 */
public record RuntimeResourceTotalsDto(
        long sweeps,
        long processCpuNanos,
        long requestCpuNanos,
        long internalCpuNanos,
        Map<String, Long> familyCpuNanos) {

    public RuntimeResourceTotalsDto {
        familyCpuNanos = DtoCollections.immutableCopy(familyCpuNanos);
    }
}
