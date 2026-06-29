package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.McpServerStatus;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.config.Config;

/**
 * Read-only MCP Server panel endpoint on Quarkus ({@code GET /bootui/api/mcp-server}).
 *
 * <p>Honest-partial: the live JSON-RPC MCP bridge ({@code /bootui/api/mcp}) and its tool catalog are
 * coupled to the Spring controller/Jackson registry, so they are <strong>not</strong> served on
 * Quarkus. This endpoint reports the configured mode and an empty tool catalog with a {@code none}
 * transport so the shared panel renders an accurate "bridge not available" state. There is no toggle.
 */
@Path("/bootui/api/mcp-server")
public class McpServerResource {

    private final String configuredMode;

    public McpServerResource(Config config) {
        this.configuredMode = config.getOptionalValue("bootui.mcp.enabled", String.class)
                .orElse("OFF")
                .trim()
                .toUpperCase();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public McpServerStatus status() {
        String version = getClass().getPackage().getImplementationVersion();
        return new McpServerStatus(
                false,
                configuredMode,
                false,
                "bootui",
                version == null ? "dev" : version,
                "none",
                "",
                "2025-06-18",
                200,
                0,
                List.of());
    }
}
