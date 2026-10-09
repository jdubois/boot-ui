package io.github.jdubois.bootui.core.dto;

/**
 * The counters of the BootUI agent's {@code inventory} sensor ({@code docs/PLAN-v2.md} §5.15, M5-3): which application
 * methods it instruments and saw run in this run, and the classes it counted per code source.
 *
 * @param methodsTracked application methods instrumented, whose executions the sensor sees
 * @param executedThisRun tracked methods that ran at least once in this run, since this application's claim
 * @param methodOverflow methods left without instrumentation because the agent's method limit was reached
 * @param transformFailures application classes that failed to transform, whose methods are not tracked
 * @param codeSources jars and class directories that defined at least one class
 * @param ringDropped records the sensor dropped because the agent's transport ring was full
 * @param ringLost records lost because their producer never finished writing them
 * @param internOverflow strings, such as routes, recorded as unknown because the claim's intern table was full
 * @param disabledReason why the sensor stopped recording, such as a failed self-test, or {@code null}
 */
public record JavaAgentInventoryCountersDto(
        long methodsTracked,
        long executedThisRun,
        long methodOverflow,
        long transformFailures,
        long codeSources,
        long ringDropped,
        long ringLost,
        long internOverflow,
        String disabledReason) {}
