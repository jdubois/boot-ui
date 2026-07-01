package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.InitializeResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.NoResponse;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PingResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolsListResult;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpDispatcherTests {

    private final McpTool overview = new McpTool(
            "get_overview",
            "Read the overview.",
            McpToolSchema.NONE,
            "overview",
            false,
            args -> Map.of("name", "demo"));
    private final McpTool architecture = new McpTool(
            "architecture_scan",
            "Run the architecture advisor.",
            McpToolSchema.NONE,
            "architecture",
            true,
            args -> Map.of("findings", List.of()));
    private final McpTool search = new McpTool(
            "get_config",
            "Search config.",
            McpToolSchema.QUERY_LIMIT,
            "config",
            false,
            args -> Map.of("query", String.valueOf(args.query()), "limit", args.limit()));

    private final FakePolicy policy = new FakePolicy();

    private McpDispatcher dispatcher() {
        return new McpDispatcher(List.of(overview, architecture, search), policy, "1.2.3", "instructions text", 50);
    }

    @Test
    void initializeEchoesRequestedProtocolVersionAndServerInfo() {
        McpDispatchOutcome outcome = dispatcher().dispatch(initialize("2025-06-18"));

        assertThat(outcome).isInstanceOf(InitializeResult.class);
        InitializeResult result = (InitializeResult) outcome;
        assertThat(result.protocolVersion()).isEqualTo("2025-06-18");
        assertThat(result.serverName()).isEqualTo(McpProtocol.SERVER_NAME);
        assertThat(result.serverVersion()).isEqualTo("1.2.3");
        assertThat(result.instructions()).isEqualTo("instructions text");
    }

    @Test
    void initializeFallsBackToDefaultProtocolVersion() {
        McpDispatchOutcome outcome = dispatcher().dispatch(initialize(null));

        assertThat(((InitializeResult) outcome).protocolVersion()).isEqualTo(McpProtocol.DEFAULT_PROTOCOL_VERSION);
        assertThat(((InitializeResult) dispatcher().dispatch(initialize(""))).protocolVersion())
                .isEqualTo(McpProtocol.DEFAULT_PROTOCOL_VERSION);
    }

    @Test
    void pingReturnsPingResult() {
        assertThat(dispatcher().dispatch(method("ping"))).isInstanceOf(PingResult.class);
    }

    @Test
    void toolsListAdvertisesEveryToolInOrder() {
        McpDispatchOutcome outcome = dispatcher().dispatch(method("tools/list"));

        ToolsListResult result = (ToolsListResult) outcome;
        assertThat(result.tools())
                .extracting(McpToolDescriptor::name)
                .containsExactly("get_overview", "architecture_scan", "get_config");
        assertThat(result.tools().get(2).schema()).isEqualTo(McpToolSchema.QUERY_LIMIT);
    }

    @Test
    void toolsCallReturnsPayload() {
        McpDispatchOutcome outcome = dispatcher().dispatch(call("get_overview"));

        assertThat(outcome).isInstanceOf(ToolCallResult.class);
        assertThat(((ToolCallResult) outcome).payload()).isEqualTo(Map.of("name", "demo"));
    }

    @Test
    void toolsCallAppliesNormalizedArguments() {
        McpDispatchOutcome outcome =
                dispatcher().dispatch(new McpRequest("tools/call", false, null, "get_config", "  hi  ", 5));

        Object payload = ((ToolCallResult) outcome).payload();
        assertThat(payload).isEqualTo(Map.of("query", "hi", "limit", 5));
    }

    @Test
    void toolsCallCapsLimitAtMaxResults() {
        McpDispatchOutcome outcome =
                dispatcher().dispatch(new McpRequest("tools/call", false, null, "get_config", null, 9999));

        assertThat(((ToolCallResult) outcome).payload()).isEqualTo(Map.of("query", "null", "limit", 50));
    }

    @Test
    void missingToolNameIsInBandError() {
        McpDispatchOutcome outcome = dispatcher().dispatch(call(""));

        assertThat(outcome).isEqualTo(new ToolCallError(McpProtocol.MISSING_TOOL_NAME_MESSAGE));
    }

    @Test
    void unknownToolIsInBandError() {
        McpDispatchOutcome outcome = dispatcher().dispatch(call("does_not_exist"));

        assertThat(outcome).isEqualTo(new ToolCallError("Unknown tool: does_not_exist"));
    }

    @Test
    void disabledPanelIsInBandError() {
        policy.disabled.add("overview");

        McpDispatchOutcome outcome = dispatcher().dispatch(call("get_overview"));

        assertThat(outcome).isEqualTo(new ToolCallError("disabled:overview"));
    }

    @Test
    void readOnlyActionToolIsInBandError() {
        policy.readOnly.add("architecture");

        McpDispatchOutcome outcome = dispatcher().dispatch(call("architecture_scan"));

        assertThat(outcome).isEqualTo(new ToolCallError("read-only:architecture"));
    }

    @Test
    void readToolOnReadOnlyPanelStillRuns() {
        policy.readOnly.add("overview");

        assertThat(dispatcher().dispatch(call("get_overview"))).isInstanceOf(ToolCallResult.class);
    }

    @Test
    void toolHandlerRuntimeExceptionBecomesProtocolError() {
        McpTool boom = new McpTool("boom", "Boom.", McpToolSchema.NONE, "overview", false, args -> {
            throw new IllegalStateException("kaboom");
        });
        McpDispatcher dispatcher = new McpDispatcher(List.of(boom), policy, "1.0", "x", 50);

        McpDispatchOutcome outcome = dispatcher.dispatch(call("boom"));

        assertThat(outcome).isEqualTo(new ProtocolError(McpProtocol.INTERNAL_ERROR, "kaboom"));
    }

    @Test
    void unknownMethodIsMethodNotFound() {
        McpDispatchOutcome outcome = dispatcher().dispatch(method("tools/unknown"));

        assertThat(outcome).isEqualTo(new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: tools/unknown"));
    }

    @Test
    void unknownNotificationMethodProducesNoResponse() {
        McpDispatchOutcome outcome =
                dispatcher().dispatch(new McpRequest("notifications/initialized", true, null, null, null, null));

        assertThat(outcome).isInstanceOf(NoResponse.class);
    }

    @Test
    void blankMethodNotificationProducesNoResponse() {
        assertThat(dispatcher().dispatch(new McpRequest("", true, null, null, null, null)))
                .isInstanceOf(NoResponse.class);
    }

    @Test
    void blankMethodRequestIsInvalidParams() {
        McpDispatchOutcome outcome = dispatcher().dispatch(new McpRequest("", false, null, null, null, null));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_METHOD_MESSAGE));
    }

    private static McpRequest initialize(String protocolVersion) {
        return new McpRequest("initialize", false, protocolVersion, null, null, null);
    }

    private static McpRequest method(String method) {
        return new McpRequest(method, false, null, null, null, null);
    }

    private static McpRequest call(String toolName) {
        return new McpRequest("tools/call", false, null, toolName, null, null);
    }

    private static final class FakePolicy implements McpPanelPolicy {
        private final Set<String> disabled = new HashSet<>();
        private final Set<String> readOnly = new HashSet<>();

        @Override
        public boolean isEnabled(String panelId) {
            return !disabled.contains(panelId);
        }

        @Override
        public String disabledReason(String panelId) {
            return "disabled:" + panelId;
        }

        @Override
        public boolean isReadOnly(String panelId) {
            return readOnly.contains(panelId);
        }

        @Override
        public String readOnlyReason(String panelId) {
            return "read-only:" + panelId;
        }
    }
}
