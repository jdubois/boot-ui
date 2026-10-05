package io.github.jdubois.bootui.core.dto;

/**
 * One Side Effects sensor in a run comparison ({@code docs/PLAN-v2.md} M5-7b).
 *
 * @param sensor its {@code bootui.agent.sensors} id: {@code network}, {@code files}, {@code processes}, or {@code
 *     environment}
 * @param status {@value #COMPARED}, {@value #PARTIAL} when a run kept only part of its keys, or {@value #NOT_COMPARED}
 * @param reason why it is not compared, or partial, or why its startup is not compared, or {@code null}
 * @param added its keys new in this run
 * @param removed its keys gone from this run, whose owner this run exercised
 * @param notExercised its keys of the previous run whose owner this run did not exercise
 */
public record RuntimeSideEffectSensorDto(
        String sensor, String status, String reason, int added, int removed, int notExercised) {

    public static final String COMPARED = "COMPARED";
    public static final String PARTIAL = "PARTIAL";
    public static final String NOT_COMPARED = "NOT_COMPARED";
}
