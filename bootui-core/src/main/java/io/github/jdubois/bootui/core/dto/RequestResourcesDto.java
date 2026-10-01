package io.github.jdubois.bootui.core.dto;

/**
 * The CPU time, allocated bytes, and GC pauses one request's segments measured, summed over every thread its work ran
 * on ({@code docs/PLAN-v2.md} §5.11).
 *
 * @param availability {@code AVAILABLE} when every segment was measured, {@code PARTIAL} when some were, so CPU time
 *     and allocated bytes are a floor, and {@code UNAVAILABLE} when none was
 * @param unmeasuredReason why some segments were not measured: {@code VIRTUAL_THREAD}, {@code THREAD_ENDED}, or
 *     {@code UNSUPPORTED}; {@code null} when every one was
 * @param cpuNanos CPU time of the measured segments
 * @param allocatedBytes bytes allocated by the measured segments
 * @param segments the segments the request ran
 * @param unmeasuredSegments those the JVM did not measure
 * @param gcPauses collections of pause collectors that completed during the request
 * @param gcPausesTruncated whether more collections completed than the request kept, so {@code gcPauses} is a floor
 */
public record RequestResourcesDto(
        String availability,
        String unmeasuredReason,
        long cpuNanos,
        long allocatedBytes,
        int segments,
        int unmeasuredSegments,
        long gcPauses,
        boolean gcPausesTruncated) {}
