package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class BootUiMcpServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BootUiProperties properties;
    private BootUiMcpService service;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        List<McpTool> tools = List.of(
                new McpTool(
                        "get_overview",
                        "Read the overview.",
                        schema(),
                        BootUiPanels.OVERVIEW,
                        false,
                        args -> java.util.Map.of("name", "demo")),
                new McpTool(
                        "architecture_scan",
                        "Run the architecture advisor.",
                        schema(),
                        BootUiPanels.ARCHITECTURE,
                        true,
                        args -> java.util.Map.of("findings", List.of())));
        service = new BootUiMcpService(new BootUiMcpTools(tools), properties, objectMapper, "1.2.3");
    }

    @Test
    void initializeReturnsServerInfoAndEchoesProtocolVersion() {
        JsonNode response = service.handle(request("initialize", 1, params("protocolVersion", "2025-06-18")));

        assertThat(response.path("result").path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(response.path("result").path("serverInfo").path("name").asString())
                .isEqualTo("bootui");
        assertThat(response.path("result").path("serverInfo").path("version").asString())
                .isEqualTo("1.2.3");
        assertThat(response.path("result").path("capabilities").has("tools")).isTrue();
        assertThat(response.path("result").path("capabilities").has("prompts")).isTrue();
        assertThat(response.path("result").path("instructions").asString())
                .contains("get_overview", "do not modify code blindly", "may still contain sensitive data");
    }

    @Test
    void initializeFallsBackToDefaultProtocolVersion() {
        JsonNode response = service.handle(request("initialize", 1, JsonNodeFactory.instance.objectNode()));

        assertThat(response.path("result").path("protocolVersion").asString())
                .isEqualTo(BootUiMcpService.DEFAULT_PROTOCOL_VERSION);
    }

    @Test
    void toolsListAdvertisesEveryTool() {
        JsonNode response = service.handle(request("tools/list", 2, null));

        JsonNode toolsArray = response.path("result").path("tools");
        assertThat(toolsArray).hasSize(2);
        assertThat(toolsArray.get(0).path("name").asString()).isEqualTo("get_overview");
        assertThat(toolsArray.get(0).path("inputSchema").path("type").asString())
                .isEqualTo("object");
        assertThat(toolsArray.get(0).path("outputSchema").path("description").asString())
                .contains("get_overview");
    }

    @Test
    void promptsListAndGetExposeDiagnosticWorkflows() {
        JsonNode list = service.handle(request("prompts/list", 3, null));

        JsonNode prompts = list.path("result").path("prompts");
        assertThat(prompts).hasSize(3);
        assertThat(prompts.get(0).path("name").asString()).isEqualTo("diagnose_runtime_issue");
        assertThat(prompts.get(1).path("name").asString()).isEqualTo("review_application");
        assertThat(prompts.get(2).path("name").asString()).isEqualTo("assess_application");
        assertThat(prompts.get(0).path("arguments").isArray()).isTrue();
        assertThat(prompts.get(0).path("arguments")).hasSize(0);

        JsonNode prompt = service.handle(request("prompts/get", 4, params("name", "diagnose_runtime_issue")));
        assertThat(prompt.path("result").path("messages").get(0).path("role").asString())
                .isEqualTo("user");
        assertThat(prompt.path("result")
                        .path("messages")
                        .get(0)
                        .path("content")
                        .path("text")
                        .asString())
                .contains("get_live_activity", "Separate observed evidence from hypotheses");

        JsonNode assessment = service.handle(request("prompts/get", 5, params("name", "assess_application")));
        assertThat(assessment
                        .path("result")
                        .path("messages")
                        .get(0)
                        .path("content")
                        .path("text")
                        .asString())
                .contains("STOP after presenting the plan", "specific plan version");
    }

    @Test
    void toolsCallReturnsSerializedDtoContent() {
        JsonNode response = service.handle(callRequest("get_overview", 3));

        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        String text = result.path("content").get(0).path("text").asString();
        assertThat(text).contains("\"name\":\"demo\"");
    }

    @Test
    void toolsCallOnDisabledPanelReturnsInBandError() {
        properties.panel(BootUiPanels.OVERVIEW).setEnabled(false);

        JsonNode response = service.handle(callRequest("get_overview", 4));

        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asString())
                .contains("bootui.panels.overview.enabled=false");
    }

    @Test
    void actionToolOnReadOnlyPanelIsRefused() {
        properties.panel(BootUiPanels.ARCHITECTURE).setReadOnly(true);

        JsonNode response = service.handle(callRequest("architecture_scan", 5));

        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asString()).contains("read-only");
    }

    @Test
    void readToolOnReadOnlyPanelStillSucceeds() {
        properties.panel(BootUiPanels.OVERVIEW).setReadOnly(true);

        JsonNode response = service.handle(callRequest("get_overview", 6));

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
    }

    @Test
    void toolClientErrorIsRenderedInBandInsteadOfAnInternalError() {
        BootUiMcpService failing = new BootUiMcpService(
                new BootUiMcpTools(List.of(new McpTool(
                        "get_exception_detail",
                        "Read one exception group's detail.",
                        McpToolSchema.ID,
                        BootUiPanels.EXCEPTIONS,
                        false,
                        SpringMcpToolFailures.translating(args -> {
                            throw new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "exception " + args.id() + " not found");
                        })))),
                properties,
                objectMapper,
                "1.2.3");
        ObjectNode request = callRequest("get_exception_detail", 8);
        ((ObjectNode) request.path("params").path("arguments")).put("id", "does-not-exist");

        JsonNode response = failing.handle(request);

        assertThat(response.path("error").isMissingNode()).isTrue();
        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asString())
                .isEqualTo("exception does-not-exist not found");
    }

    @Test
    void unknownToolReturnsInvalidParamsError() {
        JsonNode response = service.handle(callRequest("does_not_exist", 7));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString()).contains("Unknown tool");
    }

    @Test
    void toolCallRejectsNonObjectAndUnexpectedArguments() {
        ObjectNode nonObject = request("tools/call", 71, params("name", "get_overview"));
        ((ObjectNode) nonObject.path("params")).put("arguments", "invalid");
        assertThat(service.handle(nonObject).path("error").path("message").asString())
                .isEqualTo(McpProtocol.ARGUMENTS_OBJECT_MESSAGE);

        ObjectNode unexpected = callRequest("get_overview", 72);
        ((ObjectNode) unexpected.path("params").path("arguments")).put("extra", true);
        assertThat(service.handle(unexpected).path("error").path("message").asString())
                .isEqualTo("Unexpected tool argument: extra");
    }

    @Test
    void rejectsInvalidIdAndParamsTypes() {
        ObjectNode invalidId = request("ping", 73, null);
        invalidId.put("id", true);
        assertThat(service.handle(invalidId).path("error").path("message").asString())
                .isEqualTo(McpProtocol.INVALID_ID_MESSAGE);

        ObjectNode invalidParams = request("ping", 74, null);
        invalidParams.put("params", "invalid");
        assertThat(service.handle(invalidParams).path("error").path("message").asString())
                .isEqualTo(McpProtocol.PARAMS_OBJECT_MESSAGE);
    }

    @Test
    void rejectsNonPositiveLimit() {
        List<McpTool> tools = List.of(new McpTool(
                "get_config",
                "Read configuration.",
                McpToolSchema.QUERY_LIMIT,
                BootUiPanels.CONFIG,
                false,
                args -> java.util.Map.of("limit", args.limit())));
        BootUiMcpService strict = new BootUiMcpService(new BootUiMcpTools(tools), properties, objectMapper, "1.2.3");
        ObjectNode request = callRequest("get_config", 75);
        ((ObjectNode) request.path("params").path("arguments")).put("limit", 0);

        assertThat(strict.handle(request).path("error").path("message").asString())
                .isEqualTo("Argument 'limit' must be at least 1");
    }

    @Test
    void rejectsOversizedRenderedResponse() {
        BootUiProperties bounded = new BootUiProperties();
        bounded.getMcp().setMaxResponseBytes(128);
        McpTool large = new McpTool(
                "get_overview",
                "Read overview.",
                McpToolSchema.NONE,
                BootUiPanels.OVERVIEW,
                false,
                args -> java.util.Map.of("value", "x".repeat(512)));
        BootUiMcpService boundedService =
                new BootUiMcpService(new BootUiMcpTools(List.of(large)), bounded, objectMapper, "1.2.3");

        JsonNode response = boundedService.handle(callRequest("get_overview", 76));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(McpProtocol.RESPONSE_TOO_LARGE);
        assertThat(boundedService.dispatcher().runtimeStats().snapshot().responseLimitRefusals())
                .isEqualTo(1);
    }

    @Test
    void unknownMethodReturnsJsonRpcMethodNotFound() {
        JsonNode response = service.handle(request("tools/unknown", 8, null));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void notificationsProduceNoResponse() {
        JsonNode notification = request("notifications/initialized", null, null);

        assertThat(service.handle(notification)).isNull();
    }

    @Test
    void toolHandlerExceptionReturnsCanonicalInternalErrorWithoutSensitiveDetails() {
        IllegalStateException failure =
                new IllegalStateException("token=ghp_secret jdbc:mysql://localhost/private /home/admin/key.pem");
        List<McpTool> tools = List.of(new McpTool(
                "get_overview", "Read the overview.", McpToolSchema.NONE, BootUiPanels.OVERVIEW, false, args -> {
                    throw failure;
                }));
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        BootUiMcpService failing = new BootUiMcpService(tools, properties, objectMapper, "1.2.3", diagnostics);

        JsonNode response = failing.handle(callRequest("get_overview", 9));

        assertThat(response.toString())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}");
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(response.path("error").path("message").asString()).isEqualTo("Internal error");
        assertThat(response.toString())
                .doesNotContain("ghp_secret", "jdbc:mysql", "/home/admin", "IllegalStateException");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
        assertThat(diagnostics.operation()).isEqualTo("dispatching a request");
    }

    @Test
    void toolHandlerExceptionForNotificationProducesNoBodyAndIsReportedOnce() {
        IllegalStateException failure =
                new IllegalStateException("SENSITIVE_NOTIFICATION_SENTINEL /private/spring/runtime/path");
        List<McpTool> tools = List.of(new McpTool(
                "get_overview", "Read the overview.", McpToolSchema.NONE, BootUiPanels.OVERVIEW, false, args -> {
                    throw failure;
                }));
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        BootUiMcpService failing = new BootUiMcpService(tools, properties, objectMapper, "1.2.3", diagnostics);

        JsonNode response = failing.handle(callNotification("get_overview"));

        assertThat(response).isNull();
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
        assertThat(diagnostics.failure().getMessage())
                .as("the original detail remains available only to server diagnostics")
                .contains("SENSITIVE_NOTIFICATION_SENTINEL");
        assertThat(diagnostics.operation()).isEqualTo("dispatching a request");
    }

    @Test
    void toolResultSerializationFailureReturnsCanonicalInternalErrorAndIsReportedOnce() {
        List<McpTool> tools = List.of(new McpTool(
                "get_overview",
                "Read the overview.",
                McpToolSchema.NONE,
                BootUiPanels.OVERVIEW,
                false,
                args -> new Object() {
                    @SuppressWarnings("unused")
                    public String getValue() {
                        throw new IllegalStateException(
                                "password=hunter2 SELECT * FROM secrets /Users/admin/private.txt");
                    }
                }));
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        BootUiMcpService failing = new BootUiMcpService(tools, properties, objectMapper, "1.2.3", diagnostics);

        JsonNode response = failing.handle(callRequest("get_overview", 10));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(response.path("error").path("message").asString()).isEqualTo("Internal error");
        assertThat(response.toString()).doesNotContain("hunter2", "SELECT", "/Users/admin", "IllegalStateException");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isNotNull();
        assertThat(diagnostics.operation()).isEqualTo("serializing a tool result");
    }

    private static McpToolSchema schema() {
        return McpToolSchema.NONE;
    }

    private ObjectNode callRequest(String toolName, int id) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", toolName);
        params.set("arguments", JsonNodeFactory.instance.objectNode());
        return request("tools/call", id, params);
    }

    private ObjectNode callNotification(String toolName) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", toolName);
        params.set("arguments", JsonNodeFactory.instance.objectNode());
        return request("tools/call", null, params);
    }

    private ObjectNode params(String key, String value) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put(key, value);
        return params;
    }

    private ObjectNode request(String method, Integer id, JsonNode params) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (id != null) {
            request.put("id", id);
        }
        if (params != null) {
            request.set("params", params);
        }
        return request;
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
