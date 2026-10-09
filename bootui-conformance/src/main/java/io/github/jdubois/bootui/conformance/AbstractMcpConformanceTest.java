package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.github.jdubois.bootui.engine.advisor.AdvisorScanState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Shared black-box HTTP conformance contract for the BootUI MCP endpoint. */
public abstract class AbstractMcpConformanceTest {

    protected abstract String baseUrl();

    /**
     * Enable the MCP server for tests that require it.
     *
     * @return {@code true} when the server was enabled and the test may proceed; {@code false} to skip
     */
    protected boolean enableMcp() {
        return setMcpEnabled(true);
    }

    /** Disable the MCP server after a test that enabled it. */
    protected void disableMcp() {
        assertThat(setMcpEnabled(false))
                .as("MCP server must be disabled after the test")
                .isTrue();
    }

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl());
    }

    private boolean setMcpEnabled(boolean enabled) {
        BootUiHttpProbe probe = probe();
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Origin", baseUrl());
        probe.get("/bootui/api/overview");
        probe.cookie("XSRF-TOKEN").ifPresent(token -> headers.put("X-XSRF-TOKEN", token));
        Response response =
                probe.request("POST", "/bootui/api/mcp-server/toggle", headers, "{\"enabled\":" + enabled + "}");
        return response.status() == 200 && response.json().path("enabled").asBoolean(!enabled) == enabled;
    }

    @Test
    void testPentestingToolsApplyLiveDismissalsToScanAndCachedRead() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            PentestingDismissalContract.verify(
                    probe(),
                    "/bootui/api",
                    Path.of("target/mcp-conformance/boot-ui.yml"),
                    () -> pentestingTool("pentest_scan"),
                    () -> pentestingTool("get_pentest_report"));
        }
    }

    private JsonNode pentestingTool(String tool) {
        JsonNode envelope = PentestingDismissalContract.response(probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "application/json"),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                                + "\"}}"));
        assertThat(envelope.has("error")).as(envelope.toString()).isFalse();
        JsonNode result = envelope.path("result");
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        try {
            return new ObjectMapper()
                    .readTree(result.path("content").get(0).path("text").asText());
        } catch (java.io.IOException ex) {
            throw new AssertionError("Pentesting MCP content must contain the JSON report", ex);
        }
    }

    private static final String MODERN = "2026-07-28";

    private Response modernRequest(String method, String id, String name, String params) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("MCP-Protocol-Version", MODERN);
        headers.put("Mcp-Method", method);
        if (name != null) {
            headers.put("Mcp-Name", name);
        }
        return probe().request("POST", "/bootui/api/mcp", headers, modernBody(method, id, name, params, MODERN));
    }

    private static String modernBody(String method, String id, String name, String params, String version) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":{"
                + (name == null ? "" : "\"name\":\"" + name + "\",")
                + (params == null ? "" : params + ",")
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + version + "\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void testModernClientDiscoversTheServerWithCacheHints() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            Response response = modernRequest("server/discover", "\"d-1\"", null, null);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.isJson()).isTrue();
            JsonNode envelope = response.json();
            assertThat(envelope.path("id").asText()).isEqualTo("d-1");
            JsonNode result = envelope.path("result");
            assertThat(fieldNames(result))
                    .containsExactly(
                            "resultType",
                            "supportedVersions",
                            "capabilities",
                            "instructions",
                            "_meta",
                            "ttlMs",
                            "cacheScope");
            assertThat(result.path("resultType").asText()).isEqualTo("complete");
            assertThat(result.path("supportedVersions").toString()).isEqualTo("[\"2026-07-28\",\"2025-06-18\"]");
            assertThat(result.path("capabilities").toString())
                    .isEqualTo("{\"tools\":{\"listChanged\":false},\"prompts\":{\"listChanged\":false}}");
            assertThat(result.path("instructions").asText()).contains("get_overview");
            assertThat(result.path("_meta")
                            .path("io.modelcontextprotocol/serverInfo")
                            .path("name")
                            .asText())
                    .isEqualTo("bootui");
            assertThat(result.path("ttlMs").asLong()).isEqualTo(60_000);
            assertThat(result.path("cacheScope").asText()).isEqualTo("private");
        }
    }

    @Test
    void testModernClientListsAndCallsToolsWithJsonResponses() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            JsonNode legacyTools = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                    .json()
                    .path("result");
            Response list = modernRequest("tools/list", "2", null, null);
            assertThat(list.status()).isEqualTo(200);
            JsonNode modernTools = list.json().path("result");
            assertThat(fieldNames(modernTools)).containsExactly("resultType", "tools", "_meta", "ttlMs", "cacheScope");
            assertThat(modernTools.path("tools"))
                    .as("both eras advertise the same tools in the same deterministic order")
                    .isEqualTo(legacyTools.path("tools"));

            Response call = modernRequest("tools/call", "3", "get_overview", "\"arguments\":{}");
            assertThat(call.status()).isEqualTo(200);
            assertThat(call.contentType()).startsWith("application/json");
            JsonNode result = call.json().path("result");
            assertThat(result.path("resultType").asText()).isEqualTo("complete");
            assertThat(result.path("isError").asBoolean()).isFalse();
            assertThat(result.has("ttlMs")).as("tool results are not cacheable").isFalse();
            assertThat(result.path("_meta").has("io.modelcontextprotocol/serverInfo"))
                    .isTrue();

            Map<String, String> encodedHeaders = new java.util.LinkedHashMap<>();
            encodedHeaders.put("Content-Type", "application/json");
            encodedHeaders.put("mcp-protocol-version", MODERN);
            encodedHeaders.put("mcp-method", "tools/call");
            encodedHeaders.put("mcp-name", "=?base64?Z2V0X292ZXJ2aWV3?=");
            Response encoded = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            encodedHeaders,
                            modernBody("tools/call", "5", "get_overview", "\"arguments\":{}", MODERN));
            assertThat(encoded.status())
                    .as("header names are case-insensitive and Mcp-Name may be Base64-encoded")
                    .isEqualTo(200);
            assertThat(encoded.json().path("result").path("isError").asBoolean())
                    .isFalse();
        }
    }

    @Test
    void testModernRequestsNeedAStringOrIntegerIdAndNeverRunWithoutOne() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            long callsBefore = probe().get("/bootui/api/mcp-server")
                    .json()
                    .path("callCount")
                    .asLong();
            String meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                    + "\"io.modelcontextprotocol/clientCapabilities\":{}}";
            Map<String, String> json = Map.of("Content-Type", "application/json");
            String[][] cases = {
                // id, then the error message; the request carries no Mcp-Method or Mcp-Name, as reported
                {",\"id\":null", "Request id must be a string or an integer"},
                {",\"id\":1.5", "Request id must be a string or an integer"},
                {"", "A request needs an id; only notifications/ methods omit it"}
            };
            for (String[] each : cases) {
                Response response = probe().request(
                                "POST",
                                "/bootui/api/mcp",
                                json,
                                "{\"jsonrpc\":\"2.0\"" + each[0]
                                        + ",\"method\":\"tools/call\",\"params\":{\"name\":\"get_health\",\"arguments\":{},"
                                        + meta + "}}");
                assertThat(response.status()).as(each[0]).isEqualTo(400);
                assertThat(response.body())
                        .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\""
                                + each[1] + "\"}}");
            }
            assertThat(probe().get("/bootui/api/mcp-server")
                            .json()
                            .path("callCount")
                            .asLong())
                    .as("no refused modern call ran its tool")
                    .isEqualTo(callsBefore);

            // Without a modern _meta, an absent or null id is still a notification, answered 202, as in BootUI 1.x,
            // and a tools/call sent that way still runs, as it did there.
            for (String id : List.of("", ",\"id\":null")) {
                Response legacy = probe().request(
                                "POST", "/bootui/api/mcp", json, "{\"jsonrpc\":\"2.0\"" + id + ",\"method\":\"ping\"}");
                assertThat(legacy.status()).as(id).isEqualTo(202);
                assertThat(legacy.body()).isEmpty();
                Response legacyCall = probe().request(
                                "POST",
                                "/bootui/api/mcp",
                                json,
                                "{\"jsonrpc\":\"2.0\"" + id
                                        + ",\"method\":\"tools/call\",\"params\":{\"name\":\"get_health\",\"arguments\":{}}}");
                assertThat(legacyCall.status()).as(id).isEqualTo(202);
                assertThat(legacyCall.body()).isEmpty();
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (probe().get("/bootui/api/mcp-server")
                                    .json()
                                    .path("callCount")
                                    .asLong()
                            < callsBefore + 2
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(probe().get("/bootui/api/mcp-server")
                            .json()
                            .path("callCount")
                            .asLong())
                    .as("the two legacy tools/call notifications ran, as in BootUI 1.x")
                    .isEqualTo(callsBefore + 2);
        }
    }

    @Test
    void testModernProtocolErrorsAreExplicitAndByteIdentical() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            Map<String, String> unsupportedHeaders = Map.of(
                    "Content-Type",
                    "application/json",
                    "MCP-Protocol-Version",
                    "2099-01-01",
                    "Mcp-Method",
                    "tools/list");
            Response unsupported = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            unsupportedHeaders,
                            modernBody("tools/list", "7", null, null, "2099-01-01"));
            assertThat(unsupported.status()).isEqualTo(400);
            assertThat(unsupported.body())
                    .isEqualTo(
                            "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32022,"
                                    + "\"message\":\"Unsupported protocol version\","
                                    + "\"data\":{\"supported\":[\"2026-07-28\",\"2025-06-18\"],\"requested\":\"2099-01-01\"}}}");

            Response ping = modernRequest("ping", "8", null, null);
            assertThat(ping.status()).as("modern clients have no ping").isEqualTo(404);
            assertThat(ping.body())
                    .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":8,\"error\":{\"code\":-32601,"
                            + "\"message\":\"Unknown method: ping\"}}");

            Map<String, String> wrongName = Map.of(
                    "Content-Type",
                    "application/json",
                    "MCP-Protocol-Version",
                    MODERN,
                    "Mcp-Method",
                    "tools/call",
                    "Mcp-Name",
                    "get_health");
            Response mismatch = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            wrongName,
                            modernBody("tools/call", "9", "get_overview", "\"arguments\":{}", MODERN));
            assertThat(mismatch.status()).isEqualTo(400);
            assertThat(mismatch.body())
                    .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32020,"
                            + "\"message\":\"Header mismatch: Mcp-Name must be sent once and match params.name\"}}");

            Response repeated = probe().requestWithHeaderLines(
                            "POST",
                            "/bootui/api/mcp",
                            List.of(
                                    Map.entry("Content-Type", "application/json"),
                                    Map.entry("MCP-Protocol-Version", MODERN),
                                    Map.entry("Mcp-Method", "tools/list"),
                                    Map.entry("Mcp-Method", "tools/list")),
                            modernBody("tools/list", "10", null, null, MODERN));
            assertThat(repeated.status())
                    .as("a repeated header is never resolved differently per stack")
                    .isEqualTo(400);
            assertThat(repeated.json().path("error").path("code").asInt()).isEqualTo(-32020);

            Response noCapabilities = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of(
                                    "Content-Type",
                                    "application/json",
                                    "MCP-Protocol-Version",
                                    MODERN,
                                    "Mcp-Method",
                                    "tools/list"),
                            "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\",\"params\":{\"_meta\":"
                                    + "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}");
            assertThat(noCapabilities.status()).isEqualTo(400);
            assertThat(noCapabilities.json().path("error").path("code").asInt()).isEqualTo(-32602);

            Response invalidToken = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of(
                                    "Content-Type",
                                    "application/json",
                                    "MCP-Protocol-Version",
                                    MODERN,
                                    "Mcp-Method",
                                    "tools/list"),
                            "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/list\",\"params\":{\"_meta\":"
                                    + "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                                    + "\"io.modelcontextprotocol/clientCapabilities\":{},\"progressToken\":1.5}}}");
            assertThat(invalidToken.status()).isEqualTo(400);
            assertThat(invalidToken.json().path("error").path("code").asInt()).isEqualTo(-32602);
        }
    }

    @Test
    void testModernDisabledServerAndCrossSiteWritesStayRefused() {
        Response disabled = modernRequest("tools/list", "14", null, null);
        assertThat(disabled.status()).isEqualTo(200);
        assertThat(disabled.json().path("error").path("code").asInt())
                .as("BootUI's server codes move out of the reserved range for modern clients")
                .isEqualTo(-31000);

        Map<String, String> crossSite = new java.util.LinkedHashMap<>();
        crossSite.put("Content-Type", "application/json");
        crossSite.put("Origin", "http://evil.example.com");
        crossSite.put("Sec-Fetch-Site", "cross-site");
        crossSite.put("MCP-Protocol-Version", MODERN);
        crossSite.put("Mcp-Method", "server/discover");
        Response refused = probe().request(
                        "POST", "/bootui/api/mcp", crossSite, modernBody("server/discover", "15", null, null, MODERN));
        assertThat(refused.status()).isEqualTo(403);
    }

    private Response progressCall(String id, String accept, boolean withToken) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", accept);
        headers.put("MCP-Protocol-Version", MODERN);
        headers.put("Mcp-Method", "tools/call");
        headers.put("Mcp-Name", "architecture_scan");
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"architecture_scan\",\"arguments\":{},"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}"
                + (withToken ? ",\"progressToken\":\"p-1\"" : "") + "}}}";
        return probe().request("POST", "/bootui/api/mcp", headers, body, java.time.Duration.ofSeconds(60));
    }

    @Test
    void testModernProgressCallAnswersOnARequestScopedEventStream() throws Exception {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            Response response = progressCall("21", "application/json, text/event-stream", true);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.contentType()).startsWith("text/event-stream");
            assertThat(response.header("X-Accel-Buffering")).isEqualTo("no");
            String body = response.body();
            assertThat(body).endsWith("\n\n");
            List<JsonNode> messages = new java.util.ArrayList<>();
            ObjectMapper mapper = new ObjectMapper();
            for (String event : body.split("\n\n")) {
                if (event.startsWith(":")) {
                    continue;
                }
                assertThat(event)
                        .as("one JSON-RPC message per event, no event ids")
                        .startsWith("data:");
                assertThat(event).doesNotContain("\n");
                messages.add(mapper.readTree(event.substring("data:".length())));
            }
            assertThat(messages.size()).as(body).isGreaterThanOrEqualTo(5);
            List<JsonNode> progress = messages.subList(0, messages.size() - 1);
            JsonNode last = messages.get(messages.size() - 1);

            double total = progress.get(0).path("params").path("total").asDouble();
            assertThat(total).as("rules evaluated plus three phases").isGreaterThan(3);
            assertThat(progress.get(0).toString())
                    .isEqualTo("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":"
                            + "{\"progressToken\":\"p-1\",\"progress\":0,\"total\":" + (long) total
                            + ",\"message\":\"Importing application classes\"}}");
            assertThat(progress.get(1).path("params").path("message").asText()).isEqualTo("Checking generated code");
            assertThat(progress.get(2).path("params").path("message").asText())
                    .isEqualTo("Evaluating architecture rules");
            JsonNode final_ = progress.get(progress.size() - 1).path("params");
            assertThat(final_.path("message").asText()).isEqualTo("Locating violations");
            assertThat(final_.path("progress").asDouble())
                    .as("the final response, not a notification, marks completion")
                    .isEqualTo(total - 1);
            double previous = -1;
            for (JsonNode notification : progress) {
                assertThat(notification.has("id"))
                        .as("notifications carry no id")
                        .isFalse();
                assertThat(notification.path("method").asText()).isEqualTo("notifications/progress");
                assertThat(notification.path("params").path("progressToken").asText())
                        .isEqualTo("p-1");
                assertThat(notification.path("params").path("progress").asDouble())
                        .isGreaterThan(previous);
                previous = notification.path("params").path("progress").asDouble();
            }

            assertThat(last.path("id").asInt()).isEqualTo(21);
            assertThat(last.has("method")).isFalse();
            JsonNode result = last.path("result");
            assertThat(result.path("resultType").asText()).isEqualTo("complete");
            assertThat(result.path("isError").asBoolean()).as(last.toString()).isFalse();
            assertThat(result.path("structuredContent")
                            .path("scan")
                            .path("status")
                            .asText())
                    .isIn("SCANNED", "PARTIAL");
            assertThat(result.path("_meta")
                            .path("io.modelcontextprotocol/serverInfo")
                            .path("name")
                            .asText())
                    .isEqualTo("bootui");
        }
    }

    @Test
    void testModernCallsWithoutATokenOrStreamSupportStayJson() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            for (Response response : List.of(
                    progressCall("22", "application/json, text/event-stream", false),
                    progressCall("23", "application/json", true),
                    progressCall("24", "*/*", true))) {
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.contentType()).startsWith("application/json");
                JsonNode result = response.json().path("result");
                assertThat(result.path("resultType").asText()).isEqualTo("complete");
                assertThat(result.path("isError").asBoolean()).isFalse();
            }
        }
    }

    /** A response body with the values a scan measures (timestamps, ids, numbers) normalised, keys and text kept. */
    private static String withoutMetaShape(String body) {
        return body.replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z?", "<time>")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
                .replaceAll("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?", "<n>");
    }

    private Response legacyProgressCall(String id, String accept, String token) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", accept);
        headers.put("MCP-Protocol-Version", "2025-06-18");
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"architecture_scan\",\"arguments\":{}"
                + (token == null ? "" : ",\"_meta\":{\"progressToken\":" + token + "}") + "}}";
        return probe().request("POST", "/bootui/api/mcp", headers, body, java.time.Duration.ofSeconds(60));
    }

    @Test
    void testLegacyProgressCallAnswersOnARequestScopedEventStream() throws Exception {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            Response response = legacyProgressCall("\"l-1\"", "application/json, text/event-stream", "7");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.contentType()).startsWith("text/event-stream");
            List<JsonNode> messages = new java.util.ArrayList<>();
            ObjectMapper mapper = new ObjectMapper();
            for (String event : response.body().split("\n\n")) {
                if (!event.startsWith(":")) {
                    assertThat(event).startsWith("data:").doesNotContain("\n");
                    messages.add(mapper.readTree(event.substring("data:".length())));
                }
            }
            assertThat(messages.size()).as(response.body()).isGreaterThanOrEqualTo(5);
            for (JsonNode notification : messages.subList(0, messages.size() - 1)) {
                assertThat(notification.path("method").asText()).isEqualTo("notifications/progress");
                assertThat(notification.path("params").path("progressToken").isIntegralNumber())
                        .as("a number token is echoed as a number")
                        .isTrue();
                assertThat(notification.path("params").path("progressToken").asLong())
                        .isEqualTo(7);
            }
            JsonNode last = messages.get(messages.size() - 1);
            assertThat(last.path("id").asText()).isEqualTo("l-1");
            JsonNode result = last.path("result");
            assertThat(result.path("isError").asBoolean()).as(last.toString()).isFalse();
            assertThat(result.has("resultType"))
                    .as("a legacy stream ends with a legacy result")
                    .isFalse();
            assertThat(result.has("_meta")).isFalse();
        }
    }

    @Test
    void testLegacyCallsWithoutAUsableTokenOrStreamSupportStayJson() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            String[][] cases = {
                {"application/json, text/event-stream", null},
                {"application/json", "\"t\""},
                {"application/json, text/event-stream", "{}"},
                {"application/json, text/event-stream", "null"},
                {"application/json, text/event-stream", "1.5"}
            };
            for (String[] each : cases) {
                Response response = legacyProgressCall("31", each[0], each[1]);
                assertThat(response.status()).as(response.body()).isEqualTo(200);
                assertThat(response.contentType()).startsWith("application/json");
                JsonNode result = response.json().path("result");
                assertThat(result.path("isError").asBoolean())
                        .as("a legacy request is never rejected because of its token")
                        .isFalse();
                assertThat(result.has("resultType")).isFalse();
                // The same request without _meta: the same bytes, apart from what the scan measures (times, ids,
                // counts).
                Response withoutMeta = legacyProgressCall("31", each[0], null);
                assertThat(withoutMetaShape(response.body()))
                        .as("token " + each[1] + " changes nothing in a legacy answer")
                        .isEqualTo(withoutMetaShape(withoutMeta.body()));
            }
        }
    }

    @Test
    void testNullAndNonStringEnvelopeFieldsAreTheSameClientErrorOnEveryStack() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            Map<String, String> json = Map.of("Content-Type", "application/json");
            for (String method : List.of("null", "5", "{}")) {
                Response response = probe().request(
                                "POST",
                                "/bootui/api/mcp",
                                json,
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":" + method + "}");
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.body())
                        .as(method)
                        .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,"
                                + "\"message\":\"Missing 'method'\"}}");
            }
            Response nullName = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            json,
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":null}}");
            assertThat(nullName.body())
                    .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602,"
                            + "\"message\":\"Missing tool name\"}}");
            Response nullNotification =
                    probe().request("POST", "/bootui/api/mcp", json, "{\"jsonrpc\":\"2.0\",\"method\":null}");
            assertThat(nullNotification.status()).isEqualTo(202);
            Map<String, String> modern = Map.of(
                    "Content-Type", "application/json", "MCP-Protocol-Version", MODERN, "Mcp-Method", "tools/call");
            Response modernNullName = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            modern,
                            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":null,"
                                    + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                                    + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}");
            assertThat(modernNullName.status()).isEqualTo(200);
            assertThat(modernNullName.body())
                    .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32602,"
                            + "\"message\":\"Missing tool name\"}}");
        }
    }

    @Test
    void testInitializeStaysLegacyWhenItCarriesModernMeta() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            modernBody("initialize", "16", null, "\"protocolVersion\":\"2025-06-18\"", MODERN));
            assertThat(response.status()).isEqualTo(200);
            JsonNode result = response.json().path("result");
            assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-06-18");
            assertThat(result.has("resultType")).isFalse();
        }
    }

    @Test
    void testMcpGetRejectsUnsupportedSseStream() {
        Response response = probe().get("/bootui/api/mcp");
        assertThat(response.status()).isEqualTo(405);
    }

    @Test
    void testMcpRequiresJsonContentType() {
        Response response = probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "text/plain"),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        // Spring MVC returns 415 Unsupported Media Type for wrong content-type before the controller
        // runs; Spring WebFlux returns 400 Bad Request. Both are valid rejections of the wrong type.
        assertThat(response.status()).isIn(400, 415);
    }

    @Test
    void testMcpRejectsBatchRequests() {
        Response response = probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "application/json"),
                        "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.isJson()).isTrue();
        assertThat(response.json().path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void testMcpRejectsOversizedRequestBeforeParsing() {
        String oversized = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"padding\":\"" + "x".repeat(300) + "\"}";
        Response response =
                probe().request("POST", "/bootui/api/mcp", Map.of("Content-Type", "application/json"), oversized);
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.json().path("error").path("message").asText()).isEqualTo("Request payload exceeds limit");
    }

    @Test
    void testMcpDisabledShortCircuit() {
        Response response = probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "application/json"),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("error").path("code").asInt()).isEqualTo(-32000);
    }

    @Test
    void testMcpInitializeWhenEnabled() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                    + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
            assertThat(response.status()).isEqualTo(200);
            JsonNode result = response.json().path("result");
            assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-06-18");
            assertThat(result.path("capabilities").path("tools").isObject()).isTrue();
            assertThat(result.path("capabilities").path("prompts").isObject()).isTrue();
            assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("bootui");
            assertThat(result.path("instructions").asText()).contains("get_overview", "sensitive data");
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpValidatesJsonrpc() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"id\":1,\"method\":\"ping\"}");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().path("error").path("code").asInt()).isEqualTo(-32600);
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpInitiateVersionNegotiation() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                    + "\"params\":{\"protocolVersion\":\"2099-01-01\"}}");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().path("result").path("protocolVersion").asText())
                    .isEqualTo("2025-06-18");
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpRejectsUnsupportedProtocolVersionHeader() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json", "MCP-Protocol-Version", "2099-01-01"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.json().path("error").path("code").asInt()).isEqualTo(-32600);
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpToolsListWhenEnabled() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            assertThat(response.status()).isEqualTo(200);
            JsonNode tools = response.json().path("result").path("tools");
            assertThat(tools.isArray()).isTrue();
            assertThat(tools.isEmpty()).isFalse();
            JsonNode first = tools.get(0);
            assertThat(first.path("name").isTextual()).isTrue();
            assertThat(first.path("description").isTextual()).isTrue();
            assertThat(first.path("inputSchema").isObject()).isTrue();
            assertThat(first.path("outputSchema").path("type").asText()).isEqualTo("object");
            tools.forEach(tool -> {
                JsonNode hints = tool.path("annotations");
                assertThat(hints.path("readOnlyHint").isBoolean())
                        .as("%s readOnlyHint", tool.path("name").asText())
                        .isTrue();
                assertThat(hints.path("destructiveHint").isBoolean()).isTrue();
                assertThat(hints.path("idempotentHint").isBoolean()).isTrue();
                assertThat(hints.path("openWorldHint").isBoolean()).isTrue();
                tool.path("inputSchema")
                        .path("properties")
                        .forEach(property -> assertThat(
                                        property.path("description").asText())
                                .as("%s argument description", tool.path("name").asText())
                                .isNotBlank());
            });
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpAdvisorDetailToolsMatchReportAvailabilityAndDeclarePaging() {
        assertThat(enableMcp()).isTrue();
        try {
            JsonNode tools = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                    .json()
                    .path("result")
                    .path("tools");
            Map<String, JsonNode> byName = new java.util.LinkedHashMap<>();
            for (JsonNode tool : tools) byName.put(tool.path("name").asText(), tool);
            for (String advisor : List.of(
                    "architecture", "hibernate", "spring", "rest_api", "memory", "security", "database_advisor")) {
                String name = "get_" + advisor + "_rule_violations";
                assertThat(byName.containsKey(name))
                        .as(name)
                        .isEqualTo(byName.containsKey("get_" + advisor + "_report"));
                if (!byName.containsKey(name)) continue;
                JsonNode schema = byName.get(name).path("inputSchema");
                assertThat(schema.path("properties").has("offset")).isTrue();
                assertThat(schema.path("properties").has("limit")).isTrue();
                assertThat(schema.path("properties").has("scanId")).isTrue();
                assertThat(schema.path("required").toString()).contains("\"id\"", "\"scanId\"");
                assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            }
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpArchitectureDetailsReturnTheSameSnapshotAsThePanel() throws Exception {
        assertThat(enableMcp()).isTrue();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode scanEnvelope = callAdvisorTool("architecture_scan", Map.of());
            assertThat(scanEnvelope.path("isError").asBoolean()).isFalse();
            JsonNode report = mapper.readTree(
                    scanEnvelope.path("content").get(0).path("text").asText());
            String scanId = report.path("violationDetails").path("scanId").asText();
            assertThat(scanId).isNotBlank();
            assertThat(probe().get("/bootui/api/architecture").json().path("violationDetails"))
                    .isEqualTo(report.path("violationDetails"));
            // The scan answers with a summary: its top findings name the rules to page, with their counts.
            assertThat(report.has("results")).isFalse();
            if (!report.path("topFindings").isEmpty()) {
                JsonNode rule = report.path("topFindings").get(0);
                JsonNode detailEnvelope = callAdvisorTool(
                        "get_architecture_rule_violations",
                        Map.of("id", rule.path("id").asText(), "scanId", scanId, "offset", 0, "limit", 1));
                assertThat(detailEnvelope.path("isError").asBoolean()).isFalse();
                JsonNode detail = mapper.readTree(
                        detailEnvelope.path("content").get(0).path("text").asText());
                assertThat(detail.path("scanId").asText()).isEqualTo(scanId);
                assertThat(detail.path("violationCount").asInt())
                        .isEqualTo(rule.path("count").asInt());
                assertThat(detail.path("page").path("limit").asInt()).isEqualTo(1);
            }
            JsonNode unknownRule = callAdvisorTool(
                    "get_architecture_rule_violations", Map.of("id", "definitely-unknown", "scanId", scanId));
            assertThat(unknownRule.path("isError").asBoolean()).isTrue();
            assertThat(unknownRule.path("content").get(0).path("text").asText())
                    .as("an unknown rule is told apart from a rule without findings")
                    .isEqualTo(AdvisorScanState.UNKNOWN_RULE_MESSAGE);
            // The scan answers with a summary, so the full report names the rules with findings or errors; its results
            // list only violating rules, so a rule that passed is one of the catalogue's rules absent from them.
            JsonNode fullReport = probe().get("/bootui/api/architecture").json();
            assertThat(fullReport.path("violationDetails").path("scanId").asText())
                    .isEqualTo(scanId);
            java.util.Set<String> reported = new java.util.HashSet<>();
            fullReport
                    .path("results")
                    .forEach(result -> reported.add(result.path("id").asText()));
            fullReport
                    .path("analysisErrors")
                    .forEach(result -> reported.add(result.path("id").asText()));
            assertThat(reported)
                    .as("the full report lists every rule with a finding, as the scan summary counts them")
                    .hasSizeGreaterThanOrEqualTo(report.path("topFindings").size());
            String passedRule = java.util.stream.Stream.of(
                            "ARCH-CODE-004", "ARCH-CODE-006", "ARCH-CODE-007", "ARCH-SPRING-014", "ARCH-SPRING-020")
                    .filter(candidate -> !reported.contains(candidate))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("every candidate rule reported a finding: " + reported));
            JsonNode passed =
                    callAdvisorTool("get_architecture_rule_violations", Map.of("id", passedRule, "scanId", scanId));
            assertThat(passed.path("isError").asBoolean()).isTrue();
            assertThat(passed.path("content").get(0).path("text").asText())
                    .as("%s ran without findings, so it is not an unknown rule", passedRule)
                    .isEqualTo(AdvisorScanState.NO_FINDINGS_MESSAGE);
            JsonNode stale = callAdvisorTool(
                    "get_architecture_rule_violations", Map.of("id", "ARCH-CODE-002", "scanId", "stale-snapshot"));
            assertThat(stale.path("isError").asBoolean()).isTrue();
            assertThat(stale.path("content").get(0).path("text").asText()).doesNotContain("Internal error");
            assertThat(probe().get("/bootui/api/architecture")
                            .json()
                            .path("violationDetails")
                            .path("scanId")
                            .asText())
                    .isEqualTo(scanId);
        } finally {
            disableMcp();
        }
    }

    private JsonNode callAdvisorTool(String tool, Map<String, Object> arguments) throws Exception {
        String body = new ObjectMapper()
                .writeValueAsString(Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        1,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", tool, "arguments", arguments)));
        Response response =
                probe().request("POST", "/bootui/api/mcp", Map.of("Content-Type", "application/json"), body);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().has("error")).isFalse();
        return response.json().path("result");
    }

    @Test
    void testMcpTransactionsToolMatchesAdapterSupport() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response panelsResponse = probe().get("/bootui/api/panels");
            boolean expected = false;
            for (JsonNode panel : panelsResponse.json().path("panels")) {
                if ("transactions".equals(panel.path("id").asText())) {
                    expected = panel.path("available").asBoolean(false);
                    break;
                }
            }

            Response listResponse = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            boolean advertised = false;
            for (JsonNode tool : listResponse.json().path("result").path("tools")) {
                if ("get_transactions".equals(tool.path("name").asText())) {
                    advertised = true;
                    break;
                }
            }
            assertThat(advertised).isEqualTo(expected);

            if (advertised) {
                Response callResponse = probe().request(
                                "POST",
                                "/bootui/api/mcp",
                                Map.of("Content-Type", "application/json"),
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                                        + "\"params\":{\"name\":\"get_transactions\"}}");
                assertThat(callResponse.status()).isEqualTo(200);
                assertThat(callResponse
                                .json()
                                .path("result")
                                .path("content")
                                .get(0)
                                .path("text")
                                .asText())
                        .contains("\"entries\"");
            }
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpPingWhenEnabled() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().path("result").isObject()).isTrue();
            assertThat(response.json().path("result").size()).isZero();
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpLogTailToolFollowsTheExposurePolicy() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            LogTailExposureContract contract = new LogTailExposureContract().log();
            contract.assertMaskedIn(mcpLogTail(), "MCP get_log_tail");
            LogTailExposureContract.withExposure(
                    "METADATA_ONLY",
                    null,
                    () -> contract.assertOmittedIn(mcpLogTail(), "MCP get_log_tail (METADATA_ONLY)"));
            LogTailExposureContract.withExposure(
                    "FULL", null, () -> contract.assertVerbatimIn(mcpLogTail(), "MCP get_log_tail (FULL)"));
        }
    }

    private JsonNode mcpLogTail() {
        Response response = probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "application/json"),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_log_tail\"}}");
        assertThat(response.status()).isEqualTo(200);
        JsonNode result = response.json().path("result");
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return LogTailExposureContract.json(
                        result.path("content").get(0).path("text").asText())
                .path("entries");
    }

    @Test
    void testMcpUnknownToolRetainsSafeActionableError() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                                    + "\"params\":{\"name\":\"does_not_exist\"}}");
            assertThat(response.status()).isEqualTo(200);
            JsonNode error = response.json().path("error");
            assertThat(error.path("code").asInt()).isEqualTo(-32602);
            assertThat(error.path("message").asText()).isEqualTo("Unknown tool: does_not_exist");
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpUnadvertisedCatalogToolSaysWhyItsPanelIsUnavailable() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            BootUiHttpProbe probe = probe();
            java.util.Set<String> advertised = new java.util.HashSet<>();
            probe.request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                    .json()
                    .path("result")
                    .path("tools")
                    .forEach(tool -> advertised.add(tool.path("name").asText()));
            Map<String, String> unavailable = new java.util.LinkedHashMap<>();
            probe.get("/bootui/api/panels").json().path("panels").forEach(panel -> {
                if (!panel.path("available").asBoolean(true)
                        && !panel.path("unavailableReason").asText("").isBlank()) {
                    unavailable.put(
                            panel.path("id").asText(),
                            panel.path("unavailableReason").asText().trim());
                }
            });
            io.github.jdubois.bootui.engine.mcp.McpToolCatalog.Entry entry =
                    io.github.jdubois.bootui.engine.mcp.McpToolCatalog.entries().stream()
                            .filter(candidate -> !advertised.contains(candidate.name()))
                            .filter(candidate -> unavailable.containsKey(candidate.panelId()))
                            .findFirst()
                            .orElse(null);
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    entry != null, "every panel backing a catalog tool is available in this application");

            JsonNode error = probe.request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\""
                                    + entry.name() + "\"}}")
                    .json()
                    .path("error");
            assertThat(error.path("code").asInt()).isEqualTo(-32602);
            assertThat(error.path("message").asText())
                    .startsWith("Tool not available in this application: " + entry.name() + ".")
                    .contains(unavailable.get(entry.panelId()));
            assertThat(error.path("data").path("tool").asText()).isEqualTo(entry.name());
            assertThat(error.path("data").path("panel").asText()).isEqualTo(entry.panelId());
            assertThat(error.path("data").path("reason").asText()).isEqualTo(unavailable.get(entry.panelId()));
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpRequestProfileRefusesAnIdNeitherRetentionWindowHas() throws Exception {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            String id = "conformance-unknown-request";
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                                    + "\"params\":{\"name\":\"get_request_profile\","
                                    + "\"arguments\":{\"id\":\"" + id + "\"}}}");
            assertThat(response.status()).isEqualTo(200);
            JsonNode result = response.json().path("result");
            assertThat(result.path("isError").asBoolean())
                    .as("an unknown id is a tool error, not an unavailable capability: " + result)
                    .isTrue();
            assertThat(result.path("content").get(0).path("text").asText())
                    .contains(id, "journal", "buffer", "get_live_activity");
        }
    }

    @Test
    void testMcpRequestProfileOpensRetainedJournalRequest() throws Exception {
        assertThat(enableMcp()).isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            String path = requestProfileProbePath();
            Response traffic = probe().get(path);
            assertThat(traffic.status()).isEqualTo(200);
            Response activity = probe().get("/bootui/api/activity?source=journal");
            assertThat(activity.status()).isEqualTo(200);
            String id = null;
            for (JsonNode entry : activity.json().path("entries")) {
                if ("REQUEST".equals(entry.path("type").asText())
                        && path.split("\\?", 2)[0].equals(entry.path("path").asText())) {
                    id = entry.path("id").asText();
                    break;
                }
            }
            assertThat(id).as("the sample request is retained by the journal").isNotBlank();

            JsonNode selected = callTool("get_request_profile", "{\"id\":\"" + id + "\"}");
            assertThat(selected.path("available").asBoolean()).isTrue();
            assertThat(selected.path("source").asText()).isEqualTo("journal");
            assertThat(selected.path("journal"))
                    .isEqualTo(probe().get("/bootui/api/activity/request/" + id + "/journal")
                            .json());
        }
    }

    protected String requestProfileProbePath() {
        return "/api/sample/slow?ms=1";
    }

    @Test
    void testMcpRuntimeInsightsToolsAnswerCompactFactsThatSayWhyInsteadOfEmptySuccesses() throws Exception {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            JsonNode list = callTool("get_runtime_insights", "{\"limit\":3}");
            assertThat(list.has("coverage")).isTrue();
            assertThat(list.has("checksNotRun")).isTrue();
            assertThat(list.path("observations").size()).isLessThanOrEqualTo(3);
            if (!list.path("available").asBoolean()) {
                assertThat(list.path("unavailableReason").asText()).isNotBlank();
            }
            JsonNode byDefault = callTool("get_runtime_insights", "{}");
            assertThat(byDefault.path("observations").size())
                    .as("a call without limit gets the compact default, not bootui.mcp.max-results rows")
                    .isLessThanOrEqualTo(8);
            assertThat(byDefault.has("requests")).isTrue();
            assertThat(byDefault.path("notExercised").size()).isLessThanOrEqualTo(8);
            for (JsonNode observation : byDefault.path("observations")) {
                assertThat(observation.path("listed").asBoolean(false))
                        .as("the default list shows only rows listed by default (M4-19)")
                        .isTrue();
                assertThat(observation.path("kind").asText())
                        .as("kinds that did not pass their external validation are not listed by default (M4-20)")
                        .isNotIn(
                                "route-time-breakdown",
                                "exception-hotspots",
                                "connections-per-request",
                                "ai-usage-by-route",
                                "repeated-selects",
                                "lazy-sql-after-handler",
                                "split-transaction-writes",
                                "framework-warnings-by-route",
                                "anonymous-data-reach");
            }
            for (JsonNode limitation : byDefault.path("limitations")) {
                assertThat(limitation.asText().toLowerCase(java.util.Locale.ROOT))
                        .as("a limitation never tells agents about the external validation (M4-20)")
                        .doesNotContain("validat");
            }
            JsonNode everything = callTool("get_runtime_insights", "{\"query\":\"all\",\"limit\":50}");
            assertThat(everything.path("observations").size()
                            + everything.path("omitted").asInt())
                    .as("the query all reaches every row the default list leaves out")
                    .isGreaterThanOrEqualTo(byDefault.path("observations").size()
                            + byDefault.path("omitted").asInt());

            assertThat(list.path("next").isArray()).isTrue();

            JsonNode unknown = callToolResult("get_runtime_insight", "{\"id\":\"conformance-unknown-observation\"}");
            assertThat(unknown.path("isError").asBoolean())
                    .as("an unknown observation id is a tool error: " + unknown)
                    .isTrue();
            assertThat(unknown.path("content").get(0).path("text").asText())
                    .as("it names the id and the call that lists the current ids")
                    .contains("conformance-unknown-observation", "get_runtime_insights");

            JsonNode impact = callTool("get_runtime_impact", "{\"id\":\"conformanceUnknownSymbol\"}");
            assertThat(impact.path("status").asText()).isIn("NOT_FOUND", "UNAVAILABLE");
            assertThat(impact.path("next").isArray()).isTrue();

            JsonNode comparison = callTool("get_runtime_run_comparison", "{\"id\":\"previous\"}");
            JsonNode defaultComparison = callTool("get_runtime_run_comparison", "{}");
            assertThat(defaultComparison.path("status")).isEqualTo(comparison.path("status"));
            assertThat(defaultComparison.path("previousRunId")).isEqualTo(comparison.path("previousRunId"));
            assertThat(comparison.path("status").asText())
                    .isIn("COMPARED", "INSUFFICIENT", "NOT_COMPARABLE", "NO_PREVIOUS_RUN", "UNAVAILABLE");
            assertThat(comparison.path("behavior").size()).isLessThanOrEqualTo(8);
            assertThat(comparison.has("runs"))
                    .as("the kept run ids an agent may name")
                    .isTrue();
            assertThat(comparison.has("latency"))
                    .as("latency is left out for agents")
                    .isFalse();
            assertThat(comparison.path("next").isArray()).isTrue();
            JsonNode unknownRun = callToolResult("get_runtime_run_comparison", "{\"id\":\"conformance-unknown-run\"}");
            if (!"UNAVAILABLE".equals(comparison.path("status").asText())) {
                assertThat(unknownRun.path("isError").asBoolean())
                        .as("an unknown run id is a tool error: " + unknownRun)
                        .isTrue();
                assertThat(unknownRun.path("content").get(0).path("text").asText())
                        .as("it names the id and the default comparison instead")
                        .contains("conformance-unknown-run", "previous");
            }
        }
    }

    /**
     * The agent's tools without the BootUI agent ({@code docs/PLAN-v2.md} M5-10's acceptance pass): only
     * {@code get_agent_status} is advertised, and it says the agent is not attached and why; a tool that needs one of
     * the agent's sensors is not advertised, so calling it says its panel needs the agent rather than answering a tool
     * failure or an empty success; and Runtime Insights lists its agent-gated checks among the checks not run, with the agent as
     * the reason, and compares runs without a code changes or side effects section.
     */
    @Test
    void testMcpAgentToolsSayTheAgentIsNotAttachedAndTheOthersAreNotAdvertised() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                JavaAgentPresence.detached(), "this JVM runs with the BootUI agent attached");
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try (var cleanup = new ConformanceCleanup(this::disableMcp)) {
            Response list = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/list\"}");
            List<String> advertised = new java.util.ArrayList<>();
            list.json()
                    .path("result")
                    .path("tools")
                    .forEach(tool -> advertised.add(tool.path("name").asText()));
            assertThat(advertised).contains("get_agent_status");
            assertThat(advertised).doesNotContainAnyElementsOf(JavaAgentPresence.AGENT_SENSOR_TOOLS);

            JsonNode status = callTool("get_agent_status", "{}");
            assertThat(status.path("state").asText()).isEqualTo("NOT_ATTACHED");
            assertThat(status.path("reason").asText()).isNotBlank();
            assertThat(status.path("sensors")).isEmpty();

            for (String tool : JavaAgentPresence.AGENT_SENSOR_TOOLS) {
                Response call = probe().request(
                                "POST",
                                "/bootui/api/mcp",
                                Map.of("Content-Type", "application/json"),
                                "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":{\"name\":\""
                                        + tool + "\",\"arguments\":{}}}");
                assertThat(call.status()).isEqualTo(200);
                assertThat(call.json().path("result").isMissingNode())
                        .as("%s answers no result without the agent: %s", tool, call.json())
                        .isTrue();
                assertThat(call.json().path("error").path("message").asText())
                        .as("%s without the agent", tool)
                        .startsWith("Tool not available in this application: " + tool + ".")
                        .contains("Requires the BootUI agent");
            }

            JsonNode insights = callTool("get_runtime_insights", "{\"query\":\"all\",\"limit\":50}");
            if (insights.path("available").asBoolean(false)) {
                List<String> notRun = new java.util.ArrayList<>();
                insights.path("checksNotRun").forEach(check -> notRun.add(check.asText()));
                for (String kind : JavaAgentPresence.AGENT_CHECKS) {
                    assertThat(notRun)
                            .as("%s is a check not run, naming the agent", kind)
                            .anySatisfy(check -> assertThat(check)
                                    .startsWith(kind + ": NOT_APPLICABLE, ")
                                    .contains("requires the BootUI agent's"));
                }
            }
            JsonNode comparison = callTool("get_runtime_run_comparison", "{}");
            for (String section : List.of("codeChanges", "sideEffects")) {
                JsonNode value = comparison.path(section);
                assertThat(value.isNull() || value.isMissingNode())
                        .as("the comparison has no %s section without the agent: %s", section, value)
                        .isTrue();
            }
        }
    }

    private JsonNode callTool(String name, String arguments) throws Exception {
        JsonNode result = callToolResult(name, arguments);
        assertThat(result.path("isError").asBoolean()).as(name + ": " + result).isFalse();
        return new ObjectMapper()
                .readTree(result.path("content").get(0).path("text").asText());
    }

    /** The {@code tools/call} result envelope, a tool error included. */
    private JsonNode callToolResult(String name, String arguments) {
        Response response = probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "application/json"),
                        "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                                + "\",\"arguments\":" + arguments + "}}");
        assertThat(response.status()).isEqualTo(200);
        return response.json().path("result");
    }

    @Test
    void testMcpToolClientErrorIsReportedInBandInsteadOfInternalError() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response listResponse = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            boolean advertised = false;
            for (JsonNode tool : listResponse.json().path("result").path("tools")) {
                if ("get_exception_detail".equals(tool.path("name").asText())) {
                    advertised = true;
                    break;
                }
            }
            if (!advertised) {
                return;
            }

            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                                    + "\"params\":{\"name\":\"get_exception_detail\","
                                    + "\"arguments\":{\"id\":\"does-not-exist\"}}}");

            assertThat(response.status()).isEqualTo(200);
            JsonNode body = response.json();
            assertThat(body.path("error").isMissingNode())
                    .as("an unknown id is a statement about the request, not a server fault")
                    .isTrue();
            JsonNode result = body.path("result");
            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.path("content").get(0).path("text").asText())
                    .contains("does-not-exist")
                    .doesNotContain("Internal error");
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpPromptsWhenEnabled() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response list = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\"}");
            assertThat(list.status()).isEqualTo(200);
            JsonNode prompts = list.json().path("result").path("prompts");
            assertThat(prompts.isArray()).isTrue();
            assertThat(prompts)
                    .extracting(prompt -> prompt.path("name").asText())
                    .containsExactly(
                            "diagnose_runtime_issue",
                            "verify_after_change",
                            "review_application",
                            "assess_application");

            for (JsonNode prompt : prompts) {
                String name = prompt.path("name").asText();
                assertThat(prompt.path("arguments").isArray()).isTrue();
                assertThat(prompt.path("arguments"))
                        .as("every prompt argument is optional, so a client that sends none keeps working")
                        .isNotEmpty()
                        .allSatisfy(argument -> {
                            assertThat(argument.path("name").asText()).isNotBlank();
                            assertThat(argument.path("description").asText()).isNotBlank();
                            assertThat(argument.path("required").asBoolean(true))
                                    .isFalse();
                        });
                Response get = probe().request(
                                "POST",
                                "/bootui/api/mcp",
                                Map.of("Content-Type", "application/json"),
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\"," + "\"params\":{\"name\":\""
                                        + name + "\"}}");
                assertThat(get.status()).isEqualTo(200);
                JsonNode result = get.json().path("result");
                assertThat(result.path("description").isTextual()).isTrue();
                assertThat(result.path("messages")).hasSize(1);
                JsonNode message = result.path("messages").get(0);
                assertThat(message.path("role").asText()).isEqualTo("user");
                assertThat(message.path("content").path("type").asText()).isEqualTo("text");
                assertThat(message.path("content").path("text").asText()).isNotBlank();
                if ("assess_application".equals(name)) {
                    assertThat(message.path("content").path("text").asText())
                            .contains(
                                    "Start with existing evidence only",
                                    "STOP after presenting the plan",
                                    "specific plan version",
                                    "external agent host's permissions",
                                    "rule AND affected target");
                }
            }

            Response focused = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/get\",\"params\":{\"name\":"
                                    + "\"diagnose_runtime_issue\",\"arguments\":{\"route\":\"GET /conformance\"}}}");
            assertThat(focused.json()
                            .path("result")
                            .path("messages")
                            .get(0)
                            .path("content")
                            .path("text")
                            .asText())
                    .as("a prompt argument reaches the rendered prompt on every stack")
                    .endsWith("- The route, job, or listener involved: GET /conformance");
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpNotificationReturns202() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            assertThat(response.status()).isEqualTo(202);
            assertThat(response.body()).isBlank();
        } finally {
            disableMcp();
        }
    }

    @Test
    void testRecognizedMcpNotificationReturns202() {
        assertThat(enableMcp()).as("this adapter claims MCP support").isTrue();
        try {
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}");
            assertThat(response.status()).isEqualTo(202);
            assertThat(response.body()).isBlank();
        } finally {
            disableMcp();
        }
    }

    @Test
    void testDisabledMcpNotificationReturns202() {
        Response response = probe().request(
                        "POST",
                        "/bootui/api/mcp",
                        Map.of("Content-Type", "application/json"),
                        "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}");
        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body()).isBlank();
    }
}
