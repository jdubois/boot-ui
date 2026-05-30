package io.github.jdubois.bootui.autoconfigure.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;

class MetricsControllerTests {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @Test
    void listGroupsMetersByNameAndAggregatesTagValues() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("bootui.sample.requests")
                .description("Sample requests")
                .baseUnit("requests")
                .tag("outcome", "success")
                .register(registry)
                .increment(2);
        Counter.builder("bootui.sample.requests")
                .description("Sample requests")
                .baseUnit("requests")
                .tag("outcome", "failure")
                .register(registry)
                .increment();

        MockMvc mvc =
                standaloneSetup(new MetricsController(providerOf(registry))).build();

        mvc.perform(get("/bootui/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsAvailable").value(true))
                .andExpect(jsonPath("$.total").value(greaterThan(0)))
                .andExpect(jsonPath("$.meters[0].name").value("bootui.sample.requests"))
                .andExpect(jsonPath("$.meters[0].description").value("Sample requests"))
                .andExpect(jsonPath("$.meters[0].baseUnit").value("requests"))
                .andExpect(jsonPath("$.meters[0].type").value("COUNTER"))
                .andExpect(jsonPath("$.meters[0].availableTags[0].key").value("outcome"))
                .andExpect(jsonPath("$.meters[0].availableTags[0].values", contains("failure", "success")));
    }

    @Test
    void detailFiltersByTagsAndAggregatesFiniteMeasurements() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("bootui.sample.requests")
                .tag("outcome", "success")
                .register(registry)
                .increment(2);
        Counter.builder("bootui.sample.requests")
                .tag("outcome", "failure")
                .register(registry)
                .increment(3);

        MockMvc mvc =
                standaloneSetup(new MetricsController(providerOf(registry))).build();

        mvc.perform(get("/bootui/api/metrics/detail")
                        .param("name", "bootui.sample.requests")
                        .param("tag", "outcome:success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsAvailable").value(true))
                .andExpect(jsonPath("$.name").value("bootui.sample.requests"))
                .andExpect(jsonPath("$.measurements[0].statistic").value("count"))
                .andExpect(jsonPath("$.measurements[0].value").value(2.0))
                .andExpect(jsonPath("$.samples.length()").value(1))
                .andExpect(jsonPath("$.samples[0].tags[0].key").value("outcome"))
                .andExpect(jsonPath("$.samples[0].tags[0].value").value("success"));
    }

    @Test
    void detailUsesMaximumWhenAggregatingMaxStatistic() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer.builder("bootui.sample.latency")
                .tag("node", "one")
                .register(registry)
                .record(10, TimeUnit.MILLISECONDS);
        Timer.builder("bootui.sample.latency")
                .tag("node", "two")
                .register(registry)
                .record(25, TimeUnit.MILLISECONDS);

        MockMvc mvc =
                standaloneSetup(new MetricsController(providerOf(registry))).build();

        mvc.perform(get("/bootui/api/metrics/detail").param("name", "bootui.sample.latency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[?(@.statistic == 'max')].value", contains(0.025)));
    }

    @Test
    void detailAcceptsColonInTagValues() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger value = new AtomicInteger(7);
        Gauge.builder("bootui.sample.gauge", value, AtomicInteger::get)
                .tags(Tags.of("uri", "http://localhost:8080/api"))
                .register(registry);

        MockMvc mvc =
                standaloneSetup(new MetricsController(providerOf(registry))).build();

        mvc.perform(get("/bootui/api/metrics/detail")
                        .param("name", "bootui.sample.gauge")
                        .param("tag", "uri:http://localhost:8080/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samples.length()").value(1))
                .andExpect(jsonPath("$.measurements[0].value").value(7.0));
    }

    @Test
    void metricsHideBootUiPathTagSamples() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("http.server.requests")
                .tag("uri", "/bootui/api/beans")
                .register(registry)
                .increment(5);
        Counter.builder("http.server.requests")
                .tag("uri", "/api/orders")
                .register(registry)
                .increment(2);

        MockMvc mvc =
                standaloneSetup(new MetricsController(providerOf(registry))).build();

        mvc.perform(get("/bootui/api/metrics/detail").param("name", "http.server.requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samples.length()").value(1))
                .andExpect(jsonPath("$.samples[0].tags[0].value").value("/api/orders"))
                .andExpect(jsonPath("$.availableTags[0].values", contains("/api/orders")));
    }

    @Test
    void detailRejectsMalformedTagFilters() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("bootui.sample.requests").increment();

        MockMvc mvc =
                standaloneSetup(new MetricsController(providerOf(registry))).build();

        mvc.perform(get("/bootui/api/metrics/detail")
                        .param("name", "bootui.sample.requests")
                        .param("tag", "malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
