package io.github.jdubois.bootui.autoconfigure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.AttributeValue;
import io.github.jdubois.bootui.autoconfigure.otlp.NormalizedSpan;
import io.github.jdubois.bootui.autoconfigure.otlp.TelemetryStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class TracesControllerTests {

    @Test
    void listAndDetailHideBootUiSelfTraces() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        TelemetryStore store = new TelemetryStore(properties.getTelemetry());
        store.add(span("bootui-trace", "bootui-root", null, "GET /bootui/api/traces", "/bootui/api/traces"));
        store.add(span("host-trace", "host-root", null, "GET /api/orders", "/api/orders"));

        MockMvc mvc = standaloneSetup(new TracesController(store, properties)).build();

        mvc.perform(get("/bootui/api/traces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retained").value(1))
                .andExpect(jsonPath("$.traces.length()").value(1))
                .andExpect(jsonPath("$.traces[0].traceId").value("host-trace"));

        mvc.perform(get("/bootui/api/traces/bootui-trace")).andExpect(status().isNotFound());
        mvc.perform(get("/bootui/api/traces/host-trace")).andExpect(status().isOk());
    }

    private static NormalizedSpan span(String traceId, String spanId, String parentSpanId, String name, String route) {
        return new NormalizedSpan(
                traceId,
                spanId,
                parentSpanId,
                name,
                "SERVER",
                "sample",
                "test",
                1L,
                2L,
                "OK",
                null,
                Map.of("http.route", AttributeValue.ofString(route)),
                List.of());
    }
}
