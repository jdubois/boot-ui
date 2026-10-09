package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Database work attributed to one inbound request route over the retained SQL Trace window.
 *
 * <p>A route is either a real route template supplied by the adapter (for example
 * {@code /api/orders/{id}}) or, when no template is available, a masked request path whose
 * identifier-looking segments are replaced by {@code {value}}. {@link #routeSource()} says which, so the
 * panel never implies a precision it does not have. Query strings and path-parameter values are never
 * part of the grouping key.</p>
 *
 * <p>The three {@code *Correlated} counters expose <em>how</em> the executions reached this route, so a
 * reader can tell exact trace-id attribution from the weaker serving-thread and time-window evidence
 * rather than being asked to trust a single opaque number.</p>
 *
 * @param id stable key for this route group, used for deep links and as a table key
 * @param method HTTP method of the attributed requests
 * @param route the route template or masked path this group represents
 * @param routeSource {@code ROUTE_TEMPLATE} or {@code MASKED_PATH}
 * @param requests distinct captured requests that contributed executions to this route
 * @param executions statement executions attributed to this route
 * @param totalDurationMillis summed duration of those executions, in fractional milliseconds
 * @param maxDurationMillis slowest attributed execution, in fractional milliseconds
 * @param avgDurationMillis mean duration across the attributed executions, in fractional milliseconds
 * @param errorCount attributed executions that failed
 * @param distinctStatements distinct normalized statements attributed to this route
 * @param shareOfRetainedTimePercent this route's share of the window's total database time, 0-100
 * @param traceCorrelated executions attributed by exact distributed-trace id
 * @param threadCorrelated executions attributed by a unique serving thread inside the request window
 * @param timeWindowCorrelated executions attributed by a unique overlapping request window only
 * @param topStatements the route's heaviest normalized statements, bounded
 * @param topStatementsTruncated whether {@link #topStatements()} omits further statements
 * @param entryIds ids of the retained executions attributed to this route, so the panel can filter the
 *     executions table to exactly this route's work; bounded, and a shorter list than
 *     {@link #executions()} means the link covers only the executions listed
 */
public record SqlRouteRankingDto(
        String id,
        String method,
        String route,
        String routeSource,
        long requests,
        long executions,
        double totalDurationMillis,
        double maxDurationMillis,
        double avgDurationMillis,
        long errorCount,
        int distinctStatements,
        double shareOfRetainedTimePercent,
        long traceCorrelated,
        long threadCorrelated,
        long timeWindowCorrelated,
        List<SqlRouteStatementDto> topStatements,
        boolean topStatementsTruncated,
        List<Long> entryIds) {

    public SqlRouteRankingDto {
        topStatements = DtoCollections.immutableCopy(topStatements);
        entryIds = DtoCollections.immutableCopy(entryIds);
    }
}
