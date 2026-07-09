package io.github.jdubois.bootui.engine.support;

import java.time.DateTimeException;
import java.time.Instant;

/**
 * Shared blank-string normalization reused across the engine and both adapters wherever an optional
 * {@code String} value (a request query parameter, a captured event field, an annotation attribute) needs
 * null-if-blank handling instead of an empty/whitespace string.
 *
 * <p>Two flavors, matching the two call-site shapes found across the codebase: {@link #blankToNull(String)}
 * preserves the original value's surrounding whitespace (for values that are never user-typed, e.g. a
 * captured trace id or an annotation attribute), while {@link #blankToNullTrimmed(String)} additionally
 * trims (for values that come straight off an HTTP query parameter, which can carry incidental leading/
 * trailing whitespace).
 */
public final class BlankStrings {

    private BlankStrings() {}

    /** {@code null} if {@code value} is null/blank, else {@code value} unchanged. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** {@code null} if {@code value} is null/blank, else {@code value} trimmed. */
    public static String blankToNullTrimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Parses an optional ISO-8601 instant query parameter: {@code null} if blank, else {@link
     * Instant#parse}. Lets {@link DateTimeException} (which {@link java.time.format.DateTimeParseException}
     * extends) propagate so each adapter can translate a malformed value into its own 400 response.
     */
    public static Instant parseInstant(String value) {
        String normalized = blankToNullTrimmed(value);
        return normalized == null ? null : Instant.parse(normalized);
    }
}
