package io.github.jdubois.bootui.engine.mcp;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Decodes MCP 2026-07-28 mirrored header values ({@code Mcp-Name}, {@code Mcp-Param-*}).
 *
 * <p>A plain value must be visible ASCII, spaces, or tabs, with no leading or trailing whitespace. A value that cannot be
 * sent that way travels as {@code =?base64?<Base64 of its UTF-8 bytes>?=}; the markers are case-sensitive, and the
 * server must decode before comparing with the body.
 */
public final class McpHeaderValues {

    private static final String PREFIX = "=?base64?";
    private static final String SUFFIX = "?=";

    private McpHeaderValues() {}

    /** The decoded value, or {@code null} when the header value is malformed. */
    public static String decode(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        if (headerValue.startsWith(PREFIX)
                && headerValue.endsWith(SUFFIX)
                && headerValue.length() >= PREFIX.length() + SUFFIX.length()) {
            String encoded = headerValue.substring(PREFIX.length(), headerValue.length() - SUFFIX.length());
            try {
                byte[] bytes = Base64.getDecoder().decode(encoded);
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (IllegalArgumentException | CharacterCodingException malformed) {
                return null;
            }
        }
        return isPlain(headerValue) ? headerValue : null;
    }

    private static boolean isPlain(String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (isWhitespace(value.charAt(0)) || isWhitespace(value.charAt(value.length() - 1))) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c == ' ' || c == '\t' || (c >= 0x21 && c <= 0x7E))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }
}
