package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HttpExchangeTraceRegistry}: the bounded side-buffer that lets either Spring adapter
 * stamp a captured server trace id onto an Actuator {@code HttpExchange} it has no trace-id field of its
 * own to carry one in. Mirrors {@code RequestCorrelationRegistryTests}' matching conventions (method +
 * path + overlapping time window, unique-candidate-only).
 */
class HttpExchangeTraceRegistryTests {

    @Test
    void matchesUniqueRequestByWindowOverlap() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/a", "trace-1"));
        registry.record(new HttpExchangeTrace(2000, 2100, "GET", "/a", "trace-2"));

        assertThat(registry.match("get", "/a", 1010, 1090)).isEqualTo("trace-1");
    }

    @Test
    void returnsNullWhenMethodOrPathDiffer() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/a", "trace-1"));

        assertThat(registry.match("POST", "/a", 1000, 1100)).isNull();
        assertThat(registry.match("GET", "/b", 1000, 1100)).isNull();
    }

    @Test
    void returnsNullWhenMultipleCandidatesOverlap() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/a", "trace-1"));
        registry.record(new HttpExchangeTrace(1050, 1150, "GET", "/a", "trace-2"));

        assertThat(registry.match("GET", "/a", 1000, 1100)).isNull();
    }

    @Test
    void doesNotMatchWhenWindowsDoNotOverlap() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/a", "trace-1"));

        assertThat(registry.match("GET", "/a", 5000, 5100)).isNull();
    }

    @Test
    void evictsOldestBeyondCapacity() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(2);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/a", "trace-1"));
        registry.record(new HttpExchangeTrace(2000, 2100, "GET", "/b", "trace-2"));
        registry.record(new HttpExchangeTrace(3000, 3100, "GET", "/c", "trace-3"));

        assertThat(registry.snapshot()).hasSize(2);
        assertThat(registry.match("GET", "/a", 1000, 1100)).isNull();
        assertThat(registry.match("GET", "/c", 3000, 3100)).isEqualTo("trace-3");
    }

    @Test
    void retainsRecordsWithNoUsableTraceIdAsAmbiguityBlockers() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/a", null));
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/b", ""));
        registry.record(null);

        assertThat(registry.snapshot()).hasSize(2);
        assertThat(registry.match("GET", "/a", 1000, 1100)).isNull();
        assertThat(registry.match("GET", "/b", 1000, 1100)).isNull();
    }

    @Test
    void untracedOverlapBlocksAttributionToTracedRequest() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/a", "trace-1"));
        registry.record(new HttpExchangeTrace(1025, 1125, "GET", "/a", null));

        assertThat(registry.match("GET", "/a", 1030, 1090)).isNull();
    }

    @Test
    void keepsFailedAndSlowRequestsAsLongAsTheReservedShareOfTheExchangeRepository() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(4, 50);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/failing", "trace-failing"), true);
        for (int i = 0; i < 20; i++) {
            registry.record(new HttpExchangeTrace(2000 + i, 2001 + i, "GET", "/ok-" + i, "trace-" + i));
        }

        assertThat(registry.match("GET", "/failing", 1000, 1100)).isEqualTo("trace-failing");
        assertThat(registry.recent()).hasSize(4);
        assertThat(registry.recent().get(0).path()).isEqualTo("/failing");
    }

    @Test
    void keepsTheTraceOfEveryFailureTheRepositoryRetainsDespiteOutOfOrderRecording() {
        BootUiHttpExchangeRepository repository = new BootUiHttpExchangeRepository(8, 25, 1_000L, false);
        HttpExchangeTraceRegistry registry = HttpExchangeTraceRegistry.forExchangeRepository(8);
        // Five failures reach the repository, but their trace records are written only after a sixth failure's.
        for (int i = 1; i <= 5; i++) {
            repository.add(exchange("/failing-" + i, 500, i * 10L));
        }
        repository.add(exchange("/failing-6", 500, 60L));
        registry.record(new HttpExchangeTrace(60, 61, "GET", "/failing-6", "trace-6"), true);
        for (int i = 1; i <= 5; i++) {
            registry.record(new HttpExchangeTrace(i * 10L, i * 10L + 1, "GET", "/failing-" + i, "trace-" + i), true);
        }
        for (int i = 0; i < 100; i++) {
            long start = 1_000L + i;
            repository.add(exchange("/ok-" + i, 200, start));
            registry.record(new HttpExchangeTrace(start, start + 1, "GET", "/ok-" + i, "ok-" + i));
        }

        for (org.springframework.boot.actuate.web.exchanges.HttpExchange exchange : repository.findAll()) {
            String path = exchange.getRequest().getUri().getPath();
            long start = exchange.getTimestamp().toEpochMilli();
            assertThat(registry.match("GET", path, start, start + 1))
                    .as("trace of retained exchange %s", path)
                    .isNotNull();
        }
        assertThat(repository.findAll())
                .extracting(exchange -> exchange.getRequest().getUri().getPath())
                .contains("/failing-5", "/failing-6");
    }

    private static org.springframework.boot.actuate.web.exchanges.HttpExchange exchange(
            String path, int status, long startMillis) {
        return new org.springframework.boot.actuate.web.exchanges.HttpExchange(
                java.time.Instant.ofEpochMilli(startMillis),
                new org.springframework.boot.actuate.web.exchanges.HttpExchange.Request(
                        java.net.URI.create("http://localhost" + path), "127.0.0.1", "GET", java.util.Map.of()),
                new org.springframework.boot.actuate.web.exchanges.HttpExchange.Response(status, java.util.Map.of()),
                null,
                null,
                java.time.Duration.ofMillis(1));
    }

    @Test
    void matchesTheRouteTemplateRecordedForTheOverlappingRequest() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/orders/1", null, " /orders/{id} "));
        registry.record(new HttpExchangeTrace(5000, 5100, "GET", "/orders/1", null, "/elsewhere"));

        assertThat(registry.matchRouteTemplate("get", "/orders/1", 1010, 1090)).isEqualTo("/orders/{id}");
        assertThat(registry.matchRouteTemplate("POST", "/orders/1", 1010, 1090)).isNull();
        assertThat(registry.matchRouteTemplate("GET", "/orders/2", 1010, 1090)).isNull();
        assertThat(registry.matchRouteTemplate("GET", "/orders/1", 3000, 3100)).isNull();
        assertThat(registry.matchRouteTemplate(null, "/orders/1", 1010, 1090)).isNull();
    }

    /**
     * Concurrent identical requests make the trace id ambiguous, but not the route: the same method and
     * path reach the same handler, so an agreeing pattern is still decided.
     */
    @Test
    void concurrentIdenticalRequestsStillResolveAnAgreeingTemplate() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/orders/1", "trace-1", "/orders/{id}"));
        registry.record(new HttpExchangeTrace(1050, 1150, "GET", "/orders/1", "trace-2", "/orders/{id}"));

        assertThat(registry.match("GET", "/orders/1", 1000, 1100)).isNull();
        assertThat(registry.matchRouteTemplate("GET", "/orders/1", 1000, 1100)).isEqualTo("/orders/{id}");
    }

    @Test
    void leavesTheRouteUndecidedWhenOverlappingCandidatesDisagreeOrRecordedNone() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/orders/1", null, "/orders/{id}"));
        registry.record(new HttpExchangeTrace(1050, 1150, "GET", "/orders/1", null, "/orders/{orderId}"));
        registry.record(new HttpExchangeTrace(3000, 3100, "GET", "/static/app.js", null, null));

        assertThat(registry.matchRouteTemplate("GET", "/orders/1", 1000, 1100)).isNull();
        assertThat(registry.matchRouteTemplate("GET", "/static/app.js", 3000, 3100))
                .isNull();
    }
}
