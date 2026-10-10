package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One sweep of the JVM's resources ({@code docs/PLAN-v2.md} §5.11). When its remainder is known, the requests' share,
 * each family's share, and the unclassified remainder sum to measured process CPU. CPU values are nanoseconds;
 * {@code -1} means unavailable or inconsistent consecutive readings, never measured zero.
 *
 * @param epochMillis when the sweep ran, for display only
 * @param sequence the journal's last sequence number when the sweep ran, which orders it among the events
 * @param intervalNanos the interval since the previous sweep
 * @param processCpuNanos the process's CPU time in the interval
 * @param requestCpuNanos the share credited to requests
 * @param internalCpuNanos unclassified CPU: GC, JIT, VM threads, and work without consecutive thread readings, or
 *     {@code -1} when the process delta is unknown or smaller than measured thread CPU
 * @param familyCpuNanos each thread family's share outside requests, in the order of {@code families}
 * @param unreadThreads platform threads beyond {@code bootui.resources.max-threads}
 * @param heapUsedBytes heap used
 * @param heapCommittedBytes heap committed
 * @param heapAfterGcBytes heap used after each heap pool's last collection
 * @param allocatedBytes bytes allocated by the threads read, or {@code -1} if any lacks consecutive allocation readings
 * @param liveThreads live platform threads
 * @param daemonThreads live daemon threads
 */
public record RuntimeResourcePointDto(
        long epochMillis,
        long sequence,
        long intervalNanos,
        long processCpuNanos,
        long requestCpuNanos,
        long internalCpuNanos,
        List<Long> familyCpuNanos,
        int unreadThreads,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapAfterGcBytes,
        long allocatedBytes,
        int liveThreads,
        int daemonThreads) {

    public RuntimeResourcePointDto {
        familyCpuNanos = DtoCollections.immutableCopy(familyCpuNanos);
    }
}
