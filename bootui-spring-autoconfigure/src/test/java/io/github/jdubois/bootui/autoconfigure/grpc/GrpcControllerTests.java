package io.github.jdubois.bootui.autoconfigure.grpc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.web.GrpcController;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.grpc.GrpcReportService;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.GrpcCallMetricSample;
import io.github.jdubois.bootui.spi.GrpcCallSide;
import io.github.jdubois.bootui.spi.GrpcChannelSnapshot;
import io.github.jdubois.bootui.spi.GrpcMetadataProvider;
import io.github.jdubois.bootui.spi.GrpcMethodSnapshot;
import io.github.jdubois.bootui.spi.GrpcMethodType;
import io.github.jdubois.bootui.spi.GrpcMetricsProvider;
import io.github.jdubois.bootui.spi.GrpcRegistrySnapshot;
import io.github.jdubois.bootui.spi.GrpcServerSnapshot;
import io.github.jdubois.bootui.spi.GrpcServiceSnapshot;
import io.github.jdubois.bootui.spi.GrpcTransportSecurity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the shared {@code GET /bootui/api/grpc} contract served by the servlet and WebFlux adapters. */
class GrpcControllerTests {

    private static final ExposurePolicy MASKED = new ExposurePolicy() {

        @Override
        public ValueExposure valueExposure() {
            return ValueExposure.MASKED;
        }

        @Override
        public boolean maskSecrets() {
            return true;
        }
    };

    @Test
    void servesTheStableUnavailableContractWhenNoGrpcIntegrationIsPresent() throws Exception {
        MockMvc mvc = buildMvc(null, GrpcMetricsProvider.UNAVAILABLE);

        mvc.perform(get("/bootui/api/grpc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").isNotEmpty())
                .andExpect(jsonPath("$.integration").doesNotExist())
                .andExpect(jsonPath("$.serverCount").value(0))
                .andExpect(jsonPath("$.channelCount").value(0))
                .andExpect(jsonPath("$.metricsAvailable").value(false))
                .andExpect(jsonPath("$.metricsUnavailableReason").isNotEmpty())
                .andExpect(jsonPath("$.servers").isArray())
                .andExpect(jsonPath("$.channels").isArray())
                .andExpect(jsonPath("$.clientServices").isArray());
    }

    @Test
    void servesTheRegistryJoinedWithNativeCallMetrics() throws Exception {
        MockMvc mvc = buildMvc(
                new StubMetadataProvider(new GrpcRegistrySnapshot(
                        List.of(new GrpcServerSnapshot(
                                "spring-grpc-server",
                                "gRPC server",
                                "*",
                                9090,
                                GrpcTransportSecurity.PLAINTEXT,
                                Boolean.TRUE,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(new GrpcServiceSnapshot(
                                        "shop.Inventory",
                                        "com.example.InventoryService",
                                        List.of(),
                                        List.of(new GrpcMethodSnapshot(
                                                "Get", "shop.Inventory/Get", GrpcMethodType.UNARY)))))),
                        List.of(new GrpcChannelSnapshot(
                                "billing",
                                "static://localhost:9091",
                                "round_robin",
                                GrpcTransportSecurity.PLAINTEXT,
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of())),
                        List.of())),
                new StubMetricsProvider(List.of(
                        new GrpcCallMetricSample(
                                GrpcCallSide.SERVER, "shop.Inventory", "Get", "OK", 7L, 21.0d, 9.0d, null),
                        new GrpcCallMetricSample(
                                GrpcCallSide.CLIENT, "billing.Billing", "Charge", "OK", 3L, 6.0d, 4.0d, null))));

        mvc.perform(get("/bootui/api/grpc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.integration").value("stub"))
                .andExpect(jsonPath("$.serverCount").value(1))
                .andExpect(jsonPath("$.serviceCount").value(1))
                .andExpect(jsonPath("$.methodCount").value(1))
                .andExpect(jsonPath("$.channelCount").value(1))
                .andExpect(jsonPath("$.metricsAvailable").value(true))
                .andExpect(jsonPath("$.servers[0].port").value(9090))
                .andExpect(jsonPath("$.servers[0].transportSecurity").value("PLAINTEXT"))
                .andExpect(jsonPath("$.servers[0].reflectionEnabled").value(true))
                .andExpect(jsonPath("$.servers[0].services[0].name").value("shop.Inventory"))
                .andExpect(
                        jsonPath("$.servers[0].services[0].metrics.callCount").value(7))
                .andExpect(
                        jsonPath("$.servers[0].services[0].methods[0].fullName").value("shop.Inventory/Get"))
                .andExpect(jsonPath("$.servers[0].services[0].methods[0].type").value("UNARY"))
                .andExpect(jsonPath("$.servers[0].services[0].methods[0].metrics.callCount")
                        .value(7))
                .andExpect(jsonPath("$.channels[0].name").value("billing"))
                .andExpect(jsonPath("$.channels[0].target").value("static://localhost:9091"))
                .andExpect(jsonPath("$.channels[0].authority").value("localhost:9091"))
                .andExpect(jsonPath("$.clientServices[0].name").value("billing.Billing"))
                .andExpect(jsonPath("$.clientServices[0].metrics.callCount").value(3));
    }

    private static MockMvc buildMvc(GrpcMetadataProvider metadataProvider, GrpcMetricsProvider metricsProvider) {
        GrpcReportService service =
                new GrpcReportService(metadataProvider, metricsProvider, MASKED, new SecretMasker());
        return standaloneSetup(new GrpcController(service)).build();
    }

    private record StubMetadataProvider(GrpcRegistrySnapshot registry) implements GrpcMetadataProvider {

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return null;
        }

        @Override
        public String integration() {
            return "stub";
        }
    }

    private record StubMetricsProvider(List<GrpcCallMetricSample> samples) implements GrpcMetricsProvider {

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return null;
        }
    }
}
