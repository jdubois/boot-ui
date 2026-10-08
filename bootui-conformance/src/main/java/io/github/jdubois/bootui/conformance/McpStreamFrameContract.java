package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Arrays;
import java.util.Map;

/**
 * The raw bytes of request-scoped MCP event streams, the same on every stack: each stack's own transport (the servlet
 * output stream, WebFlux's {@code ServerSentEvent} encoder, and Quarkus REST's streaming output) must write exactly the
 * frames below, byte for byte in UTF-8, with Unicode written as is and a carriage return or line feed in a value
 * escaped by JSON, never breaking the {@code data:} line. Keep-alive comments ({@code :} and a blank line) depend on
 * timing, so the contract asserts each one exactly but accepts any number of them between the frames.
 *
 * <p>Install {@link #tool()}, then call {@link #assertFrames(int, String, String)}; the server version must be
 * {@code test}.
 */
public final class McpStreamFrameContract {

    private static final ProgressPhase PHASE = ProgressPhase.of("Checking frames");
    /**
     * Twice the 2-second keep-alive interval: the keep-alive clock starts with the writer, not the tool, so this leaves
     * room for scheduling on a loaded runner and still guarantees a heartbeat.
     */
    private static final long SLOW_MILLIS = 4_200;

    private static final String TOKEN_JSON = "tok-\u00e9\u2713\\r\\n";
    private static final String HEARTBEAT = ":\n\n";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private enum Mode {
        SUCCESS,
        TOOL_ERROR,
        TOO_LARGE
    }

    private volatile Mode mode = Mode.SUCCESS;

    /** The tool the streams call, under the catalog name of a tool that streams progress. */
    public McpTool tool() {
        return new McpTool(
                "vulnerabilities_scan",
                "Answers with known frames.",
                McpToolSchema.NONE,
                BootUiPanels.VULNERABILITIES,
                true,
                arguments -> run());
    }

    private Object run() {
        OperationProgress.current().report(PHASE, 1, 2);
        switch (mode) {
            case SUCCESS -> {
                try {
                    Thread.sleep(SLOW_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                // U+2028 is not a line break in SSE: every stack writes it as is, inside the data: line.
                return Map.of("note", "caf\u00e9 \u2713\u2028\r\n");
            }
            case TOOL_ERROR -> throw new McpToolClientException(404, "No caf\u00e9 \u2713 here\r\n");
            default -> {
                return Map.of("note", "x".repeat(5 * 1024 * 1024));
            }
        }
    }

    /** Streams a modern success, a modern tool error, a modern oversized result, and a legacy success. */
    public void assertFrames(int port, String path, String serverVersion) throws Exception {
        String serverInfo = ",\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"bootui\",\"version\":\""
                + serverVersion + "\"}}";
        String success =
                "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"note\\\":\\\"caf\u00e9 \u2713\u2028\\\\r\\\\n\\\"}\"}],"
                        + "\"structuredContent\":{\"note\":\"caf\u00e9 \u2713\u2028\\r\\n\"},\"isError\":false";

        mode = Mode.SUCCESS;
        assertStream(
                stream(port, path, 1, true),
                "data:{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"resultType\":\"complete\"," + success + serverInfo
                        + "}}\n\n",
                true);

        mode = Mode.TOOL_ERROR;
        assertStream(
                stream(port, path, 2, true),
                "data:{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"resultType\":\"complete\",\"content\":[{\"type\":"
                        + "\"text\",\"text\":\"No caf\u00e9 \u2713 here\\r\\n\"}],\"isError\":true" + serverInfo
                        + "}}\n\n",
                false);

        mode = Mode.TOO_LARGE;
        assertStream(
                stream(port, path, 3, true),
                "data:{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-31003,"
                        + "\"message\":\"MCP response exceeds configured byte limit\"}}\n\n",
                false);

        mode = Mode.SUCCESS;
        assertStream(
                stream(port, path, 4, false),
                "data:{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{" + success + "}}\n\n",
                true);
    }

    private static void assertStream(HttpResponse<byte[]> response, String finalFrame, boolean heartbeatExpected) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        byte[] body = response.body();
        byte[] progress = utf8(
                "data:{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{" + "\"progressToken\":\""
                        + TOKEN_JSON + "\",\"progress\":1,\"total\":2,\"message\":\"Checking frames\"}}\n\n");
        assertThat(startsWith(body, 0, progress))
                .as("the progress frame, byte for byte:%n%s", new String(body, StandardCharsets.UTF_8))
                .isTrue();
        int offset = progress.length;
        byte[] heartbeat = utf8(HEARTBEAT);
        int heartbeats = 0;
        while (startsWith(body, offset, heartbeat)) {
            offset += heartbeat.length;
            heartbeats++;
        }
        if (heartbeatExpected) {
            assertThat(heartbeats).as("a keep-alive comment").isPositive();
        }
        assertThat(Arrays.copyOfRange(body, offset, body.length))
                .as("the final frame, byte for byte")
                .isEqualTo(utf8(finalFrame));
    }

    private static HttpResponse<byte[]> stream(int port, String path, int id, boolean modern) throws Exception {
        String meta = modern
                ? "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                        + "\"io.modelcontextprotocol/clientCapabilities\":{},"
                : "";
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"vulnerabilities_scan\",\"arguments\":{},\"_meta\":{" + meta + "\"progressToken\":\""
                + TOKEN_JSON + "\"}}}";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (modern) {
            request.header("MCP-Protocol-Version", "2026-07-28")
                    .header("Mcp-Method", "tools/call")
                    .header("Mcp-Name", "vulnerabilities_scan");
        } else {
            request.header("MCP-Protocol-Version", "2025-06-18");
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static boolean startsWith(byte[] body, int offset, byte[] prefix) {
        return body.length - offset >= prefix.length
                && Arrays.equals(body, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
