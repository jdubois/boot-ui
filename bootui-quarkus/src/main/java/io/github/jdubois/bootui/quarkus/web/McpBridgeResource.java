package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader;
import io.github.jdubois.bootui.engine.mcp.McpPayloadReader.PayloadTooLargeException;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.quarkus.mcp.BootUiMcpProducer;
import io.github.jdubois.bootui.quarkus.mcp.McpServerState;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpEnvelope;
import io.smallrye.common.annotation.Blocking;
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
import java.io.InputStream;
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

    private final McpServerState state;
    private final QuarkusMcpEnvelope envelope;
    private final int maxPayloadBytes;

    @Inject
    public McpBridgeResource(McpServerState state, QuarkusMcpEnvelope envelope, Config config) {
        this.state = state;
        this.envelope = envelope;
        this.maxPayloadBytes = BootUiMcpProducer.maxPayloadBytes(config);
    }

    @POST
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rpc(InputStream requestBody, @Context HttpHeaders headers) {
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
        QuarkusMcpEnvelope.Reply reply = envelope.exchange(request, headers(headers), state.isEnabled());
        if (reply.body() == null) {
            return Response.accepted().build();
        }
        return json(reply.status(), reply.body());
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
