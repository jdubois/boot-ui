package io.github.jdubois.bootui.quarkus.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class QuarkusMcpEnvelopeTest {

    @Test
    void advisorSchemaAndCodecProjectAllRequiredAndOptionalPageArguments() throws Exception {
        AtomicReference<McpArguments> received = new AtomicReference<>();
        QuarkusMcpEnvelope pages = advisorEnvelope(262144, args -> {
            received.set(args);
            return java.util.Map.of("scanId", args.scanId(), "offset", args.offset());
        });
        JsonNode listed =
                pages.handle(objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        JsonNode schema = listed.path("result").path("tools").get(0).path("inputSchema");
        assertThat(schema.path("required").toString()).isEqualTo("[\"id\",\"scanId\"]");
        List<String> names = new java.util.ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("id", "scanId", "offset", "limit");
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
    void advisorCodecRejectsMalformedMissingNullUnknownAndOverflowingPageArguments() throws Exception {
        AtomicInteger invoked = new AtomicInteger();
        QuarkusMcpEnvelope pages = advisorEnvelope(262144, args -> invoked.incrementAndGet());
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
                        .asText())
                .isEqualTo("Argument 'limit' must be at least 1");
        assertThat(invoked).hasValue(0);
    }

    @Test
    void advisorByteBudgetRefusalAllowsSmallerPageAtTheSameOffsetAndScan() throws Exception {
        AtomicReference<McpArguments> received = new AtomicReference<>();
        QuarkusMcpEnvelope pages = advisorEnvelope(2048, args -> {
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
    void advisorStaleSnapshotIsAnInBandClientErrorNotAnInternalFailure() throws Exception {
        QuarkusMcpEnvelope pages = advisorEnvelope(262144, args -> {
            throw new AdvisorViolationException(409, "Reread the cached report.");
        });
        JsonNode response = pages.handle(advisorRequest("{\"id\":\"RULE-1\",\"scanId\":\"old\"}"));
        assertThat(response.has("error")).isFalse();
        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .isEqualTo("Reread the cached report.");
    }

    @Test
    void toolsListRendersHintsAndPerToolArgumentSchemas() throws Exception {
        JsonNode tool = advisorEnvelope(262144, args -> args)
                .handle(objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .path("result")
                .path("tools")
                .get(0);
        assertThat(tool.path("annotations").toString())
                .isEqualTo("{\"readOnlyHint\":true,\"destructiveHint\":false,\"idempotentHint\":true,"
                        + "\"openWorldHint\":false}");
        JsonNode properties = tool.path("inputSchema").path("properties");
        assertThat(properties.path("id").path("description").asText())
                .isEqualTo("The rule id, from get_architecture_report.");
        assertThat(properties.path("offset").path("default").asInt()).isZero();
        assertThat(properties.path("limit").path("default").asInt()).isEqualTo(100);
    }

    @Test
    void anUnadvertisedCatalogToolAnswersWithItsPanelsReasonInMessageAndData() throws Exception {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        McpDispatcher dispatcher = new McpDispatcher(
                List::of,
                List.of(),
                new AllowAllPolicy(),
                "1.2.3",
                "",
                250,
                20,
                30_000,
                diagnostics,
                panelId -> "No KafkaTemplate bean is available");
        JsonNode response = new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics, 262144)
                .handle(objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"get_kafka_activity\"}}"));
        assertThat(response.path("error").toString())
                .isEqualTo("{\"code\":-32602,\"message\":\"Tool not available in this application: "
                        + "get_kafka_activity. Its Kafka panel is unavailable: No KafkaTemplate bean is available.\","
                        + "\"data\":{\"tool\":\"get_kafka_activity\",\"panel\":\"kafka\","
                        + "\"reason\":\"No KafkaTemplate bean is available\"}}");
    }

    private QuarkusMcpEnvelope advisorEnvelope(
            int maxBytes, java.util.function.Function<McpArguments, Object> handler) {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(new McpTool(
                        "get_architecture_rule_violations",
                        "Read retained violations.",
                        McpToolSchema.RULE_VIOLATIONS,
                        BootUiPanels.ARCHITECTURE,
                        false,
                        handler)),
                List.of(),
                new AllowAllPolicy(),
                "1.2.3",
                "",
                250,
                20,
                diagnostics);
        return new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics, maxBytes);
    }

    private JsonNode advisorRequest(String arguments) throws Exception {
        return objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"get_architecture_rule_violations\",\"arguments\":" + arguments + "}}");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toolHandlerExceptionReturnsCanonicalInternalErrorWithoutSensitiveDetails() {
        IllegalStateException failure =
                new IllegalStateException("token=ghp_secret jdbc:postgresql://localhost/private /home/admin/key.pem");
        McpTool tool = tool(args -> {
            throw failure;
        });
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool, diagnostics);

        JsonNode response = envelope.handle(callRequest(41));

        assertThat(response.toString())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":41,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}")
                .doesNotContain("ghp_secret", "jdbc:postgresql", "/home/admin", "IllegalStateException");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isSameAs(failure);
        assertThat(diagnostics.operation()).isEqualTo("dispatching a request");
    }

    @Test
    void toolHandlerExceptionForNotificationProducesNoBodyAndIsReportedOnce() {
        IllegalStateException failure =
                new IllegalStateException("SENSITIVE_NOTIFICATION_SENTINEL /private/quarkus/runtime/path");
        McpTool tool = tool(args -> {
            throw failure;
        });
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool, diagnostics);

        JsonNode response = envelope.handle(callNotification());

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
        McpTool tool = tool(args -> new Object() {
            @SuppressWarnings("unused")
            public String getValue() {
                throw new IllegalStateException("password=hunter2 SELECT * FROM secrets /Users/admin/private.txt");
            }
        });
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool, diagnostics);

        JsonNode response = envelope.handle(callRequest(42));

        assertThat(response.toString())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":42,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}")
                .doesNotContain("hunter2", "SELECT", "/Users/admin", "IllegalStateException");
        assertThat(diagnostics.count()).isEqualTo(1);
        assertThat(diagnostics.failure()).isNotNull();
        assertThat(diagnostics.operation()).isEqualTo("serializing a tool result");
    }

    @Test
    void expectedUnknownToolErrorRetainsItsActionableMessageWithoutDiagnostics() {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool(args -> "ok"), diagnostics);
        ObjectNode request = callRequest(43);
        ((ObjectNode) request.path("params")).put("name", "does_not_exist");

        JsonNode response = envelope.handle(request);

        assertThat(response.path("error").path("code").asInt()).isEqualTo(McpProtocol.INVALID_PARAMS);
        assertThat(response.path("error").path("message").asText()).isEqualTo("Unknown tool: does_not_exist");
        assertThat(diagnostics.count()).isZero();
    }

    @Test
    void toolClientErrorIsRenderedInBandInsteadOfAnInternalError() {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        McpTool tool = tool(QuarkusMcpToolFailures.translating(args -> {
            throw new NotFoundException("exception does-not-exist not found");
        }));
        QuarkusMcpEnvelope envelope = envelope(tool, diagnostics);

        JsonNode response = envelope.handle(callRequest(50));

        assertThat(response.path("error").isMissingNode()).isTrue();
        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("exception does-not-exist not found");
        assertThat(diagnostics.count())
                .as("a client error is not a server fault and must not be reported as one")
                .isZero();
    }

    @Test
    void malformedToolArgumentsAreRejected() {
        QuarkusMcpEnvelope envelope = envelope(tool(args -> "ok"), new RecordingFailureReporter());
        ObjectNode nonObject = callRequest(44);
        ((ObjectNode) nonObject.path("params")).put("arguments", "invalid");
        assertThat(envelope.handle(nonObject).path("error").path("message").asText())
                .isEqualTo(McpProtocol.ARGUMENTS_OBJECT_MESSAGE);

        ObjectNode unexpected = callRequest(45);
        ((ObjectNode) unexpected.path("params").path("arguments")).put("extra", true);
        assertThat(envelope.handle(unexpected).path("error").path("message").asText())
                .isEqualTo("Unexpected tool argument: extra");
    }

    @Test
    void invalidIdAndParamsTypesAreRejected() {
        QuarkusMcpEnvelope envelope = envelope(tool(args -> "ok"), new RecordingFailureReporter());
        ObjectNode invalidId = callRequest(46);
        invalidId.put("id", true);
        assertThat(envelope.handle(invalidId).path("error").path("message").asText())
                .isEqualTo(McpProtocol.INVALID_ID_MESSAGE);

        ObjectNode invalidParams = callRequest(47);
        invalidParams.put("params", "invalid");
        assertThat(envelope.handle(invalidParams).path("error").path("message").asText())
                .isEqualTo(McpProtocol.PARAMS_OBJECT_MESSAGE);
    }

    @Test
    void nonPositiveLimitIsRejected() {
        McpTool tool = new McpTool(
                "get_config",
                "Read configuration.",
                McpToolSchema.QUERY_LIMIT,
                BootUiPanels.CONFIG,
                false,
                args -> java.util.Map.of("limit", args.limit()));
        QuarkusMcpEnvelope envelope = envelope(tool, new RecordingFailureReporter());
        ObjectNode request = callRequest(48);
        ((ObjectNode) request.path("params")).put("name", "get_config");
        ((ObjectNode) request.path("params").path("arguments")).put("limit", 0);

        assertThat(envelope.handle(request).path("error").path("message").asText())
                .isEqualTo("Argument 'limit' must be at least 1");
    }

    @Test
    void oversizedRenderedResponseIsRejected() {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        McpTool large = tool(args -> java.util.Map.of("value", "x".repeat(512)));
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(large), List.of(), new AllowAllPolicy(), "1.2.3", "instructions", 50, 20, diagnostics);
        QuarkusMcpEnvelope envelope = new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics, 128);

        JsonNode response = envelope.handle(callRequest(49));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(McpProtocol.RESPONSE_TOO_LARGE);
        assertThat(dispatcher.runtimeStats().snapshot().responseLimitRefusals()).isEqualTo(1);
    }

    @Test
    void modernResultsAndErrorsMatchTheSpringAdapterByteForByte() throws Exception {
        QuarkusMcpEnvelope envelope =
                envelope(tool(args -> java.util.Map.of("name", "demo")), new RecordingFailureReporter());
        QuarkusMcpEnvelope.Reply discover = modern(envelope, "server/discover", "1", null, "2026-07-28", true);
        assertThat(discover.status()).isEqualTo(200);
        List<String> fields = new java.util.ArrayList<>();
        discover.body().path("result").fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactly(
                        "resultType",
                        "supportedVersions",
                        "capabilities",
                        "instructions",
                        "_meta",
                        "ttlMs",
                        "cacheScope");
        assertThat(discover.body().path("result").path("_meta").toString())
                .isEqualTo("{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"bootui\",\"version\":\"1.2.3\"}}");

        assertThat(modern(envelope, "tools/call", "4", "get_overview", "2026-07-28", true)
                        .body()
                        .toString())
                .isEqualTo(
                        "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"resultType\":\"complete\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"name\\\":\\\"demo\\\"}\"}],"
                                + "\"structuredContent\":{\"name\":\"demo\"},\"isError\":false,"
                                + "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"bootui\",\"version\":\"1.2.3\"}}}}");

        QuarkusMcpEnvelope.Reply unknown = modern(envelope, "ping", "6", null, "2026-07-28", true);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().toString())
                .isEqualTo(
                        "{\"jsonrpc\":\"2.0\",\"id\":6,\"error\":{\"code\":-32601,\"message\":\"Unknown method: ping\"}}");
        assertThat(modern(envelope, "tools/list", "7", null, "2026-07-28", false)
                        .body()
                        .path("error")
                        .path("code")
                        .asInt())
                .isEqualTo(-31000);

        QuarkusMcpEnvelope.Reply unsupported = modern(envelope, "tools/list", "\"a\"", null, "2099-01-01", true);
        assertThat(unsupported.status()).isEqualTo(400);
        assertThat(unsupported.body().toString())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"error\":{\"code\":-32022,"
                        + "\"message\":\"Unsupported protocol version\","
                        + "\"data\":{\"supported\":[\"2026-07-28\",\"2025-06-18\"],\"requested\":\"2099-01-01\"}}}");

        QuarkusMcpEnvelope.Reply legacy = envelope.exchange(
                objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"}"),
                new McpRequestHeaders(List.of("2099-01-01"), List.of(), List.of()),
                true);
        assertThat(legacy.status()).isEqualTo(400);
        assertThat(legacy.body().toString())
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                        + "\"message\":\"Unsupported MCP-Protocol-Version\"}}");
    }

    private QuarkusMcpEnvelope.Reply modern(
            QuarkusMcpEnvelope envelope, String method, String id, String name, String version, boolean enabled)
            throws Exception {
        String params = (name == null ? "" : "\"name\":\"" + name + "\",\"arguments\":{},")
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + version + "\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}";
        JsonNode request = objectMapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":{" + params + "}}");
        return envelope.exchange(
                request,
                new McpRequestHeaders(List.of(version), List.of(method), name == null ? List.of() : List.of(name)),
                enabled);
    }

    private QuarkusMcpEnvelope envelope(McpTool tool, RecordingFailureReporter diagnostics) {
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(tool), List.of(), new AllowAllPolicy(), "1.2.3", "instructions", 50, 20, diagnostics);
        return new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics);
    }

    private static McpTool tool(
            java.util.function.Function<io.github.jdubois.bootui.engine.mcp.McpArguments, Object> handler) {
        return new McpTool(
                "get_overview", "Read the overview.", McpToolSchema.NONE, BootUiPanels.OVERVIEW, false, handler);
    }

    private static ObjectNode callRequest(int id) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", "tools/call");
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", "get_overview");
        params.set("arguments", JsonNodeFactory.instance.objectNode());
        request.set("params", params);
        return request;
    }

    private static ObjectNode callNotification() {
        ObjectNode request = callRequest(0);
        request.remove("id");
        return request;
    }

    private static final class AllowAllPolicy implements McpPanelPolicy {

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
    }

    private static final class RecordingFailureReporter extends QuarkusMcpFailureReporter {
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
