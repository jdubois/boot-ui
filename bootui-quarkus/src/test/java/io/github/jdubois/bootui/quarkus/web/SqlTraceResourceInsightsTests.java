package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.SqlRouteRankingDto;
import io.github.jdubois.bootui.core.dto.SqlTraceInsightsReport;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import jakarta.enterprise.inject.Instance;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Quarkus serves the same rankings as the Spring bindings from the same engine services, but it is honest
 * about two gaps: it has no one-thread-per-request invariant, and RESTEasy Reactive gives it no reliable
 * JAX-RS route template. These tests pin both rather than letting the panel imply parity.
 */
class SqlTraceResourceInsightsTests {

    private static final Instant NOW = Instant.parse("2024-05-01T10:00:00Z");

    private SqlTraceRecorder recorder() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 100, 100, 2_000, 200, 5);
        recorder.registerDataSource("dataSource");
        return recorder;
    }

    private void record(SqlTraceRecorder recorder, String sql, String traceId) {
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace(traceId, null));
        recorder.record(
                StatementType.PREPARED,
                Category.SELECT,
                sql,
                List.of(),
                10,
                true,
                null,
                null,
                0,
                "conn-1",
                "vert.x-worker-1");
    }

    @SuppressWarnings("unchecked")
    private <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(value != null);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private CapturedHttpExchange exchange(String method, String uri, String traceId, long durationMs) {
        return new CapturedHttpExchange(
                NOW, method, URI.create(uri), 200, durationMs, "127.0.0.1", null, null, Map.of(), Map.of(), traceId);
    }

    private SqlTraceResource resource(SqlTraceRecorder recorder, HttpExchangeBuffer exchanges) {
        return resource(recorder, exchanges, null);
    }

    private SqlTraceResource resource(
            SqlTraceRecorder recorder, HttpExchangeBuffer exchanges, List<String> declaredPatterns) {
        io.github.jdubois.bootui.spi.MappingProvider mappings = null;
        if (declaredPatterns != null) {
            List<io.github.jdubois.bootui.core.dto.MappingDto> declared = new java.util.ArrayList<>();
            for (String pattern : declaredPatterns) {
                declared.add(new io.github.jdubois.bootui.core.dto.MappingDto("GET", pattern, "Resource", null, null));
            }
            mappings = mock(io.github.jdubois.bootui.spi.MappingProvider.class);
            when(mappings.available()).thenReturn(true);
            when(mappings.mappings()).thenReturn(declared);
        }
        return new SqlTraceResource(
                instance(recorder), instance(exchanges), instance(mappings), mock(QuarkusExposurePolicy.class));
    }

    @Test
    void groupsByADeclaredJaxRsTemplateWhenTheApplicationDeclaresOne() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from orders", "trace-a");
        HttpExchangeBuffer exchanges = new HttpExchangeBuffer(20);
        exchanges.record(exchange("GET", "http://localhost:8080/api/orders/8f3a-91", "trace-a", 25));

        SqlRouteRankingDto route = resource(recorder, exchanges, List.of("/api/orders/{id}", "/api/orders"))
                .insights()
                .attribution()
                .routes()
                .get(0);

        assertThat(route.routeSource()).isEqualTo("ROUTE_TEMPLATE");
        assertThat(route.route()).isEqualTo("/api/orders/{id}");
        assertThat(route.route()).doesNotContain("8f3a-91");
    }

    @Test
    void neverShowsAWordShapedPathParameterAsAFixedRouteSegment() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from users", "trace-a");
        HttpExchangeBuffer exchanges = new HttpExchangeBuffer(20);
        exchanges.record(exchange("GET", "http://localhost:8080/api/users/alice", "trace-a", 25));

        SqlRouteRankingDto route = resource(recorder, exchanges, List.of("/api/users/{name}"))
                .insights()
                .attribution()
                .routes()
                .get(0);

        assertThat(route.route()).isEqualTo("/api/users/{name}");
        assertThat(route.route()).doesNotContain("alice");
    }

    @Test
    void reportsUnavailableWhenSqlTracingIsNotConfigured() {
        SqlTraceInsightsReport report = resource(null, null).insights();

        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isEqualTo("SQL tracing is not configured");
    }

    @Test
    void attributesByTraceContextBecauseThereIsNoThreadAffinityToRelyOn() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from orders where id = 1", "trace-a");
        HttpExchangeBuffer exchanges = new HttpExchangeBuffer(20);
        exchanges.record(exchange("GET", "http://localhost:8080/api/orders/42", "trace-a", 25));

        SqlTraceInsightsReport report = resource(recorder, exchanges).insights();

        assertThat(report.attribution().supportedCorrelations()).containsExactly("TIME_WINDOW", "TRACE_ID");
        assertThat(report.attribution().routes()).hasSize(1);
        assertThat(report.attribution().routes().get(0).traceCorrelated()).isEqualTo(1);
        assertThat(report.attribution().routes().get(0).threadCorrelated()).isZero();
        assertThat(report.attribution().notes())
                .anySatisfy(note -> assertThat(note).contains("no one-thread-per-request invariant"));
    }

    @Test
    void labelsRoutesAsMaskedPathsBecauseNoRouteTemplateIsAvailable() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from orders", "trace-a");
        HttpExchangeBuffer exchanges = new HttpExchangeBuffer(20);
        exchanges.record(exchange("GET", "http://localhost:8080/api/orders/8f3a-91?full=true", "trace-a", 25));

        SqlRouteRankingDto route =
                resource(recorder, exchanges).insights().attribution().routes().get(0);

        assertThat(route.routeSource()).isEqualTo("MASKED_PATH");
        assertThat(route.route()).isEqualTo("/api/orders/{value}");
        assertThat(route.route()).doesNotContain("8f3a-91");
        assertThat(route.route()).doesNotContain("full=true");
    }

    @Test
    void ranksNormalizedStatementsWithoutExposingTheLiteralsTheyEmbedded() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from orders where customer_id = 17", null);
        record(recorder, "select * from orders where customer_id = 42", null);

        SqlTraceInsightsReport report =
                resource(recorder, new HttpExchangeBuffer(20)).insights();

        assertThat(report.statements()).hasSize(1);
        assertThat(report.statements().get(0).sql()).isEqualTo("select * from orders where customer_id = ?");
        assertThat(report.statements().get(0).executions()).isEqualTo(2);
    }

    @Test
    void keepsWorkWithNoCandidateRequestInTheUnattributedBucket() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from flyway_schema_history", null);

        SqlTraceInsightsReport report =
                resource(recorder, new HttpExchangeBuffer(20)).insights();

        assertThat(report.attribution().routes()).isEmpty();
        assertThat(report.attribution().unattributed().executions()).isEqualTo(1);
        assertThat(report.attribution().ambiguous().executions()).isZero();
    }
}
