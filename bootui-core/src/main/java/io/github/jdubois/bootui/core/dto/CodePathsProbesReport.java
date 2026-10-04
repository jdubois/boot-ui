package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * This run's method probes ({@code docs/PLAN-v2.md} §5.14, M5-8), newest first, with their bounds. Probes are actions,
 * blocked by read-only policy; they record metadata only, need the BootUI agent, and end with the run.
 *
 * @param available whether probes can be started in this run
 * @param unavailableReason why not, or {@code null}
 * @param maxActive how many probes may run at once
 * @param maxInvocations the most invocations a probe records
 * @param windowSeconds the longest a probe records
 * @param probes this run's probes, newest first
 * @param limitations what a probe cannot see
 */
public record CodePathsProbesReport(
        boolean available,
        String unavailableReason,
        int maxActive,
        int maxInvocations,
        long windowSeconds,
        List<CodePathsProbeDto> probes,
        List<String> limitations) {

    public CodePathsProbesReport {
        probes = DtoCollections.immutableCopy(probes);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
