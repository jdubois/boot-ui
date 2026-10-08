package io.github.jdubois.bootui.engine.cli;

import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;

/**
 * Translates an {@link McpDispatchOutcome} into the HTTP answer the {@code /bootui/api/cli} facade returns.
 *
 * <p>This is the whole of the CLI facade's policy. Every gate — unknown tool, unexpected argument, disabled
 * panel, action on a read-only panel, missing {@code id}, result capping, concurrency, execution timeout —
 * is already applied by {@code McpDispatcher}; all that is left is choosing a status, and doing it once here
 * means the Spring MVC, WebFlux, and Quarkus facades cannot answer differently.
 *
 * <p>MCP reports refused gates in-band ({@code isError:true}, HTTP 200) because that is what an agent
 * expects to read. A CLI is not an agent: a shell needs the failure in the status line so {@code set -e},
 * {@code curl --fail}, and exit codes work. That difference in rendering is the only thing this class
 * introduces, and it changes no policy.
 */
public final class CliOutcomes {

    /** Returned when {@code bootui.cli.enabled} is off. */
    public static final String DISABLED_MESSAGE =
            "BootUI CLI endpoint is disabled. Set bootui.cli.enabled=true to allow command-line access.";

    private CliOutcomes() {}

    /** The answer returned while the CLI endpoint is disabled. */
    public static CliToolResponse disabled() {
        return CliToolResponse.failure(CliStatus.SERVICE_UNAVAILABLE, DISABLED_MESSAGE);
    }

    /** Maps one {@code tools/call} dispatch outcome to its HTTP answer. */
    public static CliToolResponse toResponse(McpDispatchOutcome outcome) {
        if (outcome instanceof McpDispatchOutcome.ToolCallResult result) {
            return CliToolResponse.success(result.payload());
        }
        if (outcome instanceof McpDispatchOutcome.ToolCallError error) {
            return CliToolResponse.failure(statusOf(error), error.message());
        }
        if (outcome instanceof McpDispatchOutcome.ProtocolError error) {
            return CliToolResponse.failure(statusOf(error), error.message());
        }
        // The facade only ever dispatches tools/call, so no other variant is reachable. Answering 500
        // rather than throwing keeps a future protocol addition from turning into a stack trace.
        return CliToolResponse.failure(CliStatus.INTERNAL_SERVER_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE);
    }

    private static CliStatus statusOf(McpDispatchOutcome.ToolCallError error) {
        if (error.status() != null) {
            // The tool ran and refused the request itself — an unknown id, an unsupported argument value.
            // Reporting the status it asked for is the whole point of McpToolClientException; collapsing
            // it into a 500 would tell the shell a server fault happened when the call was simply wrong.
            return CliStatus.forClientError(error.status());
        }
        return switch (error.reason()) {
            case PANEL_DISABLED, PANEL_READ_ONLY -> CliStatus.FORBIDDEN;
            case ACTION_BUSY -> CliStatus.CONFLICT;
            case TOOL_FAILED -> CliStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static boolean isUnknownOrUnavailableTool(String message) {
        return message != null
                && (message.startsWith(McpProtocol.UNKNOWN_TOOL_PREFIX)
                        || message.startsWith(McpProtocol.UNAVAILABLE_TOOL_PREFIX));
    }

    private static CliStatus statusOf(McpDispatchOutcome.ProtocolError error) {
        return switch (error.code()) {
            case McpProtocol.INVALID_PARAMS ->
                // The dispatcher reports an unknown or unavailable tool with the same code as a malformed
                // argument. For a CLI they are different failures: one is "no such command here", the other "you
                // called it wrong", so the shared message constants separate them. An unavailable tool stays a 404,
                // which is what every published CLI reads as "this application does not expose it".
                isUnknownOrUnavailableTool(error.message()) ? CliStatus.NOT_FOUND : CliStatus.BAD_REQUEST;
            case McpProtocol.SERVER_AT_CAPACITY -> CliStatus.TOO_MANY_REQUESTS;
            case McpProtocol.TOOL_TIMEOUT -> CliStatus.GATEWAY_TIMEOUT;
            case McpProtocol.SERVER_DISABLED -> CliStatus.SERVICE_UNAVAILABLE;
            default -> CliStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
