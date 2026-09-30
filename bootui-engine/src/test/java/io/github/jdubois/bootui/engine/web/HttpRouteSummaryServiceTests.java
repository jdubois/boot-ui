package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HttpRouteDto;
import io.github.jdubois.bootui.core.dto.HttpRoutesReport;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HttpRouteSummaryServiceTests {

    private static final Instant BASE = Instant.parse("2026-09-30T08:00:00Z");

    private static final RouteTemplateResolver DECLARED = RouteTemplateResolver.of(List.of(
            new MappingDto("GET", "/api/orders/{id}", "handler", null, null),
            new MappingDto("GET", "/api/items/{a}", "handler", null, null),
            new MappingDto("GET", "/api/items/{b}", "handler", null, null)));

    private static final HttpExchangesService.BootUiSelfPath SELF = uri -> uri.contains("/bootui/");

    private final HttpRouteSummaryService service = new HttpRouteSummaryService();

    @Test
    void groupsByFrameworkTemplateDeclaredMappingAndMaskedPathAndReportsTheSource() {
        List<CapturedHttpExchange> exchanges = List.of(
                exchange(0, "GET", "/api/customers/7", 200, 10L, "/api/customers/{customerId}"),
                exchange(1, "GET", "/api/customers/8", 200, 30L, "/api/customers/{customerId}"),
                exchange(2, "GET", "/api/orders/42", 200, 20L, null),
                exchange(3, "GET", "/api/orders/43", 404, 5L, null),
                exchange(4, "GET", "/api/items/sku-9", 200, 8L, null),
                exchange(5, "POST", "/uploads/3f2b8c1e-0a4d-4e8b-9c55-1d2e3f4a5b6c", 201, 12L, null));

        HttpRoutesReport report = summarize(exchanges);

        Map<String, HttpRouteDto> routes = byId(report);
        assertThat(routes)
                .containsOnlyKeys(
                        "GET /api/customers/{customerId}",
                        "GET /api/orders/{id}",
                        "GET /api/items/{value}",
                        "POST /uploads/{value}");
        assertThat(routes.get("GET /api/customers/{customerId}").routeSource()).isEqualTo("FRAMEWORK_TEMPLATE");
        assertThat(routes.get("GET /api/orders/{id}").routeSource()).isEqualTo("DECLARED_MAPPING");
        // Two declarations match equally well, so no template is chosen and the path is masked instead.
        assertThat(routes.get("GET /api/items/{value}").routeSource()).isEqualTo("MASKED_PATH");
        assertThat(routes.get("POST /uploads/{value}").routeSource()).isEqualTo("MASKED_PATH");
        assertThat(report.routes())
                .allSatisfy(route -> assertThat(route.id())
                        .doesNotContain("42")
                        .doesNotContain("sku-9")
                        .doesNotContain("3f2b8c1e")
                        .doesNotContain("?"));
        assertThat(report.distinctRoutes()).isEqualTo(4);
        assertThat(report.routesTruncated()).isFalse();
    }

    @Test
    void aRouteSeenThroughSeveralTiersReportsTheStrongest() {
        HttpRoutesReport report = summarize(List.of(
                exchange(0, "GET", "/api/orders/42", 200, 10L, null),
                exchange(1, "GET", "/api/orders/43", 200, 10L, "/api/orders/{id}")));

        assertThat(report.routes()).singleElement().satisfies(route -> {
            assertThat(route.requests()).isEqualTo(2);
            assertThat(route.routeSource()).isEqualTo("FRAMEWORK_TEMPLATE");
        });
    }

    @Test
    void countsStatusClassesSoTheyReconcileWithRequests() {
        HttpRoutesReport report = summarize(List.of(
                exchange(0, "GET", "/health", 200, 1L, null),
                exchange(1, "GET", "/health", 204, 1L, null),
                exchange(2, "GET", "/health", 302, 1L, null),
                exchange(3, "GET", "/health", 404, 1L, null),
                exchange(4, "GET", "/health", 503, 1L, null),
                exchange(5, "GET", "/health", 101, 1L, null),
                exchange(6, "GET", "/health", 0, 1L, null)));

        HttpRouteDto route = report.routes().get(0);
        assertThat(route.requests()).isEqualTo(7);
        assertThat(route.status2xx()).isEqualTo(2);
        assertThat(route.status3xx()).isEqualTo(1);
        assertThat(route.status4xx()).isEqualTo(1);
        assertThat(route.status5xx()).isEqualTo(1);
        assertThat(route.statusOther()).isEqualTo(2);
        assertThat(route.errorCount()).isEqualTo(2);
        assertThat(route.status2xx() + route.status3xx() + route.status4xx() + route.status5xx() + route.statusOther())
                .isEqualTo(route.requests());
    }

    @Test
    void computesExactDurationsPercentilesAndSharesThatReconcileWithTheWindow() {
        List<CapturedHttpExchange> exchanges = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            exchanges.add(exchange(i, "GET", "/api/orders/" + i, 200, (long) i, "/api/orders/{id}"));
        }
        exchanges.add(exchange(200, "GET", "/api/health", 200, 50L, "/api/health"));
        exchanges.add(exchange(201, "GET", "/api/health", 200, 150L, "/api/health"));

        HttpRoutesReport report = summarize(exchanges);

        HttpRouteDto orders = byId(report).get("GET /api/orders/{id}");
        assertThat(orders.requests()).isEqualTo(100);
        assertThat(orders.timedRequests()).isEqualTo(100);
        assertThat(orders.totalDurationMs()).isEqualTo(5050);
        assertThat(orders.avgDurationMs()).isEqualTo(50.5);
        assertThat(orders.p50DurationMs()).isEqualTo(50L);
        assertThat(orders.p95DurationMs()).isEqualTo(95L);
        assertThat(orders.p99DurationMs()).isEqualTo(99L);
        assertThat(orders.maxDurationMs()).isEqualTo(100L);
        assertThat(orders.shareOfRetainedTimePercent()).isEqualTo(96.19);

        HttpRouteDto health = byId(report).get("GET /api/health");
        assertThat(health.p50DurationMs()).isEqualTo(50L);
        assertThat(health.p95DurationMs()).isEqualTo(150L);
        assertThat(health.shareOfRetainedTimePercent()).isEqualTo(3.81);

        assertThat(report.window().summarizedExchanges()).isEqualTo(102);
        assertThat(report.window().totalDurationMs()).isEqualTo(5250);
        assertThat(report.routes().stream().mapToLong(HttpRouteDto::requests).sum())
                .isEqualTo(report.window().summarizedExchanges());
        assertThat(report.routes().stream()
                        .mapToLong(HttpRouteDto::totalDurationMs)
                        .sum())
                .isEqualTo(report.window().totalDurationMs());
        assertThat(report.window().oldestTimestamp())
                .isEqualTo(BASE.plusMillis(1).toEpochMilli());
        assertThat(report.window().newestTimestamp())
                .isEqualTo(BASE.plusMillis(201).toEpochMilli());
    }

    @Test
    void untimedExchangesCountAsRequestsButNotAsTimings() {
        HttpRoutesReport report = summarize(List.of(
                exchange(0, "GET", "/stream", 200, null, null), exchange(1, "GET", "/stream", 200, null, null)));

        HttpRouteDto route = report.routes().get(0);
        assertThat(route.requests()).isEqualTo(2);
        assertThat(route.timedRequests()).isZero();
        assertThat(route.avgDurationMs()).isNull();
        assertThat(route.p50DurationMs()).isNull();
        assertThat(route.p95DurationMs()).isNull();
        assertThat(route.p99DurationMs()).isNull();
        assertThat(route.maxDurationMs()).isNull();
        assertThat(route.shareOfRetainedTimePercent()).isZero();
        assertThat(route.topFor()).containsExactly("REQUESTS");
        assertThat(report.window().timedExchanges()).isZero();
        assertThat(report.notes()).anyMatch(note -> note.contains("2 exchanges carry no duration"));
    }

    @Test
    void criteriaOnlyAdmitRoutesScoringAboveZero() {
        HttpRoutesReport report = summarize(List.of(
                exchange(0, "GET", "/ok", 200, 5L, null),
                exchange(1, "GET", "/fails", 500, 0L, null),
                exchange(2, "GET", "/fails", 404, 0L, null)));

        Map<String, HttpRouteDto> routes = byId(report);
        assertThat(routes.get("GET /ok").topFor())
                .containsExactly("REQUESTS", "TOTAL_DURATION", "P95_DURATION", "MAX_DURATION");
        assertThat(routes.get("GET /fails").topFor()).containsExactly("REQUESTS", "ERROR_COUNT");
    }

    @Test
    void tiesBreakOnRouteIdSoTheResultNeverDependsOnBufferOrder() {
        List<CapturedHttpExchange> exchanges = new ArrayList<>();
        for (String word : List.of("delta", "alpha", "charlie", "bravo")) {
            exchanges.add(exchange(exchanges.size(), "GET", "/" + word, 200, 10L, null));
            exchanges.add(exchange(exchanges.size(), "GET", "/" + word, 500, 30L, null));
        }

        HttpRoutesReport ordered = summarize(exchanges);
        List<CapturedHttpExchange> shuffled = new ArrayList<>(exchanges);
        Collections.shuffle(shuffled, new Random(42));
        HttpRoutesReport reshuffled = summarize(shuffled);

        assertThat(ordered.routes())
                .extracting(HttpRouteDto::id)
                .containsExactly("GET /alpha", "GET /bravo", "GET /charlie", "GET /delta");
        assertThat(reshuffled).isEqualTo(ordered);
    }

    @Test
    void highCardinalityIsBoundedPerCriterionWithAVisibleTruncationCount() {
        List<CapturedHttpExchange> exchanges = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            String word = "route" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
            // Descending durations make the slowest routes different from the most requested ones.
            for (int n = 0; n <= i % 3; n++) {
                exchanges.add(exchange(exchanges.size(), "GET", "/" + word, 200, (long) (150 - i), null));
            }
        }

        HttpRoutesReport report =
                service.summarize(exchanges, SELF, DECLARED, HttpRouteSummaryService.ExchangeSource.unknown(), 10);

        assertThat(report.topPerCriterion()).isEqualTo(10);
        assertThat(report.distinctRoutes()).isEqualTo(150);
        assertThat(report.routesTruncated()).isTrue();
        assertThat(report.routes()).hasSizeLessThanOrEqualTo(10 * HttpRouteSummaryService.Criterion.values().length);
        assertThat(report.routes()).hasSizeGreaterThan(10);
        assertThat(report.notes()).anyMatch(note -> note.contains("150 distinct routes"));
        for (HttpRouteSummaryService.Criterion criterion : HttpRouteSummaryService.Criterion.values()) {
            assertThat(report.routes().stream().filter(route -> route.topFor().contains(criterion.name())))
                    .as("routes selected for %s", criterion)
                    .hasSizeLessThanOrEqualTo(10);
        }
    }

    @Test
    void limitIsDefaultedAndCapped() {
        assertThat(HttpRouteSummaryService.topPerCriterion(null))
                .isEqualTo(HttpRouteSummaryService.DEFAULT_TOP_PER_CRITERION);
        assertThat(HttpRouteSummaryService.topPerCriterion(0))
                .isEqualTo(HttpRouteSummaryService.DEFAULT_TOP_PER_CRITERION);
        assertThat(HttpRouteSummaryService.topPerCriterion(5)).isEqualTo(5);
        assertThat(HttpRouteSummaryService.topPerCriterion(10_000))
                .isEqualTo(HttpRouteSummaryService.MAX_TOP_PER_CRITERION);
    }

    @Test
    void bootUiTrafficIsHiddenFromTheSummaryButCountedInTheWindow() {
        HttpRoutesReport report = summarize(List.of(
                exchange(0, "GET", "/api/orders/1", 200, 10L, "/api/orders/{id}"),
                exchange(1, "GET", "/bootui/api/http-exchanges", 200, 900L, null),
                exchange(2, "GET", "/bootui/api/activity", 200, 900L, null)));

        assertThat(report.routes()).extracting(HttpRouteDto::id).containsExactly("GET /api/orders/{id}");
        assertThat(report.window().retainedExchanges()).isEqualTo(3);
        assertThat(report.window().hiddenSelfExchanges()).isEqualTo(2);
        assertThat(report.window().summarizedExchanges()).isEqualTo(1);
        assertThat(report.window().totalDurationMs()).isEqualTo(10);
        assertThat(report.routes().get(0).shareOfRetainedTimePercent()).isEqualTo(100.0);
    }

    @Test
    void selfTrafficIsSummarizedWhenExcludeSelfIsOff() {
        HttpRoutesReport report = service.summarize(
                List.of(exchange(0, "GET", "/bootui/api/activity", 200, 9L, null)),
                uri -> false,
                DECLARED,
                HttpRouteSummaryService.ExchangeSource.unknown(),
                null);

        assertThat(report.window().hiddenSelfExchanges()).isZero();
        assertThat(report.routes()).extracting(HttpRouteDto::route).containsExactly("/bootui/api/activity");
    }

    @Test
    void summarizesOnlyWhatAFullBufferStillRetainsAndStatesTheWindow() {
        HttpExchangeBuffer buffer = new HttpExchangeBuffer(3);
        for (int i = 0; i < 5; i++) {
            buffer.record(exchange(i, "GET", "/evicted-" + (i < 2 ? "old" : "new"), 200, 10L, null));
        }

        HttpRoutesReport report = service.summarize(
                buffer.snapshot(),
                SELF,
                DECLARED,
                new HttpRouteSummaryService.ExchangeSource(buffer.capacity(), 2L),
                null);

        assertThat(report.routes()).extracting(HttpRouteDto::id).containsExactly("GET /evicted-new");
        assertThat(report.routes().get(0).requests()).isEqualTo(3);
        assertThat(report.window().retainedExchanges()).isEqualTo(3);
        assertThat(report.window().bufferSize()).isEqualTo(3);
        assertThat(report.window().evicted()).isEqualTo(2L);
        assertThat(report.window().oldestTimestamp())
                .isEqualTo(BASE.plusMillis(2).toEpochMilli());
        assertThat(report.notes()).anyMatch(note -> note.contains("buffer is full"));
        assertThat(report.notes()).noneMatch(note -> note.contains("does not count evictions"));
    }

    @Test
    void anUnknownExchangeSourceSaysSo() {
        HttpRoutesReport report = summarize(List.of(exchange(0, "GET", "/a", 200, 1L, null)));

        assertThat(report.window().bufferSize()).isNull();
        assertThat(report.window().evicted()).isNull();
        assertThat(report.notes()).anyMatch(note -> note.contains("does not count evictions"));
        assertThat(report.notes()).noneMatch(note -> note.contains("buffer is full"));
    }

    @Test
    void anEmptyWindowIsAvailableAndEmpty() {
        HttpRoutesReport report = service.summarize(List.of(), SELF, RouteTemplateResolver.empty(), null, null);

        assertThat(report.available()).isTrue();
        assertThat(report.routes()).isEmpty();
        assertThat(report.distinctRoutes()).isZero();
        assertThat(report.window().summarizedExchanges()).isZero();
        assertThat(report.window().oldestTimestamp()).isNull();
        assertThat(report.notes()).anyMatch(note -> note.contains("No declared route mappings"));
    }

    @Test
    void routesMatchTheRouteLabelsOfTheExchangeList() {
        List<CapturedHttpExchange> exchanges = List.of(
                exchange(0, "GET", "/api/orders/1", 200, 10L, null),
                exchange(1, "GET", "/api/items/x", 200, 10L, null),
                exchange(2, "GET", "/bootui/api/activity", 200, 10L, null));

        HttpRoutesReport routes = summarize(exchanges);
        var list = new HttpExchangesService()
                .report(
                        exchanges,
                        SELF,
                        true,
                        io.github.jdubois.bootui.core.ValueExposure.MASKED,
                        DECLARED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        for (HttpRouteDto route : routes.routes()) {
            var filtered = new HttpExchangesService()
                    .report(
                            exchanges,
                            SELF,
                            true,
                            io.github.jdubois.bootui.core.ValueExposure.MASKED,
                            DECLARED,
                            null,
                            null,
                            null,
                            route.id(),
                            null,
                            null);
            assertThat(filtered.page().matched()).as(route.id()).isEqualTo((int) route.requests());
        }
        assertThat(list.exchanges())
                .extracting(dto -> dto.method() + " " + dto.route())
                .containsExactlyInAnyOrderElementsOf(
                        routes.routes().stream().map(HttpRouteDto::id).toList());
    }

    private HttpRoutesReport summarize(List<CapturedHttpExchange> exchanges) {
        return service.summarize(exchanges, SELF, DECLARED, HttpRouteSummaryService.ExchangeSource.unknown(), null);
    }

    private static Map<String, HttpRouteDto> byId(HttpRoutesReport report) {
        return report.routes().stream().collect(Collectors.toMap(HttpRouteDto::id, Function.identity()));
    }

    static CapturedHttpExchange exchange(
            int ordinal, String method, String path, int status, Long durationMs, String routeTemplate) {
        return new CapturedHttpExchange(
                BASE.plusMillis(ordinal),
                method,
                URI.create("http://localhost:8080" + path),
                status,
                durationMs,
                "127.0.0.1",
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                routeTemplate);
    }
}
