package io.github.jdubois.bootui.quarkus4.web;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpEra;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader.PayloadTooLargeException;
import io.github.jdubois.bootui.engine.mcp.McpProgressToken;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpStreamSink;
import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import io.github.jdubois.bootui.quarkus4.mcp.BootUiMcpProducer;
import io.github.jdubois.bootui.quarkus4.mcp.McpServerState;
import io.github.jdubois.bootui.quarkus4.mcp.QuarkusMcpEnvelope;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.Config;
import org.jboss.resteasy.reactive.server.spi.ServerHttpResponse;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loopback HTTP transport for the BootUI MCP server on Quarkus (MCP "Streamable HTTP" style) — the
 * Quarkus analogue of the Spring adapter's {@code BootUiMcpController}. The body is read before any MCP header is
 * judged, because the dual-era {@link QuarkusMcpEnvelope#exchange} selects MCP 2025-06-18 or MCP 2026-07-28 from it.
 */
@ApplicationScoped
@Path("/bootui/api/mcp")
public class McpBridgeResource {

    private static final String PAYLOAD_LIMIT_MESSAGE = "Request payload exceeds limit";
    private static final long STREAM_WAIT_GRACE_MILLIS = 10_000;

    private final McpServerState state;
    private final QuarkusMcpEnvelope envelope;
    private final int maxPayloadBytes;
    private final long streamWaitMillis;

    @Inject
    public McpBridgeResource(McpServerState state, QuarkusMcpEnvelope envelope, Config config) {
        this.state = state;
        this.envelope = envelope;
        this.maxPayloadBytes = BootUiMcpProducer.maxPayloadBytes(config);
        // The call's own timeout ends a stream; this wait is only a backstop against a lost writer.
        this.streamWaitMillis = BootUiMcpProducer.executionTimeoutMillis(config) + STREAM_WAIT_GRACE_MILLIS;
    }

    @POST
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public void rpc(
            InputStream requestBody,
            @Context HttpHeaders headers,
            @Context ServerRequestContext requestContext,
            @Suspended AsyncResponse response) {
        byte[] payload;
        try {
            payload = McpPayloadReader.read(requestBody, maxPayloadBytes);
        } catch (PayloadTooLargeException ex) {
            response.resume(json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE)));
            return;
        } catch (IllegalArgumentException ex) {
            response.resume(json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage())));
            return;
        }
        JsonNode request;
        try {
            request = envelope.readTree(payload);
        } catch (IllegalArgumentException ex) {
            response.resume(json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage())));
            return;
        }
        QuarkusMcpEnvelope.Reply reply = envelope.exchange(
                request,
                headers(headers),
                state.isEnabled(),
                McpProtocol.acceptsEventStream(headers.getRequestHeader(McpProtocol.ACCEPT_HEADER)));
        if (reply.stream() != null) {
            events(reply.stream(), requestContext.serverResponse(), response);
            return;
        }
        if (reply.body() == null) {
            response.resume(Response.status(reply.status()).build());
            return;
        }
        response.resume(json(reply.status(), reply.body()));
    }

    /**
     * The call's writer awaits one native write at a time while this worker waits for its close. Avoid
     * {@code StreamingOutput}: Quarkus's output stream replaces the response close handler and closes the entire
     * HTTP/2 connection on a stream reset. The native response preserves the additive close callback and reports failed
     * writes; both reach {@link
     * McpStreamingCall#clientClosed()}, which cancels a modern call (MCP 2026-07-28) and only stops writing a legacy one
     * (MCP 2025-06-18). The frames are the same bytes the Spring transports write. If the writer is still not done when
     * the backstop wait expires (the execution timeout plus a grace period), it is stuck on a client that stopped
     * reading: the response is reset (an HTTP/2 RST_STREAM, or closing an HTTP/1.1 connection), which fails the pending
     * write and frees the call's concurrency permit, rather than waiting for the HTTP idle timeout.
     */
    private void events(QuarkusMcpEnvelope.Stream stream, ServerHttpResponse response, AsyncResponse asynchronous) {
        McpStreamingCall call = stream.call();
        CountDownLatch closed = new CountDownLatch(1);
        try {
            response.addCloseHandler(call::clientClosed);
            if (response.closed()) {
                call.clientClosed();
            }
            response.setStatusCode(200);
            response.setResponseHeader(HttpHeaders.CONTENT_TYPE, McpProtocol.EVENT_STREAM_MEDIA_TYPE);
            response.setResponseHeader(McpProtocol.ACCEL_BUFFERING_HEADER, "no");
            response.setChunked(true);
            call.start(new McpStreamSink() {
                @Override
                public void progress(McpProgressToken token, ProgressEvent event) throws IOException {
                    String json = envelope.renderProgress(token, event);
                    if (json != null) {
                        write(response, McpProtocol.sseDataFrame(json));
                    }
                }

                @Override
                public void heartbeat() throws IOException {
                    write(response, McpProtocol.SSE_HEARTBEAT);
                }

                @Override
                public void complete(McpDispatchOutcome outcome) throws IOException {
                    write(response, McpProtocol.sseDataFrame(envelope.renderFinal(stream.id(), call.era(), outcome)));
                }

                @Override
                public void close() {
                    closed.countDown();
                }
            });
            if (!closed.await(streamWaitMillis, TimeUnit.MILLISECONDS)) {
                call.cancel();
                // Resets only this stream: on HTTP/2 the client's other calls on the connection go on.
                response.reset();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            call.cancel();
            response.reset();
        } catch (RuntimeException | Error failure) {
            call.cancel();
            response.reset();
            asynchronous.resume(failure);
        } finally {
            // REST owns completion; a bodyless SSE response ends without creating a VertxOutputStream.
            asynchronous.resume(
                    Response.ok().type(McpProtocol.EVENT_STREAM_MEDIA_TYPE).build());
        }
    }

    private static void write(ServerHttpResponse response, String frame) throws IOException {
        if (response.closed()) {
            throw new IOException("The client closed the MCP event stream");
        }
        try {
            response.write(frame.getBytes(StandardCharsets.UTF_8))
                    .toCompletableFuture()
                    .get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            response.reset();
            throw new IOException("Interrupted while writing the MCP event stream", interrupted);
        } catch (ExecutionException failure) {
            throw new IOException("Failed to write the MCP event stream", failure.getCause());
        }
    }

    private static McpRequestHeaders headers(HttpHeaders headers) {
        return new McpRequestHeaders(
                headers.getRequestHeader(McpProtocol.PROTOCOL_VERSION_HEADER),
                headers.getRequestHeader(McpProtocol.METHOD_HEADER),
                headers.getRequestHeader(McpProtocol.NAME_HEADER));
    }

    @GET
    public Response getStream() {
        return Response.status(405).build();
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", code);
        error.put("message", message == null ? "Error" : message);
        response.set("error", error);
        return response;
    }

    private Response json(int status, JsonNode body) {
        QuarkusMcpEnvelope.Reply reply = envelope.limitResponse(status, body, McpEra.LEGACY);
        if (reply.body() == null) {
            return Response.status(reply.status()).build();
        }
        return Response.status(reply.status())
                .type(MediaType.APPLICATION_JSON)
                .entity(reply.body())
                .build();
    }
}
