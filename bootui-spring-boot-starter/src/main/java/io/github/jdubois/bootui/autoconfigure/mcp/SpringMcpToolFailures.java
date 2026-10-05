package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import io.github.jdubois.bootui.engine.mcp.McpToolClientExceptions;
import java.util.function.Function;
import org.springframework.web.server.ResponseStatusException;

/**
 * Translates a Spring client error raised by an MCP tool handler into the framework-neutral {@link
 * McpToolClientException} the engine dispatcher understands.
 *
 * <p>MCP tools delegate to the same controller-support methods the REST API uses, and those methods
 * report a client error by throwing {@link ResponseStatusException} — which Spring maps to the right
 * HTTP status over REST, but which is meaningless to the framework-free dispatcher. Left untranslated it
 * reaches the dispatcher's catch-all and an ordinary bad request is reported to the agent as the
 * detail-free JSON-RPC internal error and logged as a server fault. Wrapping every registered handler
 * here keeps that decision at the adapter boundary and out of {@code bootui-engine}.
 *
 * <p>Only 4xx failures are translated: a 5xx {@link ResponseStatusException} is a genuine server fault
 * and keeps its original throwable so it is logged and reported as an internal error, as does every
 * other exception. Both Spring stacks throw the same {@code org.springframework.web.server}
 * exception, so Spring MVC and WebFlux share this translation.
 */
public final class SpringMcpToolFailures {

    private SpringMcpToolFailures() {}

    /**
     * Wraps an MCP tool handler so a 4xx {@link ResponseStatusException} becomes an in-band tool error
     * carrying the status and reason the handler asked for.
     */
    public static Function<McpArguments, Object> translating(Function<McpArguments, Object> handler) {
        return arguments -> {
            try {
                return handler.apply(arguments);
            } catch (ResponseStatusException failure) {
                int status = failure.getStatusCode().value();
                if (!McpToolClientExceptions.isClientError(status)) {
                    throw failure;
                }
                throw new McpToolClientException(status, reason(failure));
            }
        };
    }

    private static String reason(ResponseStatusException failure) {
        String reason = failure.getReason();
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        return failure.getBody().getDetail();
    }
}
