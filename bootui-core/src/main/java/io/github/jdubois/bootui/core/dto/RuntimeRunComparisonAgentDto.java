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
 * @param previousRunId the run compared with, or {@code null}
 * @param notComparableReasons what differs between the runs, when not comparable
 * @param behavior what the routes did differently, at most {@value #MAX_ROWS}
 * @param behaviorOmitted the behavior rows left out
 * @param edges the runtime model's added and removed edges, at most {@value #MAX_ROWS}
 * @param edgesOmitted the edges left out
 * @param limitations what the comparison cannot see
 */
public record RuntimeRunComparisonAgentDto(
        String status,
        String reason,
        String previousRunId,
        List<String> notComparableReasons,
        List<RuntimeRunChangeDto> behavior,
        int behaviorOmitted,
        List<RuntimeRunChangeDto> edges,
        int edgesOmitted,
        List<String> limitations) {

    /** The rows each list holds at most. */
    public static final int MAX_ROWS = 8;

    public RuntimeRunComparisonAgentDto {
        notComparableReasons = DtoCollections.immutableCopy(notComparableReasons);
        behavior = DtoCollections.immutableCopy(behavior);
        edges = DtoCollections.immutableCopy(edges);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
