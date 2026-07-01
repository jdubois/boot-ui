package io.github.jdubois.bootui.autoconfigure.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.activity.RequestCorrelationRegistry.RequestCorrelation;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTests {

    @Test
    void recordsServingThreadAndWindowForApplicationRequests() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(registry, "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/products");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.snapshot()).hasSize(1);
        RequestCorrelation record = registry.snapshot().get(0);
        assertThat(record.method()).isEqualTo("GET");
        assertThat(record.path()).isEqualTo("/api/sample/products");
        assertThat(record.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(record.endMillis()).isGreaterThanOrEqualTo(record.startMillis());
    }

    @Test
    void skipsBootUiOwnRequests() throws Exception {
        RequestCorrelationRegistry registry = new RequestCorrelationRegistry(10);
        RequestCorrelationFilter filter = new RequestCorrelationFilter(registry, "/bootui");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bootui/api/activity/stream");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.snapshot()).isEmpty();
    }
}
