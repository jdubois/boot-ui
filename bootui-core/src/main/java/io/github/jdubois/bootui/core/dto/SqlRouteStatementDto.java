package io.github.jdubois.bootui.core.dto;

/**
 * One normalized statement's contribution to a single route, shown under that route's ranking row.
 *
 * <p>{@link #statementId()} matches {@link SqlStatementRankingDto#id()}, so the panel can deep-link from a
 * route straight to that statement's filtered SQL Trace entries without re-deriving anything client-side.
 * The list of these per route is bounded, so a high-cardinality workload cannot expand the response
 * through the route-by-statement cross product.</p>
 *
 * @param statementId fingerprint shared with the matching {@link SqlStatementRankingDto}
 * @param sql the normalized, literal-free statement text
 * @param category coarse SQL category
 * @param executions executions of this statement attributed to the route
 * @param totalDurationMillis summed duration of those executions, in fractional milliseconds
 * @param maxDurationMillis slowest attributed execution, in fractional milliseconds
 * @param errorCount attributed executions that failed
 */
public record SqlRouteStatementDto(
        String statementId,
        String sql,
        String category,
        long executions,
        double totalDurationMillis,
        double maxDurationMillis,
        long errorCount) {}
