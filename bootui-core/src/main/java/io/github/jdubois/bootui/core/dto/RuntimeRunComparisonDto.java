package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The comparison of the current run with a previous one ({@code docs/PLAN-v2.md} §5.8). On a laptop, warmup and noise
 * dominate latency, while the work identical requests do is stable, so behavior comes first and latency last.
 *
 * @param status {@code COMPARED}; {@code INSUFFICIENT} when no route served enough requests in both runs to tell that
 *     nothing changed; {@code NOT_COMPARABLE} when the runs differ in database, profiles, or cache;
 *     {@code NO_PREVIOUS_RUN}; or {@code UNAVAILABLE} when the journal does not record
 * @param reason why the status is not {@code COMPARED}, or {@code null}
 * @param current the current run, or {@code null} when the journal does not record
 * @param previous the run compared with, or {@code null}
 * @param runs the kept runs that can be compared with, newest first
 * @param notComparableReasons the configuration differences that make the runs not comparable, the database first
 * @param behavior what the routes did differently: statements, calls, new statements and exceptions, status classes,
 *     routes newly hit, tokens, cache misses, and allocation, each over enough requests in both runs, or new
 * @param edges the runtime model's edges one run observed and the other did not, most observed first
 * @param restartCost the time to ready and the beans whose initialization moved, compared with the previous restart
 * @param latency the routes whose warm median moved, labelled noisy, last
 * @param limitations what the comparison cannot see
 */
public record RuntimeRunComparisonDto(
        String status,
        String reason,
        RuntimeRunRefDto current,
        RuntimeRunRefDto previous,
        List<RuntimeRunRefDto> runs,
        List<String> notComparableReasons,
        List<RuntimeRunChangeDto> behavior,
        List<RuntimeRunChangeDto> edges,
        RuntimeRestartCostDto restartCost,
        List<RuntimeRunChangeDto> latency,
        List<String> limitations) {

    /** The rows each list holds at most. */
    public static final int MAX_ROWS = 200;

    public RuntimeRunComparisonDto {
        runs = DtoCollections.immutableCopy(runs);
        notComparableReasons = DtoCollections.immutableCopy(notComparableReasons);
        behavior = DtoCollections.immutableCopy(behavior);
        edges = DtoCollections.immutableCopy(edges);
        latency = DtoCollections.immutableCopy(latency);
        limitations = DtoCollections.immutableCopy(limitations);
    }
}
