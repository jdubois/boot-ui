package io.github.jdubois.bootui.quarkus.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.conformance.McpCodecParity;
import io.github.jdubois.bootui.conformance.McpModernParity;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpRequestKey;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
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
    void toolsListRendersEveryCatalogSchemaAndHintByteForByteLikeTheSpringCodec() throws Exception {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        McpDispatcher dispatcher = new McpDispatcher(
                McpCodecParity.tools(quarkusDescriptions()),
                List.of(),
                new AllowAllPolicy(),
                "1.2.3",
                "",
                McpCodecParity.MAX_RESULTS,
                20,
                diagnostics);
        JsonNode tools = new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics, 4 * 1024 * 1024)
                .handle(objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}"))
                .path("result")
                .path("tools");
        ArrayNode rendered = objectMapper.createArrayNode();
        tools.forEach(tool -> {
            ObjectNode projection = rendered.addObject();
            projection.set("name", tool.path("name"));
            projection.set("inputSchema", tool.path("inputSchema"));
            projection.set("annotations", tool.path("annotations"));
        });

        assertThat(objectMapper.writeValueAsString(rendered)).isEqualTo(McpCodecParity.expected());
    }

    /** The Quarkus description where the tool has one; descriptions are not part of the parity contract. */
    private static java.util.function.Function<String, String> quarkusDescriptions() {
        return name -> McpToolCatalog.byName(name).orElseThrow().advertisedBy(McpToolCatalog.Stack.QUARKUS)
                ? McpToolDescriptions.quarkus(name)
                : McpToolDescriptions.spring(name);
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
    void aLegacyRequestWithoutAUsableTokenAnswersTheSameBytesAsWithoutMeta() throws Exception {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(
                        new McpTool(
                                "architecture_scan",
                                "Run the architecture advisor.",
                                McpToolSchema.NONE,
                                BootUiPanels.ARCHITECTURE,
                                true,
                                args -> java.util.Map.of("findings", List.of())),
                        tool(args -> java.util.Map.of("name", "demo"))),
                List.of(),
                new AllowAllPolicy(),
                "1.2.3",
                "instructions",
                50,
                20,
                diagnostics);
        QuarkusMcpEnvelope envelope = new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics);
        String withoutMeta = "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"architecture_scan\",\"arguments\":{}}}";
        Object[][] cases = {
            // a progress tool with a token, from a client that does not accept a stream
            {"architecture_scan", "\"t\"", false},
            // an unusable token from a client that accepts a stream
            {"architecture_scan", "{}", true},
            {"architecture_scan", "null", true},
            {"architecture_scan", "1.5", true},
            // a token for a tool that does not report progress
            {"get_overview", "7", true},
        };
        for (Object[] each : cases) {
            String base = withoutMeta.replace("architecture_scan", (String) each[0]);
            String withToken =
                    base.replace("\"arguments\":{}", "\"arguments\":{},\"_meta\":{\"progressToken\":" + each[1] + "}");
            QuarkusMcpEnvelope.Reply expected =
                    envelope.exchange(objectMapper.readTree(base), McpRequestHeaders.NONE, true, (boolean) each[2]);
            QuarkusMcpEnvelope.Reply actual = envelope.exchange(
                    objectMapper.readTree(withToken), McpRequestHeaders.NONE, true, (boolean) each[2]);

            assertThat(actual.stream()).as(withToken).isNull();
            assertThat(actual.status()).isEqualTo(expected.status());
            assertThat(actual.body().toString())
                    .as(withToken)
                    .isEqualTo(expected.body().toString());
        }
    }

    @Test
    void requestKeysMatchNumericIdsByValueOnlyWhenExact() throws Exception {
        // Integers by value, whole doubles below 2^53 like their integer, everything else unkeyed (so never cancelled).
        assertThat(key("7")).isEqualTo(McpRequestKey.number(new java.math.BigDecimal("7")));
        assertThat(key("7.0")).isEqualTo(key("7"));
        assertThat(key("1e2")).isEqualTo(key("100"));
        // The default mapper reads a fraction as a double: an id within double rounding of 7 is 7.
        assertThat(key("7.0000000000000000001")).isEqualTo(key("7"));
        assertThat(key("123456789012345678901234567890"))
                .isEqualTo(McpRequestKey.number(new java.math.BigDecimal("123456789012345678901234567890")));
        assertThat(key("0")).isEqualTo(McpRequestKey.number(java.math.BigDecimal.ZERO));
        assertThat(key("\"7\"")).isEqualTo(McpRequestKey.text("7"));
        for (String unkeyed :
                List.of("1e-400", "0.0", "7.5", "1e400", "-1e400", "9007199254740992.0", "true", "null")) {
            assertThat(key(unkeyed)).as(unkeyed).isNull();
        }

        ObjectMapper exact = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        // Read as BigDecimal, values stay exact: 7.0000000000000000001 is not 7, and 1e-400 is not 0.
        assertThat(QuarkusMcpEnvelope.requestKey(exact.readTree("7.00"))).isEqualTo(key("7"));
        for (String unkeyed : List.of("7.0000000000000000001", "1e-400", "1e999999999")) {
            assertThat(QuarkusMcpEnvelope.requestKey(exact.readTree(unkeyed)))
                    .as(unkeyed)
                    .isNull();
        }
    }

    private String key(String json) throws Exception {
        return QuarkusMcpEnvelope.requestKey(objectMapper.readTree(json));
    }

    @Test
    void aNonFiniteNumericIdIsServedLikeAnyOtherId() throws Exception {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        QuarkusMcpEnvelope envelope = envelope(tool(args -> java.util.Map.of("name", "demo")), diagnostics);
        for (String method : List.of("tools/call", "tools/list", "ping")) {
            JsonNode response = envelope.handle(objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1e400,\"method\":\""
                    + method + "\",\"params\":{\"name\":\"get_overview\",\"arguments\":{}}}"));

            assertThat(response.path("error").path("code").asInt())
                    .as(method + " " + response)
                    .isNotEqualTo(McpProtocol.INTERNAL_ERROR);
        }
        assertThat(diagnostics.count).hasValue(0);
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
                .isEqualTo(McpModernParity.TOOL_CALL_DEMO);

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

    @Test
    void modernEnvelopesMatchTheSharedParityContract() throws Exception {
        QuarkusMcpEnvelope envelope =
                envelope(tool(args -> java.util.Map.of("name", "demo")), new RecordingFailureReporter());
        ObjectNode discover = (ObjectNode) modern(envelope, "server/discover", "1", null, "2026-07-28", true)
                .body()
                .path("result");
        discover.remove("instructions");
        assertThat(discover.toString()).isEqualTo(McpModernParity.DISCOVER_WITHOUT_INSTRUCTIONS);
        ObjectNode tools = (ObjectNode) modern(envelope, "tools/list", "2", null, "2026-07-28", true)
                .body()
                .path("result");
        tools.remove("tools");
        assertThat(tools.toString()).isEqualTo(McpModernParity.LIST_ENVELOPE);
        ObjectNode prompts = (ObjectNode) modern(envelope, "prompts/list", "3", null, "2026-07-28", true)
                .body()
                .path("result");
        prompts.remove("prompts");
        assertThat(prompts.toString()).isEqualTo(McpModernParity.LIST_ENVELOPE);
    }

    @Test
    void containerValuedEnvelopeFieldsAreClientErrorsLikeOnSpring() throws Exception {
        QuarkusMcpEnvelope envelope =
                envelope(tool(args -> java.util.Map.of("name", "demo")), new RecordingFailureReporter());
        for (String method : List.of("{}", "[]")) {
            JsonNode notification = objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":" + method + "}");
            assertThat(envelope.exchange(notification, McpRequestHeaders.NONE, true))
                    .isEqualTo(new QuarkusMcpEnvelope.Reply(202, null));
            JsonNode request = objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":" + method + "}");
            QuarkusMcpEnvelope.Reply reply = envelope.exchange(request, McpRequestHeaders.NONE, true);
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.body().toString())
                    .isEqualTo(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Missing 'method'\"}}");
        }
        JsonNode objectName = objectMapper.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":{}}}");
        assertThat(envelope.exchange(objectName, McpRequestHeaders.NONE, true)
                        .body()
                        .toString())
                .isEqualTo(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32602,\"message\":\"Missing tool name\"}}");
    }

    @Test
    void streamFinalResponsesStayOneLineWithAnIndentingApplicationMapper() throws Exception {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(tool(args -> java.util.Map.of("name", "demo"))),
                List.of(),
                new AllowAllPolicy(),
                "1.2.3",
                "instructions",
                50,
                20,
                diagnostics);
        ObjectMapper indenting =
                new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        QuarkusMcpEnvelope envelope = new QuarkusMcpEnvelope(dispatcher, indenting, diagnostics);

        String finalResponse = envelope.renderFinal(
                objectMapper.readTree("7"),
                io.github.jdubois.bootui.engine.mcp.McpEra.MODERN,
                new io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult(
                        java.util.Map.of("name", "demo")));

        assertThat(finalResponse).doesNotContain("\n", "\r");
        // The text content is a JSON string, so the mapper's line breaks inside it are escaped; the envelope is
        // compact.
        assertThat(McpProtocol.sseDataFrame(finalResponse))
                .startsWith("data:{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"resultType\":\"complete\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"")
                .endsWith(
                        "\"}],\"structuredContent\":{\"name\":\"demo\"},\"isError\":false,"
                                + "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"bootui\",\"version\":\"1.2.3\"}}}}\n\n");
        assertThat(diagnostics.count).hasValue(0);
    }

    @Test
    void progressTokensAndProgressEventsStayWithinTheResponseBound() throws Exception {
        RecordingFailureReporter diagnostics = new RecordingFailureReporter();
        McpDispatcher dispatcher = new McpDispatcher(
                List.of(new McpTool(
                        "architecture_scan",
                        "Run the architecture advisor.",
                        McpToolSchema.NONE,
                        BootUiPanels.ARCHITECTURE,
                        true,
                        args -> java.util.Map.of("findings", List.of()))),
                List.of(),
                new AllowAllPolicy(),
                "1.2.3",
                "instructions",
                50,
                20,
                diagnostics);
        QuarkusMcpEnvelope small = new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics, 512);
        String token = "t".repeat(2_048);

        QuarkusMcpEnvelope.Reply legacy = small.exchange(
                objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"architecture_scan\",\"arguments\":{},\"_meta\":{\"progressToken\":\"" + token
                        + "\"}}}"),
                McpRequestHeaders.NONE,
                true,
                true);
        assertThat(legacy.stream()).isNull();
        assertThat(legacy.body().path("result").path("isError").asBoolean()).isFalse();

        QuarkusMcpEnvelope.Reply modern = small.exchange(
                objectMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"architecture_scan\",\"arguments\":{},\"_meta\":{"
                        + "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                        + "\"io.modelcontextprotocol/clientCapabilities\":{},\"progressToken\":\"" + token + "\"}}}"),
                new McpRequestHeaders(List.of("2026-07-28"), List.of("tools/call"), List.of("architecture_scan")),
                true,
                true);
        assertThat(modern.stream()).isNull();
        assertThat(modern.status()).isEqualTo(400);
        assertThat(modern.body().path("error").path("message").asText())
                .isEqualTo(McpProtocol.PROGRESS_TOKEN_TYPE_MESSAGE);

        String longest = "t".repeat(io.github.jdubois.bootui.engine.mcp.McpProgressToken.MAX_TEXT_LENGTH);
        io.github.jdubois.bootui.engine.progress.ProgressEvent event =
                new io.github.jdubois.bootui.engine.progress.ProgressEvent(1, 2.0, "Evaluating architecture rules");
        assertThat(small.renderProgress(io.github.jdubois.bootui.engine.mcp.McpProgressToken.of(longest), event))
                .hasSizeLessThanOrEqualTo(512);
        QuarkusMcpEnvelope tiny = new QuarkusMcpEnvelope(dispatcher, objectMapper, diagnostics, 100);
        assertThat(tiny.renderProgress(io.github.jdubois.bootui.engine.mcp.McpProgressToken.of(longest), event))
                .isNull();
        assertThat(dispatcher.runtimeStats().snapshot().progressDropped())
                .as("a dropped progress event is counted")
                .isEqualTo(1);
    }

    @Test
    void streamFramesAreTheSameBytesOnEveryStack() throws Exception {
        QuarkusMcpEnvelope envelope =
                envelope(tool(args -> java.util.Map.of("name", "demo")), new RecordingFailureReporter());
        assertThat(envelope.renderProgress(
                        io.github.jdubois.bootui.engine.mcp.McpProgressToken.of("tok"),
                        new io.github.jdubois.bootui.engine.progress.ProgressEvent(
                                3, 43.0, "Evaluating architecture rules")))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":"
                        + "{\"progressToken\":\"tok\",\"progress\":3,\"total\":43,"
                        + "\"message\":\"Evaluating architecture rules\"}}");
        assertThat(envelope.renderProgress(
                        io.github.jdubois.bootui.engine.mcp.McpProgressToken.of(9),
                        new io.github.jdubois.bootui.engine.progress.ProgressEvent(1.5, null, "Working")))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":"
                        + "{\"progressToken\":9,\"progress\":1.5,\"message\":\"Working\"}}");
        assertThat(envelope.renderFinal(
                        objectMapper.readTree("7"),
                        io.github.jdubois.bootui.engine.mcp.McpEra.MODERN,
                        new io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError(
                                McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE)))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-31002,"
                        + "\"message\":\"MCP tool execution timed out\"}}");
        assertThat(envelope.renderFinal(
                        objectMapper.readTree("7"),
                        io.github.jdubois.bootui.engine.mcp.McpEra.MODERN,
                        new io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult(
                                java.util.Map.of("name", "demo"))))
                .isEqualTo(
                        "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"resultType\":\"complete\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"name\\\":\\\"demo\\\"}\"}],"
                                + "\"structuredContent\":{\"name\":\"demo\"},\"isError\":false,"
                                + "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"bootui\",\"version\":\"1.2.3\"}}}}");

        // A legacy (MCP 2025-06-18) stream ends with a legacy response: legacy codes, no resultType, no _meta.
        assertThat(envelope.renderFinal(
                        objectMapper.readTree("\"r\""),
                        io.github.jdubois.bootui.engine.mcp.McpEra.LEGACY,
                        new io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError(
                                McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE)))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"r\",\"error\":{\"code\":-32002,"
                        + "\"message\":\"MCP tool execution timed out\"}}");
        assertThat(envelope.renderFinal(
                        objectMapper.readTree("7"),
                        io.github.jdubois.bootui.engine.mcp.McpEra.LEGACY,
                        new io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult(
                                java.util.Map.of("name", "demo"))))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{"
                        + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"name\\\":\\\"demo\\\"}\"}],"
                        + "\"structuredContent\":{\"name\":\"demo\"},\"isError\":false}}");
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
