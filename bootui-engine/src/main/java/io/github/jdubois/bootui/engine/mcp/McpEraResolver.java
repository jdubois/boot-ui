package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Rejected;
import io.github.jdubois.bootui.engine.mcp.McpEraDecision.Serve;
import io.github.jdubois.bootui.engine.mcp.McpRequestMeta.Field;
import java.util.List;
import java.util.Objects;

/**
 * Chooses the MCP era of a {@code POST} to the MCP endpoint and validates the modern request metadata.
 *
 * <p>A request whose {@code _meta} names no protocol version, and every {@code initialize}, is legacy and keeps BootUI's
 * MCP 2025-06-18 behaviour byte for byte: an absent {@code MCP-Protocol-Version} header or a known legacy revision is
 * served, anything else is a {@code 400} with {@code -32600}. A request whose {@code _meta} names a version is checked in
 * the order MCP 2026-07-28 implies:
 *
 * <ol>
 *   <li>the version is a string, else {@code -32602};
 *   <li>{@code MCP-Protocol-Version} is sent once and equals it, else {@code -32020};
 *   <li>a known legacy revision is then served as legacy;
 *   <li>an unsupported revision is {@code -32022} with the supported list;
 *   <li>client capabilities are an object, else {@code -32602};
 *   <li>{@code Mcp-Method} is sent once and equals the method, else {@code -32020};
 *   <li>for {@code tools/call} and {@code prompts/get}, {@code Mcp-Name} is sent once and, after Base64-sentinel
 *       decoding, equals {@code params.name}, else {@code -32020};
 *   <li>a progress token is a string or an integer, else {@code -32602}.
 * </ol>
 *
 * <p>Every rejection here is HTTP {@code 400}. A notification is never refused for missing modern headers, because
 * MCP 2026-07-28 defines no header requirements for notification posts.
 */
public final class McpEraResolver {

    private McpEraResolver() {}

    /**
     * @param method the JSON-RPC method, or {@code null} when absent or not a string
     * @param notification {@code true} for a well-formed request without an id
     * @param bodyName {@code params.name} when it is a string, otherwise {@code null}
     * @param meta the request's {@code params._meta} protocol fields
     * @param headers every value of the MCP request headers
     */
    public static McpEraDecision resolve(
            String method, boolean notification, String bodyName, McpRequestMeta meta, McpRequestHeaders headers) {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(headers, "headers");
        List<String> versionHeader = headers.protocolVersion();
        if (notification) {
            return versionHeader.isEmpty()
                            || (versionHeader.size() == 1
                                    && McpProtocol.SUPPORTED_VERSIONS.contains(versionHeader.get(0)))
                    ? Serve.LEGACY
                    : legacyUnsupportedHeader();
        }
        if ("initialize".equals(method)) {
            return legacy(versionHeader, false);
        }
        if (meta.protocolVersion() == Field.ABSENT) {
            return withLegacyToken(legacy(versionHeader, true), meta);
        }
        if (meta.protocolVersion() == Field.INVALID) {
            return modern(McpProtocol.INVALID_PARAMS, McpProtocol.META_PROTOCOL_VERSION_TYPE_MESSAGE);
        }
        String version = meta.protocolVersionValue();
        if (versionHeader.size() != 1 || !version.equals(versionHeader.get(0))) {
            return modern(McpProtocol.HEADER_MISMATCH, McpProtocol.PROTOCOL_VERSION_HEADER_MISMATCH_MESSAGE);
        }
        if (McpProtocol.KNOWN_VERSIONS.contains(version)) {
            return withLegacyToken(new Serve(McpEra.LEGACY, version, null), meta);
        }
        if (!McpProtocol.MODERN_VERSIONS.contains(version)) {
            return new Rejected(
                    McpEra.MODERN,
                    400,
                    McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_ERROR,
                    McpProtocol.UNSUPPORTED_MODERN_PROTOCOL_VERSION_MESSAGE,
                    McpProtocol.SUPPORTED_VERSIONS,
                    version);
        }
        if (meta.clientCapabilities() == Field.ABSENT) {
            return modern(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_META_CLIENT_CAPABILITIES_MESSAGE);
        }
        if (meta.clientCapabilities() == Field.INVALID) {
            return modern(McpProtocol.INVALID_PARAMS, McpProtocol.META_CLIENT_CAPABILITIES_TYPE_MESSAGE);
        }
        List<String> methodHeader = headers.method();
        if (methodHeader.size() != 1 || method == null || !method.equals(methodHeader.get(0))) {
            return modern(McpProtocol.HEADER_MISMATCH, McpProtocol.METHOD_HEADER_MISMATCH_MESSAGE);
        }
        if (("tools/call".equals(method) || "prompts/get".equals(method)) && !nameMatches(bodyName, headers.name())) {
            return modern(McpProtocol.HEADER_MISMATCH, McpProtocol.NAME_HEADER_MISMATCH_MESSAGE);
        }
        if (meta.progressToken() == Field.INVALID) {
            return modern(McpProtocol.INVALID_PARAMS, McpProtocol.PROGRESS_TOKEN_TYPE_MESSAGE);
        }
        return new Serve(McpEra.MODERN, version, meta.progressTokenValue());
    }

    private static boolean nameMatches(String bodyName, List<String> nameHeader) {
        if (nameHeader.isEmpty()) {
            // Without a body name the dispatcher reports the missing name; there is nothing for a header to mirror.
            return bodyName == null || bodyName.isEmpty();
        }
        if (nameHeader.size() != 1) {
            return false;
        }
        String decoded = McpHeaderValues.decode(nameHeader.get(0));
        return decoded != null && decoded.equals(bodyName);
    }

    /**
     * MCP 2025-06-18 also lets a request ask for progress with {@code _meta.progressToken}. A valid token is kept so the
     * call may stream progress; an invalid one is ignored, because a legacy request is never refused or changed for it.
     */
    private static McpEraDecision withLegacyToken(McpEraDecision decision, McpRequestMeta meta) {
        if (decision instanceof Serve serve && serve.era() == McpEra.LEGACY && meta.progressToken() == Field.VALID) {
            return new Serve(McpEra.LEGACY, serve.protocolVersion(), meta.progressTokenValue());
        }
        return decision;
    }

    private static McpEraDecision legacy(List<String> versionHeader, boolean modernHeaderNeedsMeta) {
        if (versionHeader.isEmpty()) {
            return Serve.LEGACY;
        }
        if (versionHeader.size() == 1) {
            String version = versionHeader.get(0);
            if (McpProtocol.KNOWN_VERSIONS.contains(version)) {
                return new Serve(McpEra.LEGACY, version, null);
            }
            if (modernHeaderNeedsMeta && McpProtocol.MODERN_VERSIONS.contains(version)) {
                return modern(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_META_PROTOCOL_VERSION_MESSAGE);
            }
        }
        return legacyUnsupportedHeader();
    }

    private static Rejected legacyUnsupportedHeader() {
        return new Rejected(
                McpEra.LEGACY, McpProtocol.INVALID_REQUEST, McpProtocol.UNSUPPORTED_PROTOCOL_VERSION_MESSAGE);
    }

    private static Rejected modern(int code, String message) {
        return new Rejected(McpEra.MODERN, code, message);
    }
}
