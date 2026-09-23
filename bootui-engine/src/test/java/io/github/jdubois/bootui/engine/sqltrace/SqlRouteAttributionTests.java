package io.github.jdubois.bootui.engine.sqltrace;

import static io.github.jdubois.bootui.engine.sqltrace.SqlTraceEntryFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SqlRouteAttributionDto;
import io.github.jdubois.bootui.core.dto.SqlRouteRankingDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlRouteAttribution.Correlation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlRouteAttributionTests {

    private static final Set<Correlation> SERVLET =
            Set.of(Correlation.TRACE_ID, Correlation.SERVING_THREAD, Correlation.TIME_WINDOW);
    private static final Set<Correlation> REACTIVE = Set.of(Correlation.TRACE_ID, Correlation.TIME_WINDOW);

    @Test
    void prefersTraceIdCorrelationAndGroupsByRouteTemplate() {
        SqlRequestEvidence request = request("r1", "GET", "/api/orders/4711", "/api/orders/{id}", "trace-a", 100, 200);

        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select * from orders where id = 4711")
                        .at(150)
                        .lasting(20)
                        .withTrace("trace-a")
                        .build()),
                List.of(request),
                SERVLET,
                RouteTemplateResolver.empty(),
                retainedMicros(20));

        assertThat(attribution.routes()).hasSize(1);
        SqlRouteRankingDto route = attribution.routes().get(0);
        assertThat(route.route()).isEqualTo("/api/orders/{id}");
        assertThat(route.routeSource()).isEqualTo("ROUTE_TEMPLATE");
        assertThat(route.traceCorrelated()).isEqualTo(1);
        assertThat(route.threadCorrelated()).isZero();
        assertThat(attribution.attributedExecutions()).isEqualTo(1);
        assertThat(attribution.unattributed().executions()).isZero();
    }

    @Test
    void correlatesByTraceEvenWhenTheStatementRanOutsideTheRequestWindow() {
        SqlRequestEvidence request = request("r1", "GET", "/api/orders", "/api/orders", "trace-a", 100, 200);

        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select 1").at(9_000).withTrace("trace-a").build()),
                List.of(request),
                SERVLET,
                RouteTemplateResolver.empty(),
                retainedMicros(10));

        assertThat(attribution.routes()).hasSize(1);
        assertThat(attribution.routes().get(0).traceCorrelated()).isEqualTo(1);
    }

    @Test
    void treatsATraceSharedByTwoCapturedRequestsAsAmbiguousRatherThanPickingOne() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select 1").at(150).lasting(5).withTrace("shared").build()),
                List.of(
                        request("r1", "GET", "/a", "/a", "shared", 100, 200),
                        request("r2", "GET", "/b", "/b", "shared", 100, 200)),
                SERVLET,
                RouteTemplateResolver.empty(),
                retainedMicros(5));

        assertThat(attribution.routes()).isEmpty();
        assertThat(attribution.ambiguous().executions()).isEqualTo(1);
        assertThat(attribution.ambiguous().reason()).isNotBlank();
    }

    @Test
    void fallsBackToTheServingThreadWhenNoTraceIdIsAvailable() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select * from orders")
                        .at(150)
                        .lasting(30)
                        .onThread("exec-1")
                        .build()),
                List.of(
                        requestOnThread("r1", "GET", "/api/orders", "/api/orders", 100, 200, "exec-1"),
                        requestOnThread("r2", "GET", "/api/users", "/api/users", 100, 200, "exec-2")),
                SERVLET,
                RouteTemplateResolver.empty(),
                retainedMicros(30));

        assertThat(attribution.routes()).hasSize(1);
        assertThat(attribution.routes().get(0).route()).isEqualTo("/api/orders");
        assertThat(attribution.routes().get(0).threadCorrelated()).isEqualTo(1);
    }

    @Test
    void doesNotUseTheServingThreadOnARuntimeThatCannotGuaranteeThreadAffinity() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select * from orders").at(150).onThread("loop-1").build()),
                List.of(
                        request("r1", "GET", "/api/orders", "/api/orders", null, 100, 200),
                        request("r2", "GET", "/api/users", "/api/users", null, 100, 200)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(10));

        assertThat(attribution.supportedCorrelations()).doesNotContain("SERVING_THREAD");
        assertThat(attribution.routes()).isEmpty();
        assertThat(attribution.ambiguous().executions()).isEqualTo(1);
    }

    @Test
    void attributesByTimeWindowOnlyWhenExactlyOneRequestWasInFlight() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select * from orders").at(150).lasting(15).build()),
                List.of(request("r1", "GET", "/api/orders", "/api/orders", null, 100, 200)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(15));

        assertThat(attribution.routes()).hasSize(1);
        assertThat(attribution.routes().get(0).timeWindowCorrelated()).isEqualTo(1);
    }

    @Test
    void reportsOverlappingConcurrentRequestsAsAmbiguousInsteadOfGuessing() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select * from orders").at(150).lasting(15).build()),
                List.of(
                        request("r1", "GET", "/api/orders", "/api/orders", null, 100, 200),
                        request("r2", "GET", "/api/orders", "/api/orders", null, 120, 210)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(15));

        assertThat(attribution.routes()).isEmpty();
        assertThat(attribution.ambiguous().executions()).isEqualTo(1);
        assertThat(attribution.ambiguous().shareOfRetainedTimePercent()).isEqualTo(100.0);
    }

    @Test
    void keepsBackgroundWorkInAnExplicitUnattributedBucket() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("update job set state = 'DONE'")
                        .at(50_000)
                        .lasting(40)
                        .category("UPDATE")
                        .build()),
                List.of(request("r1", "GET", "/api/orders", "/api/orders", null, 100, 200)),
                SERVLET,
                RouteTemplateResolver.empty(),
                retainedMicros(40));

        assertThat(attribution.routes()).isEmpty();
        assertThat(attribution.unattributed().executions()).isEqualTo(1);
        assertThat(attribution.unattributed().totalDurationMillis()).isEqualTo(40);
        assertThat(attribution.unattributed().reason()).contains("Background");
    }

    @Test
    void fallsBackToAMaskedPathWhenNoRouteTemplateIsAvailable() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select * from orders").at(150).withTrace("t1").build()),
                List.of(request("r1", "GET", "/api/orders/4711", null, "t1", 100, 200)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(10));

        SqlRouteRankingDto route = attribution.routes().get(0);
        assertThat(route.route()).isEqualTo("/api/orders/{value}");
        assertThat(route.routeSource()).isEqualTo("MASKED_PATH");
    }

    @Test
    void neverGroupsByAQueryStringOrPathParameterValue() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(
                        entry("select 1").at(150).withTrace("t1").build(),
                        entry("select 1").at(350).withTrace("t2").build()),
                List.of(
                        request("r1", "GET", "/api/orders/4711?token=secret", null, "t1", 100, 200),
                        request("r2", "GET", "/api/orders/4712?token=other", null, "t2", 300, 400)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(20));

        assertThat(attribution.routes()).hasSize(1);
        assertThat(attribution.routes().get(0).route()).isEqualTo("/api/orders/{value}");
        assertThat(attribution.routes().get(0).requests()).isEqualTo(2);
    }

    @Test
    void separatesRoutesByHttpMethod() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(
                        entry("select 1").at(150).withTrace("t1").build(),
                        entry("insert into t values (1)")
                                .at(350)
                                .category("INSERT")
                                .withTrace("t2")
                                .build()),
                List.of(
                        request("r1", "GET", "/api/orders", "/api/orders", "t1", 100, 200),
                        request("r2", "POST", "/api/orders", "/api/orders", "t2", 300, 400)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(20));

        assertThat(attribution.routes())
                .extracting(SqlRouteRankingDto::method)
                .containsExactlyInAnyOrder("GET", "POST");
    }

    @Test
    void surfacesAnNPlusOneAsOneRouteWithManyExecutionsOfOneStatement() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        entries.add(entry("select * from orders where id = 1")
                .at(110)
                .lasting(5)
                .withTrace("t1")
                .build());
        for (int i = 0; i < 30; i++) {
            entries.add(entry("select * from line_item where order_id = " + i)
                    .at(120 + i)
                    .lasting(2)
                    .withTrace("t1")
                    .build());
        }

        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                entries,
                List.of(request("r1", "GET", "/api/orders/1", "/api/orders/{id}", "t1", 100, 200)),
                SERVLET,
                RouteTemplateResolver.empty(),
                retainedMicros(65));

        SqlRouteRankingDto route = attribution.routes().get(0);
        assertThat(route.executions()).isEqualTo(31);
        assertThat(route.distinctStatements()).isEqualTo(2);
        assertThat(route.topStatements()).hasSize(2);
        assertThat(route.topStatements().get(0).executions()).isEqualTo(30);
        assertThat(route.topStatements().get(0).sql()).isEqualTo("select * from line_item where order_id = ?");
    }

    @Test
    void boundsRoutesAndPerRouteStatementsUnderHighCardinality() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        List<SqlRequestEvidence> requests = new ArrayList<>();
        for (int route = 0; route < 60; route++) {
            String trace = "trace-" + route;
            requests.add(request("r" + route, "GET", "/api/thing" + route, "/api/thing" + route, trace, 0, 10_000));
            for (int statement = 0; statement < 12; statement++) {
                entries.add(entry("select col" + statement + " from t" + statement)
                        .at(100)
                        .lasting(route + 1)
                        .withTrace(trace)
                        .build());
            }
        }

        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                entries, requests, SERVLET, RouteTemplateResolver.empty(), retainedMicros(100_000));

        assertThat(attribution.routes()).hasSize(SqlRouteAttribution.MAX_ROUTES);
        assertThat(attribution.routesTruncated()).isTrue();
        assertThat(attribution.distinctRoutes()).isEqualTo(60);
        assertThat(attribution.routes())
                .allSatisfy(route -> assertThat(route.topStatements())
                        .hasSizeLessThanOrEqualTo(SqlRouteAttribution.MAX_STATEMENTS_PER_ROUTE));
        assertThat(attribution.routes().get(0).topStatementsTruncated()).isTrue();
    }

    @Test
    void countsErrorsPerRoute() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(
                        entry("select 1").at(150).withTrace("t1").build(),
                        entry("select 1").at(160).withTrace("t1").failed().build()),
                List.of(request("r1", "GET", "/api/orders", "/api/orders", "t1", 100, 200)),
                SERVLET,
                RouteTemplateResolver.empty(),
                retainedMicros(20));

        assertThat(attribution.routes().get(0).errorCount()).isEqualTo(1);
    }

    @Test
    void reportsEveryExecutionExactlyOnceAcrossRoutesAndBuckets() {
        List<SqlTraceEntryDto> entries = List.of(
                entry("select 1").at(150).withTrace("t1").lasting(10).build(),
                entry("select 2").at(150).lasting(10).build(),
                entry("select 3").at(90_000).lasting(10).build());

        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                entries,
                List.of(
                        request("r1", "GET", "/a", "/a", "t1", 100, 200),
                        request("r2", "GET", "/b", "/b", null, 100, 200)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(30));

        long inRoutes = attribution.routes().stream()
                .mapToLong(SqlRouteRankingDto::executions)
                .sum();
        assertThat(inRoutes
                        + attribution.unattributed().executions()
                        + attribution.ambiguous().executions())
                .isEqualTo(entries.size());
    }

    @Test
    void explainsThatNoRequestsWereAvailableRatherThanShowingAnEmptyRanking() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select 1").build()),
                List.of(),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(10));

        assertThat(attribution.available()).isTrue();
        assertThat(attribution.requestsConsidered()).isZero();
        assertThat(attribution.unattributed().executions()).isEqualTo(1);
        assertThat(attribution.notes()).anySatisfy(note -> assertThat(note).contains("No captured HTTP requests"));
    }

    @Test
    void tellsTheUserWhenNoStatementCarriedATraceId() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select 1").at(150).build()),
                List.of(request("r1", "GET", "/a", "/a", null, 100, 200)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(10));

        assertThat(attribution.notes()).anySatisfy(note -> assertThat(note).contains("trace id"));
    }

    @Test
    void refusesToHandAStatementWithAnUnknownTraceIdToAnOverlappingRequest() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select 1")
                        .at(150)
                        .lasting(5)
                        .onThread("http-1")
                        .withTrace("gone")
                        .build()),
                List.of(new SqlRequestEvidence(
                        "r1", "GET", "/api/orders", "/api/orders", "other-trace", 100, 200, "http-1")),
                SERVLET,
                RouteTemplateResolver.empty(),
                retainedMicros(5));

        assertThat(attribution.routes()).isEmpty();
        assertThat(attribution.attributedExecutions()).isZero();
        assertThat(attribution.unattributed().executions()).isEqualTo(1);
        assertThat(attribution.ambiguous().executions()).isZero();
    }

    @Test
    void doesNotAttributeAStatementThatWasAlreadyRunningBeforeTheRequestStarted() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select slow").at(1_000).lasting(3_000).build()),
                List.of(request("r1", "GET", "/api/orders", "/api/orders", null, 900, 1_100)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(3_000));

        assertThat(attribution.routes()).isEmpty();
        assertThat(attribution.unattributed().executions()).isEqualTo(1);
        assertThat(attribution.unattributed().totalDurationMillis()).isEqualTo(3_000);
    }

    @Test
    void attributesAStatementThatFitsEntirelyInsideTheRequestWindow() {
        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select fast").at(1_050).lasting(30).build()),
                List.of(request("r1", "GET", "/api/orders", "/api/orders", null, 900, 1_100)),
                REACTIVE,
                RouteTemplateResolver.empty(),
                retainedMicros(30));

        assertThat(attribution.routes()).hasSize(1);
        assertThat(attribution.routes().get(0).timeWindowCorrelated()).isEqualTo(1);
    }

    @Test
    void labelsARequestWithADeclaredRouteTemplateWhenTheCapturePointHasNone() {
        RouteTemplateResolver templates = RouteTemplateResolver.of(List.of(
                new io.github.jdubois.bootui.core.dto.MappingDto(
                        "GET", "/api/users/{name}", "UserResource#get", null, null),
                new io.github.jdubois.bootui.core.dto.MappingDto(
                        "GET", "/api/users", "UserResource#list", null, null)));

        SqlRouteAttributionDto attribution = SqlRouteAttribution.attribute(
                List.of(entry("select 1").at(150).lasting(5).build()),
                List.of(request("r1", "GET", "/api/users/alice", null, null, 100, 200)),
                REACTIVE,
                templates,
                5);

        assertThat(attribution.routes()).hasSize(1);
        assertThat(attribution.routes().get(0).route()).isEqualTo("/api/users/{name}");
        assertThat(attribution.routes().get(0).routeSource()).isEqualTo("ROUTE_TEMPLATE");
        assertThat(attribution.routes().get(0).route()).doesNotContain("alice");
    }

    private static SqlRequestEvidence request(
            String id, String method, String path, String template, String traceId, long start, long end) {
        return new SqlRequestEvidence(id, method, path, template, traceId, start, end, null);
    }

    private static SqlRequestEvidence requestOnThread(
            String id, String method, String path, String template, long start, long end, String thread) {
        return new SqlRequestEvidence(id, method, path, template, null, start, end, thread);
    }

    /**
     * The retained-window denominator in microseconds, the unit attribution shares are computed in, written
     * from the milliseconds the fixtures express so each test still reads in the unit it sets up.
     */
    private static long retainedMicros(long millis) {
        return millis * 1_000L;
    }
}
