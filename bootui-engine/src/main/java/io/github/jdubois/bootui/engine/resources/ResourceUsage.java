package io.github.jdubois.bootui.engine.resources;

import java.util.List;

/**
 * The CPU time, allocated bytes, and completed GC pauses of one request, summed over every segment its work ran on a
 * thread, including thread hops ({@code docs/PLAN-v2.md} §5.11).
 *
 * <p>CPU time and allocated bytes cover only the measured segments. A segment the JVM cannot measure, such as one on a
 * virtual thread, is counted in {@code unmeasuredSegments} with its reason, never as zero: see {@link #availability()}.
 * GC pauses are counted on every segment, measured or not.</p>
 *
 * @param cpuNanos CPU time of the measured segments
 * @param allocatedBytes bytes allocated by the measured segments
 * @param segments every segment the request ran
 * @param unmeasuredSegments segments whose CPU time or allocated bytes the JVM did not measure
 * @param unmeasuredReason why, or {@code null} when every segment was measured
 * @param gcPauses collections of pause collectors that completed during the request's segments
 * @param gcPauseRanges those collections by collector and id, at most {@link SegmentMeter#MAX_GC_RANGES}
 * @param gcPauseRangesTruncated whether ranges beyond that bound were dropped, which leaves {@code gcPauses} a floor
 */
public record ResourceUsage(
        long cpuNanos,
        long allocatedBytes,
        int segments,
        int unmeasuredSegments,
        Unmeasured unmeasuredReason,
        long gcPauses,
        List<GcPauseRange> gcPauseRanges,
        boolean gcPauseRangesTruncated) {

    public ResourceUsage {
        gcPauseRanges = gcPauseRanges == null ? List.of() : List.copyOf(gcPauseRanges);
    }

    /** Whether the request's CPU time and allocated bytes are complete, partial, or unknown. */
    public Availability availability() {
        if (segments == 0 || unmeasuredSegments >= segments) {
            return Availability.UNAVAILABLE;
        }
        return unmeasuredSegments == 0 ? Availability.AVAILABLE : Availability.PARTIAL;
    }

    /** Bytes this record retains, for the journal's byte bound; collector names are shared JVM strings. */
    public int estimatedBytes() {
        return 64 + gcPauseRanges.size() * 40;
    }

    /** Whether a request's CPU time and allocated bytes cover all of its work. */
    public enum Availability {
        /** Every segment was measured. */
        AVAILABLE,
        /** Some segments were measured, so the totals are a floor. */
        PARTIAL,
        /** No segment was measured, or the request ran no segment the meter saw. */
        UNAVAILABLE
    }

    /** Why a segment was not measured. */
    public enum Unmeasured {
        /** The JVM returns {@code -1} for the CPU time and allocated bytes of a virtual thread. */
        VIRTUAL_THREAD,
        /** The thread ended before its segment could be read. */
        THREAD_ENDED,
        /** This JVM does not measure per-thread CPU time or allocated bytes, or has them disabled. */
        UNSUPPORTED
    }
}
