package io.github.jdubois.bootui.core.dto;

/**
 * The recorded calls of one kind a Code Paths node issued ({@code docs/PLAN-v2.md} §5.14, M5-4c): the SQL statements,
 * REST client calls, cache accesses, or AI calls recorded while the node's method was the innermost instrumented method
 * open on their thread. A statement Hibernate flushes at commit runs after the {@code @Transactional} method returned,
 * in the transaction interceptor around it, so it is under the method that called the {@code @Transactional} one. In a
 * route tree, counts and times are per warm request of the route; in a request tree, the request's.
 *
 * @param kind {@code SQL}, {@code REST}, {@code CACHE}, or {@code AI}
 * @param callsPerRequest the calls per request
 * @param totalMillis their time per request, or {@code null} for cache accesses, which carry no duration
 */
public record CodePathsCallsDto(String kind, double callsPerRequest, Double totalMillis) {}
