package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One inbound route summarized over the retained HTTP exchange window.
 *
 * <p>A route is a method plus a low-cardinality, value-free path: the framework's own handler template,
 * the application's single best declared mapping, or the concrete path with every value-like segment
 * masked. {@link #routeSource()} says which, so a masked path is never mistaken for a declared route. No
 * query string or path-parameter value is ever part of a route.</p>
 *
 * <p>Every rankable metric travels on the row, and the report returns the union of each criterion's top
 * rows, so re-sorting client-side by any criterion is exact. {@link #topFor()} says which criteria earned
 * the row its place. Durations are whole milliseconds, as exchanges record them. Percentiles are
 * nearest-rank over the route's retained exchanges that carry a duration, never an estimate, and are
 * {@code null} when none does.</p>
 *
 * @param id grouping key, {@code METHOD route}; pass it as the {@code route} filter of
 *     {@code GET /bootui/api/http-exchanges} to list this route's exchanges
 * @param method upper-cased HTTP method, or {@code UNKNOWN}
 * @param route the resolved template or masked path
 * @param routeSource {@code FRAMEWORK_TEMPLATE}, {@code DECLARED_MAPPING} or {@code MASKED_PATH}; when
 *     exchanges of one route resolved through different tiers, the strongest is reported
 * @param requests retained exchanges on this route
 * @param status2xx exchanges answered with a 2xx status
 * @param status3xx exchanges answered with a 3xx status
 * @param status4xx exchanges answered with a 4xx status
 * @param status5xx exchanges answered with a 5xx status
 * @param statusOther exchanges with any other status, so the status counts always add up to {@code requests}
 * @param errorCount exchanges answered with a 4xx or 5xx status
 * @param timedRequests exchanges carrying a duration: the sample every duration figure is computed over
 * @param totalDurationMs summed duration of the timed exchanges
 * @param avgDurationMs mean duration of the timed exchanges, or {@code null} when none is timed
 * @param p50DurationMs median duration, or {@code null} when none is timed
 * @param p95DurationMs 95th percentile duration, or {@code null} when none is timed
 * @param p99DurationMs 99th percentile duration, or {@code null} when none is timed
 * @param maxDurationMs slowest exchange, or {@code null} when none is timed
 * @param shareOfRetainedTimePercent this route's share of all retained, visible request time, 0-100
 * @param topFor ranking criteria this row is in the top group for, never empty
 */
public record HttpRouteDto(
        String id,
        String method,
        String route,
        String routeSource,
        long requests,
        long status2xx,
        long status3xx,
        long status4xx,
        long status5xx,
        long statusOther,
        long errorCount,
        long timedRequests,
        long totalDurationMs,
        Double avgDurationMs,
        Long p50DurationMs,
        Long p95DurationMs,
        Long p99DurationMs,
        Long maxDurationMs,
        double shareOfRetainedTimePercent,
        List<String> topFor) {

    public HttpRouteDto {
        topFor = DtoCollections.immutableCopy(topFor);
    }
}
