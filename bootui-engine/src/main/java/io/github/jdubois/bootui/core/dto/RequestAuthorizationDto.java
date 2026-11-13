package io.github.jdubois.bootui.core.dto;

/**
 * One authorization decision recorded for a request ({@code docs/PLAN-v2.md} §5.18): what was checked, how the caller
 * was authenticated, the rule when known and permitted by the exposure policy, and whether access was granted.
 *
 * @param target {@code REQUEST} or {@code METHOD}
 * @param subject the method, such as {@code OrderService#cancel}, or {@code null} for a request
 * @param rule what decided, such as {@code hasAnyAuthority(ROLE_ADMIN)}, or {@code null} when unknown or withheld
 * @param authentication {@code ANONYMOUS}, {@code AUTHENTICATED}, {@code NONE}, or {@code UNKNOWN}
 * @param granted whether access was granted
 * @param authorities how many authorities or roles the caller had
 * @param offsetMillis when the check started, from the request's start
 * @param durationMicros how long the check took, or {@code null} when not measured
 */
public record RequestAuthorizationDto(
        String target,
        String subject,
        String rule,
        String authentication,
        boolean granted,
        int authorities,
        long offsetMillis,
        Long durationMicros) {}
