package io.github.jdubois.bootui.autoconfigure.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.cli.CliStatus;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code bootui.cli.*} to the settings the facade actually enforces, so a property cannot silently
 * become decorative.
 */
class BootUiCliServiceFactoryTests {

    private final McpTool config = new McpTool(
            "get_config",
            "Search configuration.",
            McpToolSchema.QUERY_LIMIT,
            "config",
            false,
            args -> Map.of(
                    "items",
                    IntStream.range(0, args.limit() == null ? 1 : args.limit())
                            .boxed()
                            .toList()));

    private final McpPanelPolicy openPolicy = new McpPanelPolicy() {
        @Override
        public boolean isEnabled(String panelId) {
            return true;
        }

        @Override
        public String disabledReason(String panelId) {
            return "disabled";
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return false;
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "read-only";
        }
    };

    private CliService create(BootUiProperties properties) {
        return BootUiCliServiceFactory.create(() -> List.of(config), openPolicy, properties, "4.5.6");
    }

    @Test
    void defaultsEnableTheEndpointWithTheDocumentedResultCap() {
        BootUiProperties properties = new BootUiProperties();

        assertThat(properties.getCli().isEnabled()).isTrue();
        assertThat(properties.getCli().getMaxResults()).isEqualTo(200);
        assertThat(properties.getCli().getMaxConcurrentCalls()).isEqualTo(20);
        assertThat(properties.getCli().getExecutionTimeout()).isEqualTo(Duration.ofSeconds(30));

        CliService service = create(properties);
        assertThat(service.enabled()).isTrue();
        assertThat(service.status().maxResults()).isEqualTo(200);
    }

    @Test
    void disabledPropertyStopsInvocation() {
        BootUiProperties properties = new BootUiProperties();
        properties.getCli().setEnabled(false);

        CliService service = create(properties);

        assertThat(service.enabled()).isFalse();
        assertThat(service.invoke("get_config", null, null, null).status()).isEqualTo(CliStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void maxResultsIsAppliedToToolInvocation() {
        BootUiProperties properties = new BootUiProperties();
        properties.getCli().setMaxResults(3);

        CliService service = create(properties);

        assertThat(service.status().maxResults()).isEqualTo(3);
        assertThat(service.invoke("get_config", null, 100, null).payload())
                .isInstanceOfSatisfying(
                        Map.class,
                        payload -> assertThat((List<?>) payload.get("items")).hasSize(3));
    }

    @Test
    void nonPositiveLimitsAreClampedRatherThanBreakingTheEndpoint() {
        // Clamped exactly as BootUiMcpService clamps the bootui.mcp.* twins: a nonsensical value degrades the
        // endpoint to its tightest budget instead of throwing at startup or disabling enforcement.
        BootUiProperties properties = new BootUiProperties();
        properties.getCli().setMaxResults(0);
        properties.getCli().setMaxConcurrentCalls(0);
        properties.getCli().setExecutionTimeout(Duration.ZERO);

        CliService service = create(properties);

        assertThat(service.status().maxResults()).isEqualTo(1);
        assertThat(service.invoke("get_config", null, 50, null).status()).isIn(CliStatus.OK, CliStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void executionTimeoutIsEnforcedAsAGatewayTimeout() {
        BootUiProperties properties = new BootUiProperties();
        properties.getCli().setExecutionTimeout(Duration.ofMillis(1));

        CliService slow = BootUiCliServiceFactory.create(
                () -> List.of(new McpTool(
                        "get_config", "Slow read.", McpToolSchema.QUERY_LIMIT, "config", false, args -> {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            return Map.of("items", List.of());
                        })),
                openPolicy,
                properties,
                "4.5.6");

        assertThat(slow.invoke("get_config", null, null, null).status()).isEqualTo(CliStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void endpointFollowsTheConfiguredApiPath() {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/console");

        assertThat(create(properties).status().endpoint()).isEqualTo("/console/api/cli");
    }

    @Test
    void serverVersionIsReported() {
        assertThat(create(new BootUiProperties()).status().serverVersion()).isEqualTo("4.5.6");
    }
}
