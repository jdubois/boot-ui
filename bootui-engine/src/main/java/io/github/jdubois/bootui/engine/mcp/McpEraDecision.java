package io.github.jdubois.bootui.engine.mcp;

import java.util.List;

/** What {@link McpEraResolver} decided for one request: the era to serve it in, or why it is refused. */
public sealed interface McpEraDecision permits McpEraDecision.Serve, McpEraDecision.Rejected {

    /** The era the response is rendered for, including a rejection's. */
    McpEra era();

    /**
     * Serve the request in {@code era}.
     *
     * @param era the era to dispatch and render in
     * @param protocolVersion the request's protocol revision, or {@code null} for a legacy request that did not name one
     * @param progressToken the modern request's progress token, or {@code null}
     */
    record Serve(McpEra era, String protocolVersion, McpProgressToken progressToken) implements McpEraDecision {

        static final Serve LEGACY = new Serve(McpEra.LEGACY, null, null);
    }

    /**
     * Refuse the request before dispatch.
     *
     * @param era the era whose wire shape the error uses
     * @param httpStatus the HTTP status of the error response
     * @param code the JSON-RPC error code
     * @param message the canonical, static error message (never echoes header or body values)
     * @param supportedVersions the supported revisions for {@link McpProtocol#UNSUPPORTED_PROTOCOL_VERSION_ERROR},
     *     otherwise empty
     * @param requestedVersion the requested revision for {@link McpProtocol#UNSUPPORTED_PROTOCOL_VERSION_ERROR},
     *     otherwise {@code null}
     */
    record Rejected(
            McpEra era,
            int httpStatus,
            int code,
            String message,
            List<String> supportedVersions,
            String requestedVersion)
            implements McpEraDecision {

        public Rejected {
            supportedVersions = supportedVersions == null ? List.of() : List.copyOf(supportedVersions);
        }

        Rejected(McpEra era, int code, String message) {
            this(era, 400, code, message, List.of(), null);
        }

        /** {@code true} when the error carries the {@code UnsupportedProtocolVersionError} data. */
        public boolean hasVersionData() {
            return code == McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_ERROR;
        }
    }
}
