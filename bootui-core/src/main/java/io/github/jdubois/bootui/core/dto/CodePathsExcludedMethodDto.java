package io.github.jdubois.bootui.core.dto;

/**
 * A method the code-paths sensor stopped timing in this run ({@code docs/PLAN-v2.md} §5.14): its time stays in its
 * caller.
 *
 * @param method the method's key, {@code class#name+descriptor}
 * @param reason why it was excluded
 */
public record CodePathsExcludedMethodDto(String method, String reason) {}
