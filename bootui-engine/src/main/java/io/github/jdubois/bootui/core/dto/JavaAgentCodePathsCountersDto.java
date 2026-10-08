package io.github.jdubois.bootui.core.dto;

/**
 * The counters of the BootUI agent's {@code code-paths} sensor ({@code docs/PLAN-v2.md} §5.14, M5-4a): the call-tree
 * fragments it flushed for requests, what it dropped to stay within its bounds, and the methods it stopped timing.
 *
 * @param fragmentsFlushed fragments of request call trees handed to the engine
 * @param fragmentsDropped fragments dropped because every tree of the agent's pool was in use
 * @param queueDropped fragments dropped because the agent's fragment queue was full
 * @param callsDropped calls recorded in no node: deeper than 32 levels, under an Other node, or past a fragment's budget
 * @param queueBytes bytes of fragments waiting for the engine
 * @param excludedMethods methods excluded in this run because they were called very often and were very fast
 * @param errors internal errors of the sensor, each resetting its thread's state
 * @param disabledReason why the sensor stopped recording, such as a failed self-test or too many internal errors, or
 *     {@code null}
 */
public record JavaAgentCodePathsCountersDto(
        long fragmentsFlushed,
        long fragmentsDropped,
        long queueDropped,
        long callsDropped,
        long queueBytes,
        long excludedMethods,
        long errors,
        String disabledReason) {}
