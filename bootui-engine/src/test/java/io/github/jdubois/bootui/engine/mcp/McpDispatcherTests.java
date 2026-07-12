package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.InitializeResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.NoResponse;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PingResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptGetResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptsListResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolsListResult;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpDispatcherTests {

    private static final String JSONRPC = "2.0";

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
    private final McpTool detail = new McpTool(
            "get_exception_detail",
            "Read one exception group's detail.",
            McpToolSchema.ID,
            "exceptions",
            false,
            args -> Map.of("id", args.id()));

    private final FakePolicy policy = new FakePolicy();

    private McpDispatcher dispatcher() {
        return new McpDispatcher(
                List.of(overview, architecture, search, detail),
                List.of(new McpPrompt("diagnose", "Diagnose an issue.", "Inspect runtime evidence.")),
                policy,
                "1.2.3",
                "instructions text",
                50,
                20);
    }

    @Test
    void initializeEchoesRequestedKnownProtocolVersionAndServerInfo() {
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
    void initializeUnknownProtocolNegotiatesDefault() {
        McpDispatchOutcome outcome = dispatcher().dispatch(initialize("2099-01-01"));

        assertThat(((InitializeResult) outcome).protocolVersion()).isEqualTo(McpProtocol.DEFAULT_PROTOCOL_VERSION);
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
                .containsExactly("get_overview", "architecture_scan", "get_config", "get_exception_detail");
        assertThat(result.tools().get(2).schema()).isEqualTo(McpToolSchema.QUERY_LIMIT);
        assertThat(result.tools().get(3).schema()).isEqualTo(McpToolSchema.ID);
        assertThat(result.tools().get(0).outputSchemaType()).isEqualTo("object");
    }

    @Test
    void promptsListAdvertisesEveryPrompt() {
        PromptsListResult result = (PromptsListResult) dispatcher().dispatch(method("prompts/list"));

        assertThat(result.prompts()).extracting(McpPrompt::name).containsExactly("diagnose");
    }

    @Test
    void promptsGetReturnsPrompt() {
        McpDispatchOutcome outcome = dispatcher()
                .dispatch(new McpRequest(JSONRPC, "prompts/get", false, null, "diagnose", null, null, null));

        assertThat(outcome)
                .isEqualTo(new PromptGetResult(
                        new McpPrompt("diagnose", "Diagnose an issue.", "Inspect runtime evidence.")));
    }

    @Test
    void promptsGetValidatesName() {
        assertThat(dispatcher().dispatch(method("prompts/get")))
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_PROMPT_NAME_MESSAGE));
        assertThat(dispatcher()
                        .dispatch(new McpRequest(JSONRPC, "prompts/get", false, null, "unknown", null, null, null)))
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, "Unknown prompt: unknown"));
    }

    @Test
    void toolsCallReturnsPayload() {
        McpDispatchOutcome outcome = dispatcher().dispatch(call("get_overview"));

        assertThat(outcome).isInstanceOf(ToolCallResult.class);
        assertThat(((ToolCallResult) outcome).payload()).isEqualTo(Map.of("name", "demo"));
    }

    @Test
    void toolsCallAppliesNormalizedArguments() {
        McpDispatchOutcome outcome = dispatcher()
                .dispatch(new McpRequest(JSONRPC, "tools/call", false, null, "get_config", "  hi  ", 5, null));

        Object payload = ((ToolCallResult) outcome).payload();
        assertThat(payload).isEqualTo(Map.of("query", "hi", "limit", 5));
    }

    @Test
    void toolsCallCapsLimitAtMaxResults() {
        McpDispatchOutcome outcome = dispatcher()
                .dispatch(new McpRequest(JSONRPC, "tools/call", false, null, "get_config", null, 9999, null));

        assertThat(((ToolCallResult) outcome).payload()).isEqualTo(Map.of("query", "null", "limit", 50));
    }

    @Test
    void toolsCallWithIdSchemaPassesTrimmedIdToHandler() {
        McpDispatchOutcome outcome = dispatcher()
                .dispatch(new McpRequest(
                        JSONRPC, "tools/call", false, null, "get_exception_detail", null, null, "  exc-1  "));

        assertThat(((ToolCallResult) outcome).payload()).isEqualTo(Map.of("id", "exc-1"));
    }

    @Test
    void toolsCallWithIdSchemaAndMissingIdIsInvalidParams() {
        McpDispatchOutcome outcome = dispatcher().dispatch(call("get_exception_detail"));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_ID_ARGUMENT_MESSAGE));
    }

    @Test
    void toolsCallWithIdSchemaAndBlankIdIsInvalidParams() {
        McpDispatchOutcome outcome = dispatcher()
                .dispatch(
                        new McpRequest(JSONRPC, "tools/call", false, null, "get_exception_detail", null, null, "   "));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_ID_ARGUMENT_MESSAGE));
    }

    @Test
    void missingToolNameIsInvalidParams() {
        McpDispatchOutcome outcome = dispatcher().dispatch(call(""));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_TOOL_NAME_MESSAGE));
    }

    @Test
    void unknownToolIsInvalidParams() {
        McpDispatchOutcome outcome = dispatcher().dispatch(call("does_not_exist"));

        assertThat(outcome).isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, "Unknown tool: does_not_exist"));
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
        McpDispatcher dispatcher = new McpDispatcher(List.of(boom), policy, "1.0", "x", 50, 20);

        McpDispatchOutcome outcome = dispatcher.dispatch(call("boom"));

        assertThat(outcome).isEqualTo(new ProtocolError(McpProtocol.INTERNAL_ERROR, "kaboom"));
    }

    @Test
    void toolCallsAreRateLimitedWhenAtCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        McpTool blocking = new McpTool("slow", "Slow.", McpToolSchema.NONE, "overview", false, args -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", ex);
            }
            return Map.of("ok", true);
        });
        McpDispatcher dispatcher = new McpDispatcher(List.of(blocking), policy, "1.0", "x", 50, 1);
        AtomicReference<McpDispatchOutcome> firstOutcome = new AtomicReference<>();
        Thread thread = new Thread(() -> firstOutcome.set(dispatcher.dispatch(call("slow"))));
        thread.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        McpDispatchOutcome second = dispatcher.dispatch(call("slow"));

        assertThat(second).isEqualTo(new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.RATE_LIMITED_MESSAGE));
        release.countDown();
        thread.join(5000);
        assertThat(firstOutcome.get()).isInstanceOf(ToolCallResult.class);
    }

    @Test
    void unknownMethodIsMethodNotFound() {
        McpDispatchOutcome outcome = dispatcher().dispatch(method("tools/unknown"));

        assertThat(outcome).isEqualTo(new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: tools/unknown"));
    }

    @Test
    void unknownNotificationMethodProducesNoResponse() {
        McpDispatchOutcome outcome = dispatcher()
                .dispatch(new McpRequest(JSONRPC, "notifications/initialized", true, null, null, null, null, null));

        assertThat(outcome).isInstanceOf(NoResponse.class);
    }

    @Test
    void recognizedNotificationsProduceNoResponse() {
        assertThat(dispatcher().dispatch(new McpRequest(JSONRPC, "ping", true, null, null, null, null, null)))
                .isInstanceOf(NoResponse.class);
        assertThat(dispatcher().dispatch(new McpRequest(JSONRPC, "tools/list", true, null, null, null, null, null)))
                .isInstanceOf(NoResponse.class);
    }

    @Test
    void blankMethodNotificationProducesNoResponse() {
        assertThat(dispatcher().dispatch(new McpRequest(JSONRPC, "", true, null, null, null, null, null)))
                .isInstanceOf(NoResponse.class);
    }

    @Test
    void blankMethodRequestIsInvalidParams() {
        McpDispatchOutcome outcome =
                dispatcher().dispatch(new McpRequest(JSONRPC, "", false, null, null, null, null, null));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_METHOD_MESSAGE));
    }

    private static McpRequest initialize(String protocolVersion) {
        return new McpRequest(JSONRPC, "initialize", false, protocolVersion, null, null, null, null);
    }

    private static McpRequest method(String method) {
        return new McpRequest(JSONRPC, method, false, null, null, null, null, null);
    }

    private static McpRequest call(String toolName) {
        return new McpRequest(JSONRPC, "tools/call", false, null, toolName, null, null, null);
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
