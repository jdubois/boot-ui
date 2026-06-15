package io.github.jdubois.bootui.autoconfigure.kernel;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class KernelInsightsControllerTests {

    private static KernelInsightsController controller(BootUiProperties.KernelInsights properties, FakeRunner runner) {
        return new KernelInsightsController(new KernelInsightsService(properties, runner));
    }

    private static BootUiProperties.KernelInsights props() {
        return new BootUiProperties().getKernelInsights();
    }

    @Test
    void statusReportsUnavailableWhenIgIsMissing() throws Exception {
        FakeRunner runner = new FakeRunner(false, "ig binary not found");
        MockMvc mvc = standaloneSetup(controller(props(), runner)).build();

        mvc.perform(get("/bootui/api/kernel-insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("ig binary not found"));
    }

    @Test
    void statusReportsReadyWhenAvailableWithoutCapturing() throws Exception {
        FakeRunner runner = new FakeRunner(true, null);
        MockMvc mvc = standaloneSetup(controller(props(), runner)).build();

        mvc.perform(get("/bootui/api/kernel-insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.status").value("NOT_SCANNED"))
                .andExpect(jsonPath("$.gadgets").isEmpty());
    }

    @Test
    void scanReportsDisabledWhenFeatureDisabled() throws Exception {
        BootUiProperties.KernelInsights properties = props();
        properties.setEnabled(false);
        MockMvc mvc = standaloneSetup(controller(properties, new FakeRunner(true, null)))
                .build();

        mvc.perform(post("/bootui/api/kernel-insights/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void scanReportsUnavailableWhenIgIsMissing() throws Exception {
        MockMvc mvc = standaloneSetup(controller(props(), new FakeRunner(false, "ig binary not found")))
                .build();

        mvc.perform(post("/bootui/api/kernel-insights/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"));
    }

    @Test
    void scanNormalizesGadgetEventsIntoStableDtos() throws Exception {
        BootUiProperties.KernelInsights properties = props();
        properties.setGadgets(List.of("trace_tcp"));
        FakeRunner runner = new FakeRunner(true, null);
        runner.results.put(
                IgGadget.TRACE_TCP,
                IgRunResult.ok(List.of(Map.of(
                        "comm",
                        "curl",
                        "pid",
                        1234,
                        "src",
                        Map.of("addr", "10.0.0.2", "port", 40000),
                        "dst",
                        Map.of("addr", "93.184.216.34", "port", 443)))));
        MockMvc mvc = standaloneSetup(controller(properties, runner)).build();

        mvc.perform(post("/bootui/api/kernel-insights/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNED"))
                .andExpect(jsonPath("$.gadgets[0].gadget").value("trace_tcp"))
                .andExpect(jsonPath("$.gadgets[0].category").value("NETWORK"))
                .andExpect(jsonPath("$.gadgets[0].status").value("OK"))
                .andExpect(jsonPath("$.gadgets[0].eventCount").value(1))
                .andExpect(jsonPath("$.gadgets[0].events[0].comm").value("curl"))
                .andExpect(jsonPath("$.gadgets[0].events[0].pid").value(1234))
                .andExpect(jsonPath("$.gadgets[0].events[0].summary", containsString("curl")))
                .andExpect(jsonPath("$.gadgets[0].events[0].summary", containsString("10.0.0.2:40000")))
                .andExpect(jsonPath("$.gadgets[0].events[0].summary", containsString("93.184.216.34:443")));
    }

    @Test
    void scanSurfacesGadgetErrors() throws Exception {
        BootUiProperties.KernelInsights properties = props();
        properties.setGadgets(List.of("trace_exec"));
        FakeRunner runner = new FakeRunner(true, null);
        runner.results.put(IgGadget.TRACE_EXEC, IgRunResult.error("permission denied (CAP_BPF required)"));
        MockMvc mvc = standaloneSetup(controller(properties, runner)).build();

        mvc.perform(post("/bootui/api/kernel-insights/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNED"))
                .andExpect(jsonPath("$.gadgets[0].status").value("ERROR"))
                .andExpect(jsonPath("$.gadgets[0].message").value("permission denied (CAP_BPF required)"));
    }

    @Test
    void scanReportsErrorWhenNoValidGadgetsConfigured() throws Exception {
        BootUiProperties.KernelInsights properties = props();
        properties.setGadgets(List.of("not_a_real_gadget"));
        MockMvc mvc = standaloneSetup(controller(properties, new FakeRunner(true, null)))
                .build();

        mvc.perform(post("/bootui/api/kernel-insights/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.gadgets").isEmpty());
    }

    @Test
    void statusReturnsLastCaptureAfterScan() throws Exception {
        BootUiProperties.KernelInsights properties = props();
        properties.setGadgets(List.of("snapshot_socket"));
        FakeRunner runner = new FakeRunner(true, null);
        runner.results.put(
                IgGadget.SNAPSHOT_SOCKET, IgRunResult.ok(List.of(Map.of("proto", "TCP", "state", "LISTEN"))));
        KernelInsightsService service = new KernelInsightsService(properties, runner);
        MockMvc mvc = standaloneSetup(new KernelInsightsController(service)).build();

        mvc.perform(post("/bootui/api/kernel-insights/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNED"));

        mvc.perform(get("/bootui/api/kernel-insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNED"))
                .andExpect(jsonPath("$.gadgets[0].gadget").value("snapshot_socket"));
    }

    private static final class FakeRunner implements IgGadgetRunner {

        private final boolean available;

        @Nullable
        private final String reason;

        private final Map<IgGadget, IgRunResult> results = new EnumMap<>(IgGadget.class);

        FakeRunner(boolean available, @Nullable String reason) {
            this.available = available;
            this.reason = reason;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public @Nullable String unavailableReason() {
            return reason;
        }

        @Override
        public String igPath() {
            return "ig";
        }

        @Override
        public @Nullable String igVersion() {
            return "ig version v0.40.0";
        }

        @Override
        public IgRunResult run(IgGadget gadget, Duration captureDuration, int maxEvents) {
            return results.getOrDefault(gadget, IgRunResult.ok(List.of()));
        }
    }
}
