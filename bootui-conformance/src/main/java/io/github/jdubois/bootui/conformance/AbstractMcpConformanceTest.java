package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
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
        } finally {
            disableMcp();
        }
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
    void testMcpExplorerToolsFollowAvailabilityAndRequireAnEventId() {
        assertThat(enableMcp()).isTrue();
        try {
            boolean available = false;
            for (JsonNode panel : probe().get("/bootui/api/panels").json().path("panels")) {
                if ("explorer".equals(panel.path("id").asText())) {
                    available = panel.path("available").asBoolean();
                }
            }
            Response response = probe().request(
                            "POST",
                            "/bootui/api/mcp",
                            Map.of("Content-Type", "application/json"),
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            java.util.List<String> names = new java.util.ArrayList<>();
            response.json()
                    .path("result")
                    .path("tools")
                    .forEach(tool -> names.add(tool.path("name").asText()));
            assertThat(names.contains("get_explorer")).isEqualTo(available);
            assertThat(names.contains("get_explorer_event")).isEqualTo(available);
            if (available) {
                Response missingId = probe().request(
                                "POST",
                                "/bootui/api/mcp",
                                Map.of("Content-Type", "application/json"),
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                                        + "\"params\":{\"name\":\"get_explorer_event\",\"arguments\":{}}}");
                assertThat(missingId.json().path("error").path("code").asInt()).isEqualTo(-32602);
                Response report = probe().request(
                                "POST",
                                "/bootui/api/mcp",
                                Map.of("Content-Type", "application/json"),
                                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                                        + "\"params\":{\"name\":\"get_explorer\",\"arguments\":{\"limit\":2}}}");
                assertThat(report.status()).isEqualTo(200);
                assertThat(report.json().path("result").path("isError").asBoolean())
                        .isFalse();
                assertThat(report.json()
                                .path("result")
                                .path("content")
                                .get(0)
                                .path("text")
                                .asText())
                        .contains("\"activity\"", "\"setup\"");
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
                    .containsExactly("diagnose_runtime_issue", "review_application", "assess_application");

            for (JsonNode prompt : prompts) {
                String name = prompt.path("name").asText();
                assertThat(prompt.path("arguments").isArray()).isTrue();
                assertThat(prompt.path("arguments")).isEmpty();
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
