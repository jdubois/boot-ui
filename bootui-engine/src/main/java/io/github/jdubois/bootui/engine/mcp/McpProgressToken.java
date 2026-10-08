package io.github.jdubois.bootui.engine.mcp;

import java.util.Objects;

/**
 * A client-chosen {@code _meta.progressToken}: MCP requires it to be a string or an integer, and every progress
 * notification echoes it with its original JSON type.
 *
 * @param text the token when the client sent a string, otherwise {@code null}
 * @param number the token when the client sent an integer, otherwise {@code null}
 */
public record McpProgressToken(String text, Long number) {

    public McpProgressToken {
        if ((text == null) == (number == null)) {
            throw new IllegalArgumentException("A progress token is exactly one of a string or an integer");
        }
    }

    /** A string token. */
    public static McpProgressToken of(String text) {
        return new McpProgressToken(Objects.requireNonNull(text, "text"), null);
    }

    /** An integer token. */
    public static McpProgressToken of(long number) {
        return new McpProgressToken(null, number);
    }

    /** {@code true} when the token must be rendered as a JSON string. */
    public boolean isText() {
        return text != null;
    }
}
