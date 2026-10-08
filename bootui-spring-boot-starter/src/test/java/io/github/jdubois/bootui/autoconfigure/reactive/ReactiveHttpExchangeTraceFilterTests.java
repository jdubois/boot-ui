package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link ReactiveHttpExchangeTraceFilter}: proves it feeds {@link HttpExchangeTraceRegistry}
 * with the trace id active when a non-BootUI request completes, and skips BootUI's own traffic exactly
 * like its sibling filters.
 */
class ReactiveHttpExchangeTraceFilterTests {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
    };

    private BootUiProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
    }

    @Test
    void recordsTheActiveTraceIdWhenTheRequestCompletes() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        ReactiveHttpExchangeTraceFilter filter =
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdSource());
        SpanContext context = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

        MockServerWebExchange exchange = exchange("GET", "/api/sample/products");
        long before = System.currentTimeMillis();
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));
        }
        long after = System.currentTimeMillis();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(registry.match("GET", "/api/sample/products", before, after)).isEqualTo(TRACE_ID);
    }

    @Test
    void doesNotRecordWhenNoSpanIsActive() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        ReactiveHttpExchangeTraceFilter filter =
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdSource());

        long before = System.currentTimeMillis();
        filter.filter(exchange("GET", "/api/sample/products"), OK_CHAIN).block(Duration.ofSeconds(5));
        long after = System.currentTimeMillis();

        assertThat(registry.match("GET", "/api/sample/products", before, after)).isNull();
    }

    @Test
    void skipsBootUiOwnTraffic() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        ReactiveHttpExchangeTraceFilter filter =
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdSource());
        SpanContext context = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

        long before = System.currentTimeMillis();
        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            filter.filter(exchange("GET", "/bootui/api/http-exchanges"), OK_CHAIN)
                    .block(Duration.ofSeconds(5));
            filter.filter(exchange("GET", "/bootui"), OK_CHAIN).block(Duration.ofSeconds(5));
        }
        long after = System.currentTimeMillis();

        assertThat(registry.match("GET", "/bootui/api/http-exchanges", before, after))
                .isNull();
        assertThat(registry.match("GET", "/bootui", before, after)).isNull();
    }

    @Test
    void capturesTheMatchedReactiveRouteTemplateFromTheExchangeAttribute() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        ReactiveHttpExchangeTraceFilter filter =
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdSource());
        MockServerWebExchange exchange = exchange("GET", "/api/sample/orders/42");
        exchange.getAttributes()
                .put(
                        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        PathPatternParser.defaultInstance.parse("/api/sample/orders/{id}"));

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(registry.recent()).hasSize(1);
        assertThat(registry.recent().get(0).routeTemplate()).isEqualTo("/api/sample/orders/{id}");
    }

    @Test
    void leavesTheRouteTemplateNullWhenNoReactivePatternMatched() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        ReactiveHttpExchangeTraceFilter filter =
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdSource());

        filter.filter(exchange("GET", "/api/sample/unmapped"), OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(registry.recent()).hasSize(1);
        assertThat(registry.recent().get(0).routeTemplate()).isNull();
    }

    @Test
    void reservesTraceRecordsOfServerErrorsAndFailedRequests() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(3, 67);
        properties.getActivity().setRequestSlowThresholdMs(0);
        ReactiveHttpExchangeTraceFilter filter =
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdSource());

        filter.filter(exchange("GET", "/api/server-error"), serverExchange -> {
                    serverExchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return Mono.empty();
                })
                .block(Duration.ofSeconds(5));
        // A failure is recorded once WebFlux's exception handlers rendered it, with the status they chose: a 500 for an
        // unhandled one, its own status for a ResponseStatusException, so a 404 is routine.
        MockServerWebExchange failing = exchange("GET", "/api/failing");
        filter.filter(failing, serverExchange -> Mono.error(new IllegalStateException("boom")))
                .onErrorResume(IllegalStateException.class, ex -> rendered(failing, HttpStatus.INTERNAL_SERVER_ERROR))
                .block(Duration.ofSeconds(5));
        MockServerWebExchange missing = exchange("GET", "/api/missing");
        filter.filter(missing, serverExchange -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .onErrorResume(ResponseStatusException.class, ex -> rendered(missing, HttpStatus.NOT_FOUND))
                .block(Duration.ofSeconds(5));
        for (int i = 0; i < 5; i++) {
            filter.filter(exchange("GET", "/api/ok-" + i), OK_CHAIN).block(Duration.ofSeconds(5));
        }

        assertThat(registry.recent())
                .extracting(HttpExchangeTrace::path)
                .containsExactly("/api/server-error", "/api/failing", "/api/ok-4");
    }

    /** Renders a failure as WebFlux's exception handlers do once the filters unwound: a status, then the commit. */
    private static Mono<Void> rendered(MockServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private static MockServerWebExchange exchange(String method, String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), uri));
    }
}
