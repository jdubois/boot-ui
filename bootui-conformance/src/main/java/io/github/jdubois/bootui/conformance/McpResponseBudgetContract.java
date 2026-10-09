package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.progress.OperationProgress;
import io.github.jdubois.bootui.engine.progress.ProgressPhase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Native HTTP byte-budget contract: install {@link #tool()} with a 512-byte budget and one concurrency slot. */
public final class McpResponseBudgetContract {

    private static final int BUDGET = 512;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicInteger invocations = new AtomicInteger();
    private volatile boolean toolError;

    public McpTool tool() {
        return new McpTool(
                "architecture_scan",
                "Tests response bounds.",
                McpToolSchema.NONE,
                BootUiPanels.ARCHITECTURE,
                true,
                arguments -> {
                    invocations.incrementAndGet();
                    OperationProgress.current().report(ProgressPhase.of("Checking budget"), 1, 1);
                    if (toolError) {
                        throw new McpToolClientException(400, "x".repeat(2048));
                    }
                    return Map.of("note", "x".repeat(2048));
                });
    }

    /**
     * Compare native wire bytes with the adapter's actual JSON serialization, including an application's configured
     * formatting. SSE always has a compact JSON payload; its seven framing bytes are outside the budget.
     */
    public void verify(int port, McpDispatcher dispatcher, Function<String, byte[]> jsonWire) throws Exception {
        long refusals = dispatcher.runtimeStats().snapshot().responseLimitRefusals();
        int expectedRefusals = 0;
        for (boolean modern : List.of(false, true)) {
            for (boolean streaming : List.of(false, true)) {
                for (String id : List.of(
                        quote("x".repeat(2048)),
                        quote("\u0001".repeat(100)),
                        quote("\u20ac".repeat(180)),
                        "9".repeat(700))) {
                    int before = invocations.get();
                    HttpResponse<byte[]> reply = call(port, id, modern, streaming);
                    assertRefusal(reply);
                    assertThat(invocations).hasValue(before);
                    assertThat(dispatcher.availableCallPermits()).isEqualTo(1);
                    expectedRefusals++;
                }
                for (String prefix : List.of("", "\r\n\t\"\\", "\u00e9\u20ac\u2713")) {
                    String id = boundaryId(prefix, modern, jsonWire);
                    assertThat(jsonWire.apply(fallback(id, modern))).hasSize(BUDGET);
                    for (boolean error : List.of(false, true)) {
                        toolError = error;
                        int before = invocations.get();
                        HttpResponse<byte[]> reply = call(port, id, modern, streaming);
                        assertThat(reply.statusCode()).isEqualTo(200);
                        assertThat(invocations).hasValue(before + 1);
                        assertFallback(reply, id, modern, streaming, jsonWire);
                        awaitIdle(dispatcher);
                        expectedRefusals++;
                    }
                    int before = invocations.get();
                    assertRefusal(call(port, quote(JSON.readTree(id).asText() + "x"), modern, streaming));
                    assertThat(invocations).hasValue(before);
                    assertThat(dispatcher.availableCallPermits()).isEqualTo(1);
                    expectedRefusals++;
                }
                toolError = false;
                HttpResponse<byte[]> ordinary = call(port, "7", modern, streaming);
                assertFallback(ordinary, "7", modern, streaming, jsonWire);
                awaitIdle(dispatcher);
                expectedRefusals++;
            }
        }
        assertThat(dispatcher.runtimeStats().snapshot().responseLimitRefusals()).isEqualTo(refusals + expectedRefusals);
        assertThat(dispatcher.runtimeStats().snapshot().capacityRefusals()).isZero();
        assertThat(dispatcher.availableCallPermits()).isEqualTo(1);

        HttpResponse<byte[]> notification =
                post(port, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", false, false);
        assertThat(notification.statusCode()).isEqualTo(202);
        assertThat(notification.body()).isEmpty();
    }

    public static String fallback(String idJson, boolean modern) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"error\":{\"code\":"
                + (modern ? "-31003" : "-32003")
                + ",\"message\":\"MCP response exceeds configured byte limit\"}}";
    }

    private static String boundaryId(String prefix, boolean modern, Function<String, byte[]> jsonWire)
            throws Exception {
        int remaining = BUDGET - jsonWire.apply(fallback(quote(prefix), modern)).length;
        assertThat(remaining).isNotNegative();
        return quote(prefix + "x".repeat(remaining));
    }

    private static void assertRefusal(HttpResponse<byte[]> reply) {
        assertThat(reply.statusCode())
                .as("bodyless resource refusal, received: %s", new String(reply.body(), StandardCharsets.UTF_8))
                .isEqualTo(413);
        assertThat(reply.body()).isEmpty();
        assertThat(reply.headers().firstValue("Content-Type").orElse("")).doesNotContain("text/event-stream");
        assertThat(reply.headers().firstValue(McpProtocol.ACCEL_BUFFERING_HEADER))
                .isEmpty();
    }

    private static void awaitIdle(McpDispatcher dispatcher) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (dispatcher.availableCallPermits() != 1 && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        assertThat(dispatcher.availableCallPermits())
                .as("tool and writer released their one slot")
                .isEqualTo(1);
    }

    private static void assertFallback(
            HttpResponse<byte[]> reply,
            String id,
            boolean modern,
            boolean streaming,
            Function<String, byte[]> jsonWire) {
        assertThat(reply.statusCode()).isEqualTo(200);
        String fallback = fallback(id, modern);
        if (!streaming) {
            assertThat(reply.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(reply.body()).isEqualTo(jsonWire.apply(fallback));
            assertThat(reply.body().length).isLessThanOrEqualTo(BUDGET);
            return;
        }
        assertThat(reply.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        String[] frames = new String(reply.body(), StandardCharsets.UTF_8).split("\n\n");
        int finals = 0;
        for (String frame : frames) {
            if (frame.equals(":")) {
                continue;
            }
            assertThat(frame).startsWith("data:");
            String payload = frame.substring(5);
            assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(BUDGET);
            if (payload.contains("\"id\":")) {
                assertThat(payload).isEqualTo(fallback);
                finals++;
            }
        }
        assertThat(finals).as("exactly one final response").isEqualTo(1);
        assertThat(new String(reply.body(), StandardCharsets.UTF_8)).endsWith(fallback + "\n\n");
    }

    private static HttpResponse<byte[]> call(int port, String id, boolean modern, boolean streaming) throws Exception {
        String meta = modern
                ? "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                        + "\"io.modelcontextprotocol/clientCapabilities\":{},"
                : "";
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"architecture_scan\",\"arguments\":{},\"_meta\":{" + meta + "\"progressToken\":\"p\"}}}";
        return post(port, body, modern, streaming);
    }

    private static HttpResponse<byte[]> post(int port, String body, boolean modern, boolean streaming)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bootui/api/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "application/json, text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (modern) {
            request.header("MCP-Protocol-Version", "2026-07-28")
                    .header("Mcp-Method", "tools/call")
                    .header("Mcp-Name", "architecture_scan");
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String quote(String text) throws Exception {
        return JSON.writeValueAsString(text);
    }
}
