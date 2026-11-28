package io.github.jdubois.bootui.quarkus4.mcp;

import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import io.github.jdubois.bootui.engine.mcp.McpToolClientExceptions;
import jakarta.ws.rs.WebApplicationException;
import java.util.function.Function;

/**
 * Translates a JAX-RS client error raised by an MCP tool handler into the framework-neutral {@link
 * McpToolClientException} the engine dispatcher understands. This is the Quarkus counterpart of the
 * Spring adapter's translation, so a tool that refuses a request behaves identically on all three
 * stacks.
 *
 * <p>MCP tools delegate to the same resource methods the REST API uses, and those methods report a
 * client error by throwing {@link WebApplicationException} (typically {@code NotFoundException}) — which
 * RESTEasy maps to the right HTTP status over REST, but which is meaningless to the framework-free
 * dispatcher. Left untranslated it reaches the dispatcher's catch-all and an ordinary bad request is
 * reported to the agent as the detail-free JSON-RPC internal error and logged as a server fault.
 *
 * <p>Only 4xx failures are translated: a 5xx {@link WebApplicationException} is a genuine server fault
 * and keeps its original throwable so it is logged and reported as an internal error, as does every
 * other exception.
 */
public final class QuarkusMcpToolFailures {

    private QuarkusMcpToolFailures() {}

    /**
     * Wraps an MCP tool handler so a 4xx {@link WebApplicationException} becomes an in-band tool error
     * carrying the status and reason the handler asked for.
     */
    public static Function<McpArguments, Object> translating(Function<McpArguments, Object> handler) {
        return arguments -> {
            try {
                return handler.apply(arguments);
            } catch (WebApplicationException failure) {
                int status = failure.getResponse().getStatus();
                if (!McpToolClientExceptions.isClientError(status)) {
                    throw failure;
                }
                throw new McpToolClientException(status, failure.getMessage());
            }
        };
    }
}
