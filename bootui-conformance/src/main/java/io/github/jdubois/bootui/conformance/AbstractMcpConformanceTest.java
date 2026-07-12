package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        return false;
    }

    /** Disable the MCP server after a test that enabled it. */
    protected void disableMcp() {}

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe(baseUrl());
    }

    @Test
    void testMcpGetStatus() {
        Response response = probe().get("/bootui/api/mcp");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        JsonNode json = response.json();
        assertThat(json.path("enabled").isBoolean()).isTrue();
        assertThat(json.path("server").asText()).isEqualTo("bootui");
        assertThat(json.path("endpoint").asText()).isEqualTo("/bootui/api/mcp");
        assertThat(json.path("toolCount").canConvertToInt()).isTrue();
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
        assumeTrue(enableMcp());
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
            assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("bootui");
        } finally {
            disableMcp();
        }
    }

    @Test
    void testMcpValidatesJsonrpc() {
        assumeTrue(enableMcp());
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
        assumeTrue(enableMcp());
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
    void testMcpToolsListWhenEnabled() {
        assumeTrue(enableMcp());
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
    void testMcpPingWhenEnabled() {
        assumeTrue(enableMcp());
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
    void testMcpNotificationReturns202() {
        assumeTrue(enableMcp());
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
}
