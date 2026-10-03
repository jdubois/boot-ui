package io.github.jdubois.bootui.core.dto;

/**
 * The BootUI Java agent's last retransformation of the classes already loaded when it was claimed
 * ({@code docs/PLAN-v2.md} §5.13): the claim's cost.
 *
 * @param state the installer's state as the agent reports it
 * @param transformed classes transformed as they loaded
 * @param retransformed already loaded classes retransformed
 * @param failed classes that failed to transform
 * @param skipped classes skipped (ignored or unmodifiable)
 * @param durationMillis how long the retransformation took
 * @param running whether the retransformation is still running
 */
public record JavaAgentRetransformationDto(
        String state,
        int transformed,
        int retransformed,
        int failed,
        int skipped,
        long durationMillis,
        boolean running) {}
