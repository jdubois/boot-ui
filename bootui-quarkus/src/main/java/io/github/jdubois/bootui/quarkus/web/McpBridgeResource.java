package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader.PayloadTooLargeException;
import io.github.jdubois.bootui.engine.mcp.McpProgressToken;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpStreamSink;
import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import io.github.jdubois.bootui.quarkus.mcp.BootUiMcpProducer;
import io.github.jdubois.bootui.quarkus.mcp.McpServerState;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpEnvelope;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.Config;

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
    public Response rpc(InputStream requestBody, @Context HttpHeaders headers, @Context RoutingContext routing) {
        byte[] payload;
        try {
            payload = McpPayloadReader.read(requestBody, maxPayloadBytes);
        } catch (PayloadTooLargeException ex) {
            return json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE));
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        JsonNode request;
        try {
            request = envelope.readTree(payload);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        QuarkusMcpEnvelope.Reply reply = envelope.exchange(
                request,
                headers(headers),
                state.isEnabled(),
                McpProtocol.acceptsEventStream(headers.getRequestHeader(McpProtocol.ACCEPT_HEADER)));
        if (reply.stream() != null) {
            return Response.ok(events(reply.stream(), routing))
                    .type(McpProtocol.EVENT_STREAM_MEDIA_TYPE)
                    .header(McpProtocol.ACCEL_BUFFERING_HEADER, "no")
                    .build();
        }
        if (reply.body() == null) {
            return Response.accepted().build();
        }
        return json(reply.status(), reply.body());
    }

    /**
     * The request-scoped event stream, written while this worker thread waits: the call's writer thread is the only
     * one that writes. The client going away cancels the call, which MCP 2026-07-28 requires: Vert.x reports the
     * closed connection through the routing context's end handler, and a write to a closed response, which Quarkus REST
     * drops silently, fails instead. The frames are the same bytes the Spring transports write.
     */
    private StreamingOutput events(QuarkusMcpEnvelope.Stream stream, RoutingContext routing) {
        McpStreamingCall call = stream.call();
        routing.addEndHandler(ended -> call.cancel());
        return output -> {
            CountDownLatch closed = new CountDownLatch(1);
            try {
                call.start(new McpStreamSink() {
                    @Override
                    public void progress(McpProgressToken token, ProgressEvent event) throws IOException {
                        write(
                                routing,
                                output,
                                McpProtocol.SSE_DATA_PREFIX
                                        + envelope.renderProgress(token, event)
                                        + McpProtocol.SSE_EVENT_END);
                    }

                    @Override
                    public void heartbeat() throws IOException {
                        write(routing, output, McpProtocol.SSE_HEARTBEAT);
                    }

                    @Override
                    public void complete(McpDispatchOutcome outcome) throws IOException {
                        write(
                                routing,
                                output,
                                McpProtocol.SSE_DATA_PREFIX
                                        + envelope.renderFinal(stream.id(), outcome)
                                        + McpProtocol.SSE_EVENT_END);
                    }

                    @Override
                    public void close() {
                        closed.countDown();
                    }
                });
                if (!closed.await(streamWaitMillis, TimeUnit.MILLISECONDS)) {
                    call.cancel();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                call.cancel();
            } catch (RuntimeException | Error failure) {
                call.cancel();
                throw failure;
            }
        };
    }

    private static void write(RoutingContext routing, OutputStream output, String frame) throws IOException {
        if (routing.response().closed()) {
            throw new IOException("The client closed the MCP event stream");
        }
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
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

    private static Response json(int status, JsonNode body) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}
