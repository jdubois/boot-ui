package io.github.jdubois.bootui.core.dto;

/**
 * One JDK hook of the BootUI agent's {@code executors} sensor ({@code docs/PLAN-v2.md} M5-2): where an executor receives
 * a task ({@code key}) or runs it ({@code apply}).
 *
 * @param id the hook, such as {@code ThreadPoolExecutor.runWorker}
 * @param kind {@code key} or {@code apply}
 * @param type the JDK class it instruments
 * @param present whether this JDK has that class
 * @param transformed whether the agent instrumented it
 * @param selfTest the self-test's result: {@code passed}, {@code failed}, {@code not-exercised}, {@code unsupported}, or
 *     {@code not-run}
 * @param fired how many tasks it keyed or applied since the agent started
 */
public record JavaAgentHookDto(
        String id, String kind, String type, boolean present, boolean transformed, String selfTest, long fired) {}
