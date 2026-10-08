package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpDispatcherTests {

    private static final String JSONRPC = "2.0";

    @Test
    void advisorPagesDefaultToOneHundredAndRespectBothCapsWithoutChangingExistingTools() {
        for (int cap : List.of(7, 250, 2000)) {
            McpDispatcher dispatcher = advisorDispatcher(cap, args -> args);
            assertThat(dispatcher.dispatch(advisorRequest(" RULE-1 ", " scan-1 ", null, null)))
                    .isEqualTo(new ToolCallResult(new McpArguments(null, Math.min(100, cap), "RULE-1", "scan-1", 0)));
            assertThat(dispatcher.dispatch(advisorRequest("RULE-1", "scan-1", 22, 5000)))
                    .isEqualTo(new ToolCallResult(new McpArguments(null, Math.min(1000, cap), "RULE-1", "scan-1", 22)));
        }
        assertThat(McpArguments.normalize(null, null, null, 2000).limit()).isEqualTo(2000);
    }

    @Test
    void advisorPagesRejectMissingRequiredArgumentsAndInvalidTypedBoundsBeforeInvocation() {
        AtomicInteger invocations = new AtomicInteger();
        McpDispatcher dispatcher = advisorDispatcher(250, args -> invocations.incrementAndGet());
        assertThat(dispatcher.dispatch(advisorRequest(" ", "scan", 0, 10)))
                .isEqualTo(new ProtocolError(
                        McpProtocol.INVALID_PARAMS,
                        "Missing required argument: id (a rule id and the violationDetails.scanId from"
                                + " get_architecture_report)"));
        for (String scanId : new String[] {null, "", " "}) {
            assertThat(dispatcher.dispatch(advisorRequest("RULE-1", scanId, 0, 10)))
                    .isEqualTo(new ProtocolError(
                            McpProtocol.INVALID_PARAMS,
                            McpProtocol.MISSING_SCAN_ID_ARGUMENT_MESSAGE
                                    + " (a rule id and the violationDetails.scanId from get_architecture_report)"));
        }
        assertThat(dispatcher.dispatch(advisorRequest("RULE-1", "scan", -1, 10)))
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, "Argument 'offset' must be at least 0"));
        assertThat(dispatcher.dispatch(advisorRequest("RULE-1", "scan", 0, 0)))
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, "Argument 'limit' must be at least 1"));
        assertThat(invocations).hasValue(0);
    }

    @Test
    void advisorReadsRemainAvailableInReadOnlyModeButRespectDisabledPanels() {
        policy.readOnly.add("architecture");
        McpDispatcher dispatcher = advisorDispatcher(250, args -> "page");
        assertThat(dispatcher.dispatch(advisorRequest("RULE-1", "scan", 0, 10))).isEqualTo(new ToolCallResult("page"));
        policy.disabled.add("architecture");
        assertThat(dispatcher.dispatch(advisorRequest("RULE-1", "scan", 0, 10))).isInstanceOf(ToolCallError.class);
    }

    @Test
    void advisorSnapshotClientFailuresKeepTheirSafeStatusAndDoNotReportServerFaults() {
        for (int status : List.of(400, 404, 409)) {
            McpDispatcher dispatcher = advisorDispatcher(250, args -> {
                throw new AdvisorViolationException(status, "Reread the cached report.");
            });
            assertThat(dispatcher.dispatch(advisorRequest("RULE-1", "scan", 0, 10)))
                    .isEqualTo(new ToolCallError("Reread the cached report.", status));
        }
        assertThat(diagnostics.count()).isZero();
    }

    private McpDispatcher advisorDispatcher(int cap, java.util.function.Function<McpArguments, Object> handler) {
        return new McpDispatcher(
                List.of(new McpTool(
                        "get_architecture_rule_violations",
                        "Read retained violations.",
                        McpToolSchema.RULE_VIOLATIONS,
                        "architecture",
                        false,
                        handler)),
                List.of(),
                policy,
                "1.2.3",
                "",
                cap,
                20,
                diagnostics);
    }

    private static McpRequest advisorRequest(String id, String scanId, Integer offset, Integer limit) {
        return new McpRequest(
                JSONRPC,
                "tools/call",
                false,
                null,
                "get_architecture_rule_violations",
                null,
                limit,
                id,
                Set.of("id", "scanId", "offset", "limit"),
                null,
                scanId,
                offset);
    }

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
    private final RecordingFailureReporter diagnostics = new RecordingFailureReporter();

    private McpDispatcher dispatcher() {
        return new McpDispatcher(
                List.of(overview, architecture, search, detail),
                List.of(new McpPrompt("diagnose", "Diagnose an issue.", "Inspect runtime evidence.")),
                policy,
                "1.2.3",
                "instructions text",
                50,
                20,
                diagnostics);
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
    void modernRequestsDiscoverTheServerAndHaveNoHandshakeOrPing() {
        McpDispatcher dispatcher = dispatcher();
        assertThat(dispatcher.dispatch(modern("server/discover")))
                .isEqualTo(new McpDispatchOutcome.DiscoverResult(
                        List.of("2026-07-28", "2025-06-18"), "bootui", "1.2.3", "instructions text"));
        assertThat(dispatcher.dispatch(modern("ping")))
                .isEqualTo(new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: ping"));
        assertThat(dispatcher.dispatch(modern("initialize")))
                .isEqualTo(new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: initialize"));
        assertThat(dispatcher.dispatch(modern("tools/list"))).isInstanceOf(ToolsListResult.class);
        assertThat(dispatcher.dispatch(method("server/discover")))
                .as("legacy clients never see the modern discovery method")
                .isEqualTo(new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: server/discover"));
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
    void promptsGetRendersDeclaredArgumentsAndRefusesUndeclaredOnes() {
        McpPrompt focused = new McpPrompt(
                "focus",
                "Focus an investigation.",
                "Inspect runtime evidence.",
                List.of(
                        new McpPrompt.Argument("symptom", "What failed.", "The symptom"),
                        new McpPrompt.Argument("route", "Where.", "The route")));
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(overview), List.of(focused), policy, "1.2.3", "instructions text", 50, 20, diagnostics);

        assertThat(dispatcher.dispatch(prompt("focus", Map.of())))
                .as("every argument is optional")
                .isEqualTo(new PromptGetResult(focused, "Inspect runtime evidence."));
        PromptGetResult rendered = (PromptGetResult)
                dispatcher.dispatch(prompt("focus", Map.of("route", " GET /orders ", "symptom", "500s")));
        assertThat(rendered.text())
                .startsWith("Inspect runtime evidence.\n\n")
                .endsWith("\n- The symptom: 500s\n- The route: GET /orders");
        assertThat(((PromptGetResult) dispatcher.dispatch(prompt("focus", Map.of("route", "  ")))).text())
                .as("a blank value adds no context")
                .isEqualTo("Inspect runtime evidence.");
        assertThat(((PromptGetResult) dispatcher.dispatch(prompt("focus", Map.of("symptom", "x".repeat(600))))).text())
                .as("a value is bounded")
                .endsWith("x".repeat(McpPrompt.MAX_ARGUMENT_LENGTH) + "...");
        assertThat(dispatcher.dispatch(prompt("focus", Map.of("sympton", "typo"))))
                .isEqualTo(new ProtocolError(
                        McpProtocol.INVALID_PARAMS,
                        "Unknown argument for prompt focus: sympton. It takes route, symptom."));
        assertThat(dispatcher().dispatch(prompt("diagnose", Map.of("symptom", "x"))))
                .isEqualTo(new ProtocolError(
                        McpProtocol.INVALID_PARAMS,
                        "Unknown argument for prompt diagnose: symptom. It takes no arguments."));
        McpRequest malformed = new McpRequest(
                JSONRPC,
                "prompts/get",
                false,
                null,
                "focus",
                null,
                null,
                null,
                Set.of(),
                McpProtocol.PROMPT_ARGUMENTS_OBJECT_MESSAGE,
                null,
                null);
        assertThat(dispatcher.dispatch(malformed))
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.PROMPT_ARGUMENTS_OBJECT_MESSAGE));
    }

    private static McpRequest prompt(String name, Map<String, String> arguments) {
        return new McpRequest(
                JSONRPC,
                "prompts/get",
                false,
                null,
                name,
                null,
                null,
                null,
                Set.of(),
                null,
                null,
                null,
                McpEra.LEGACY,
                null,
                null,
                null,
                null,
                arguments);
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
    void toolsCallWithoutLimitGetsACompactedToolsShortDefaultPage() {
        McpTool insights = new McpTool(
                "get_runtime_insights",
                "Runtime Insights for agents.",
                McpToolSchema.QUERY_LIMIT,
                "runtime-insights",
                false,
                args -> Map.of("limit", args.limit()));
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(insights), List.of(), policy, "1.2.3", "instructions text", 50, 20, diagnostics);

        assertThat(((ToolCallResult) dispatcher.dispatch(new McpRequest(
                                JSONRPC, "tools/call", false, null, "get_runtime_insights", null, null, null)))
                        .payload())
                .isEqualTo(Map.of("limit", 8));
        assertThat(((ToolCallResult) dispatcher.dispatch(new McpRequest(
                                JSONRPC, "tools/call", false, null, "get_runtime_insights", null, 20, null)))
                        .payload())
                .isEqualTo(Map.of("limit", 20));
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
                .isEqualTo(new ProtocolError(
                        McpProtocol.INVALID_PARAMS,
                        "Missing required argument: id (an exception group id from get_exceptions or"
                                + " get_live_activity)"));
    }

    @Test
    void toolsCallWithIdSchemaAndBlankIdIsInvalidParams() {
        McpDispatchOutcome outcome = dispatcher()
                .dispatch(
                        new McpRequest(JSONRPC, "tools/call", false, null, "get_exception_detail", null, null, "   "));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(
                        McpProtocol.INVALID_PARAMS,
                        "Missing required argument: id (an exception group id from get_exceptions or"
                                + " get_live_activity)"));
    }

    @Test
    void toolsCallRejectsArgumentsOutsideAdvertisedSchema() {
        McpRequest request = new McpRequest(
                JSONRPC, "tools/call", false, null, "get_overview", null, null, null, Set.of("query", "extra"), null);

        assertThat(dispatcher().dispatch(request))
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, "Unexpected tool arguments: extra, query"));
    }

    @Test
    void toolsCallRejectsAdapterDetectedArgumentTypeError() {
        McpRequest request = new McpRequest(
                JSONRPC,
                "tools/call",
                false,
                null,
                "get_config",
                null,
                null,
                null,
                Set.of(),
                McpProtocol.invalidArgumentTypeMessage("limit", "an integer"));

        assertThat(dispatcher().dispatch(request))
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, "Argument 'limit' must be an integer"));
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
    void aCatalogToolThisServerDoesNotAdvertiseSaysWhyItsPanelIsUnavailable() {
        McpDispatcher dispatcher = new McpDispatcher(
                () -> List.of(overview),
                List.of(),
                policy,
                "1.2.3",
                "",
                50,
                20,
                1_000,
                diagnostics,
                panelId -> panelId.equals("kafka") ? "No KafkaTemplate bean is available" : null);

        assertThat(dispatcher.dispatch(call("get_kafka_activity")))
                .isEqualTo(new ProtocolError(
                        McpProtocol.INVALID_PARAMS,
                        "Tool not available in this application: get_kafka_activity. Its Kafka panel is unavailable:"
                                + " No KafkaTemplate bean is available.",
                        Map.of(
                                "tool",
                                "get_kafka_activity",
                                "panel",
                                "kafka",
                                "reason",
                                "No KafkaTemplate bean is available")));
        assertThat(dispatcher.dispatch(call("get_conditions")))
                .isEqualTo(new ProtocolError(
                        McpProtocol.INVALID_PARAMS,
                        "Tool not available in this application: get_conditions. Only Spring MVC and Spring WebFlux"
                                + " advertise it.",
                        Map.of("tool", "get_conditions", "panel", "conditions")));
        assertThat(dispatcher.dispatch(call("get_emails")))
                .isEqualTo(new ProtocolError(
                        McpProtocol.INVALID_PARAMS,
                        "Tool not available in this application: get_emails. Its Email panel does not provide it in"
                                + " this application.",
                        Map.of("tool", "get_emails", "panel", "email")));
        assertThat(dispatcher.dispatch(call("does_not_exist")))
                .isEqualTo(new ProtocolError(McpProtocol.INVALID_PARAMS, "Unknown tool: does_not_exist"));
        assertThat(diagnostics.count()).isZero();
    }

    @Test
    void aCatalogToolMissingBecauseItsPanelIsDisabledGetsTheDisabledRefusal() {
        policy.disabled.add("websockets");
        McpDispatcher dispatcher = new McpDispatcher(
                () -> List.of(overview),
                List.of(),
                policy,
                "1.2.3",
                "",
                50,
                20,
                1_000,
                diagnostics,
                panelId -> "No WebSocket support is on the classpath.");

        assertThat(dispatcher.dispatch(call("get_websockets")))
                .isEqualTo(new ToolCallError("disabled:websockets", McpDispatchOutcome.ToolErrorReason.PANEL_DISABLED));
    }

    @Test
    void aFailingAvailabilityLookupStillAnswersAndIsReported() {
        McpDispatcher dispatcher = new McpDispatcher(
                () -> List.of(overview), List.of(), policy, "1.2.3", "", 50, 20, 1_000, diagnostics, panelId -> {
                    throw new IllegalStateException("boom");
                });

        assertThat(dispatcher.dispatch(call("get_kafka_activity")))
                .isInstanceOfSatisfying(
                        ProtocolError.class,
                        error -> assertThat(error.message())
                                .startsWith(McpProtocol.UNAVAILABLE_TOOL_PREFIX + "get_kafka_activity."));
        assertThat(diagnostics.count()).isEqualTo(1);
    }

    @Test
    void toolsListCarriesHintsAndPerToolArgumentSchemas() {
        ToolsListResult result = (ToolsListResult) dispatcher().dispatch(method("tools/list"));

        assertThat(result.tools().get(0).annotations()).isEqualTo(new McpToolAnnotations(true, false, true, false));
        assertThat(result.tools().get(1).annotations()).isEqualTo(new McpToolAnnotations(false, false, false, false));
        McpToolInputSchema config = result.tools().get(2).inputSchema();
        assertThat(config.properties())
                .extracting(McpToolInputSchema.Property::name)
                .containsExactly("query", "limit");
        // get_config's agent page, below the dispatcher's max-results of 50.
        assertThat(config.properties().get(1).defaultValue()).isEqualTo(McpAgentViews.INVENTORY_DEFAULT_LIMIT);
        assertThat(result.tools().get(3).inputSchema().required()).containsExactly("id");
    }

    @Test
    void disabledPanelIsInBandError() {
        policy.disabled.add("overview");

        McpDispatchOutcome outcome = dispatcher().dispatch(call("get_overview"));

        assertThat(outcome)
                .isEqualTo(new ToolCallError("disabled:overview", McpDispatchOutcome.ToolErrorReason.PANEL_DISABLED));
    }

    @Test
    void readOnlyActionToolIsInBandError() {
        policy.readOnly.add("architecture");

        McpDispatchOutcome outcome = dispatcher().dispatch(call("architecture_scan"));

        assertThat(outcome)
                .isEqualTo(new ToolCallError(
                        "read-only:architecture", McpDispatchOutcome.ToolErrorReason.PANEL_READ_ONLY));
    }

    @Test
    void busyActionToolIsInBandError() throws Exception {
        SingleFlightAction singleFlight = new SingleFlightAction();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread winner = new Thread(() -> singleFlight.run("architecture.scan", () -> {
            entered.countDown();
            await(release);
            return null;
        }));
        winner.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        McpTool busy = new McpTool(
                "architecture_scan",
                "Run the architecture advisor.",
                McpToolSchema.NONE,
                "architecture",
                true,
                args -> singleFlight.run("architecture.scan", () -> Map.of()));
        McpDispatcher dispatcher = new McpDispatcher(List.of(busy), List.of(), policy, "1.0", "x", 50, 20, diagnostics);

        assertThat(dispatcher.dispatch(call("architecture_scan")))
                .isEqualTo(new ToolCallError(
                        "Operation 'architecture.scan' cannot start while 'architecture.scan' is in progress.",
                        McpDispatchOutcome.ToolErrorReason.ACTION_BUSY));
        assertThat(diagnostics.count()).isZero();

        release.countDown();
        winner.join(5000);
    }

    @Test
    void toolClientErrorIsInBandErrorCarryingItsStatusAndIsNotReported() {
        McpTool missing =
                new McpTool("get_exception_detail", "Detail.", McpToolSchema.ID, "exceptions", false, args -> {
                    throw new McpToolClientException(404, "exception " + args.id() + " not found");
                });
        McpDispatcher dispatcher =
                new McpDispatcher(List.of(missing), List.of(), policy, "1.0", "x", 50, 20, diagnostics);

        McpDispatchOutcome outcome = dispatcher.dispatch(
                new McpRequest(JSONRPC, "tools/call", false, null, "get_exception_detail", null, null, "unknown"));

        assertThat(outcome).isEqualTo(new ToolCallError("exception unknown not found", 404));
        assertThat(diagnostics.count()).isZero();
    }

    @Test
    void toolClientErrorWithoutMessageFallsBackToCanonicalToolFailure() {
        McpTool refusing = new McpTool("get_overview", "Overview.", McpToolSchema.NONE, "overview", false, args -> {
            throw new McpToolClientException(409, "  ");
        });
        McpDispatcher dispatcher =
                new McpDispatcher(List.of(refusing), List.of(), policy, "1.0", "x", 50, 20, diagnostics);

        assertThat(dispatcher.dispatch(call("get_overview")))
                .isEqualTo(new ToolCallError(McpProtocol.TOOL_CALL_FAILED_MESSAGE, 409));
        assertThat(diagnostics.count()).isZero();
    }

    @Test
    void toolClientErrorRejectsNonClientStatus() {
        assertThatThrownBy(() -> new McpToolClientException(500, "boom"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
        assertThat(McpToolClientExceptions.isClientError(399)).isFalse();
        assertThat(McpToolClientExceptions.isClientError(400)).isTrue();
        assertThat(McpToolClientExceptions.isClientError(499)).isTrue();
        assertThat(McpToolClientExceptions.isClientError(500)).isFalse();
    }

    @Test
    void gateRefusalCarriesItsReasonButNoCanonicalStatus() {
        policy.disabled.add("overview");

        McpDispatchOutcome outcome = dispatcher().dispatch(call("get_overview"));

        // A gate refusal happens before the tool runs, so no tool ever asked for a status. The reason is
        // what a non-MCP transport reads; leaving the status null keeps "the tool refused with 404" and
        // "the dispatcher refused for you" distinguishable.
        assertThat(outcome)
                .isEqualTo(new ToolCallError("disabled:overview", McpDispatchOutcome.ToolErrorReason.PANEL_DISABLED));
        assertThat(((ToolCallError) outcome).status()).isNull();
    }

    @Test
    void readToolOnReadOnlyPanelStillRuns() {
        policy.readOnly.add("overview");

        assertThat(dispatcher().dispatch(call("get_overview"))).isInstanceOf(ToolCallResult.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void toolHandlerRuntimeExceptionBecomesDetailFreeInternalErrorAndIsReportedOnce() {
        IllegalStateException failure = new IllegalStateException(
                "password=hunter2 jdbc:postgresql://localhost/private /Users/admin/.ssh/id_rsa");
        McpTool boom = new McpTool("boom", "Boom.", McpToolSchema.NONE, "overview", false, args -> {
            throw failure;
        });
        McpDispatcher dispatcher = new McpDispatcher(List.of(boom), List.of(), policy, "1.0", "x", 50, 20, diagnostics);

        McpDispatchOutcome outcome = dispatcher.dispatch(call("boom"));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE));
        assertThat(outcome.toString())
                .doesNotContain("hunter2", "jdbc:postgresql", "/Users/admin", "IllegalStateException");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
        assertThat(diagnostics.operation()).isEqualTo("dispatching a request");
    }

    @Test
    void toolHandlerRuntimeExceptionForNotificationProducesNoResponseAndIsReportedOnce() {
        IllegalStateException failure =
                new IllegalStateException("SENSITIVE_NOTIFICATION_SENTINEL /private/runtime/path");
        McpTool boom = new McpTool("boom", "Boom.", McpToolSchema.NONE, "overview", false, args -> {
            throw failure;
        });
        McpDispatcher dispatcher = new McpDispatcher(List.of(boom), List.of(), policy, "1.0", "x", 50, 20, diagnostics);
        McpRequest notification = new McpRequest(JSONRPC, "tools/call", true, null, "boom", null, null, null);

        McpDispatchOutcome outcome = dispatcher.dispatch(notification);

        assertThat(outcome).isInstanceOf(NoResponse.class);
        assertThat(outcome.toString()).doesNotContain("SENSITIVE_NOTIFICATION_SENTINEL", "/private/runtime/path");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
        assertThat(diagnostics.operation()).isEqualTo("dispatching a request");
    }

    @Test
    void aCancelledToolIsNotReportedAsAServerFault() {
        McpTool cancelled = new McpTool("cancelled", "Cancelled.", McpToolSchema.NONE, "overview", false, args -> {
            throw new io.github.jdubois.bootui.engine.progress.OperationCancelledException();
        });
        McpDispatcher dispatcher =
                new McpDispatcher(List.of(cancelled), List.of(), policy, "1.0", "x", 50, 20, diagnostics);

        assertThat(dispatcher.dispatch(call("cancelled"))).isEqualTo(new McpDispatchOutcome.Cancelled());
        assertThat(diagnostics.count()).isZero();
        assertThat(dispatcher.runtimeStats().snapshot().cancellations()).isEqualTo(1);
    }

    @Test
    void aCancellationHandleStopsTheRunningToolAndReleasesItsPermitOnce() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Boolean> sawCancellation = new AtomicReference<>();
        McpTool slow = new McpTool("slow", "Slow.", McpToolSchema.NONE, "overview", false, args -> {
            io.github.jdubois.bootui.engine.progress.OperationProgress progress =
                    io.github.jdubois.bootui.engine.progress.OperationProgress.current();
            try {
                running.countDown();
                Thread.sleep(30_000);
                return "never";
            } catch (InterruptedException interrupted) {
                sawCancellation.set(progress.cancelled());
                return "interrupted";
            } finally {
                stopped.countDown();
            }
        });
        McpDispatcher dispatcher = new McpDispatcher(List.of(slow), List.of(), policy, "1.0", "x", 50, 1, diagnostics);
        McpCancellation cancellation = new McpCancellation();
        AtomicReference<McpDispatchOutcome> outcome = new AtomicReference<>();
        Thread caller = new Thread(() -> outcome.set(dispatcher.dispatch(call("slow"), cancellation)));
        caller.start();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        cancellation.cancel();
        cancellation.cancel();
        caller.join(5_000);

        assertThat(outcome.get()).isEqualTo(new McpDispatchOutcome.Cancelled());
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sawCancellation.get())
                .as("the tool's progress is cancelled too")
                .isTrue();
        McpRuntimeStats.Snapshot stats = dispatcher.runtimeStats().snapshot();
        assertThat(stats.cancellations()).isEqualTo(1);
        assertThat(stats.timeouts()).isZero();
        assertThat(stats.callCount()).isEqualTo(1);
        assertThat(diagnostics.count()).isZero();
        // The tool thread releases the permit after its finally block has run, so wait for it rather than racing it.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.availableCallPermits() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(dispatcher.availableCallPermits()).isEqualTo(1);
        assertThat(dispatcher.dispatch(call("slow"), cancelledBeforeStart()))
                .as("the single permit is free again, and a handle cancelled first stops the call at once")
                .isEqualTo(new McpDispatchOutcome.Cancelled());
        assertThat(dispatcher.runtimeStats().snapshot().callCount()).isEqualTo(2);
        cancellation.cancel();
    }

    private static McpCancellation cancelledBeforeStart() {
        McpCancellation cancellation = new McpCancellation();
        cancellation.cancel();
        return cancellation;
    }

    @Test
    void panelPolicyRuntimeExceptionBecomesDetailFreeInternalErrorAndIsReportedOnce() {
        IllegalStateException failure = new IllegalStateException("token=ghp_sensitive_policy_token");
        policy.failure = failure;

        McpDispatchOutcome outcome = dispatcher().dispatch(call("get_overview"));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE));
        assertThat(outcome.toString()).doesNotContain("ghp_sensitive_policy_token");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
    }

    @Test
    void toolHandlerErrorBecomesDetailFreeInternalErrorAndIsReportedOnce() {
        AssertionError failure = new AssertionError("SELECT secret FROM credentials");
        McpTool boom = new McpTool("boom", "Boom.", McpToolSchema.NONE, "overview", false, args -> {
            throw failure;
        });
        McpDispatcher dispatcher = new McpDispatcher(List.of(boom), List.of(), policy, "1.0", "x", 50, 20, diagnostics);

        McpDispatchOutcome outcome = dispatcher.dispatch(call("boom"));

        assertThat(outcome)
                .isEqualTo(new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE));
        assertThat(outcome.toString()).doesNotContain("SELECT", "credentials", "AssertionError");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
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

        assertThat(second)
                .isEqualTo(new ProtocolError(McpProtocol.SERVER_AT_CAPACITY, McpProtocol.RATE_LIMITED_MESSAGE));
        release.countDown();
        thread.join(5000);
        assertThat(firstOutcome.get()).isInstanceOf(ToolCallResult.class);
        assertThat(dispatcher.runtimeStats().snapshot().capacityRefusals()).isEqualTo(1);
    }

    @Test
    void toolCallsTimeOutAndAreRecorded() {
        McpTool blocking = new McpTool("slow", "Slow.", McpToolSchema.NONE, "overview", false, args -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return Map.of("ok", true);
        });
        McpDispatcher dispatcher =
                new McpDispatcher(List.of(blocking), List.of(), policy, "1.0", "x", 50, 1, 10, diagnostics);

        assertThat(dispatcher.dispatch(call("slow")))
                .isEqualTo(new ProtocolError(McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE));
        assertThat(dispatcher.runtimeStats().snapshot().timeouts()).isEqualTo(1);
    }

    @Test
    void interruptedDispatchReleasesCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        McpTool interruptible = new McpTool("slow", "Slow.", McpToolSchema.NONE, "overview", false, args -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }
            return Map.of("ok", true);
        });
        McpDispatcher dispatcher = new McpDispatcher(List.of(interruptible), policy, "1.0", "x", 50, 1);
        Thread interruptedCaller = new Thread(() -> dispatcher.dispatch(call("slow")));
        interruptedCaller.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        interruptedCaller.interrupt();
        interruptedCaller.join(5_000);
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();

        McpDispatchOutcome capacityError =
                new ProtocolError(McpProtocol.SERVER_AT_CAPACITY, McpProtocol.RATE_LIMITED_MESSAGE);
        McpDispatchOutcome retry;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            retry = dispatcher.dispatch(call("slow"));
            if (retry.equals(capacityError)) {
                Thread.sleep(1);
            }
        } while (retry.equals(capacityError) && System.nanoTime() < deadline);

        assertThat(retry).isInstanceOf(ToolCallResult.class);
        assertThat(dispatcher.runtimeStats().snapshot().callCount()).isEqualTo(2);
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

    private static McpRequest modern(String method) {
        return new McpRequest(
                JSONRPC, method, false, null, null, null, null, null, Set.of(), null, null, null, McpEra.MODERN, null);
    }

    private static McpRequest call(String toolName) {
        return new McpRequest(JSONRPC, "tools/call", false, null, toolName, null, null, null);
    }

    private static final class FakePolicy implements McpPanelPolicy {
        private final Set<String> disabled = new HashSet<>();
        private final Set<String> readOnly = new HashSet<>();
        private RuntimeException failure;

        @Override
        public boolean isEnabled(String panelId) {
            if (failure != null) {
                throw failure;
            }
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

    private static final class RecordingFailureReporter implements McpFailureReporter {
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicReference<String> operation = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void report(String operation, Throwable failure) {
            count.incrementAndGet();
            this.operation.set(operation);
            this.failure.set(failure);
        }

        private int count() {
            return count.get();
        }

        private String operation() {
            return operation.get();
        }

        private Throwable failure() {
            return failure.get();
        }
    }
}
