package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A run comparison compacted for an agent ({@code docs/PLAN-v2.md} §5.6, §5.8): comparability first, then at most
 * {@value #MAX_ROWS} behavior rows and edges. Latency is left out, since it is noisy; {@code INSUFFICIENT} and {@code
 * NOT_COMPARABLE} never mean "no change".
 *
 * @param status {@code COMPARED}, {@code INSUFFICIENT}, {@code NOT_COMPARABLE}, {@code NO_PREVIOUS_RUN}, or {@code
 *     UNAVAILABLE}
 * @param reason why it is not {@code COMPARED}, or {@code null}
 * @param currentRunId the run compared, or {@code null} when the journal does not record
 * @param previousRunId the run compared with, or {@code null}
 * @param runs the kept runs, newest first, with the HTTP requests each served: any id can be passed instead of
 *     {@code previous}
 * @param notComparableReasons what differs between the runs, when not comparable
 * @param codeChanges the methods changed, added, and removed since the previous run, which ran, and where, first; at
 *     most {@value #MAX_ROWS} methods, each with at most {@value #MAX_ROWS} routes; {@code null} without the BootUI
 *     agent
 * @param sideEffects the hosts, file patterns, processes, and variable names new or gone outside the JVM, at most
 *     {@value #MAX_ROWS} changes, new first; {@code null} without the BootUI agent
 * @param behavior what the routes did differently, at most {@value #MAX_ROWS}
 * @param behaviorOmitted the behavior rows left out
 * @param edges the runtime model's added and removed edges, at most {@value #MAX_ROWS}
 * @param edgesOmitted the edges left out
 * @param limitations what the comparison cannot see
 * @param next the calls that follow the comparison up, or that recover from an unknown run id, at most three
 */
public record RuntimeRunComparisonAgentDto(
        String status,
        String reason,
        String currentRunId,
        String previousRunId,
        List<RuntimeRunRefDto> runs,
        List<String> notComparableReasons,
        RuntimeCodeChangesDto codeChanges,
        RuntimeSideEffectChangesDto sideEffects,
        List<RuntimeRunChangeDto> behavior,
        int behaviorOmitted,
        List<RuntimeRunChangeDto> edges,
        int edgesOmitted,
        List<String> limitations,
        List<RuntimeNextStepDto> next) {

    /** The rows each list holds at most. */
    public static final int MAX_ROWS = 8;

    public RuntimeRunComparisonAgentDto {
        runs = DtoCollections.immutableCopy(runs);
        notComparableReasons = DtoCollections.immutableCopy(notComparableReasons);
        behavior = DtoCollections.immutableCopy(behavior);
        edges = DtoCollections.immutableCopy(edges);
        limitations = DtoCollections.immutableCopy(limitations);
        next = DtoCollections.immutableCopy(next);
    }

    public RuntimeRunComparisonAgentDto(
            String status,
            String reason,
            String currentRunId,
            String previousRunId,
            List<RuntimeRunRefDto> runs,
            List<String> notComparableReasons,
            RuntimeCodeChangesDto codeChanges,
            List<RuntimeRunChangeDto> behavior,
            int behaviorOmitted,
            List<RuntimeRunChangeDto> edges,
            int edgesOmitted,
            List<String> limitations) {
        this(
                status,
                reason,
                currentRunId,
                previousRunId,
                runs,
                notComparableReasons,
                codeChanges,
                null,
                behavior,
                behaviorOmitted,
                edges,
                edgesOmitted,
                limitations,
                List.of());
    }

    public RuntimeRunComparisonAgentDto(
            String status,
            String reason,
            String currentRunId,
            String previousRunId,
            List<RuntimeRunRefDto> runs,
            List<String> notComparableReasons,
            List<RuntimeRunChangeDto> behavior,
            int behaviorOmitted,
            List<RuntimeRunChangeDto> edges,
            int edgesOmitted,
            List<String> limitations) {
        this(
                status,
                reason,
                currentRunId,
                previousRunId,
                runs,
                notComparableReasons,
                null,
                behavior,
                behaviorOmitted,
                edges,
                edgesOmitted,
                limitations);
    }
}
