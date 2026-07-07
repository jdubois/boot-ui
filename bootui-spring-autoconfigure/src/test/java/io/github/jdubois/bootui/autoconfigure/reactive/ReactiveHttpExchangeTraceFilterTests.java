package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
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
import org.springframework.web.server.WebFilterChain;
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
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdProvider());
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
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdProvider());

        long before = System.currentTimeMillis();
        filter.filter(exchange("GET", "/api/sample/products"), OK_CHAIN).block(Duration.ofSeconds(5));
        long after = System.currentTimeMillis();

        assertThat(registry.match("GET", "/api/sample/products", before, after)).isNull();
    }

    @Test
    void skipsBootUiOwnTraffic() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        ReactiveHttpExchangeTraceFilter filter =
                new ReactiveHttpExchangeTraceFilter(properties, registry, new ReactiveOtelTraceIdProvider());
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

    private static MockServerWebExchange exchange(String method, String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), uri));
    }
}
