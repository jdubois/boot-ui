package io.github.jdubois.bootui.core.dto;

/**
 * One hook of a Side Effects sensor ({@code docs/PLAN-v2.md} §5.16): the JDK method the BootUI agent advises, whether
 * the JVM has it and the agent transformed it, its self-test result, and how often it recorded.
 *
 * @param id the hook's id, such as {@code ProcessBuilder.start}
 * @param type the class it transforms
 * @param present whether this JVM has the class
 * @param transformed whether the agent transformed it
 * @param selfTest its self-test result: {@code passed}, {@code failed}, {@code not-run}, or why it was not exercised
 * @param recorded how many calls it recorded since the JVM started
 */
public record SideEffectsHookDto(
        String id, String type, boolean present, boolean transformed, String selfTest, long recorded) {}
