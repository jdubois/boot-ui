package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Rejected;
import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Serve;
import java.util.List;
import java.util.Objects;

/**
 * The request flow of one {@code POST} to the MCP endpoint, shared by every adapter: refuse a batch, select and validate
 * the protocol era, short-circuit while the server is disabled, validate the JSON-RPC envelope, and otherwise dispatch.
 *
 * <p>The adapter extracts the {@link Envelope} with its own JSON library, renders the returned {@link Plan}, and never
 * re-decides it. What stays adapter-side is only what needs the JSON library: parsing the arguments into an {@link
 * McpRequest}, rendering the outcome, and measuring the rendered bytes, which {@link #checkResponseSize} judges.
 * The HTTP status of a dispatched outcome is {@link McpProtocol#httpStatus(McpEra, McpDispatchOutcome)}.
 */
public final class McpExchange {

    private McpExchange() {}

    /** The shape of the request {@code id}, which decides both validity and what a refusal echoes. */
    public enum IdShape {
        /** No {@code id} member. */
        ABSENT,
        /** JSON {@code null}. */
        NULL,
        /** A string. */
        STRING,
        /** An integer. */
        INTEGER,
        /** A number with a fraction or an exponent, such as {@code 1.5} or {@code 1.0}. */
        FRACTIONAL,
        /** Any other JSON type. */
        INVALID;

        /** BootUI 1.x and MCP 2025-06-18 read an absent or {@code null} id the same way: a notification. */
        boolean legacyNotification() {
            return this == ABSENT || this == NULL;
        }

        /** MCP 2026-07-28 types a request id as a string or an integer. */
        boolean modernRequestId() {
            return this == STRING || this == INTEGER;
        }
    }

    /** Which request id a refusal echoes. */
    public enum IdEcho {
        /** {@code "id": null}. */
        NULL,
        /** The id exactly as sent, whatever its type (BootUI 1.x behaviour for a bad {@code jsonrpc} or {@code params}). */
        AS_SENT,
        /** The id when it is a string or a number, otherwise {@code null}. */
        READABLE
    }

    /**
     * The neutral fields of one parsed request body.
     *
     * @param batch {@code true} when the body is a JSON array
     * @param object {@code true} when the body is a JSON object
     * @param jsonrpcValid {@code true} when {@code jsonrpc} is the string {@code "2.0"}
     * @param id the shape of {@code id}
     * @param paramsValid {@code true} when {@code params} is absent or an object
     * @param method the JSON-RPC method when it is a string, otherwise {@code null}
     * @param bodyName {@code params.name} when it is a string, otherwise {@code null}
     * @param meta the request's {@code params._meta} protocol fields
     */
    public record Envelope(
            boolean batch,
            boolean object,
            boolean jsonrpcValid,
            IdShape id,
            boolean paramsValid,
            String method,
            String bodyName,
            McpRequestMeta meta) {

        public Envelope {
            Objects.requireNonNull(id, "id");
            meta = meta == null ? McpRequestMeta.NONE : meta;
        }

        /** A body that is neither an object nor an array (a JSON scalar, or nothing). */
        public static Envelope notAnObject(boolean batch) {
            return new Envelope(batch, false, false, IdShape.ABSENT, true, null, null, McpRequestMeta.NONE);
        }

        /** {@code true} for a well-formed request without an id: it gets no response. */
        public boolean notification() {
            return object && jsonrpcValid && id.legacyNotification() && method != null && !method.isBlank();
        }
    }

    /**
     * What to do with one request body.
     *
     * @param envelope the body's neutral fields
     * @param headers every value of the MCP request headers
     * @param enabled whether the MCP server currently serves requests
     */
    public static Plan plan(Envelope envelope, McpRequestHeaders headers, boolean enabled) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(headers, "headers");
        if (envelope.batch()) {
            return Plan.Reject.of(
                    McpEra.LEGACY,
                    400,
                    McpProtocol.INVALID_REQUEST,
                    McpProtocol.BATCH_NOT_SUPPORTED_MESSAGE,
                    IdEcho.NULL);
        }
        Plan.Reject badModernId = checkModernId(envelope, headers);
        if (badModernId != null) {
            return badModernId;
        }
        McpEraDecision decision = envelope.object()
                ? McpEraResolver.resolve(
                        envelope.method(), envelope.notification(), envelope.bodyName(), envelope.meta(), headers)
                : McpEraResolver.resolve(null, false, null, McpRequestMeta.NONE, headers);
        if (decision instanceof Rejected rejected) {
            return new Plan.Reject(
                    rejected.era(),
                    rejected.httpStatus(),
                    McpProtocol.wireErrorCode(rejected.era(), rejected.code()),
                    rejected.message(),
                    rejected.supportedVersions(),
                    rejected.requestedVersion(),
                    rejected.era() == McpEra.MODERN ? IdEcho.READABLE : IdEcho.NULL);
        }
        Serve serve = (Serve) decision;
        if (!enabled) {
            return envelope.notification() ? new Plan.Accept() : new Plan.Disabled(serve.era());
        }
        Plan.Reject invalid = checkEnvelope(envelope, serve.era());
        return invalid != null ? invalid : new Plan.Dispatch(serve);
    }

    /**
     * The refusal of a request that is not a BootUI 1.x request (its {@code _meta} names a protocol version other than
     * MCP 2025-06-18, or one that is not a string, or its only {@code MCP-Protocol-Version} header is MCP 2026-07-28) but
     * whose id MCP 2026-07-28 does not allow, or {@code null}. A modern request id is a string or an integer: {@code
     * null}, fractional, and other ids are refused, and a request method sent without an id is refused rather than run
     * as a notification, so a modern {@code tools/call} never runs without an answer. Only a {@code notifications/}
     * method may omit its id. BootUI 1.x clients send none of these markers, so they answer exactly as before.
     */
    static Plan.Reject checkModernId(Envelope envelope, McpRequestHeaders headers) {
        if (!envelope.object()
                || !claimsModern(envelope, headers)
                || "initialize".equals(envelope.method())
                || envelope.id().modernRequestId()) {
            return null;
        }
        if (envelope.id() == IdShape.ABSENT
                && envelope.method() != null
                && envelope.method().startsWith(McpProtocol.NOTIFICATION_METHOD_PREFIX)) {
            return null;
        }
        String message = envelope.id() == IdShape.ABSENT
                ? McpProtocol.MODERN_ID_REQUIRED_MESSAGE
                : McpProtocol.MODERN_ID_TYPE_MESSAGE;
        return Plan.Reject.of(McpEra.MODERN, 400, McpProtocol.INVALID_REQUEST, message, IdEcho.NULL);
    }

    /** Whether a request claims a protocol era BootUI 1.x never saw, so its id is judged by MCP 2026-07-28's rules. */
    private static boolean claimsModern(Envelope envelope, McpRequestHeaders headers) {
        McpRequestMeta.Field version = envelope.meta().protocolVersion();
        if (version == McpRequestMeta.Field.INVALID) {
            return true;
        }
        if (version == McpRequestMeta.Field.VALID
                && !McpProtocol.KNOWN_VERSIONS.contains(envelope.meta().protocolVersionValue())) {
            return true;
        }
        List<String> header = headers.protocolVersion();
        return header.size() == 1 && McpProtocol.MODERN_VERSIONS.contains(header.get(0));
    }

    /**
     * The JSON-RPC envelope refusal for {@code envelope}, or {@code null} when it is well formed. These answer {@code
     * 200}, as BootUI 1.x did.
     */
    public static Plan.Reject checkEnvelope(Envelope envelope, McpEra era) {
        if (!envelope.object()) {
            return Plan.Reject.of(
                    era, 200, McpProtocol.INVALID_REQUEST, McpProtocol.MALFORMED_REQUEST_MESSAGE, IdEcho.NULL);
        }
        if (!envelope.jsonrpcValid()) {
            return Plan.Reject.of(
                    era, 200, McpProtocol.INVALID_REQUEST, McpProtocol.MISSING_JSONRPC_MESSAGE, IdEcho.AS_SENT);
        }
        if (envelope.id() == IdShape.INVALID) {
            return Plan.Reject.of(era, 200, McpProtocol.INVALID_REQUEST, McpProtocol.INVALID_ID_MESSAGE, IdEcho.NULL);
        }
        if (!envelope.paramsValid()) {
            return Plan.Reject.of(
                    era, 200, McpProtocol.INVALID_PARAMS, McpProtocol.PARAMS_OBJECT_MESSAGE, IdEcho.AS_SENT);
        }
        return null;
    }

    /**
     * The refusal of a rendered response of {@code renderedBytes} bytes in {@code era}, or {@code null} when it fits in
     * {@code maxResponseBytes}. The JSON response and the final event of a stream obey the same rule: the response is
     * replaced, with HTTP {@code 200} on the JSON path, by {@link McpProtocol#RESPONSE_TOO_LARGE} echoing the request id.
     */
    public static Plan.Reject checkResponseSize(McpEra era, long renderedBytes, int maxResponseBytes) {
        if (canAnswer(renderedBytes, maxResponseBytes)) {
            return null;
        }
        return responseTooLarge(era);
    }

    /** The canonical fallback, echoing the original response id without truncation or coercion. */
    public static Plan.Reject responseTooLarge(McpEra era) {
        return Plan.Reject.of(
                era,
                200,
                McpProtocol.wireErrorCode(era, McpProtocol.RESPONSE_TOO_LARGE),
                McpProtocol.RESPONSE_TOO_LARGE_MESSAGE,
                IdEcho.AS_SENT);
    }

    /** Whether the adapter's measured fallback fits, required before starting a response-bearing dispatch. */
    public static boolean canAnswer(long fallbackBytes, int maxResponseBytes) {
        return fallbackBytes <= Math.max(1, maxResponseBytes);
    }

    public enum ResponseBudget {
        FITS,
        REPLACE,
        /** Refuse at the HTTP transport with an empty body, never a forged JSON-RPC id or notification. */
        REFUSE
    }

    /** Both the original JSON payload and its fallback obey the same UTF-8 byte budget (excluding SSE framing). */
    public static ResponseBudget responseBudget(long renderedBytes, long fallbackBytes, int maxResponseBytes) {
        if (canAnswer(renderedBytes, maxResponseBytes)) {
            return ResponseBudget.FITS;
        }
        return canAnswer(fallbackBytes, maxResponseBytes) ? ResponseBudget.REPLACE : ResponseBudget.REFUSE;
    }

    /** BootUI's implementation resource refusal, not a JSON-RPC protocol error. */
    public static final int RESPONSE_BUDGET_REFUSAL_STATUS = 413;

    /**
     * Whether a rendered {@code notifications/progress} of {@code renderedBytes} bytes may be sent: every event of a
     * stream obeys {@code bootui.mcp.max-response-bytes} too. One that does not fit is dropped rather than replaced,
     * because progress is advisory and the final response still ends the stream.
     */
    public static boolean progressFits(long renderedBytes, int maxResponseBytes) {
        return canAnswer(renderedBytes, maxResponseBytes);
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
         * @param idEcho which request id the error echoes
         */
        record Reject(
                McpEra era,
                int httpStatus,
                int code,
                String message,
                List<String> supportedVersions,
                String requestedVersion,
                IdEcho idEcho)
                implements Plan {

            public Reject {
                supportedVersions = supportedVersions == null ? List.of() : List.copyOf(supportedVersions);
                Objects.requireNonNull(idEcho, "idEcho");
            }

            static Reject of(McpEra era, int httpStatus, int code, String message, IdEcho idEcho) {
                return new Reject(era, httpStatus, code, message, List.of(), null, idEcho);
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

        /** Parse the request in {@code serve}'s era and dispatch it; a notification answers {@code 202}. */
        record Dispatch(Serve serve) implements Plan {}
    }
}
