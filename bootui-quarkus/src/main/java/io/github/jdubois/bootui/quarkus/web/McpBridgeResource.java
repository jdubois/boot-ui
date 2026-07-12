package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.quarkus.mcp.BootUiMcpProducer;
import io.github.jdubois.bootui.quarkus.mcp.McpServerState;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpEnvelope;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;

/**
 * Loopback HTTP transport for the BootUI MCP server on Quarkus (MCP "Streamable HTTP" style) — the
 * Quarkus analogue of the Spring adapter's {@code BootUiMcpController}.
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
    public Response rpc(byte[] requestBody, @HeaderParam(McpProtocol.PROTOCOL_VERSION_HEADER) String protocolVersion) {
        if (protocolVersion != null && !McpProtocol.KNOWN_VERSIONS.contains(protocolVersion)) {
            return json(
                    400, error(null, McpProtocol.INVALID_REQUEST, McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_MESSAGE));
        }
        if (requestBody != null && requestBody.length > maxPayloadBytes) {
            return json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE));
        }
        JsonNode request;
        try {
            request = envelope.readTree(requestBody == null ? new byte[0] : requestBody);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        if (request != null && request.isArray()) {
            return json(400, error(null, McpProtocol.INVALID_REQUEST, McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE));
        }
        if (!state.isEnabled()) {
            if (isNotification(request)) {
                return Response.accepted().build();
            }
            return json(200, envelope.disabledError(request));
        }
        JsonNode response = envelope.handle(request);
        if (response == null) {
            return Response.accepted().build();
        }
        return json(200, response);
    }

    @GET
    public Response getStream() {
        return Response.status(405).build();
    }

    private static boolean isNotification(JsonNode request) {
        return request != null
                && request.isObject()
                && !request.hasNonNull("id")
                && McpProtocol.JSONRPC_VERSION.equals(request.path("jsonrpc").asText())
                && !request.path("method").asText().isBlank();
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
