package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.conformance.McpCodecParity;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class BootUiMcpServiceTests {

    @Test
    void comparisonAdvertisesAnOptionalIdAndAcceptsOmission() {
        AtomicReference<McpArguments> received = new AtomicReference<>();
        BootUiMcpService comparison = new BootUiMcpService(
                List.of(new McpTool(
                        "get_runtime_run_comparison",
                        "Compare.",
                        McpToolSchema.OPTIONAL_ID,
                        BootUiPanels.RUNTIME_INSIGHTS,
                        false,
                        args -> {
                            received.set(args);
                            return java.util.Map.of("selected", args.id() == null ? "previous" : args.id());
                        })),
                properties,
                objectMapper,
                "1.2.3",
                (operation, failure) -> {
                    throw new AssertionError(failure);
                });
        JsonNode schema = comparison
                .handle(request("tools/list", 1, null))
                .path("result")
                .path("tools")
                .get(0)
                .path("inputSchema");
        assertThat(schema.has("required")).isFalse();
        assertThat(schema.path("properties").has("id")).isTrue();
        JsonNode reply = comparison.handle(
                objectMapper.readTree(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_runtime_run_comparison\"}}"));
        assertThat(reply.has("error")).isFalse();
        assertThat(received.get().id()).isNull();
    }

    @Test
    void advisorSchemaAndCodecProjectAllRequiredAndOptionalPageArguments() {
        AtomicReference<McpArguments> received = new AtomicReference<>();
        BootUiMcpService pages = advisorService(args -> {
            received.set(args);
            return java.util.Map.of("scanId", args.scanId(), "offset", args.offset());
        });
        JsonNode listed = pages.handle(request("tools/list", 1, null));
        JsonNode schema = listed.path("result").path("tools").get(0).path("inputSchema");
        assertThat(schema.path("required").toString()).isEqualTo("[\"id\",\"scanId\"]");
        assertThat(schema.path("properties").propertyNames()).containsExactly("id", "scanId", "offset", "limit");
        assertThat(schema.path("properties").path("offset").path("minimum").asInt())
                .isZero();
        assertThat(schema.path("properties").path("limit").path("minimum").asInt())
                .isEqualTo(1);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        pages.handle(advisorRequest("{\"id\":\" RULE-1 \",\"scanId\":\" scan-1 \"}"));
        assertThat(received.get()).isEqualTo(new McpArguments(null, 100, "RULE-1", "scan-1", 0));
        pages.handle(advisorRequest("{\"id\":\"RULE-1\",\"scanId\":\"scan-1\",\"offset\":22,\"limit\":7}"));
        assertThat(received.get()).isEqualTo(new McpArguments(null, 7, "RULE-1", "scan-1", 22));
    }

    @Test
    void advisorCodecRejectsMalformedMissingNullUnknownAndOverflowingPageArguments() {
        AtomicInteger invoked = new AtomicInteger();
        BootUiMcpService pages = advisorService(args -> invoked.incrementAndGet());
        for (String arguments : List.of(
                "null",
                "[]",
                "{}",
                "{\"id\":\"RULE-1\"}",
                "{\"scanId\":\"scan-1\"}",
                "{\"id\":\"RULE-1\",\"scanId\":\"scan-1\",\"extra\":1}",
                "{\"id\":\"RULE-1\",\"scanId\":\"scan-1\",\"query\":\"x\"}")) {
            assertThat(pages.handle(advisorRequest(arguments))
                            .path("error")
                            .path("code")
                            .asInt())
                    .as(arguments)
                    .isEqualTo(McpProtocol.INVALID_PARAMS);
        }
        for (String field : List.of("id", "scanId", "offset", "limit")) {
            List<String> invalid = field.equals("id") || field.equals("scanId")
                    ? List.of("null", "3", "true", "\"\"", "\" \"")
                    : List.of("null", "\"3\"", "true", "1.5", "2147483648", "-2147483649", "-1");
            for (String value : invalid) {
                ObjectNode arguments =
                        objectMapper.createObjectNode().put("id", "RULE-1").put("scanId", "scan-1");
                arguments.set(field, objectMapper.readTree(value));
                assertThat(pages.handle(advisorRequest(arguments.toString()))
                                .path("error")
                                .path("code")
                                .asInt())
                        .as("%s=%s", field, value)
                        .isEqualTo(McpProtocol.INVALID_PARAMS);
            }
        }
        assertThat(pages.handle(advisorRequest("{\"id\":\"RULE-1\",\"scanId\":\"scan\",\"limit\":0}"))
                        .path("error")
                        .path("message")
                        .asString())
                .isEqualTo("Argument 'limit' must be at least 1");
        assertThat(invoked).hasValue(0);
    }

    @Test
    void advisorByteBudgetRefusalAllowsSmallerPageAtTheSameOffsetAndScan() {
        properties.getMcp().setMaxResponseBytes(2048);
        AtomicReference<McpArguments> received = new AtomicReference<>();
        BootUiMcpService pages = advisorService(args -> {
            received.set(args);
            return java.util.Map.of("violations", java.util.Collections.nCopies(args.limit(), "x".repeat(256)));
        });
        assertThat(pages.handle(advisorRequest("{\"id\":\"RULE-1\",\"scanId\":\"scan-1\",\"offset\":22}"))
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(McpProtocol.RESPONSE_TOO_LARGE);
        JsonNode retry =
                pages.handle(advisorRequest("{\"id\":\"RULE-1\",\"scanId\":\"scan-1\",\"offset\":22,\"limit\":1}"));
        assertThat(retry.path("result").path("isError").asBoolean()).isFalse();
        assertThat(retry.has("error")).isFalse();
        assertThat(received.get()).isEqualTo(new McpArguments(null, 1, "RULE-1", "scan-1", 22));
    }

    @Test
    void advisorStaleSnapshotIsAnInBandClientErrorNotAnInternalFailure() {
        BootUiMcpService pages = advisorService(args -> {
            throw new AdvisorViolationException(409, "Reread the cached report.");
        });
        JsonNode response = pages.handle(advisorRequest("{\"id\":\"RULE-1\",\"scanId\":\"old\"}"));
        assertThat(response.has("error")).isFalse();
        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asString())
                .isEqualTo("Reread the cached report.");
    }

    private BootUiMcpService advisorService(java.util.function.Function<McpArguments, Object> handler) {
        return new BootUiMcpService(
                List.of(new McpTool(
                        "get_architecture_rule_violations",
                        "Read retained violations.",
                        McpToolSchema.RULE_VIOLATIONS,
                        BootUiPanels.ARCHITECTURE,
                        false,
                        handler)),
                properties,
                objectMapper,
                "1.2.3",
                (operation, failure) -> {
                    throw new AssertionError("Unexpected server failure: " + operation, failure);
                });
    }

    private JsonNode advisorRequest(String arguments) {
        return objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"get_architecture_rule_violations\",\"arguments\":" + arguments + "}}");
    }

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
    void toolsListRendersHintsAndPerToolArgumentSchemas() {
        JsonNode tools =
                service.handle(request("tools/list", 2, null)).path("result").path("tools");

        assertThat(tools.get(0).path("annotations").toString())
                .isEqualTo("{\"readOnlyHint\":true,\"destructiveHint\":false,\"idempotentHint\":true,"
                        + "\"openWorldHint\":false}");
        assertThat(tools.get(1).path("annotations").path("readOnlyHint").asBoolean())
                .isFalse();
        JsonNode properties = advisorService(args -> args)
                .handle(request("tools/list", 3, null))
                .path("result")
                .path("tools")
                .get(0)
                .path("inputSchema")
                .path("properties");
        assertThat(properties.path("id").path("description").asString())
                .isEqualTo("The rule id, from get_architecture_report.");
        assertThat(properties.path("offset").path("default").asInt()).isZero();
        assertThat(properties.path("limit").path("default").asInt()).isEqualTo(100);
    }

    @Test
    void toolsListRendersEveryCatalogSchemaAndHintByteForByteLikeTheQuarkusCodec() {
        properties.getMcp().setMaxResults(McpCodecParity.MAX_RESULTS);
        BootUiMcpService catalog = new BootUiMcpService(
                McpCodecParity.tools(McpToolDescriptions::spring),
                properties,
                objectMapper,
                "1.2.3",
                (operation, failure) -> {
                    throw new AssertionError(failure);
                });
        ArrayNode rendered = JsonNodeFactory.instance.arrayNode();
        catalog.handle(request("tools/list", 4, null))
                .path("result")
                .path("tools")
                .forEach(tool -> {
                    ObjectNode projection = rendered.addObject();
                    projection.set("name", tool.path("name"));
                    projection.set("inputSchema", tool.path("inputSchema"));
                    projection.set("annotations", tool.path("annotations"));
                });

        assertThat(objectMapper.writeValueAsString(rendered)).isEqualTo(McpCodecParity.expected());
    }

    @Test
    void anUnadvertisedCatalogToolAnswersWithItsPanelsReasonInMessageAndData() {
        BootUiMcpService empty = new BootUiMcpService(
                List::of,
                panelId -> "No KafkaTemplate bean is available",
                properties,
                objectMapper,
                "1.2.3",
                (operation, failure) -> {
                    throw new AssertionError(failure);
                });
        JsonNode response = empty.handle(objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"method\":\"tools/call\",\"params\":{\"name\":\"get_kafka_activity\"}}"));

        assertThat(response.path("error").toString())
                .isEqualTo("{\"code\":-32602,\"message\":\"Tool not available in this application: "
                        + "get_kafka_activity. Its Kafka panel is unavailable: No KafkaTemplate bean is available.\","
                        + "\"data\":{\"tool\":\"get_kafka_activity\",\"panel\":\"kafka\","
                        + "\"reason\":\"No KafkaTemplate bean is available\"}}");
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
        assertThat(prompts).hasSize(4);
        assertThat(prompts.get(0).path("name").asString()).isEqualTo("diagnose_runtime_issue");
        assertThat(prompts.get(1).path("name").asString()).isEqualTo("verify_after_change");
        assertThat(prompts.get(2).path("name").asString()).isEqualTo("review_application");
        assertThat(prompts.get(3).path("name").asString()).isEqualTo("assess_application");
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

    @Test
    void modernDiscoverAndListsCarryResultTypeServerInfoAndCacheHints() {
        BootUiMcpService.Reply discover = modern("server/discover", 1, null, true);
        assertThat(discover.status()).isEqualTo(200);
        JsonNode result = discover.body().path("result");
        assertThat(result.propertyNames())
                .containsExactly(
                        "resultType",
                        "supportedVersions",
                        "capabilities",
                        "instructions",
                        "_meta",
                        "ttlMs",
                        "cacheScope");
        assertThat(result.path("supportedVersions").toString()).isEqualTo("[\"2026-07-28\",\"2025-06-18\"]");
        assertThat(result.path("capabilities").toString())
                .isEqualTo("{\"tools\":{\"listChanged\":false},\"prompts\":{\"listChanged\":false}}");
        assertThat(result.path("_meta").toString())
                .isEqualTo("{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"bootui\",\"version\":\"1.2.3\"}}");

        JsonNode tools = modern("tools/list", 2, null, true).body().path("result");
        assertThat(tools.propertyNames()).containsExactly("resultType", "tools", "_meta", "ttlMs", "cacheScope");
        assertThat(tools.path("ttlMs").asLong()).isEqualTo(60_000);
        assertThat(tools.path("cacheScope").asString()).isEqualTo("private");
        assertThat(tools.path("tools").get(0).path("name").asString()).isEqualTo("get_overview");
        JsonNode prompts = modern("prompts/list", 3, null, true).body().path("result");
        assertThat(prompts.propertyNames()).containsExactly("resultType", "prompts", "_meta", "ttlMs", "cacheScope");
    }

    @Test
    void modernToolCallsAreUncacheableCompleteResults() {
        BootUiMcpService.Reply call = modern("tools/call", 4, "get_overview", true);
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.body().toString())
                .isEqualTo(
                        "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"resultType\":\"complete\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"name\\\":\\\"demo\\\"}\"}],"
                                + "\"structuredContent\":{\"name\":\"demo\"},\"isError\":false,"
                                + "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"bootui\",\"version\":\"1.2.3\"}}}}");
        properties.panel(BootUiPanels.OVERVIEW).setEnabled(false);
        JsonNode refused = modern("tools/call", 5, "get_overview", true).body().path("result");
        assertThat(refused.path("resultType").asString()).isEqualTo("complete");
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(refused.has("ttlMs")).isFalse();
    }

    @Test
    void modernErrorsUseModernCodesAndStatuses() {
        BootUiMcpService.Reply unknown = modern("ping", 6, null, true);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().toString())
                .isEqualTo(
                        "{\"jsonrpc\":\"2.0\",\"id\":6,\"error\":{\"code\":-32601,\"message\":\"Unknown method: ping\"}}");
        BootUiMcpService.Reply disabled = modern("tools/list", 7, null, false);
        assertThat(disabled.status()).isEqualTo(200);
        assertThat(disabled.body().path("error").path("code").asInt()).isEqualTo(-31000);
        assertThat(service.exchange(request("ping", 8, null), McpRequestHeaders.NONE, false)
                        .body()
                        .path("error")
                        .path("code")
                        .asInt())
                .as("legacy clients keep -32000")
                .isEqualTo(-32000);
    }

    @Test
    void modernRejectionsAre400sThatEchoTheRequestId() {
        ObjectNode unsupported = modernRequest("tools/list", "a", null, "2099-01-01");
        BootUiMcpService.Reply reply = service.exchange(
                unsupported, new McpRequestHeaders(List.of("2099-01-01"), List.of("tools/list"), List.of()), true);
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body().toString())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"error\":{\"code\":-32022,"
                        + "\"message\":\"Unsupported protocol version\","
                        + "\"data\":{\"supported\":[\"2026-07-28\",\"2025-06-18\"],\"requested\":\"2099-01-01\"}}}");

        BootUiMcpService.Reply mismatch = service.exchange(
                modernRequest("tools/call", 9, "get_overview", "2026-07-28"),
                new McpRequestHeaders(List.of("2026-07-28"), List.of("tools/call"), List.of("architecture_scan")),
                false);
        assertThat(mismatch.status())
                .as("validation precedes the disabled short-circuit")
                .isEqualTo(400);
        assertThat(mismatch.body().path("error").path("code").asInt()).isEqualTo(-32020);
        assertThat(mismatch.body().path("id").asInt()).isEqualTo(9);

        BootUiMcpService.Reply legacy = service.exchange(
                request("ping", 10, null), new McpRequestHeaders(List.of("2099-01-01"), List.of(), List.of()), true);
        assertThat(legacy.status()).isEqualTo(400);
        assertThat(legacy.body().toString())
                .as("legacy rejections keep BootUI 1.x's bytes")
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                        + "\"message\":\"Unsupported MCP-Protocol-Version\"}}");
    }

    @Test
    void containerValuedEnvelopeFieldsAreClientErrorsLikeOnQuarkus() {
        for (String method : List.of("{}", "[]")) {
            JsonNode notification = objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":" + method + "}");
            assertThat(service.exchange(notification, McpRequestHeaders.NONE, true))
                    .isEqualTo(new BootUiMcpService.Reply(202, null));
            JsonNode request = objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":" + method + "}");
            BootUiMcpService.Reply reply = service.exchange(request, McpRequestHeaders.NONE, true);
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.body().toString())
                    .isEqualTo(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Missing 'method'\"}}");
        }
        JsonNode objectJsonrpc = objectMapper.readTree("{\"jsonrpc\":{},\"id\":2,\"method\":\"ping\"}");
        assertThat(service.exchange(objectJsonrpc, McpRequestHeaders.NONE, true)
                        .body()
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(McpProtocol.INVALID_REQUEST);
        JsonNode objectName = objectMapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":{}}}");
        assertThat(service.exchange(objectName, McpRequestHeaders.NONE, true)
                        .body()
                        .toString())
                .isEqualTo(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32602,\"message\":\"Missing tool name\"}}");
    }

    private BootUiMcpService.Reply modern(String method, int id, String name, boolean enabled) {
        return service.exchange(
                modernRequest(method, id, name, "2026-07-28"),
                new McpRequestHeaders(List.of("2026-07-28"), List.of(method), name == null ? List.of() : List.of(name)),
                enabled);
    }

    private ObjectNode modernRequest(String method, Object id, String name, String version) {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        if (name != null) {
            params.put("name", name);
            params.set("arguments", JsonNodeFactory.instance.objectNode());
        }
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", version);
        meta.putObject("io.modelcontextprotocol/clientCapabilities");
        ObjectNode request = request(method, null, params);
        if (id instanceof Integer number) {
            request.put("id", number);
        } else {
            request.put("id", String.valueOf(id));
        }
        return request;
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
