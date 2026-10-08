package io.github.jdubois.bootui.core.dto;

/**
 * The counters of one of the BootUI agent's propagation sensors, {@code executors} or {@code threads}, since the agent
 * started ({@code docs/PLAN-v2.md} M5-2).
 *
 * @param pending tasks keyed and not yet run
 * @param neverApplied keyed tasks that never reached an instrumented run point, such as tasks of pools whose workers
 *     started before the claim
 * @param ambiguous tasks submitted more than once by different owners, which are left unowned
 * @param stale tasks keyed under an earlier claim, which are not reopened
 * @param refused snapshots the bridge refused because they held other values than strings and numbers
 * @param virtualSkipped virtual-thread continuations, which run their own thread's context
 * @param periodicSkipped periodic scheduled tasks, which are never propagated
 * @param skippedTasks tasks left alone because their class is in {@code bootui.agent.executors.skip-tasks}
 * @param skippedThreads tasks left alone because their worker is in {@code bootui.agent.executors.skip-threads}
 * @param failures propagated tasks that failed
 * @param disabledReason why propagation is disabled for this claim, such as a failed self-test, or {@code null}
 * @param asyncApplies whether {@code CompletableFuture}'s async tasks are applied by their own hook
 * @param libraryThreadsSkipped threads sensor: threads started inside owned work by code outside the claimed packages,
 *     such as a library's lazily started thread, which are never propagated; {@code null} for the executors sensor
 * @param poolWorkersSkipped threads sensor: pool worker threads, which never inherit the request they were created
 *     under; {@code null} for the executors sensor
 */
public record JavaAgentExecutorCountersDto(
        long pending,
        long neverApplied,
        long ambiguous,
        long stale,
        long refused,
        long virtualSkipped,
        long periodicSkipped,
        long skippedTasks,
        long skippedThreads,
        long failures,
        String disabledReason,
        boolean asyncApplies,
        Long libraryThreadsSkipped,
        Long poolWorkersSkipped) {

    /** The executors sensor's counters, which have no thread counters. */
    public JavaAgentExecutorCountersDto(
            long pending,
            long neverApplied,
            long ambiguous,
            long stale,
            long refused,
            long virtualSkipped,
            long periodicSkipped,
            long skippedTasks,
            long skippedThreads,
            long failures,
            String disabledReason,
            boolean asyncApplies) {
        this(
                pending,
                neverApplied,
                ambiguous,
                stale,
                refused,
                virtualSkipped,
                periodicSkipped,
                skippedTasks,
                skippedThreads,
                failures,
                disabledReason,
                asyncApplies,
                null,
                null);
    }
}
