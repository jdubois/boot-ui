package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.support.Percentiles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Live Activity request-latency KPIs — p50, p95 and the slowest retained request with its route —
 * computed once, here, for Spring MVC, Spring WebFlux and Quarkus alike.
 *
 * <p>Every figure is over the retained, visible exchanges that carry a duration, which {@link #sampleCount()}
 * states so the strip can say how much evidence stands behind it. The slowest request is the one with the
 * longest duration; a tie goes to the newest, then to the lowest exchange id, so the pick never depends on
 * buffer order. Its route is the one the HTTP Exchanges route summary groups it under, and
 * {@link #slowestRouteId()} is that summary row's id, so the KPI can link straight to it.</p>
 *
 * @param sampleCount exchanges with a recorded duration
 * @param p50Ms median duration, or {@code null} when no exchange is timed
 * @param p95Ms 95th percentile duration, or {@code null} when no exchange is timed
 * @param slowestPath path of the slowest exchange, or {@code null}
 * @param slowestMs duration of the slowest exchange, or {@code null}
 * @param slowestRoute resolved route of the slowest exchange, or {@code null} when it carries none
 * @param slowestRouteId route-summary row id of the slowest exchange, or {@code null}
 * @param slowestRouteSource how {@link #slowestRoute()} was resolved, or {@code null}
 */
public record RequestLatencyKpis(
        int sampleCount,
        Long p50Ms,
        Long p95Ms,
        String slowestPath,
        Long slowestMs,
        String slowestRoute,
        String slowestRouteId,
        String slowestRouteSource) {

    private static final Comparator<HttpExchangeDto> SLOWEST_FIRST = Comparator.comparingLong(
                    (HttpExchangeDto exchange) -> exchange.durationMs())
            .reversed()
            .thenComparing(HttpExchangeDto::timestamp, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(HttpExchangeDto::id, Comparator.nullsLast(Comparator.<String>naturalOrder()));

    /** No timed request: every figure is {@code null}. */
    public static RequestLatencyKpis empty() {
        return new RequestLatencyKpis(0, null, null, null, null, null, null, null);
    }

    /** Computes the KPIs over {@code exchanges}, the retained, visible, already-masked exchanges. */
    public static RequestLatencyKpis of(List<HttpExchangeDto> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return empty();
        }
        List<Long> durations = new ArrayList<>(exchanges.size());
        HttpExchangeDto slowest = null;
        for (HttpExchangeDto exchange : exchanges) {
            if (exchange == null || exchange.durationMs() == null) {
                continue;
            }
            durations.add(exchange.durationMs());
            if (slowest == null || SLOWEST_FIRST.compare(exchange, slowest) < 0) {
                slowest = exchange;
            }
        }
        if (slowest == null) {
            return empty();
        }
        List<Long> sorted = Percentiles.sortedAscending(durations);
        String route = slowest.route();
        return new RequestLatencyKpis(
                sorted.size(),
                Percentiles.ofSorted(sorted, 50),
                Percentiles.ofSorted(sorted, 95),
                slowest.path(),
                slowest.durationMs(),
                route,
                route == null ? null : RouteLabel.idOf(slowest.method(), route),
                route == null ? null : slowest.routeSource());
    }
}
