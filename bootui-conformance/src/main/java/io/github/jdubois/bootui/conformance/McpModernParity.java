package io.github.jdubois.bootui.conformance;

/**
 * The cross-codec parity contract for MCP 2026-07-28 results: the Spring (Jackson 3) and Quarkus (Jackson 2) codecs
 * must render the modern envelope of {@code server/discover}, {@code tools/list}, and {@code prompts/list} to the same
 * bytes. Each codec test renders with server version {@link #SERVER_VERSION}, removes the stack-specific payload
 * ({@code instructions}, {@code tools}, {@code prompts}), and compares the compact JSON with these constants.
 */
public final class McpModernParity {

    /** The server version both codec tests construct their dispatcher with. */
    public static final String SERVER_VERSION = "1.2.3";

    /** The {@code _meta} of every modern result. */
    public static final String RESULT_META =
            "{\"io.modelcontextprotocol/serverInfo\":{\"name\":\"bootui\",\"version\":\"" + SERVER_VERSION + "\"}}";

    /** {@code server/discover}'s result without {@code instructions}. */
    public static final String DISCOVER_WITHOUT_INSTRUCTIONS = "{\"resultType\":\"complete\","
            + "\"supportedVersions\":[\"2026-07-28\",\"2025-06-18\"],"
            + "\"capabilities\":{\"tools\":{\"listChanged\":false},\"prompts\":{\"listChanged\":false}},"
            + "\"_meta\":" + RESULT_META + ",\"ttlMs\":60000,\"cacheScope\":\"private\"}";

    /** A modern {@code tools/list} or {@code prompts/list} result without its {@code tools} or {@code prompts}. */
    public static final String LIST_ENVELOPE =
            "{\"resultType\":\"complete\",\"_meta\":" + RESULT_META + ",\"ttlMs\":60000,\"cacheScope\":\"private\"}";

    private McpModernParity() {}
}
