package io.github.jdubois.bootui.core.dto;

/**
 * One side-effect key that differs between two runs ({@code docs/PLAN-v2.md} M5-7b).
 *
 * @param sensor its sensor: {@code network}, {@code files}, {@code processes}, or {@code environment}
 * @param kind what it did, as a Side Effects row says: {@code connect}, {@code lookup}, {@code datagram}, {@code read},
 *     {@code write}, {@code process}, {@code environment variable}, {@code system property}
 * @param target the normalized, masked target: a host and port, a path pattern, a file name, or a variable name
 * @param scope its owner's kind: {@code route}, {@code execution}, or {@code startup}
 * @param owner the route, the execution's label, or {@code startup}
 * @param change {@value #ADDED}, {@value #REMOVED}, or {@value #NOT_EXERCISED} when its owner did not run in the other
 *     run, so it is not compared
 * @param client the network client recognized, or {@code null}
 * @param count how many operations it counts in the run that has it
 * @param sentence the change as one sentence, with names in backticks
 */
public record RuntimeSideEffectChangeDto(
        String sensor,
        String kind,
        String target,
        String scope,
        String owner,
        String change,
        String client,
        long count,
        String sentence) {

    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String NOT_EXERCISED = "NOT_EXERCISED";
}
