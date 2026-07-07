package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.web.HttpExchangeTraceRegistry.HttpExchangeTrace;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HttpExchangeTraceRegistry}: the reactive adapter's side-buffer that lets {@link
 * HttpExchangesController} stamp a captured OpenTelemetry trace id onto an Actuator {@code HttpExchange}
 * it has no trace-id field of its own to carry one in. Mirrors {@code RequestCorrelationRegistryTests}'
 * matching conventions (method + path + overlapping time window, unique-candidate-only).
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
    void ignoresRecordsWithNoUsableTraceId() {
        HttpExchangeTraceRegistry registry = new HttpExchangeTraceRegistry(10);
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/a", null));
        registry.record(new HttpExchangeTrace(1000, 1100, "GET", "/b", ""));
        registry.record(null);

        assertThat(registry.snapshot()).isEmpty();
    }
}
