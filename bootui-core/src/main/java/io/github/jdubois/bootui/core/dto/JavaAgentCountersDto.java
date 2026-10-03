package io.github.jdubois.bootui.core.dto;

/**
 * The bootstrap bridge's claim counters, for the whole JVM ({@code docs/PLAN-v2.md} §5.13).
 *
 * @param claims claims granted
 * @param takeovers claims that took the agent over from another application
 * @param holds claims and releases refused because another application holds the agent
 * @param staleTokens calls made with a token whose claim was replaced or ended
 * @param errors agent calls that failed
 */
public record JavaAgentCountersDto(long claims, long takeovers, long holds, long staleTokens, long errors) {}
