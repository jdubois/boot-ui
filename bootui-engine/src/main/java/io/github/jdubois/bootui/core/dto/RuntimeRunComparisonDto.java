package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The comparison of the current run with a previous one ({@code docs/PLAN-v2.md} §5.8). On a laptop, warmup and noise
 * dominate latency, while the work identical requests do is stable, so behavior comes first and latency last.
 *
 * @param status {@code COMPARED}; {@code PARTIAL} when completeness or bounds prevent comparing some dimensions;
 *     {@code INSUFFICIENT} when no route or execution recorded enough samples in both runs to tell that
 *     nothing changed; {@code NOT_COMPARABLE} when the runs differ in database, profiles, or cache;
 *     {@code NO_PREVIOUS_RUN}; or {@code UNAVAILABLE} when the journal does not record
 * @param reason why the status is not {@code COMPARED}, or {@code null}
 * @param current the current run, or {@code null} when the journal does not record
 * @param previous the run compared with, or {@code null}
 * @param runs the kept runs that can be compared with, newest first
 * @param notComparableReasons the configuration differences that make the runs not comparable, the database first
 * @param behavior what the routes and executions did differently: statements, calls, new or gone statements and exceptions, status classes,
 *     routes newly hit, tokens, cache misses, and allocation, each over enough requests in both runs, or new
 * @param edges the runtime model's edges one run observed and the other did not, most observed first
 * @param restartCost the time to ready and the beans whose initialization moved, compared with the previous restart
 * @param latency the routes whose warm median moved, labelled noisy, last
 * @param limitations what the comparison cannot see
 * @param codeChanges the methods changed, added, and removed since the previous run, which ran, and where: shown first;
 *     {@code null} without the BootUI agent, and unavailable with the reason when the agent cannot list them
 * @param sideEffects the hosts, file patterns, processes, and variable names new or gone outside the JVM, from the BootUI
 *     agent's Side Effects (M5-7b); {@code null} without the agent, and unavailable with the reason when they cannot
 *     be compared
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
        List<String> limitations,
        RuntimeCodeChangesDto codeChanges,
        RuntimeSideEffectChangesDto sideEffects) {

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

    /** Why code changes are not listed when the inventory gave no reason. */
    public static final String NO_CODE_CHANGES =
            "Code changes need the BootUI agent's inventory sensor: see the Java" + " Agent panel.";

    public RuntimeRunComparisonDto(
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
            List<String> limitations,
            RuntimeCodeChangesDto codeChanges) {
        this(
                status,
                reason,
                current,
                previous,
                runs,
                notComparableReasons,
                behavior,
                edges,
                restartCost,
                latency,
                limitations,
                codeChanges,
                null);
    }

    public RuntimeRunComparisonDto(
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
        this(
                status,
                reason,
                current,
                previous,
                runs,
                notComparableReasons,
                behavior,
                edges,
                restartCost,
                latency,
                limitations,
                null,
                null);
    }

    /** This comparison with {@code codeChanges}. */
    public RuntimeRunComparisonDto withCodeChanges(RuntimeCodeChangesDto codeChanges) {
        return new RuntimeRunComparisonDto(
                status,
                reason,
                current,
                previous,
                runs,
                notComparableReasons,
                behavior,
                edges,
                restartCost,
                latency,
                limitations,
                codeChanges,
                sideEffects);
    }

    /** This comparison with {@code sideEffects}. */
    public RuntimeRunComparisonDto withSideEffects(RuntimeSideEffectChangesDto sideEffects) {
        return new RuntimeRunComparisonDto(
                status,
                reason,
                current,
                previous,
                runs,
                notComparableReasons,
                behavior,
                edges,
                restartCost,
                latency,
                limitations,
                codeChanges,
                sideEffects);
    }
}
