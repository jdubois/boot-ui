package io.github.jdubois.bootui.engine.support;

/**
 * Shared sanitization for the free-form "detail" strings advisor rules attach to a finding (an ArchUnit
 * failure message, an exception message, a config value, ...). Every advisor's rule-support class
 * (Architecture, Hibernate, Memory, REST API, CRaC, GraalVM) flattened and truncated these the same way
 * so the browser panel never renders raw newlines/tabs or unbounded text; this class is the single copy
 * of that logic.
 */
public final class DetailText {

    /** Every advisor's prior hardcoded limit; kept as the default so behavior is unchanged. */
    public static final int DEFAULT_MAX_CHARS = 240;

    private static final String NO_DETAIL = "No additional detail.";

    private DetailText() {}

    /** {@link #sanitize(String, int)} using {@link #DEFAULT_MAX_CHARS}. */
    public static String sanitize(String value) {
        return sanitize(value, DEFAULT_MAX_CHARS);
    }

    /**
     * Flattens newlines/tabs to single spaces, trims, and truncates to {@code maxChars} (appending
     * {@code "..."} when truncated). Returns {@value #NO_DETAIL} for a {@code null} or blank input.
     */
    public static String sanitize(String value, int maxChars) {
        if (value == null) {
            return NO_DETAIL;
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (sanitized.isBlank()) {
            return NO_DETAIL;
        }
        if (sanitized.length() <= maxChars) {
            return sanitized;
        }
        return sanitized.substring(0, maxChars - 3) + "...";
    }
}
