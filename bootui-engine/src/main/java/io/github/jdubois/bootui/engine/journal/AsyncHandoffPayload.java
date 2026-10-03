package io.github.jdubois.bootui.engine.journal;

/**
 * A task the BootUI agent propagated through a JDK executor, as it ran on its worker ({@code docs/PLAN-v2.md} M5-2,
 * D32): the child execution it opened, the execution that submitted it, and what is known about its run. Its event's
 * {@code epochMillis} is when it started on the worker, its {@code durationNanos} how long it ran, and its request,
 * execution, and trace ids those of the context it ran with. Never the task's arguments, result, or exception message.
 *
 * @param executionId the child execution the task ran as, {@code async-…}
 * @param parentExecutionId the execution that submitted it, or {@code null} when the request itself did
 * @param taskClass the class of the task the executor ran, best effort: often a JDK wrapper such as {@code FutureTask}
 * @param hook the agent hook that reopened the context, such as {@code ThreadPoolExecutor.runWorker}
 * @param submittedEpochMillis when it was submitted
 * @param queuedNanos how long it waited between its submission and its start
 * @param allocatedBytes the bytes its thread allocated while it ran, or {@code null} when unmeasured (virtual threads)
 * @param failed whether it failed
 * @param exceptionClass the class of its failure, or {@code null}
 * @param afterResponse whether it was still running once its request's response started, or {@code null} when the
 *     response start is unknown
 * @param afterResponseMicros how long it ran after the response started, or {@code null}
 * @param capped whether it ended more than {@code bootui.agent.executors.max-handoff} after it started, after which
 *     its work is not attributed
 * @param bodyAfterResponse whether a confirmed JDK task-body completion saw its response already started, or
 *     {@code null} when no body marker or request timeline was available
 * @param bodyAfterResponseMicros time the body ran after its response boundary, excluding result-publication tails
 * @param responseAtMicros the actual response start (request end for an unphased request), or {@code null}
 */
public record AsyncHandoffPayload(
        String executionId,
        String parentExecutionId,
        String taskClass,
        String hook,
        long submittedEpochMillis,
        long queuedNanos,
        Long allocatedBytes,
        boolean failed,
        String exceptionClass,
        Boolean afterResponse,
        Long afterResponseMicros,
        boolean capped,
        Boolean bodyAfterResponse,
        Long bodyAfterResponseMicros,
        Long responseAtMicros)
        implements RuntimeEventPayload {

    /** Compatibility for producers without the optional body-completion evidence. */
    public AsyncHandoffPayload(
            String executionId,
            String parentExecutionId,
            String taskClass,
            String hook,
            long submittedEpochMillis,
            long queuedNanos,
            Long allocatedBytes,
            boolean failed,
            String exceptionClass,
            Boolean afterResponse,
            Long afterResponseMicros,
            boolean capped) {
        this(
                executionId,
                parentExecutionId,
                taskClass,
                hook,
                submittedEpochMillis,
                queuedNanos,
                allocatedBytes,
                failed,
                exceptionClass,
                afterResponse,
                afterResponseMicros,
                capped,
                null,
                null,
                null);
    }

    /** This handoff with its task class, hook, and exception class replaced by the run's shared copies. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new AsyncHandoffPayload(
                executionId,
                parentExecutionId,
                dictionary.shared(taskClass),
                dictionary.shared(hook),
                submittedEpochMillis,
                queuedNanos,
                allocatedBytes,
                failed,
                dictionary.shared(exceptionClass),
                afterResponse,
                afterResponseMicros,
                capped,
                bodyAfterResponse,
                bodyAfterResponseMicros,
                responseAtMicros);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 144
                + RuntimeEvent.stringBytes(executionId)
                + RuntimeEvent.stringBytes(parentExecutionId)
                + JournalDictionary.retained(dictionary, taskClass)
                + JournalDictionary.retained(dictionary, hook)
                + JournalDictionary.retained(dictionary, exceptionClass);
    }
}
