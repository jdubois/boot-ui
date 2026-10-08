package io.github.jdubois.bootui.core.dto;

/**
 * One request against its route's other requests in this run ({@code docs/PLAN-v2.md} §5.3), from the journal's
 * aggregates, which count every request, retained or not. Percentiles are within 6.25 % of the exact value.
 *
 * @param route the route, such as {@code GET /api/orders/{id}}
 * @param requests the route's requests in this run
 * @param p50Micros its median duration, or {@code null}
 * @param p95Micros its 95th percentile duration, or {@code null}
 * @param durationMicros this request's duration
 * @param standing {@code AT_OR_BELOW_P50}, {@code ABOVE_P50}, or {@code ABOVE_P95}; {@code null} below
 *     {@code minimumRequests}
 * @param minimumRequests the requests a route needs before the comparison says where this one stands
 */
public record RouteComparisonDto(
        String route,
        long requests,
        Long p50Micros,
        Long p95Micros,
        long durationMicros,
        String standing,
        int minimumRequests) {}
