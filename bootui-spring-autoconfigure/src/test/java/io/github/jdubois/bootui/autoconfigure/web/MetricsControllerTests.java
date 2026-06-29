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
import io.github.jdubois.bootui.core.dto.MetricMeterDto;
import io.github.jdubois.bootui.core.dto.MetricsReport;
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
        when(provider.metrics())
                .thenReturn(new MetricsReport(
                        true,
                        1,
                        List.of(new MetricMeterDto(
                                "bootui.sample.requests", "desc", "requests", "COUNTER", List.of()))));

        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsAvailable").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.meters[0].name").value("bootui.sample.requests"));
    }

    @Test
    void detailBindsNameAndTagParametersAndDelegates() throws Exception {
        MetricsReportProvider provider = mock(MetricsReportProvider.class);
        when(provider.metric(eq("bootui.sample.requests"), eq(List.of("outcome:success"))))
                .thenReturn(new MetricDetailDto(
                        true, "bootui.sample.requests", null, null, "COUNTER", List.of(), List.of(), List.of()));

        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics/detail")
                        .param("name", "bootui.sample.requests")
                        .param("tag", "outcome:success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsAvailable").value(true))
                .andExpect(jsonPath("$.name").value("bootui.sample.requests"));
    }

    @Test
    void malformedTagFilterIsMappedToBadRequest() throws Exception {
        MetricsReportProvider provider = mock(MetricsReportProvider.class);
        when(provider.metric(any(), any()))
                .thenThrow(new IllegalArgumentException("Metric tag filters must use key:value syntax"));

        MockMvc mvc = standaloneSetup(new MetricsController(provider)).build();

        mvc.perform(get("/bootui/api/metrics/detail")
                        .param("name", "bootui.sample.requests")
                        .param("tag", "malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
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
