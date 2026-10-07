package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.HandoffWindow;

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
 * @param failureAfterResponse whether the failure was after the response, distinguishing body outcomes from throws
 *     in result-publication tails, or {@code null} when unconfirmed
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
        Long responseAtMicros,
        Boolean failureAfterResponse)
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
                null,
                null);
    }

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
            boolean capped,
            Boolean bodyAfterResponse,
            Long bodyAfterResponseMicros,
            Long responseAtMicros) {
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
                bodyAfterResponse,
                bodyAfterResponseMicros,
                responseAtMicros,
                null);
    }

    /**
     * Whether the task worked once its request's response had started, as Live Activity badges it and the request
     * profile lists it: a confirmed body completion first, which is ordered before the JDK releases a waiting handler,
     * so a handler that waited for the task never reads as answered before it however its handoff's close races the
     * response. After a body that ended before the response, only I/O of its result-publication tail, such as a
     * synchronous dependent stage, ending at least {@link HandoffWindow#RESPONSE_TIMESTAMP_SLACK_MICROS} past the
     * response counts, or a failure the agent timed after it. Without a confirmed body, the run's end decides.
     *
     * @param lastWorkEndMicros when the last SQL statement, REST call, or message recorded under the task's execution
     *     ended, or {@link Long#MIN_VALUE} when none was
     * @return {@code null} when the agent did not know the response's start
     */
    public Boolean workedAfterResponse(long lastWorkEndMicros) {
        if (bodyAfterResponse == null) {
            return afterResponse;
        }
        return bodyAfterResponse || tailWorkedAfterResponse(lastWorkEndMicros);
    }

    /**
     * How long the task worked after its request's response started, by the rule of
     * {@link #workedAfterResponse(long)}: its body's time after the response, its run's for a late tail, else 0.
     */
    public Long workedAfterResponseMicros(long lastWorkEndMicros) {
        if (bodyAfterResponse == null) {
            return afterResponseMicros;
        }
        if (bodyAfterResponse) {
            return bodyAfterResponseMicros != null ? bodyAfterResponseMicros : afterResponseMicros;
        }
        return tailWorkedAfterResponse(lastWorkEndMicros) ? afterResponseMicros : Long.valueOf(0L);
    }

    private boolean tailWorkedAfterResponse(long lastWorkEndMicros) {
        if (Boolean.TRUE.equals(failureAfterResponse)) {
            return true;
        }
        return Boolean.TRUE.equals(afterResponse)
                && responseAtMicros != null
                && lastWorkEndMicros != Long.MIN_VALUE
                && lastWorkEndMicros - responseAtMicros >= HandoffWindow.RESPONSE_TIMESTAMP_SLACK_MICROS;
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
                responseAtMicros,
                failureAfterResponse);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 152
                + RuntimeEvent.stringBytes(executionId)
                + RuntimeEvent.stringBytes(parentExecutionId)
                + JournalDictionary.retained(dictionary, taskClass)
                + JournalDictionary.retained(dictionary, hook)
                + JournalDictionary.retained(dictionary, exceptionClass);
    }
}
