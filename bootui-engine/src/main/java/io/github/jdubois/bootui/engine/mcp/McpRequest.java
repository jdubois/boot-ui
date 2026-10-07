package io.github.jdubois.bootui.engine.mcp;

import java.util.HashSet;
import java.util.Set;

/**
 * A neutral, already-parsed view of a JSON-RPC request, built by each adapter's envelope codec and
 * consumed by {@link McpDispatcher}.
 *
 * <p>The codec performs all JSON work (deciding {@code notification} from the presence of an id,
 * extracting {@code params}, and pulling the {@code tools/call} name + raw {@code query}/{@code
 * limit}/{@code id} arguments). The top-level JSON-RPC message id (used to correlate a response with
 * its request) is <em>not</em> carried here: the codec pairs the returned {@link McpDispatchOutcome}
 * with the original message-id node when it renders the response. {@link #rawId} below is a different,
 * tool-specific concept: the {@code arguments.id} of an {@link McpToolSchema#ID} or
 * {@link McpToolSchema#RULE_VIOLATIONS} tool call (e.g. which exception group or advisor rule to fetch).
 *
 * @param jsonrpc the top-level JSON-RPC version string (typically {@code "2.0"})
 * @param method the JSON-RPC method (possibly blank; the dispatcher decides what to do)
 * @param notification {@code true} when the request carried no id (no response is emitted)
 * @param requestedProtocolVersion the client {@code params.protocolVersion} for {@code initialize}
 *     (may be {@code null}/blank → the default is advertised)
 * @param toolName the {@code params.name} for {@code tools/call} (may be {@code null}/blank)
 * @param rawQuery the client {@code arguments.query} as parsed (may be {@code null}/blank/untrimmed)
 * @param rawLimit the client {@code arguments.limit} as parsed (may be {@code null} or out of range)
 * @param rawId the client {@code arguments.id} as parsed (may be {@code null}/blank/untrimmed)
 * @param argumentNames every property present in {@code arguments}
 * @param argumentsError a safe adapter-detected shape/type error, or {@code null}
 * @param rawScanId the completed advisor snapshot identifier, or {@code null}
 * @param rawOffset the zero-based retained detail offset, or {@code null}
 * @param era the protocol era {@link McpEraResolver} chose ({@code null} → {@link McpEra#LEGACY})
 * @param progressToken the request's {@code _meta.progressToken} when it is a string or an integer, or {@code null}
 * @param requestKey this request's id as an {@link McpRequestKey}, or {@code null} for a notification
 * @param cancelledRequestKey for a legacy {@code notifications/cancelled}, the {@link McpRequestKey} of its {@code
 *     params.requestId}, or {@code null}
 */
public record McpRequest(
        String jsonrpc,
        String method,
        boolean notification,
        String requestedProtocolVersion,
        String toolName,
        String rawQuery,
        Integer rawLimit,
        String rawId,
        Set<String> argumentNames,
        String argumentsError,
        String rawScanId,
        Integer rawOffset,
        McpEra era,
        McpProgressToken progressToken,
        String requestKey,
        String cancelledRequestKey) {

    public McpRequest {
        argumentNames = argumentNames == null ? Set.of() : Set.copyOf(argumentNames);
        era = era == null ? McpEra.LEGACY : era;
    }

    /** Legacy-era constructor used by codecs and engine callers that predate era selection. */
    public McpRequest(
            String jsonrpc,
            String method,
            boolean notification,
            String requestedProtocolVersion,
            String toolName,
            String rawQuery,
            Integer rawLimit,
            String rawId,
            Set<String> argumentNames,
            String argumentsError,
            String rawScanId,
            Integer rawOffset) {
        this(
                jsonrpc,
                method,
                notification,
                requestedProtocolVersion,
                toolName,
                rawQuery,
                rawLimit,
                rawId,
                argumentNames,
                argumentsError,
                rawScanId,
                rawOffset,
                McpEra.LEGACY,
                null,
                null,
                null);
    }

    /** A request without the id keys that legacy cancellation uses. */
    public McpRequest(
            String jsonrpc,
            String method,
            boolean notification,
            String requestedProtocolVersion,
            String toolName,
            String rawQuery,
            Integer rawLimit,
            String rawId,
            Set<String> argumentNames,
            String argumentsError,
            String rawScanId,
            Integer rawOffset,
            McpEra era,
            McpProgressToken progressToken) {
        this(
                jsonrpc,
                method,
                notification,
                requestedProtocolVersion,
                toolName,
                rawQuery,
                rawLimit,
                rawId,
                argumentNames,
                argumentsError,
                rawScanId,
                rawOffset,
                era,
                progressToken,
                null,
                null);
    }

    /** Backward-compatible constructor for codecs without advisor detail arguments. */
    public McpRequest(
            String jsonrpc,
            String method,
            boolean notification,
            String requestedProtocolVersion,
            String toolName,
            String rawQuery,
            Integer rawLimit,
            String rawId,
            Set<String> argumentNames,
            String argumentsError) {
        this(
                jsonrpc,
                method,
                notification,
                requestedProtocolVersion,
                toolName,
                rawQuery,
                rawLimit,
                rawId,
                argumentNames,
                argumentsError,
                null,
                null);
    }

    /** Backward-compatible constructor for engine callers that already provide typed values. */
    public McpRequest(
            String jsonrpc,
            String method,
            boolean notification,
            String requestedProtocolVersion,
            String toolName,
            String rawQuery,
            Integer rawLimit,
            String rawId) {
        this(
                jsonrpc,
                method,
                notification,
                requestedProtocolVersion,
                toolName,
                rawQuery,
                rawLimit,
                rawId,
                inferredArgumentNames(rawQuery, rawLimit, rawId),
                null);
    }

    private static Set<String> inferredArgumentNames(String rawQuery, Integer rawLimit, String rawId) {
        Set<String> names = new HashSet<>();
        if (rawQuery != null) {
            names.add("query");
        }
        if (rawLimit != null) {
            names.add("limit");
        }
        if (rawId != null) {
            names.add("id");
        }
        return names;
    }
}
