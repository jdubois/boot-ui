package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RequestLatencyKpisTests {

    private static final Instant BASE = Instant.parse("2026-09-30T08:00:00Z");

    @Test
    void noTimedRequestMeansNoKpi() {
        assertThat(RequestLatencyKpis.of(null)).isEqualTo(RequestLatencyKpis.empty());
        assertThat(RequestLatencyKpis.of(List.of())).isEqualTo(RequestLatencyKpis.empty());
        assertThat(RequestLatencyKpis.of(List.of(exchange("a", 0, "GET", "/a", null, "/a", "MASKED_PATH"))))
                .isEqualTo(RequestLatencyKpis.empty());
    }

    @Test
    void labelsTheSlowestRequestWithItsRouteAndSummaryRowId() {
        RequestLatencyKpis kpis = RequestLatencyKpis.of(List.of(
                exchange("a", 3, "GET", "/api/orders/42", 900L, "/api/orders/{id}", "FRAMEWORK_TEMPLATE"),
                exchange("b", 2, "GET", "/api/health", 5L, "/api/health", "DECLARED_MAPPING"),
                exchange("c", 1, "POST", "/uploads/1", null, "/uploads/{value}", "MASKED_PATH")));

        assertThat(kpis.sampleCount()).isEqualTo(2);
        assertThat(kpis.p50Ms()).isEqualTo(5L);
        assertThat(kpis.p95Ms()).isEqualTo(900L);
        assertThat(kpis.slowestMs()).isEqualTo(900L);
        assertThat(kpis.slowestPath()).isEqualTo("/api/orders/42");
        assertThat(kpis.slowestRoute()).isEqualTo("/api/orders/{id}");
        assertThat(kpis.slowestRouteId()).isEqualTo("GET /api/orders/{id}");
        assertThat(kpis.slowestRouteSource()).isEqualTo("FRAMEWORK_TEMPLATE");
    }

    @Test
    void aZeroMillisecondSlowestRequestIsStillReported() {
        RequestLatencyKpis kpis =
                RequestLatencyKpis.of(List.of(exchange("a", 0, "GET", "/a", 0L, "/a", "MASKED_PATH")));

        assertThat(kpis.slowestMs()).isZero();
        assertThat(kpis.slowestPath()).isEqualTo("/a");
        assertThat(kpis.p50Ms()).isZero();
    }

    @Test
    void tiesGoToTheNewestThenTheLowestIdWhateverTheInputOrder() {
        List<HttpExchangeDto> exchanges = new ArrayList<>(List.of(
                exchange("old", 1, "GET", "/old", 50L, "/old", "MASKED_PATH"),
                exchange("new-b", 9, "GET", "/new-b", 50L, "/new-b", "MASKED_PATH"),
                exchange("new-a", 9, "GET", "/new-a", 50L, "/new-a", "MASKED_PATH"),
                exchange("fast", 10, "GET", "/fast", 1L, "/fast", "MASKED_PATH")));

        for (int seed = 0; seed < 10; seed++) {
            Collections.shuffle(exchanges, new Random(seed));
            assertThat(RequestLatencyKpis.of(exchanges).slowestPath()).isEqualTo("/new-a");
        }
    }

    @Test
    void anExchangeFromAnOlderServerCarriesNoRoute() {
        RequestLatencyKpis kpis = RequestLatencyKpis.of(List.of(exchange("a", 0, "GET", "/a", 3L, null, null)));

        assertThat(kpis.slowestPath()).isEqualTo("/a");
        assertThat(kpis.slowestRoute()).isNull();
        assertThat(kpis.slowestRouteId()).isNull();
        assertThat(kpis.slowestRouteSource()).isNull();
    }

    static HttpExchangeDto exchange(
            String id, int second, String method, String path, Long durationMs, String route, String routeSource) {
        return new HttpExchangeDto(
                id,
                BASE.plusSeconds(second),
                method,
                path,
                null,
                path,
                200,
                "2xx",
                durationMs,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                route,
                routeSource);
    }
}
