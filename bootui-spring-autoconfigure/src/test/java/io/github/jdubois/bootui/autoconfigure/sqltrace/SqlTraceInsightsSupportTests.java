package io.github.jdubois.bootui.autoconfigure.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry;
import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.SqlRouteRankingDto;
import io.github.jdubois.bootui.core.dto.SqlTraceInsightsReport;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.Category;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder.StatementType;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The adapter seam between the Spring stacks' existing request-correlation buffers and the engine's
 * ranking and attribution services. The two bindings deliberately differ in what they claim, and these
 * tests pin that difference rather than assuming parity.
 */
class SqlTraceInsightsSupportTests {

    private SqlTraceRecorder recorder() {
        SqlTraceRecorder recorder = new SqlTraceRecorder(true, true, false, false, 100, 100, 2_000, 200, 5);
        recorder.registerDataSource("dataSource");
        return recorder;
    }

    private void record(SqlTraceRecorder recorder, String sql, String thread, String traceId) {
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace(traceId, null));
        recorder.record(
                StatementType.PREPARED, Category.SELECT, sql, List.of(), 10, true, null, null, 0, "conn-1", thread);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<SqlTraceRecorder> recorderProvider(SqlTraceRecorder recorder) {
        ObjectProvider<SqlTraceRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RequestCorrelationRegistry> servletProvider(RequestCorrelationRegistry registry) {
        ObjectProvider<RequestCorrelationRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<HttpExchangeTraceRegistry> reactiveProvider(HttpExchangeTraceRegistry registry) {
        ObjectProvider<HttpExchangeTraceRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MappingProvider> mappingProvider(String... patterns) {
        ObjectProvider<MappingProvider> provider = mock(ObjectProvider.class);
        if (patterns.length == 0) {
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }
        List<MappingDto> mappings = new java.util.ArrayList<>();
        for (String pattern : patterns) {
            mappings.add(new MappingDto("GET", pattern, "Handler", null, null));
        }
        MappingProvider mappingProvider = mock(MappingProvider.class);
        when(mappingProvider.available()).thenReturn(true);
        when(mappingProvider.mappings()).thenReturn(mappings);
        when(provider.getIfAvailable()).thenReturn(mappingProvider);
        return provider;
    }

    @Test
    void servletBindingOffersServingThreadCorrelationAndGroupsByRouteTemplate() {
        SqlTraceRecorder recorder = recorder();
        record(
                recorder,
                "select * from orders where id = 1",
                Thread.currentThread().getName(),
                null);
        long now = System.currentTimeMillis();
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        registry.record(new RequestCorrelation(
                now - 500,
                now + 500,
                Thread.currentThread().getName(),
                "GET",
                "/api/orders/42",
                "/api/orders/{id}",
                null));

        SqlTraceInsightsReport report = SqlTraceInsightsSupport.servletInsights(
                recorderProvider(recorder), servletProvider(registry), mappingProvider());

        assertThat(report.attribution().supportedCorrelations())
                .containsExactly("SERVING_THREAD", "TIME_WINDOW", "TRACE_ID");
        assertThat(report.attribution().routes()).hasSize(1);
        SqlRouteRankingDto route = report.attribution().routes().get(0);
        assertThat(route.route()).isEqualTo("/api/orders/{id}");
        assertThat(route.routeSource()).isEqualTo("ROUTE_TEMPLATE");
        assertThat(route.threadCorrelated()).isEqualTo(1);
        assertThat(route.entryIds()).hasSize(1);
    }

    @Test
    void reactiveBindingNeverClaimsServingThreadCorrelation() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from orders", "reactor-http-nio-3", "trace-a");
        long now = System.currentTimeMillis();
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(now - 500, now + 500, "GET", "/api/orders", "trace-a", "/api/orders"));

        SqlTraceInsightsReport report = SqlTraceInsightsSupport.reactiveInsights(
                recorderProvider(recorder), reactiveProvider(registry), mappingProvider());

        assertThat(report.attribution().supportedCorrelations()).containsExactly("TIME_WINDOW", "TRACE_ID");
        assertThat(report.attribution().routes()).hasSize(1);
        assertThat(report.attribution().routes().get(0).traceCorrelated()).isEqualTo(1);
        assertThat(report.attribution().routes().get(0).threadCorrelated()).isZero();
        assertThat(report.attribution().notes())
                .anySatisfy(note -> assertThat(note).contains("no one-thread-per-request invariant"));
    }

    @Test
    void reactiveTraceCorrelationSurvivesAThreadThatNeverServedTheRequest() {
        SqlTraceRecorder recorder = recorder();
        // A WebFlux request hops schedulers: the statement runs on a bounded-elastic thread while the
        // request was accepted on an event loop. Only the trace context connects the two.
        record(recorder, "select * from orders", "boundedElastic-7", "trace-a");
        long now = System.currentTimeMillis();
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(
                new HttpExchangeTrace(now - 10_000, now - 9_000, "GET", "/api/orders", "trace-a", "/api/orders"));

        SqlTraceInsightsReport report = SqlTraceInsightsSupport.reactiveInsights(
                recorderProvider(recorder), reactiveProvider(registry), mappingProvider());

        assertThat(report.attribution().routes()).hasSize(1);
        assertThat(report.attribution().routes().get(0).traceCorrelated()).isEqualTo(1);
        assertThat(report.attribution().unattributed().executions()).isZero();
    }

    @Test
    void masksThePathWhenNoRouteTemplateWasMatched() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from orders", Thread.currentThread().getName(), null);
        long now = System.currentTimeMillis();
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        registry.record(new RequestCorrelation(
                now - 500, now + 500, Thread.currentThread().getName(), "GET", "/api/orders/8f3a-91", null, null));

        SqlTraceInsightsReport report = SqlTraceInsightsSupport.servletInsights(
                recorderProvider(recorder), servletProvider(registry), mappingProvider());

        SqlRouteRankingDto route = report.attribution().routes().get(0);
        assertThat(route.routeSource()).isEqualTo("MASKED_PATH");
        assertThat(route.route()).isEqualTo("/api/orders/{value}");
        assertThat(route.route()).doesNotContain("8f3a-91");
    }

    @Test
    void fallsBackToADeclaredRouteTemplateWhenTheRequestCarriedNone() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from users", Thread.currentThread().getName(), null);
        long now = System.currentTimeMillis();
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        registry.record(new RequestCorrelation(
                now - 500, now + 500, Thread.currentThread().getName(), "GET", "/api/users/alice", null, null));

        SqlTraceInsightsReport report = SqlTraceInsightsSupport.servletInsights(
                recorderProvider(recorder), servletProvider(registry), mappingProvider("/api/users/{name}"));

        SqlRouteRankingDto route = report.attribution().routes().get(0);
        assertThat(route.routeSource()).isEqualTo("ROUTE_TEMPLATE");
        assertThat(route.route()).isEqualTo("/api/users/{name}");
        assertThat(route.route()).doesNotContain("alice");
    }

    @Test
    void reportsAttributionUnavailableWhenWebFluxHasNoTraceRegistry() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from orders", "reactor-http-nio-3", null);

        SqlTraceInsightsReport report = SqlTraceInsightsSupport.reactiveInsights(
                recorderProvider(recorder), reactiveProvider(null), mappingProvider());

        assertThat(report.available()).isTrue();
        assertThat(report.statements()).hasSize(1);
        assertThat(report.attribution().available()).isFalse();
        assertThat(report.attribution().unavailableReason()).contains("OpenTelemetry");
    }

    @Test
    void reportsEverythingUnattributedWhenNoRequestEvidenceExists() {
        SqlTraceRecorder recorder = recorder();
        record(recorder, "select * from schema_version", "background-1", null);

        SqlTraceInsightsReport report = SqlTraceInsightsSupport.servletInsights(
                recorderProvider(recorder), servletProvider(null), mappingProvider());

        assertThat(report.attribution().routes()).isEmpty();
        assertThat(report.attribution().unattributed().executions()).isEqualTo(1);
        assertThat(report.attribution().notes())
                .anySatisfy(note -> assertThat(note).contains("No captured HTTP requests"));
    }

    @Test
    void reportsUnavailableWhenNoRecorderBeanExists() {
        assertThat(SqlTraceInsightsSupport.servletInsights(
                                recorderProvider(null), servletProvider(null), mappingProvider())
                        .available())
                .isFalse();
        assertThat(SqlTraceInsightsSupport.reactiveInsights(
                                recorderProvider(null), reactiveProvider(null), mappingProvider())
                        .available())
                .isFalse();
    }
}
