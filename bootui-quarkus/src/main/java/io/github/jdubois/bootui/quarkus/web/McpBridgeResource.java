package io.github.jdubois.bootui.quarkus.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpTool;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Loopback HTTP transport for the BootUI MCP server on Quarkus (MCP "Streamable HTTP" style) — the
 * Quarkus analogue of the Spring adapter's {@code BootUiMcpController}.
 *
 * <p>Clients POST JSON-RPC 2.0 requests to {@code /bootui/api/mcp} and receive JSON-RPC responses.
 * The endpoint lives under {@code /bootui/api} so it inherits {@code BootUiQuarkusSafetyFilter}'s
 * loopback, Host allow-list, and cross-site write defenses (the shared {@code LocalhostGuard} write
 * floor). The beans are always registered while BootUI is active (dev/test launch modes), but requests
 * are only served while the server is enabled — the live state is initialized from
 * {@code bootui.mcp.enabled} and can be toggled at runtime from the MCP Server panel via
 * {@link McpServerState}.
 *
 * <p>{@code POST} is {@code @Blocking}: a {@code tools/call} can run an advisor scan (e.g.
 * {@code memory_scan} forces a full GC for the class histogram), which must not run on the Vert.x
 * event loop. The {@code GET} variant returns a small human-readable status document so the server can
 * be sanity-checked with a browser or {@code curl} on the loopback interface.
 */
@ApplicationScoped
@Path("/bootui/api/mcp")
public class McpBridgeResource {

    private final McpServerState state;
    private final QuarkusMcpEnvelope envelope;
    private final McpDispatcher dispatcher;

    @Inject
    public McpBridgeResource(McpServerState state, QuarkusMcpEnvelope envelope, McpDispatcher dispatcher) {
        this.state = state;
        this.envelope = envelope;
        this.dispatcher = dispatcher;
    }

    @POST
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rpc(JsonNode request) {
        if (!state.isEnabled()) {
            return Response.ok(envelope.disabledError(request)).build();
        }
        if (request != null && request.isArray()) {
            ArrayNode responses = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : request) {
                JsonNode response = envelope.handle(element);
                if (response != null) {
                    responses.add(response);
                }
            }
            if (responses.isEmpty()) {
                return Response.accepted().build();
            }
            return Response.ok(responses).build();
        }

        JsonNode response = envelope.handle(request);
        if (response == null) {
            // Notification (no id) — acknowledge with 202 and no body.
            return Response.accepted().build();
        }
        return Response.ok(response).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public JsonNode status() {
        ObjectNode status = JsonNodeFactory.instance.objectNode();
        status.put("server", McpProtocol.SERVER_NAME);
        status.put("enabled", state.isEnabled());
        status.put("transport", "http");
        status.put("endpoint", "/bootui/api/mcp");
        status.put("protocolVersion", McpProtocol.DEFAULT_PROTOCOL_VERSION);
        status.put("toolCount", dispatcher.tools().size());
        ArrayNode toolNames = JsonNodeFactory.instance.arrayNode();
        for (McpTool tool : dispatcher.tools()) {
            toolNames.add(tool.name());
        }
        status.set("tools", toolNames);
        return status;
    }
}
