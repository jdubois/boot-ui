package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.autoconfigure.web.PanelsController;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class BootUiMcpAutoConfigurationTests {

    private static final Map<String, List<String>> BOUNDED_ACTIONS_BY_PANEL = Map.of(
            BootUiPanels.SQL_TRACE,
            List.of("clear_sql_traces", "pause_sql_trace_recording", "resume_sql_trace_recording"),
            BootUiPanels.TRANSACTIONS,
            List.of("clear_transactions", "pause_transaction_recording", "resume_transaction_recording"),
            BootUiPanels.TRACES,
            List.of("clear_traces"),
            BootUiPanels.REST_CLIENT_TRACE,
            List.of("clear_rest_client_traces", "pause_rest_client_recording", "resume_rest_client_recording"),
            BootUiPanels.EXCEPTIONS,
            List.of("clear_exceptions"),
            BootUiPanels.HEAP_DUMP,
            List.of("analyze_heap_dump"),
            BootUiPanels.DEVTOOLS,
            List.of("trigger_devtools_livereload"));

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class));

    private WebApplicationContextRunner webMvcRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        BootUiAutoConfiguration.class));
    }

    @Test
    void mcpBeansAreRegisteredButServerDisabledByDefault() {
        runner.withPropertyValues("bootui.enabled=ON").run(context -> {
            assertThat(context).hasSingleBean(BootUiMcpController.class);
            assertThat(context).hasSingleBean(BootUiMcpService.class);
            assertThat(context).hasSingleBean(BootUiMcpTools.class);
            assertThat(context).hasSingleBean(McpServerState.class);
            assertThat(context.getBean(McpServerState.class).isEnabled()).isFalse();
        });
    }

    @Test
    void mcpServerIsEnabledWhenConfigured() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON").run(context -> {
            assertThat(context).hasSingleBean(BootUiMcpController.class);
            assertThat(context).hasSingleBean(BootUiMcpService.class);
            assertThat(context.getBean(McpServerState.class).isEnabled()).isTrue();
            assertThat(context.getBean(BootUiMcpTools.class).tools()).isNotEmpty();
        });
    }

    @Test
    void explorerReadToolsAreWiredWithoutOptingIntoBeanCapture() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.explorer.enabled=false")
                .run(context -> {
                    assertThat(context.getBean(BootUiMcpTools.class).tools())
                            .extracting(McpTool::name)
                            .contains("get_explorer", "get_explorer_event");
                });
    }

    @Test
    void disablingLiveActivityRemovesTheAlternateExplorerReadPath() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.panels.activity.enabled=false")
                .run(context -> {
                    assertThat(context.getBean(BootUiMcpTools.class).tools())
                            .extracting(McpTool::name)
                            .doesNotContain("get_explorer", "get_explorer_event");
                });
    }

    @Test
    void passiveCatalogCoversEveryAvailablePanelExceptExplicitExclusions() {
        runner.withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON").run(context -> {
            Set<String> availablePanels = context.getBean(PanelsController.class).panels().panels().stream()
                    .filter(panel -> panel.available())
                    .map(panel -> panel.id())
                    .collect(Collectors.toSet());
            Set<String> expectedPanels = availablePanels.stream()
                    .filter(panel -> !Set.of(BootUiPanels.HTTP_PROBE, BootUiPanels.MCP_SERVER, BootUiPanels.CLI)
                            .contains(panel))
                    .collect(Collectors.toSet());
            List<McpTool> tools = context.getBean(BootUiMcpTools.class).tools();
            Set<String> passivePanels = tools.stream()
                    .filter(tool -> !tool.action())
                    .map(tool -> tool.panelId())
                    .collect(Collectors.toSet());

            assertThat(passivePanels).containsAll(expectedPanels);
            assertBoundedActions(tools, availablePanels);
            assertThat(tools)
                    .extracting(McpTool::name)
                    .doesNotContain(
                            "get_http_probe",
                            "get_mcp_server_status",
                            "pause_email_recording",
                            "resume_email_recording",
                            "pause_kafka_recording",
                            "resume_kafka_recording",
                            "pause_rabbitmq_recording",
                            "resume_rabbitmq_recording",
                            "pause_jms_recording",
                            "resume_jms_recording");
        });
    }

    private static void assertBoundedActions(List<McpTool> tools, Set<String> availablePanels) {
        Set<String> expected = BOUNDED_ACTIONS_BY_PANEL.entrySet().stream()
                .filter(entry -> availablePanels.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .collect(Collectors.toSet());
        List<McpTool> actual = tools.stream()
                .filter(tool -> BOUNDED_ACTIONS_BY_PANEL
                        .getOrDefault(tool.panelId(), List.of())
                        .contains(tool.name()))
                .toList();

        assertThat(actual).extracting(McpTool::name).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).allSatisfy(tool -> {
            assertThat(tool.action()).as(tool.name()).isTrue();
            assertThat(tool.schema()).as(tool.name()).isEqualTo(McpToolSchema.NONE);
        });
    }

    @Test
    void rpcRequestsAreRefusedWhileServerDisabled() {
        webMvcRunner().withPropertyValues("bootui.enabled=ON").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                    .build();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            mvc.perform(post("/bootui/api/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.error.code").value(-32000));
            mvc.perform(get("/bootui/api/mcp")).andExpect(status().isMethodNotAllowed());
        });
    }

    @Test
    void toggleEndpointEnablesServerAtRuntime() {
        webMvcRunner().withPropertyValues("bootui.enabled=ON").run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                    .build();

            mvc.perform(get("/bootui/api/mcp-server"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false))
                    .andExpect(jsonPath("$.configuredMode").value("OFF"))
                    .andExpect(jsonPath("$.endpoint").value("/bootui/api/mcp"))
                    .andExpect(jsonPath("$.tools").isArray());

            mvc.perform(post("/bootui/api/mcp-server/toggle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.overridden").value(true));

            // The JSON-RPC endpoint now serves requests.
            mvc.perform(post("/bootui/api/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.tools").isNotEmpty());
        });
    }

    @Test
    void getTransportEndpointReturnsMethodNotAllowed() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                            .build();
                    mvc.perform(get("/bootui/api/mcp")).andExpect(status().isMethodNotAllowed());
                });
    }

    @Test
    void initializeHandshakeOverHttp() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                            .build();
                    String body =
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
                    mvc.perform(post("/bootui/api/mcp")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.result.serverInfo.name").value("bootui"));
                });
    }

    @Test
    void toolsListOverHttp() {
        webMvcRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.mcp.enabled=ON")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                            .build();
                    String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
                    mvc.perform(post("/bootui/api/mcp")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.result.tools").isNotEmpty());
                });
    }
}
