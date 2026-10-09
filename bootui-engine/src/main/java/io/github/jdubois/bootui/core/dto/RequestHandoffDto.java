package io.github.jdubois.bootui.core.dto;

/**
 * One task a request handed to a JDK executor, as the BootUI agent propagated it ({@code docs/PLAN-v2.md} M5-2): where
 * and when it ran, what it did under its own execution id, and how it ended.
 *
 * @param executionId the child execution it ran as, {@code async-…}
 * @param parentExecutionId the execution that submitted it, or {@code null}
 * @param thread the worker thread it ran on
 * @param startOffsetMicros when it started, from the request's start
 * @param durationMicros how long it ran
 * @param queuedMicros how long it waited between its submission and its start
 * @param taskClass the class of the task the executor ran, best effort: often a JDK wrapper such as {@code FutureTask}
 * @param hook the agent hook that propagated it, such as {@code ThreadPoolExecutor.runWorker}
 * @param failed whether it failed
 * @param exceptionClass the class of its failure, or {@code null}; never its message
 * @param afterResponse whether it worked once the response started: its body was still running, or, after a body that
 *     ended before, its result-publication tail ran I/O or failed after it; without body evidence, whether it was still
 *     running then, or, when the response start is unknown, after the request ended
 * @param afterResponseMicros how long it worked after the response started, by the same rule, or after the request
 *     ended when the response start is unknown
 * @param capped whether it ended more than {@code bootui.agent.executors.max-handoff} after it started, after which
 *     its work is not attributed
 * @param sqlCount the SQL statements recorded under its execution id
 * @param restClientCount the REST client calls recorded under its execution id
 * @param messagingCount the messages sent or received under its execution id
 * @param allocatedBytes the bytes its thread allocated while it ran, or {@code null} when unmeasured
 */
public record RequestHandoffDto(
        String executionId,
        String parentExecutionId,
        String thread,
        long startOffsetMicros,
        long durationMicros,
        long queuedMicros,
        String taskClass,
        String hook,
        boolean failed,
        String exceptionClass,
        boolean afterResponse,
        Long afterResponseMicros,
        boolean capped,
        int sqlCount,
        int restClientCount,
        int messagingCount,
        Long allocatedBytes) {}
