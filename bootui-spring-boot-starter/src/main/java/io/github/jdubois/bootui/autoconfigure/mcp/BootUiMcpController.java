package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader.PayloadTooLargeException;
import io.github.jdubois.bootui.engine.mcp.McpProgressToken;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpStreamSink;
import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Loopback HTTP transport for the BootUI MCP server (MCP "Streamable HTTP" style).
 *
 * <p>Clients POST JSON-RPC 2.0 requests to {@code /bootui/api/mcp} and receive JSON-RPC responses.
 * The endpoint lives under {@code /bootui/api} so it inherits {@code LocalhostOnlyFilter}'s loopback,
 * Host allow-list, and cross-site write defenses. The beans are always registered while BootUI is
 * active (dev contexts), but requests are only served while the server is enabled — the live state
 * is initialized from {@code bootui.mcp.enabled} and can be toggled at runtime from the MCP Server
 * panel via {@link McpServerState}.
 *
 * <p>The endpoint is dual-era: {@link BootUiMcpService#exchange} selects MCP 2025-06-18 or MCP 2026-07-28 from the
 * request body and its {@code MCP-Protocol-Version}, {@code Mcp-Method}, and {@code Mcp-Name} headers, so the body is
 * read before any header is judged. The {@code GET} variant returns 405 because BootUI offers no server-to-client
 * stream at this endpoint, which MCP 2026-07-28 removed altogether. Human-readable status is available from
 * {@code /bootui/api/mcp-server}.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/mcp")
public class BootUiMcpController {

    private static final String PAYLOAD_LIMIT_MESSAGE = "Request payload exceeds limit";
    private static final long ASYNC_TIMEOUT_GRACE_MILLIS = 10_000;

    private final BootUiMcpService service;
    private final McpServerState state;
    private final int maxPayloadBytes;
    private final long asyncTimeoutMillis;

    public BootUiMcpController(BootUiMcpService service, McpServerState state, BootUiProperties properties) {
        this.service = service;
        this.state = state;
        this.maxPayloadBytes = Math.max(1, properties.getMcp().getMaxPayloadBytes());
        // The call's own timeout ends a stream; the container's is only a backstop against a lost writer.
        this.asyncTimeoutMillis =
                Math.max(1, properties.getMcp().getExecutionTimeout().toMillis()) + ASYNC_TIMEOUT_GRACE_MILLIS;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rpc(
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse,
            @RequestHeader HttpHeaders headers) {
        byte[] requestBody;
        try {
            requestBody = McpPayloadReader.read(servletRequest.getInputStream(), maxPayloadBytes);
        } catch (PayloadTooLargeException ex) {
            return json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE));
        } catch (IllegalArgumentException | java.io.IOException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, "Could not read request payload"));
        }
        JsonNode request;
        try {
            request = service.readTree(requestBody);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        BootUiMcpService.Reply reply = service.exchange(
                request,
                BootUiMcpService.headers(headers),
                state.isEnabled(),
                McpProtocol.acceptsEventStream(headers.get(HttpHeaders.ACCEPT)));
        if (reply.stream() != null) {
            stream(servletRequest, servletResponse, reply.stream());
            // The response is written asynchronously; a null entity tells Spring MVC it is already handled.
            return null;
        }
        if (reply.body() == null) {
            // Notification (no id) — acknowledge with 202 and no body.
            return ResponseEntity.accepted().build();
        }
        return json(reply.status(), reply.body());
    }

    /**
     * Answers on a request-scoped {@code text/event-stream}. The servlet response is put in async mode so the
     * container thread returns at once, and only the call's writer thread writes to it. A container-reported error,
     * timeout, or completion means the client went away: MCP 2026-07-28 makes that a cancellation, and MCP 2025-06-18
     * does not ({@link McpStreamingCall#clientClosed()} decides).
     */
    private void stream(
            HttpServletRequest servletRequest, HttpServletResponse servletResponse, BootUiMcpService.Stream stream) {
        McpStreamingCall call = stream.call();
        try {
            AsyncContext async = servletRequest.startAsync(servletRequest, servletResponse);
            async.setTimeout(asyncTimeoutMillis);
            servletResponse.setStatus(200);
            servletResponse.setContentType(McpProtocol.EVENT_STREAM_MEDIA_TYPE);
            servletResponse.setHeader(McpProtocol.ACCEL_BUFFERING_HEADER, "no");
            ServletOutputStream output = servletResponse.getOutputStream();
            output.flush();
            AtomicBoolean closed = new AtomicBoolean();
            async.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    call.clientClosed();
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    call.clientClosed();
                    complete(async, closed);
                }

                @Override
                public void onError(AsyncEvent event) {
                    call.clientClosed();
                    complete(async, closed);
                }

                @Override
                public void onStartAsync(AsyncEvent event) {}
            });
            call.start(new McpStreamSink() {
                @Override
                public void progress(McpProgressToken token, ProgressEvent event) throws IOException {
                    write(
                            output,
                            McpProtocol.SSE_DATA_PREFIX
                                    + service.renderProgress(token, event)
                                    + McpProtocol.SSE_EVENT_END);
                }

                @Override
                public void heartbeat() throws IOException {
                    write(output, McpProtocol.SSE_HEARTBEAT);
                }

                @Override
                public void complete(McpDispatchOutcome outcome) throws IOException {
                    write(
                            output,
                            McpProtocol.SSE_DATA_PREFIX
                                    + service.renderFinal(stream.id(), call.era(), outcome)
                                    + McpProtocol.SSE_EVENT_END);
                }

                @Override
                public void close() {
                    BootUiMcpController.complete(async, closed);
                }
            });
        } catch (IOException ex) {
            call.cancel();
            throw new UncheckedIOException(ex);
        } catch (RuntimeException | Error failure) {
            call.cancel();
            throw failure;
        }
    }

    private static void write(ServletOutputStream output, String frame) throws IOException {
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void complete(AsyncContext async, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) {
            try {
                async.complete();
            } catch (IllegalStateException alreadyCompleted) {
                // The container already ended the request (client gone or timed out).
            }
        }
    }

    @GetMapping
    public ResponseEntity<Void> getStream() {
        return ResponseEntity.status(405).build();
    }

    private static tools.jackson.databind.node.ObjectNode error(JsonNode id, int code, String message) {
        tools.jackson.databind.node.ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        tools.jackson.databind.node.ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", code);
        error.put("message", message == null ? "Error" : message);
        response.set("error", error);
        return response;
    }

    private static ResponseEntity<String> json(int status, JsonNode body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString());
    }
}
