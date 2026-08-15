package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTests {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void recordsServingThreadWindowAndServerTraceForApplicationRequests() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(10);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(registry, traceRegistry, "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/products");
        MDC.put("traceId", "server-created-trace");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.snapshot()).hasSize(1);
        RequestCorrelation record = registry.snapshot().get(0);
        assertThat(record.method()).isEqualTo("GET");
        assertThat(record.path()).isEqualTo("/api/sample/products");
        assertThat(record.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(record.endMillis()).isGreaterThanOrEqualTo(record.startMillis());
        assertThat(traceRegistry.match(record.method(), record.path(), record.startMillis(), record.endMillis()))
                .isEqualTo("server-created-trace");
    }

    @Test
    void skipsBootUiOwnRequests() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(10);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(registry, traceRegistry, "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bootui/api/activity/stream");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.snapshot()).isEmpty();
        long now = System.currentTimeMillis();
        assertThat(traceRegistry.match("GET", "/bootui/api/activity/stream", now - 1000, now + 1000))
                .isNull();
    }

    @Test
    void normalizesEncodedPathLikeActuatorBeforeRegisteringServerTrace() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        HttpExchangeTraceRegistry traceRegistry = new HttpExchangeTraceRegistry(10);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(registry, traceRegistry, "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items/a%20b");
        MDC.put("traceId", "server-created-trace");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        RequestCorrelation record = registry.snapshot().get(0);
        assertThat(traceRegistry.match("GET", "/api/items/a b", record.startMillis(), record.endMillis()))
                .isEqualTo("server-created-trace");
    }
}
