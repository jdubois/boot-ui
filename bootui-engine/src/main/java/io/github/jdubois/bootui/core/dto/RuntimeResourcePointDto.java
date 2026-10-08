package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One sweep of the JVM's resources ({@code docs/PLAN-v2.md} §5.11). Its CPU parts sum to the process's CPU time in
 * the interval: the requests' share, each family's share, and the JVM's own work. CPU values are nanoseconds; a value
 * of {@code -1} means the JVM did not report it.
 *
 * @param epochMillis when the sweep ran, for display only
 * @param sequence the journal's last sequence number when the sweep ran, which orders it among the events
 * @param intervalNanos the interval since the previous sweep
 * @param processCpuNanos the process's CPU time in the interval
 * @param requestCpuNanos the share credited to requests
 * @param internalCpuNanos the JVM's own work: GC, JIT, VM threads, and threads that ended or were not read
 * @param familyCpuNanos each thread family's share outside requests, in the order of {@code families}
 * @param unreadThreads platform threads beyond {@code bootui.resources.max-threads}
 * @param heapUsedBytes heap used
 * @param heapCommittedBytes heap committed
 * @param heapAfterGcBytes heap used after each heap pool's last collection
 * @param allocatedBytes bytes allocated in the interval by the threads read
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
