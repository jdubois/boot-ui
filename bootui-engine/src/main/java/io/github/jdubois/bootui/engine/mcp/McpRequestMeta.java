package io.github.jdubois.bootui.engine.mcp;

/**
 * The protocol fields of a request's {@code params._meta}, extracted by each adapter's codec without interpretation.
 *
 * <p>Era selection, validation order, and error codes are engine decisions ({@link McpEraResolver}); the codec only
 * reports what it found and whether each value had the JSON type MCP requires.
 *
 * @param protocolVersion whether {@code io.modelcontextprotocol/protocolVersion} was present and a string
 * @param protocolVersionValue the version string when {@link Field#VALID}, otherwise {@code null}
 * @param clientCapabilities whether {@code io.modelcontextprotocol/clientCapabilities} was present and an object
 * @param progressToken whether {@code progressToken} was present and a string or an integer
 * @param progressTokenValue the token when {@link Field#VALID}, otherwise {@code null}
 */
public record McpRequestMeta(
        Field protocolVersion,
        String protocolVersionValue,
        Field clientCapabilities,
        Field progressToken,
        McpProgressToken progressTokenValue) {

    /** A request with no {@code params._meta}, or one whose {@code _meta} is not an object. */
    public static final McpRequestMeta NONE = new McpRequestMeta(Field.ABSENT, null, Field.ABSENT, Field.ABSENT, null);

    public McpRequestMeta {
        protocolVersion = protocolVersion == null ? Field.ABSENT : protocolVersion;
        clientCapabilities = clientCapabilities == null ? Field.ABSENT : clientCapabilities;
        progressToken = progressToken == null ? Field.ABSENT : progressToken;
        if ((protocolVersion == Field.VALID) != (protocolVersionValue != null)) {
            throw new IllegalArgumentException("A valid protocol version needs a value, and only a valid one has one");
        }
        if ((progressToken == Field.VALID) != (progressTokenValue != null)) {
            throw new IllegalArgumentException("A valid progress token needs a value, and only a valid one has one");
        }
    }

    /** Whether a {@code _meta} field was present and had the JSON type MCP requires. */
    public enum Field {
        ABSENT,
        VALID,
        INVALID
    }
}
