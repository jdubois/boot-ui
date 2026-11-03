package io.github.jdubois.bootui.engine.threads;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.CapturedThreadDto;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sanitized retained payload; never holds JVM ThreadInfo, Thread, or application objects. */
record ThreadSnapshot(
        ThreadDumpReport report,
        List<CapturedThreadDto> threads,
        Map<Long, CpuReading> cpu,
        Map<Long, String> waitingLocks,
        long completedAt,
        long durationNanos,
        long retainedBytes,
        Set<Long> bootUiThreadIds) {
    ThreadSnapshot {
        threads = List.copyOf(threads);
        cpu = Map.copyOf(cpu);
        waitingLocks = Map.copyOf(waitingLocks);
        bootUiThreadIds = Set.copyOf(bootUiThreadIds);
    }

    ThreadSnapshot(
            ThreadDumpReport report,
            List<CapturedThreadDto> threads,
            Map<Long, CpuReading> cpu,
            Map<Long, String> waitingLocks,
            long completedAt,
            long durationNanos,
            long retainedBytes) {
        this(report, threads, cpu, waitingLocks, completedAt, durationNanos, retainedBytes, Set.of());
    }

    ThreadSnapshot(
            ThreadDumpReport report,
            List<CapturedThreadDto> threads,
            Map<Long, CpuReading> cpu,
            long completedAt,
            long durationNanos,
            long retainedBytes) {
        this(report, threads, cpu, Map.of(), completedAt, durationNanos, retainedBytes);
    }

    record CpuReading(long cpuNanos, long userNanos, long readAtNanos) {}

    static boolean hasComparableStack(CapturedThreadDto row) {
        return !row.thread().stackTrace().isEmpty()
                && !row.thread().stackTrace().contains(SecretMasker.MASKED_VALUE);
    }
}
