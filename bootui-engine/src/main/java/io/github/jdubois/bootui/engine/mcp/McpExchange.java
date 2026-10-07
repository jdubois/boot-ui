package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Rejected;
import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Serve;
import java.util.List;
import java.util.Objects;

/**
 * The transport-level request flow of one {@code POST} to the MCP endpoint, shared by every adapter: refuse a batch,
 * select and validate the protocol era, short-circuit while the server is disabled, and otherwise dispatch. The
 * adapter extracts the envelope fields with its own JSON library, renders the {@link Plan}, and never re-decides it.
 */
public final class McpExchange {

    private McpExchange() {}

    /**
     * What to do with one request body.
     *
     * @param batch {@code true} when the body is a JSON array
     * @param method the JSON-RPC method when it is a string, otherwise {@code null}
     * @param notification {@code true} for a well-formed request without an id
     * @param bodyName {@code params.name} when it is a string, otherwise {@code null}
     * @param meta the request's {@code params._meta} protocol fields
     * @param headers every value of the MCP request headers
     * @param enabled whether the MCP server currently serves requests
     */
    public static Plan plan(
            boolean batch,
            String method,
            boolean notification,
            String bodyName,
            McpRequestMeta meta,
            McpRequestHeaders headers,
            boolean enabled) {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(headers, "headers");
        if (batch) {
            return new Plan.Reject(
                    McpEra.LEGACY,
                    400,
                    McpProtocol.INVALID_REQUEST,
                    McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE,
                    List.of(),
                    null,
                    false);
        }
        McpEraDecision decision = McpEraResolver.resolve(method, notification, bodyName, meta, headers);
        if (decision instanceof Rejected rejected) {
            return new Plan.Reject(
                    rejected.era(),
                    rejected.httpStatus(),
                    McpProtocol.wireErrorCode(rejected.era(), rejected.code()),
                    rejected.message(),
                    rejected.supportedVersions(),
                    rejected.requestedVersion(),
                    rejected.era() == McpEra.MODERN);
        }
        Serve serve = (Serve) decision;
        if (!enabled) {
            return notification ? new Plan.Accept() : new Plan.Disabled(serve.era());
        }
        return new Plan.Dispatch(serve);
    }

    /** The decision {@link #plan} makes for one request body. */
    public sealed interface Plan permits Plan.Reject, Plan.Accept, Plan.Disabled, Plan.Dispatch {

        /**
         * Answer with a JSON-RPC error and no dispatch.
         *
         * @param era the era whose wire shape the error uses
         * @param httpStatus the HTTP status
         * @param code the wire JSON-RPC error code, already moved for {@code era}
         * @param message the canonical, static message
         * @param supportedVersions the {@code data.supported} list, or empty when the error has no data
         * @param requestedVersion the {@code data.requested} value, or {@code null}
         * @param echoId {@code true} to echo a readable (string or number) request id; a legacy refusal keeps BootUI
         *     1.x's {@code null} id
         */
        record Reject(
                McpEra era,
                int httpStatus,
                int code,
                String message,
                List<String> supportedVersions,
                String requestedVersion,
                boolean echoId)
                implements Plan {

            public Reject {
                supportedVersions = supportedVersions == null ? List.of() : List.copyOf(supportedVersions);
            }

            /** {@code true} when the error carries {@code UnsupportedProtocolVersionError} data. */
            public boolean hasVersionData() {
                return code == McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_ERROR;
            }
        }

        /** Answer {@code 202 Accepted} with no body: a notification while the server is disabled. */
        record Accept() implements Plan {}

        /**
         * Answer {@code 200} with the disabled-server error ({@link McpProtocol#SERVER_DISABLED}, moved for {@code
         * era}), echoing the request id as sent.
         */
        record Disabled(McpEra era) implements Plan {

            /** The wire code of the disabled-server error. */
            public int code() {
                return McpProtocol.wireErrorCode(era, McpProtocol.SERVER_DISABLED);
            }
        }

        /** Parse the request in {@code serve}'s era and dispatch it. */
        record Dispatch(Serve serve) implements Plan {}
    }
}
