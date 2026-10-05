package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.MetricDetailDto;
import io.github.jdubois.bootui.core.dto.MetricGroupDto;
import io.github.jdubois.bootui.core.dto.MetricMeterDto;
import io.github.jdubois.bootui.core.dto.MetricProvenanceDto;
import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for {@link MetricsController}: the report logic lives in the engine
 * {@link MetricsReportProvider} (covered by {@code MetricsReportProviderTests}), so here we only assert
 * that the two routes delegate, that request parameters bind, that the {@code @ExceptionHandler} maps a
 * bad tag filter to 400, and that the adapter's default self-data filter is wired into the engine.
 */
class MetricsControllerTests {

    @Test
    void metricsDelegatesToProvider() throws Exception {
        MetricsReportProvider provider = mock(MetricsReportProvider.class);
        when(provider.metrics(eq("jvm"), eq("gauge"), eq("jvm"), eq("classified"), eq("CURATED"), eq("1"), eq("2")))
                .thenReturn(new MetricsReport(
                        true,
                        1,
                        List.of(new MetricMeterDto(
                                "bootui.sample.requests",
                                "desc",
                                "requests",
                                "COUNTER",
                                List.of(),
                                new MetricProvenanceDto(
                                        "jvm",
                                        "JVM",
                                        "Micrometer JVM binders",
                                        "jvm.memory",
                                        "JVM memory",
                                        true,
                                        "Heap and non-heap memory usage per memory pool.",
                                        "CURATED",
                                        "Used, committed and maximum bytes are gauges."))),
                        List.of("COUNTER"),
                        new PageMetadata(1, 1, 0, 200, 1, false),
                        List.of(new MetricGroupDto(
                                "jvm",
                                "JVM",
                                "Micrometer JVM binders",
                                "JVM internals.",
                                "Gauges are point-in-time readings.",
                                1,
                                1,
                                List.of("JVM memory"),
                                List.of("area"),
                                List.of("bytes"))),
                        "2026.1"));

        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics")
                        .param("q", "jvm")
                        .param("type", "gauge")
                        .param("group", "jvm")
                        .param("provenance", "classified")
                        .param("explanation", "CURATED")
                        .param("offset", "1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsAvailable").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.catalogueVersion").value("2026.1"))
                .andExpect(jsonPath("$.groups[0].id").value("jvm"))
                .andExpect(jsonPath("$.groups[0].contributor").value("Micrometer JVM binders"))
                .andExpect(jsonPath("$.meters[0].name").value("bootui.sample.requests"))
                .andExpect(jsonPath("$.meters[0].provenance.groupId").value("jvm"))
                .andExpect(jsonPath("$.meters[0].provenance.explanationSource").value("CURATED"));
    }

    @Test
    void detailBindsNameAndTagParametersAndDelegates() throws Exception {
        MetricsReportProvider provider = mock(MetricsReportProvider.class);
        when(provider.metric(eq("bootui.sample.requests"), eq(List.of("outcome:success")), eq("2"), eq("10")))
                .thenReturn(new MetricDetailDto(
                        true, "bootui.sample.requests", null, null, "COUNTER", List.of(), List.of(), List.of()));

        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics/detail")
                        .param("name", "bootui.sample.requests")
                        .param("tag", "outcome:success")
                        .param("offset", "2")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsAvailable").value(true))
                .andExpect(jsonPath("$.name").value("bootui.sample.requests"))
                .andExpect(jsonPath("$.samplePage").exists())
                .andExpect(jsonPath("$.samplesTruncated").value(false));
    }

    @Test
    void malformedTagFilterIsMappedToBadRequest() throws Exception {
        MetricsReportProvider provider = mock(MetricsReportProvider.class);
        when(provider.metric(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Metric tag filters must use key:value syntax"));

        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics/detail")
                        .param("name", "bootui.sample.requests")
                        .param("tag", "malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Metric tag filters must use key:value syntax"));
    }

    @Test
    void invalidListLimitIsMappedToCanonicalBadRequest() throws Exception {
        MetricsReportProvider provider = new MetricsReportProvider(() -> null, meter -> true);
        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics").param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Metric limit must be between 1 and 1000"));
    }

    @Test
    void unknownProvenanceFiltersAreMappedToCanonicalBadRequests() throws Exception {
        MetricsReportProvider provider = new MetricsReportProvider(() -> null, meter -> true);
        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics").param("group", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Matchers.startsWith("Metric group must be one of: application")));
        mvc.perform(get("/bootui/api/metrics").param("provenance", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Metric provenance must be one of: classified, unclassified"));
        mvc.perform(get("/bootui/api/metrics").param("explanation", "guessed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Metric explanation source must be one of: CURATED, NATIVE, UNKNOWN"));
    }

    @Test
    void missingDetailNameIsMappedToCanonicalBadRequest() throws Exception {
        MetricsReportProvider provider = new MetricsReportProvider(() -> null, meter -> true);
        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics/detail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Metric name must not be blank"));
    }

    @Test
    void defaultSelfDataFilterHidesBootUiSamples() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("http.server.requests")
                .tag("uri", "/bootui/api/beans")
                .register(registry)
                .increment(5);
        Counter.builder("http.server.requests")
                .tag("uri", "/api/orders")
                .register(registry)
                .increment(2);

        MetricsReportProvider provider =
                new MetricsReportProvider(() -> registry, BootUiSelfDataFilter.defaults()::shouldIncludeMeter);
        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics/detail").param("name", "http.server.requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samples.length()").value(1))
                .andExpect(jsonPath("$.samples[0].tags[0].value").value("/api/orders"))
                .andExpect(jsonPath("$.availableTags[0].values", Matchers.contains("/api/orders")));
    }
}
