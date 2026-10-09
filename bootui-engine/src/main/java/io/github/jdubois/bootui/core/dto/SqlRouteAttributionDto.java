package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Per-route database attribution over the retained SQL Trace window.
 *
 * <p>Attribution is deliberately conservative and tiered: an execution reaches a route by exact
 * distributed-trace id first, then by a <em>unique</em> serving thread inside a request's window (only on
 * a runtime where one thread serves one request start to finish), then by a <em>unique</em> overlapping
 * request window. Anything that stays undecided lands in {@link #ambiguous()}, and anything with no
 * candidate request at all lands in {@link #unattributed()} — never in a route.</p>
 *
 * <p>{@link #supportedCorrelations()} reports which tiers this runtime can actually use, so a reactive or
 * event-loop adapter with no reliable thread affinity says so instead of appearing to have lost data.</p>
 *
 * @param available whether route attribution could be computed at all
 * @param unavailableReason populated when {@code available} is {@code false}
 * @param supportedCorrelations correlation tiers this adapter can use: {@code TRACE_ID},
 *     {@code SERVING_THREAD}, {@code TIME_WINDOW}
 * @param requestsConsidered captured inbound requests that were candidates for attribution
 * @param routes ranked routes, heaviest cumulative database time first, bounded
 * @param routesTruncated whether further routes exist beyond {@link #routes()}
 * @param distinctRoutes distinct routes observed before truncation
 * @param attributedExecutions executions attributed to some route
 * @param unattributed executions with no candidate inbound request
 * @param ambiguous executions whose candidate request could not be decided safely
 * @param notes plain-language explanations of what was and was not correlated, and why
 */
public record SqlRouteAttributionDto(
        boolean available,
        String unavailableReason,
        List<String> supportedCorrelations,
        int requestsConsidered,
        List<SqlRouteRankingDto> routes,
        boolean routesTruncated,
        int distinctRoutes,
        long attributedExecutions,
        SqlAttributionBucketDto unattributed,
        SqlAttributionBucketDto ambiguous,
        List<String> notes) {

    public SqlRouteAttributionDto {
        supportedCorrelations = DtoCollections.immutableCopy(supportedCorrelations);
        routes = DtoCollections.immutableCopy(routes);
        notes = DtoCollections.immutableCopy(notes);
        unattributed = unattributed == null ? SqlAttributionBucketDto.empty(null) : unattributed;
        ambiguous = ambiguous == null ? SqlAttributionBucketDto.empty(null) : ambiguous;
    }

    public static SqlRouteAttributionDto unavailable(String reason) {
        return new SqlRouteAttributionDto(
                false,
                reason,
                List.of(),
                0,
                List.of(),
                false,
                0,
                0,
                SqlAttributionBucketDto.empty(null),
                SqlAttributionBucketDto.empty(null),
                List.of());
    }
}
